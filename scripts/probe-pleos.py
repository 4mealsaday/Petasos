#!/usr/bin/env python3
"""Run an explicit SDK probe on a Pleos emulator, without changing the product UI."""

import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import time

ROOT = Path(__file__).resolve().parent.parent
PROBES = ("vehicle-preferences", "trip-results", "trip-lifecycle", "app-trip-lifecycle", "app-location-live", "location", "tts-connect", "tts-speak", "tts-stop", "tts-close", "stt-connect", "stt-ready",
          "stt-listen", "stt-cancel", "stt-close", "stt-pcm", "stt-owned-pcm", "stt-owned-listen", "stt-owned-stream", "stt-owned-delayed", "stt-owned-eof",
          "location-storage", "android-location-contract", "android-speech-capability", "region-storage", "region-policy-storage", "region-hierarchy", "bundled-stories", "regional-content", "landmark-storage", "automatic-planning", "local-commands", "session-recovery", "recovery-music-gap", "navigation", "music-runtime", "media-session", "app-playback", "app-music", "car-policy-ui",
          "storage", "runtime-scripted", "runtime-tts", "runtime-tts-stop", "story-generation-storage", "resume-context", "app-story-generation",
          "summary-storage", "summary-work", "summary-rights", "generation-https", "generation-provision", "generation-live",
          "content-install", "content-install-local", "content-runtime", "generation-planning-live")
CAAS = "ai.pleos.playground.caas"
APP = "com.petasos.app"
BUILD_TYPES = ("debug", "privacy")
RUN_AS_PROBES = frozenset(("stt-cancel", "stt-close", "tts-close", "generation-https", "generation-provision",
                          "generation-live", "generation-planning-live", "app-story-generation",
                          "content-install", "content-install-local", "content-runtime"))


class AuditFailure(RuntimeError):
    """A sanitized, machine-readable audit failure (never a vendor payload)."""


def apk_paths(build_type, root=ROOT):
    """Never pair an optimized app with the ordinary debug instrumentation APK."""
    if build_type not in BUILD_TYPES:
        raise AuditFailure("unsupported_build_type")
    folder = root / "app/build/outputs/apk"
    return (folder / build_type / f"app-{build_type}.apk",
            folder / "androidTest" / build_type / f"app-{build_type}-androidTest.apk")


def build_instruction(build_type):
    apk_paths(build_type)  # Validate even callers outside argparse.
    variant = build_type.capitalize()
    option = "-PpetasosTestBuildType=privacy " if build_type == "privacy" else ""
    return f"Build {option}:app:assemble{variant} :app:assemble{variant}AndroidTest first."


def evidence_stem(probe, build_type):
    if probe not in PROBES:
        raise AuditFailure("unsupported_probe")
    apk_paths(build_type)
    # Preserve historical debug paths; optimized evidence must not overwrite them.
    return probe if build_type == "debug" else f"{probe}-privacy"


def artifact_metadata(build_type, apk, test_apk):
    return {"buildType": build_type, "appApk": str(apk.resolve()), "testApk": str(test_apk.resolve())}


def private_bytes(path, maximum):
    try:
        info = path.lstat()
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid()
                or stat.S_IMODE(info.st_mode) != 0o600 or not 0 < info.st_size <= maximum):
            raise AuditFailure("invalid_private_development_file")
        with path.open("rb") as source:
            value = source.read(maximum + 1)
        if not 0 < len(value) <= maximum:
            raise AuditFailure("invalid_private_development_file")
        return value
    except OSError:
        raise AuditFailure("invalid_private_development_file") from None


def read_development_server(probe, path, token_path=None):
    if probe not in ("generation-provision", "generation-live", "generation-planning-live", "app-story-generation"):
        if path is not None or token_path is not None:
            raise AuditFailure("development_server_not_applicable")
        return None, None
    if path is None:
        raise AuditFailure("development_server_ready_required")
    if token_path is not None and probe != "generation-provision":
        raise AuditFailure("device_token_only_for_provision")
    try:
        ready = json.loads(private_bytes(path, 16384))
        port = ready.get("port")
        if (type(port) is not int or not 1 <= port <= 65535 or
                ready.get("serverOrigin") != f"https://localhost:{port}" or
                ready.get("scope") != "debug_loopback_real_configured_provider"):
            raise AuditFailure("invalid_development_server_ready")
        token = None
        if probe == "generation-provision":
            token = private_bytes(token_path or Path(ready["tokenFile"]), 4096).strip()
            if not 32 <= len(token) <= 4096 or re.fullmatch(rb"[A-Za-z0-9._~+/-]+=*", token) is None:
                raise AuditFailure("invalid_development_device_token")
        return ready, token
    except (ValueError, KeyError, TypeError, AttributeError):
        raise AuditFailure("invalid_development_server_ready") from None


def probe_timeout(probe):
    if probe == "app-music":
        return 190  # One full licensed track plus public story and standard stop/resume cleanup.
    if probe in ("app-trip-lifecycle", "app-location-live", "music-runtime", "generation-live", "generation-planning-live", "app-story-generation", "resume-context", "story-generation-storage"):
        return 120
    # Multi-database Keystore rights checks also need headroom on a loaded emulator.
    # These are host process limits, not product speech or generation deadlines.
    if probe in ("generation-https", "stt-listen", "stt-owned-listen", "stt-owned-stream", "stt-owned-delayed", "stt-owned-eof", "app-playback", "summary-rights", "automatic-planning", "landmark-storage", "region-policy-storage", "region-hierarchy", "bundled-stories", "recovery-music-gap", "local-commands"):
        return 90
    return 50


def probe_evidence_metadata(probe, has_https_fixture=False):
    """Describe fixture boundaries identically in the report and host regressions."""
    return {
        "fixture": "official_packaged_geometry_disposable_database_public_synthetic_coordinates" if probe in ("region-storage", "region-policy-storage", "region-hierarchy", "bundled-stories") else "two_synthetic_coordinates" if probe == "location" else (
            "synthetic_korean_pcm" if probe in ("stt-pcm", "stt-owned-pcm", "stt-owned-stream", "stt-owned-delayed", "stt-owned-eof") else (
                "disposable_database_and_synthetic_test_content" if probe in ("vehicle-preferences", "trip-results", "trip-lifecycle", "regional-content", "landmark-storage", "recovery-music-gap", "storage", "runtime-scripted", "runtime-tts", "runtime-tts-stop", "story-generation-storage", "resume-context", "automatic-planning", "local-commands", "summary-storage", "summary-work") else (
                    "local_https_synthetic_provider_one_day_ca_ephemeral_device_token" if has_https_fixture else None))),
        "scope": {
            "stt-owned-pcm": "actual Pleos PCM with finite synthetic capture and fixture stop proof; no microphone; timing acceptance is separate",
            "stt-owned-stream": "actual Pleos PCM with continuous synthetic silence after speech; no microphone; product 5s/500ms timing recorded separately",
            "stt-owned-delayed": "actual Pleos PCM with 1500ms initial synthetic silence and continuous fixture; no microphone; unchanged product Ready-based 5s/500ms timing",
            "stt-owned-eof": "actual Pleos PCM with finite synthetic input trimmed only at exact trailing-zero samples; actual EOF and completeAudioSend call timestamps; no microphone or SDK flush acknowledgment; unchanged product 5s/500ms timing",
            "stt-owned-listen": "actual owned AudioRecord and Pleos PCM; explicit human opt-in required",
            "vehicle-preferences": "actual Room explicit preferences and stale CAS; disposable synthetic data; no platform permission or capture",
            "trip-results": "actual Room committed trip history projections; disposable synthetic data; no raw payload read or capture",
            "trip-lifecycle": "actual Room explicit trip end and recovery boundary; disposable synthetic data; no capture",
            "app-trip-lifecycle": "actual product public TTS stopped ACK, committed trip end, result reads and next trip; no microphone, location opt-in or model call",
            "app-location-live": "actual product with existing saved location consent and permissions; real Activity onStop/return using Home or moveTaskToBack, or explicitly marked Activity close/reopen fallback with observed onStop and onDestroy; close/reopen does not prove Home/background acceptance; session-scoped Room counts/quality; media stopped after opening a session; history retained; no coordinate output, permission grant, consent change, Virtual Console or model call; storage quiescence is not a vendor unregister acknowledgment",
            "location-storage": "actual Room with synthetic coordinates, boundary policies, region proofs and heard-fact filtering; no location SDK or movement",
            "android-location-contract": "public Android provider metadata and synthetic Location/Room mapping, monotonic freshness and corrupt evidence rejection; no real coordinate read, subscription or permission change",
            "android-speech-capability": "public Android recognition availability, on-device Korean support and requested-engine TTS voice metadata only; no recognition, microphone, PCM transmission, synthesis, model download, consent change or product database access; support does not prove audio-source handling, engine binding identity, privacy or speech acceptance",
            "region-storage": "actual packaged official NGII geometry and Room provenance, synthetic public coordinate and pending-policy rejection; no location SDK, microphone, consent change or model",
            "region-policy-storage": "disposable Room with packaged official geometry, explicitly synthetic region policy reviews and public synthetic coordinates; atomic first finalization, exact no-op, immutable conflicts, catalog reload, terminal current-authority revocation and existing-proof preservation; no product policy approval, consent change, location subscription, audio or model",
            "recovery-music-gap": "actual Room durable story/music completion recovery with synthetic content and scripted outputs; no location, microphone, audio or external model",
            "landmark-storage": "actual Room reviewed landmark policy, coordinate and story association with synthetic content; no location, audio, consent change or model",
            "region-hierarchy": "disposable Room with reviewed hierarchy and public synthetic points; leaf-only card and parent story scope; no product policy, microphone or network",
            "bundled-stories": "disposable Room with ten packaged public stories and exact bindings; no product records, visit policy, audio or network",
            "regional-content": "actual Room v1-to-v2 migration and reviewed textual region bindings with synthetic content; no location, audio, consent change or model",
            "local-commands": "actual Room/Keystore explicit playback commands with scripted input, output and generation; durable final input, stop ACK, user-only frozen turns and recovery; no microphone, audio playback, location or external model",
            "automatic-planning": "actual Room with synthetic installed content and scripted runtime for region/explicit-interest planning, repeat filtering and playback boundaries; no microphone, model, location SDK or actual audio playback",
            "session-recovery": "actual Room/Keystore process-recovery transactions with synthetic interrupted data; no OS suspend or real microphone",
            "navigation": "actual read-only NaviHelper connection and callbacks or explicit fallback; no destination write or vehicle mutation",
            "music-runtime": "actual Media3 playback and Room runtime using synthetic test tones; no licensed product music or microphone",
            "media-session": "actual MediaController command transport to synthetic target plus product browse and voice gate; no audio or microphone",
            "car-policy-ui": "actual CarUx observation plus synthetic visibility states; no vehicle mutation",
            "app-playback": "actual product MediaBrowser/service/host, installed public story TTS and durable petasos.db completion; no microphone or model call",
            "app-music": "actual product public story then licensed bundled music with attribution, Media3 Stop/resume/natural end and durable petasos.db commits; no microphone, model, synthetic tone or location opt-in",
            "storage": "actual Room/SQLite/Keystore, synthetic data; not full product acceptance",
            "runtime-scripted": "actual Room/Keystore with explicitly scripted speech and generation; no microphone or network",
            "runtime-tts": "actual Pleos TTS and Room via runtime; synthetic story; no microphone or generation server",
            "runtime-tts-stop": "actual Pleos TTS stopped callback and Room interrupted commit; no microphone; not acoustic silence verification",
            "summary-storage": "actual Room/Keystore TX03, stale results, retry and restart recovery; synthetic data; no model call",
            "summary-rights": "actual Room/Keystore summary authorization, freshness, blocked payload retention and explicit recovery; scripted transport; no external model or audio",
            "summary-work": "actual WorkManager test driver and Room, scripted generation/retry; no live model or OS timing guarantee",
            "generation-https": "actual Android HTTPS client to local server, explicit test CA and synthetic provider; no external model or microphone",
            "generation-provision": "debug app product Keystore configuration installed from private stdin token; no model call; reverse retained",
            "story-generation-storage": "actual Room generated public story snapshot/install and stale rights rejection; synthetic data; no network or audio",
            "resume-context": "actual Room/Keystore frozen completed-only context and revocation; synthetic data; no microphone or network",
            "generation-planning-live": "normal Android HTTPS real story generation and disposable Room install; resume actual three-second success or timeout; public fixed context",
            "app-story-generation": "actual product explicit public story generation, immutable install, TTS start and Stop; no microphone",
            "generation-live": "normal Android HTTPS trust to private loopback server and real configured model; fixed nonpersonal answer/replay/summary; no microphone or playback",
            "content-install": "actual Room TX05 rollback/immutability/idempotence; disposable databases; optional reviewed real bundle; no audio",
            "content-install-local": "reviewed bundle installed in persistent petasos.db; previous rows preserved; no playback or microphone",
            "content-runtime": "actual Room/Keystore and installed content context with scripted speech and transport; disposable databases; no microphone, audio or external model",
        }.get(probe, "actual SDK callbacks; not full product acceptance or subjective audio quality"),
    }


def ensure_development_reverse(adb, port):
    endpoint = f"tcp:{port}"
    for line in adb("reverse", "--list").splitlines():
        fields = line.split()
        if len(fields) == 3 and fields[1] == endpoint:
            if fields[2] != endpoint:
                raise AuditFailure("development_reverse_already_used")
            return
    adb("reverse", "--no-rebind", endpoint, endpoint)


def read_content_bundle(probe, path):
    if path is None:
        if probe in ("content-install-local", "generation-planning-live"):
            raise AuditFailure("content_install_local_requires_reviewed_bundle")
        return None
    if probe not in ("content-install", "content-install-local", "content-runtime", "generation-planning-live"):
        raise AuditFailure("content_bundle_not_applicable")
    try:
        with path.open("rb") as source:
            value = source.read(256 * 1024 + 1)
        if not 0 < len(value) <= 256 * 1024:
            raise AuditFailure("invalid_content_bundle_size")
        document = json.loads(value)
        if not isinstance(document, dict) or type(document.get("formatVersion")) is not int or document["formatVersion"] not in (1, 2):
            raise AuditFailure("invalid_content_bundle_shape")
        keys = {"formatVersion", "sources", "facts", "places", "stories", "versions"}
        if document["formatVersion"] == 2:
            keys.add("regionBindings")
        if set(document) != keys:
            raise AuditFailure("invalid_content_bundle_shape")
        return value
    except (OSError, ValueError):
        raise AuditFailure("invalid_content_bundle_file") from None


def recording_identity(entry):
    return (entry["recordingId"], entry["uid"], entry["sessionId"])


def audio_snapshot(dump):
    """Retain only CaaS recording metadata, never audio or unrelated package activity."""
    section = dump.partition("RecordActivityMonitor dump")[2].partition("AudioDeviceBroker:")[0]
    if "Events log: recording activity" not in section:
        raise AuditFailure("unsupported_recording_dump")
    active, _, history = section.partition("Events log: recording activity")
    pattern = (r"\brec (start|stop|update) riid:(\d+) uid:(\d+) session:(\d+) "
               r"src:(\S+) .*pack:" + re.escape(CAAS) + r"\s*$")
    entries = []
    for line in history.splitlines():
        if CAAS not in line:
            continue
        match = re.search(pattern, line)
        if not match:
            raise AuditFailure("unparsed_caas_recording_event")
        entries.append((line.strip(), {"event": match[1], "recordingId": int(match[2]),
                                     "uid": int(match[3]), "sessionId": int(match[4]),
                                     "source": match[5]}))
    configurations = []
    blocks = re.split(r"(?=\briid -?\d+; active\? (?:true|false))", active)
    for block in blocks:
        if CAAS not in block:
            continue
        header = re.search(r"\briid (\d+); active\? (true|false)", block)
        uid = re.search(r"\buid[:=]\s*(\d+)", block)
        session = re.search(r"\bsession[:=]\s*(\d+)", block)
        if not header or not uid or not session:
            raise AuditFailure("unparsed_caas_recording_configuration")
        if header[2] == "true":
            configurations.append({"recordingId": int(header[1]), "uid": int(uid[1]),
                                   "sessionId": int(session[1])})
    return {"activeCaas": bool(configurations), "entries": entries,
            "activeRecordings": configurations}


def process_alive(dump, pid):
    lines = [line.split() for line in dump.splitlines() if line.strip()]
    if not lines or lines[0] != ["PID", "NAME"]:
        raise AuditFailure("unsupported_process_dump")
    return any(fields == [str(pid), APP] for fields in lines[1:])


def service_binding_present(dump, pid):
    if not dump.startswith("ACTIVITY MANAGER SERVICES (dumpsys activity services)"):
        raise AuditFailure("unsupported_service_dump")
    # Match this exact probe process, not a stale process or a similarly named app.
    return bool(re.search(r"ProcessRecord\{[^}\n]*\b" + str(pid) + r":" +
                          re.escape(APP) + r"/", dump))


def new_recording_events(before, after):
    previous = {line for line, _ in before["entries"]}
    return [entry for line, entry in after["entries"] if line not in previous]


def audit_audio_lifecycle(probe, baseline, after, ready, closed_samples):
    events = new_recording_events(baseline, after)
    result = {"passed": False, "events": events, "activeCaasAfter": after["activeCaas"],
              "readyWhileProbeProcessAlive": None,
              "closedWhileProbeProcessAlive": closed_samples}
    if probe == "stt-pcm":
        result["passed"] = not events and not after["activeCaas"]
        return result
    if ready is None:
        result["failure"] = "ready_observation_missing"
        return result
    result["readyWhileProbeProcessAlive"] = {
        "pid": ready["pid"], "activeRecordings": ready["audio"]["activeRecordings"],
        "samePidAliveBeforeAndAfter": True,
    }
    expected = {recording_identity(item) for item in ready["audio"]["activeRecordings"]}
    if not expected:
        result["failure"] = "no_active_caas_recording_at_ready"
        return result
    # Ready is positive evidence of recording, even if Android's history calls the
    # initial event 'update'. Never reinterpret update as start.
    if any(recording_identity(event) not in expected for event in events):
        result["failure"] = "unexpected_caas_recording_identity"
        return result
    terminal_events = {}
    for event in new_recording_events(ready["audio"], after):
        identity = recording_identity(event)
        if identity not in expected:
            result["failure"] = "unexpected_caas_recording_identity"
            return result
        terminal_events[identity] = event["event"]
    stable_closed = (len(closed_samples) == 3 and all(
        sample["pid"] == ready["pid"] and sample["samePidAliveBeforeAndAfter"] and
        not sample["activeCaas"] and not sample["petasosBindingPresent"]
        for sample in closed_samples))
    result["passed"] = (all(terminal_events.get(identity) == "stop" for identity in expected) and
                        not after["activeCaas"] and stable_closed)
    if not result["passed"]:
        result["failure"] = "recording_or_binding_not_released_while_process_alive"
    return result


def audit_tts_binding_lifecycle(ready, closed_samples):
    """Prove client unbinding while alive; this does not prove speaker/player silence."""
    result = {
        "passed": False,
        "readyWhileProbeProcessAlive": ready,
        "closedWhileProbeProcessAlive": closed_samples,
        "scope": "TTS Started callback and CaaS client binding release while same process alive",
        "playerActivityVerified": False,
        "limitation": "No PlayerActivityMonitor or acoustic stop verification; unbinding alone does not prove silence",
    }
    if ready is None:
        result["failure"] = "ready_observation_missing"
        return result
    if not ready["samePidAliveBeforeAndAfter"] or not ready["petasosBindingPresent"]:
        result["failure"] = "no_live_tts_binding_at_ready"
        return result
    result["passed"] = len(closed_samples) == 3 and all(
        sample["pid"] == ready["pid"] and sample["samePidAliveBeforeAndAfter"] and
        not sample["petasosBindingPresent"] for sample in closed_samples)
    if not result["passed"]:
        result["failure"] = "binding_not_released_while_same_process_alive"
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("probe", choices=PROBES)
    parser.add_argument("--build-type", choices=BUILD_TYPES, default="debug",
                        help="Matching app and instrumentation variant (default: debug).")
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--artifacts-dir", type=Path, default=ROOT / "artifacts/milestone-2",
                        help="Evidence directory (default: artifacts/milestone-2).")
    parser.add_argument("--https-fixture-ready", type=Path,
                        help="Private ready JSON emitted by server/tests/tls_fixture.py (generation-https only).")
    parser.add_argument("--development-server-ready", type=Path,
                        help="Private ready JSON from server/dev-local-https.py (live generation probes). Reverse is retained for product use.")
    parser.add_argument("--device-token-file", type=Path,
                        help="Optional private token file for generation-provision; transferred only over stdin, never printed.")
    parser.add_argument("--content-bundle", type=Path,
                        help="Reviewed local bundle for content or generation-planning-live probes; copied privately, never printed.")
    args = parser.parse_args()
    if args.build_type == "privacy" and args.probe in RUN_AS_PROBES:
        parser.error("privacy_probe_requires_unavailable_run_as; use a supported synthetic probe or the ordinary debug build.")
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("Only an explicitly selected Android emulator is supported.")
    fixture = None
    if args.probe == "generation-https":
        if args.https_fixture_ready is None:
            parser.error("generation-https requires --https-fixture-ready from the synthetic local TLS fixture.")
        fixture = json.loads(args.https_fixture_ready.read_text())
        if type(fixture.get("port")) is not int or not 1 <= fixture["port"] <= 65535:
            parser.error("Invalid fixture port.")
    elif args.https_fixture_ready is not None:
        parser.error("--https-fixture-ready applies only to generation-https.")
    try:
        development, development_token = read_development_server(args.probe, args.development_server_ready, args.device_token_file)
        content_bytes = read_content_bundle(args.probe, args.content_bundle)
    except AuditFailure as failure:
        parser.error(str(failure))

    properties = ROOT / "local.properties"
    local_sdk = ""
    if properties.exists():
        for line in properties.read_text().splitlines():
            if line.startswith("sdk.dir="):
                local_sdk = line.partition("=")[2].replace("\\ ", " ")
    roots = (os.environ.get("ANDROID_SDK_ROOT"), os.environ.get("ANDROID_HOME"), local_sdk,
             str(Path.home() / "Library/Android/sdk"))
    adb_path = next((Path(p) / "platform-tools/adb" for p in roots if p and
                     (Path(p) / "platform-tools/adb").is_file()), None)
    if adb_path is None:
        parser.error("Set sdk.dir in local.properties or ANDROID_SDK_ROOT.")

    def adb(*command, timeout=25):
        result = subprocess.run([str(adb_path), "-s", args.serial, *command],
                                capture_output=True, text=True, timeout=timeout, check=True)
        return result.stdout.strip()

    fingerprint = adb("shell", "getprop", "ro.build.fingerprint")
    api = adb("shell", "getprop", "ro.build.version.sdk")
    if "pleos" not in fingerprint.lower() or api != "34":
        parser.error("Expected a Pleos API 34 emulator.")
    if adb("shell", "getprop", "sys.boot_completed") != "1":
        parser.error("Wait for the emulator to finish booting.")
    user = adb("shell", "am", "get-current-user")
    if not user.isdecimal():
        parser.error("Cannot determine the current Android user.")
    audit_audio = args.probe in ("stt-cancel", "stt-close", "stt-pcm")
    audit_tts = args.probe == "tts-close"
    audit_kind = "tts" if audit_tts else "stt"

    apk, test_apk = apk_paths(args.build_type)
    if not apk.is_file() or not test_apk.is_file():
        parser.error(build_instruction(args.build_type))
    apk_hash = hashlib.sha256(apk.read_bytes()).hexdigest()
    test_apk_hash = hashlib.sha256(test_apk.read_bytes()).hexdigest()
    for path in (apk, test_apk):
        if "Success" not in adb("install", "--user", user, "-r", str(path)):
            raise RuntimeError("APK installation did not report success")
    if args.probe == "location":
        # An emulator-only grant for this explicit capability probe, not a production permission flow.
        adb("shell", "pm", "grant", "--user", user, "com.petasos.app",
            "pleos.car.permission.FUSED_LOCATION")

    folder = args.artifacts_dir.expanduser().resolve()
    folder.mkdir(parents=True, exist_ok=True)
    stem = evidence_stem(args.probe, args.build_type)
    log = folder / f"{stem}.txt"
    command = [str(adb_path), "-s", args.serial, "shell", "am", "instrument", "-w", "--user", user,
               "-e", "probe", args.probe]
    if fixture is not None:
        command += ["-e", "fixturePort", str(fixture["port"])]
    if development is not None:
        command += ["-e", "fixturePort", str(development["port"])]
    command += ["com.petasos.app.test/com.petasos.app.diagnostics.SdkProbeInstrumentation"]
    started = datetime.datetime.now(datetime.timezone.utc).isoformat()
    print(f"Running {args.probe} ({args.build_type}) on {args.serial}, Android user {user}...", flush=True)
    process = None
    timed_out = False
    completed = False
    baseline = None
    ready_observation = None
    closed_samples = []
    audit_errors = []
    handled_markers = set()
    log.write_text("")
    fixture_reverse_created = False
    development_reverse_retained = False

    def require_alive(pid):
        if not process_alive(adb("shell", "ps", "-p", str(pid), "-o", "PID,NAME", timeout=3), pid):
            raise AuditFailure("probe_process_not_alive")

    def capture_phase(phase, pid, token):
        nonlocal ready_observation
        try:
            if phase == "ready":
                if ready_observation is not None:
                    raise AuditFailure("duplicate_ready_audit")
                require_alive(pid)
                if audit_tts:
                    bindings = adb("shell", "dumpsys", "activity", "services", CAAS, timeout=3)
                    present = service_binding_present(bindings, pid)
                    require_alive(pid)
                    ready_observation = {"pid": pid, "samePidAliveBeforeAndAfter": True,
                                         "petasosBindingPresent": present}
                    if not present:
                        raise AuditFailure("no_live_tts_binding_at_ready")
                else:
                    snapshot = audio_snapshot(adb("shell", "dumpsys", "audio", timeout=3))
                    require_alive(pid)
                    ready_observation = {"pid": pid, "audio": snapshot}
                    if not snapshot["activeCaas"]:
                        raise AuditFailure("no_active_caas_recording_at_ready")
            else:
                if ready_observation is None or ready_observation["pid"] != pid or closed_samples:
                    raise AuditFailure("invalid_closed_audit_sequence")
                window_start = time.monotonic()
                # The adapter deliberately retains its connection for a 1s stop
                # drain. Observe after that grace while instrumentation awaits ack.
                for offset in (1.25, 1.75, 2.25):
                    time.sleep(max(0, window_start + offset - time.monotonic()))
                    require_alive(pid)
                    snapshot = (audio_snapshot(adb("shell", "dumpsys", "audio", timeout=3))
                                if audit_audio else None)
                    bindings = adb("shell", "dumpsys", "activity", "services", CAAS, timeout=3)
                    present = service_binding_present(bindings, pid)
                    require_alive(pid)
                    sample = {
                        "pid": pid, "samePidAliveBeforeAndAfter": True,
                        "observedAfterCloseMs": round((time.monotonic() - window_start) * 1_000),
                        "petasosBindingPresent": present,
                    }
                    if snapshot is not None:
                        sample.update({"activeCaas": snapshot["activeCaas"],
                                       "activeRecordings": snapshot["activeRecordings"]})
                    closed_samples.append(sample)
        finally:
            # Always release the diagnostic wait, including an observed leak.
            # The unique token prevents acknowledgments leaking across probes.
            adb("shell", "run-as", APP, "--user", user, "touch",
                f"cache/{audit_kind}-{phase}-{token}.ack", timeout=3)

    try:
        if development is not None:
            ensure_development_reverse(adb, development["port"])
            development_reverse_retained = True
            adb("shell", "run-as", APP, "--user", user, "mkdir", "-p", "cache")
            adb("shell", "run-as", APP, "--user", user, "rm", "-f", "cache/development-generation-token")
            if development_token is not None:
                subprocess.run([str(adb_path), "-s", args.serial, "shell", "run-as", APP, "--user", user,
                                "sh", "-c", "'umask 077; cat > cache/development-generation-token'"],
                               input=development_token, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, check=True, timeout=10)
                development_token = None
        if args.probe in ("content-install", "content-install-local", "content-runtime", "generation-planning-live"):
            adb("shell", "run-as", APP, "--user", user, "mkdir", "-p", "cache")
            # Remove a stale probe input even when this run requests only synthetic tests.
            adb("shell", "run-as", APP, "--user", user, "rm", "-f", "cache/content-bundle.json")
        if content_bytes is not None:
            subprocess.run([str(adb_path), "-s", args.serial, "shell", "run-as", APP, "--user", user,
                            "sh", "-c", "'umask 077; cat > cache/content-bundle.json'"],
                           input=content_bytes, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, check=True, timeout=10)
        if fixture is not None:
            adb("reverse", "--no-rebind", f"tcp:{fixture['port']}", f"tcp:{fixture['port']}")
            fixture_reverse_created = True
            adb("shell", "run-as", APP, "--user", user, "mkdir", "-p", "cache")
            for key, filename, limit_bytes in (("caFile", "generation-probe-ca.pem", 16_384),
                                               ("tokenFile", "generation-probe-token", 4096)):
                value = Path(fixture[key]).read_bytes()
                if not 0 < len(value) <= limit_bytes:
                    raise AuditFailure("invalid_https_fixture_file")
                # Secret bytes travel only over stdin into app-private test cache, never argv/logs.
                subprocess.run([str(adb_path), "-s", args.serial, "shell", "run-as", APP, "--user", user,
                                "sh", "-c", f"'umask 077; cat > cache/{filename}'"],
                               input=value, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, check=True, timeout=10)
        if audit_audio:
            baseline = audio_snapshot(adb("shell", "dumpsys", "audio", timeout=3))
            if baseline["activeCaas"]:
                raise AuditFailure("caas_recording_active_before_probe")
        with log.open("w") as stream:
            process = subprocess.Popen(command, stdout=stream, stderr=subprocess.STDOUT)
            limit = time.monotonic() + probe_timeout(args.probe)
            injected_a = injected_b = False
            prompted = False
            while process.poll() is None:
                text = log.read_text()
                if args.probe in ("stt-listen", "stt-owned-listen") and not prompted and (
                        "event=stt_ready" in text or "event=owned_input_ready_at_" in text):
                    print("마이크 입력 준비 완료. 지금 짧은 시험 문장을 말하세요 (최대 15초).", flush=True)
                    prompted = True
                if audit_audio or audit_tts:
                    pattern = rf"event={audit_kind}_audit_(ready|closed)_pid_(\d+)_token_(\d+)\b"
                    for marker in re.finditer(pattern, text):
                        key = marker.group(0)
                        if key not in handled_markers:
                            handled_markers.add(key)
                            capture_phase(marker[1], int(marker[2]), marker[3])
                if args.probe == "location":
                    if not injected_a and "event=location_subscribed" in text:
                        adb("emu", "geo", "fix", "127.0276", "37.4979")
                        injected_a = True
                    if injected_a and not injected_b and "event=location_fixture_a" in text:
                        adb("emu", "geo", "fix", "127.0286", "37.4989")
                        injected_b = True
                if time.monotonic() >= limit:
                    timed_out = True
                    break
                time.sleep(0.2)
            completed = process.poll() == 0 and not timed_out
    except (AuditFailure, subprocess.SubprocessError, OSError) as failure:
        audit_errors.append(str(failure) if isinstance(failure, AuditFailure) else type(failure).__name__)
    finally:
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=3)
        if process is not None and not completed:
            # Killing adb alone does not stop device-side recording; stop the target process too.
            try:
                adb("shell", "am", "force-stop", "--user", user, APP)
            except (subprocess.SubprocessError, OSError) as failure:
                audit_errors.append("force_stop_" + type(failure).__name__)
        if fixture_reverse_created:
            try:
                adb("shell", "run-as", APP, "--user", user, "rm", "-f",
                    "cache/generation-probe-ca.pem", "cache/generation-probe-token")
                adb("reverse", "--remove", f"tcp:{fixture['port']}")
            except (subprocess.SubprocessError, OSError) as failure:
                audit_errors.append("https_cleanup_" + type(failure).__name__)
        if development is not None:
            try:
                # Keep the explicitly installed encrypted config and reverse for the app host.
                adb("shell", "run-as", APP, "--user", user, "rm", "-f", "cache/development-generation-token")
            except (subprocess.SubprocessError, OSError) as failure:
                audit_errors.append("development_token_cleanup_" + type(failure).__name__)
        if args.probe in ("content-install", "content-install-local", "content-runtime", "generation-planning-live"):
            try:
                adb("shell", "run-as", APP, "--user", user, "rm", "-f", "cache/content-bundle.json")
            except (subprocess.SubprocessError, OSError) as failure:
                audit_errors.append("content_cleanup_" + type(failure).__name__)

    text = log.read_text()
    match = re.search(r"INSTRUMENTATION_RESULT: outcome=(PASS|BLOCKED|FAIL)", text)
    outcome = "FAIL" if not completed or not match else match.group(1)
    if audit_errors:
        outcome = "FAIL"
    audio_audit = None
    if audit_audio:
        try:
            after = audio_snapshot(adb("shell", "dumpsys", "audio", timeout=3))
            if baseline is None:
                raise AuditFailure("baseline_observation_missing")
            audio_audit = audit_audio_lifecycle(args.probe, baseline, after, ready_observation, closed_samples)
        except (AuditFailure, subprocess.SubprocessError, OSError) as failure:
            audit_errors.append(str(failure) if isinstance(failure, AuditFailure) else type(failure).__name__)
            audio_audit = {"passed": False}
        if audit_errors:
            audio_audit["passed"] = False
            audio_audit["errors"] = audit_errors
        if not audio_audit["passed"]:
            outcome = "FAIL"
    tts_audit = None
    if audit_tts:
        tts_audit = audit_tts_binding_lifecycle(ready_observation, closed_samples)
        if audit_errors:
            tts_audit["passed"] = False
            tts_audit["errors"] = audit_errors
        if not tts_audit["passed"]:
            outcome = "FAIL"
    if outcome != "PASS":
        try:
            adb("shell", "am", "force-stop", "--user", user, APP)
        except (subprocess.SubprocessError, OSError) as failure:
            audit_errors.append("force_stop_" + type(failure).__name__)
    metadata = {
        **artifact_metadata(args.build_type, apk, test_apk),
        "probe": args.probe, "outcome": outcome, "startedAtUtc": started,
        "serial": args.serial, "androidUser": int(user), "fingerprint": fingerprint,
        "appSha256": apk_hash,
        "testApkSha256": test_apk_hash,
        "adbExitCode": process.returncode if process is not None else None,
        "hostTimeout": timed_out,
        "hostErrors": audit_errors,
        "developmentReverseRetained": development_reverse_retained,
        "audioAudit": audio_audit,
        "ttsBindingAudit": tts_audit,
        "contentBundleSha256": hashlib.sha256(content_bytes).hexdigest() if content_bytes is not None else None,
        **probe_evidence_metadata(args.probe, has_https_fixture=fixture is not None),
    }
    (folder / f"{stem}.json").write_text(json.dumps(metadata, indent=2) + "\n")
    print(text, end="" if text.endswith("\n") else "\n")
    if audio_audit is not None:
        print("Audio lifecycle audit: " + json.dumps(audio_audit, ensure_ascii=False))
    if tts_audit is not None:
        print("TTS binding audit: " + json.dumps(tts_audit, ensure_ascii=False))
    print(f"{outcome}: {log}")
    return {"PASS": 0, "BLOCKED": 2, "FAIL": 1}[outcome]


if __name__ == "__main__":
    raise SystemExit(main())
