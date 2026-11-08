#!/usr/bin/env python3
"""Bounded, synthetic-only speech log observation. Persist counters, never raw log records."""

import argparse
import collections
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import threading
import time
import unicodedata
import uuid

ROOT = Path(__file__).resolve().parent.parent
APP = "com.petasos.app"
TEST = APP + ".test"
SERVICE = "ai.pleos.playground.caas"
COMPONENT = SERVICE + "/.service.speech.SpeechService"
SYNTHETIC_PROBES = ("stt-owned-pcm", "stt-owned-stream", "stt-owned-delayed", "stt-owned-eof", "tts-speak", "tts-stop")
ROLES = ("app_client", "speech_service", "unknown_process")
MATCHES = ("known_canary", "recognition_payload_shape", "stacktrace_sink")
MAX_RECORDS = 200_000
MAX_BYTES = 32 * 1024 * 1024
MAX_MATCHED_TAGS = 64
MAX_MATCHED_TAG_LENGTH = 64
TAG_MATCHES = ("known_canary", "recognition_payload_shape")


class AuditError(Exception):
    """Only fixed internal codes may escape this module; never attach subprocess output."""


def normalize(value):
    return "".join(char for char in unicodedata.normalize("NFKC", value).casefold() if char.isalnum())


# Public test fixtures already present in the test APK. Reports contain their IDs only.
CANARIES = {
    "synthetic_pcm_v1": normalize("지금 지나가는 지역의 이야기를 알려 주세요."),
    "synthetic_tts_connect_v1": normalize("페타소스 음성 출력 연결을 확인합니다."),
    "synthetic_tts_stop_v1": normalize("페타소스 음성 중지 시험입니다."),
}
CANARY_FRAGMENTS = tuple(normalize(value) for value in (
    "지금 지나가는 지역", "지나가는 지역의 이야기", "페타소스 음성 출력", "페타소스 음성 중지",
))


def classify(tag, message):
    """The only retained derivative of a message is a small fixed-category indicator set."""
    kinds = set()
    normal = normalize(message)
    if any(canary in normal for canary in (*CANARIES.values(), *CANARY_FRAGMENTS)):
        kinds.add("known_canary")
    if re.search(r'''["']?(?:asrText|transcript|alternatives|recognitionResults)["']?\s*[:=]''', message, re.I):
        kinds.add("recognition_payload_shape")
    if re.search(r"(?:^|\n)\s*(?:at\s+[\w.$]+\([^\n]*\)|Caused by:|Suppressed:)|(?:Exception|Error)(?::|\s*$)|FATAL EXCEPTION", message):
        kinds.add("stacktrace_sink")
    dropped = (tag.lower() in ("chatty", "logd", "logcat") and
               re.search(r"\b(?:dropped|lost|skipped|expire[ds]?)\b", message, re.I) is not None)
    crash = bool(re.search(r"FATAL EXCEPTION|Fatal signal|Abort message", message))
    return kinds, dropped, crash


def matched_tag_rejection_reason(tag):
    """Conservative implementation identifiers for a fresh synthetic experiment only.

    No normalization or truncation: the exact name is needed for log.tag.<TAG>.
    This syntax/marker screen is not a proof that an arbitrary identifier is public.
    """
    if not isinstance(tag, str) or not 1 <= len(tag) <= MAX_MATCHED_TAG_LENGTH:
        return "tag_length_or_type"
    if not tag.isascii():
        return "non_ascii_tag"
    # Android property names permit @ and digits too; distinguish that platform
    # limit from our deliberately narrower metadata-export grammar.
    if re.search(r"[^A-Za-z0-9_.:@-]", tag, flags=re.ASCII):
        return "unsupported_tag_characters"
    if tag.endswith(".") or ".." in tag:
        return "invalid_property_name"
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_.:-]*", tag, flags=re.ASCII):
        return "tag_identifier_shape"
    if re.search(r"secret|private|password|passwd|credential|authorization|bearer|token|api.?key|client.?key|access.?key|session.?id|transcript|asr.?text|alternatives|recognition.?results|payload", tag, re.I):
        return "sensitive_marker"
    # Reject likely numeric identifiers, encoded credentials, hashes and long opaque tokens.
    if re.search(r"[0-9]{4,}|[a-fA-F0-9]{16,}|[A-Za-z0-9]{33,}", tag):
        return "opaque_identifier"
    if classify("", tag)[0]:
        return "canary_or_payload_in_tag"
    return None


def safe_matched_tag(tag):
    return matched_tag_rejection_reason(tag) is None


class BinaryDecoder:
    """Strict Android logger_entry_v4: len/hdr_size,pid,tid,sec,nsec,lid,uid (28 bytes).

    Ref: AOSP system/logging/liblog/include/log/log_read.h. Unsupported headers are not
    guessed. main/system/crash use priority + NUL tag + NUL-terminated UTF-8 message.
    """
    def __init__(self, consume):
        self.consume = consume
        self.pending = bytearray()
        self.bytes_seen = 0

    def feed(self, data):
        self.bytes_seen += len(data)
        if self.bytes_seen > MAX_BYTES:
            raise AuditError("byte_limit_exceeded")
        self.pending.extend(data)
        while len(self.pending) >= 4:
            length, header_size = struct.unpack_from("<HH", self.pending)
            if header_size != 28 or length < 3 or length > 65_535:
                raise AuditError("unsupported_binary_log_header")
            size = header_size + length
            if len(self.pending) < size:
                return
            _, _, pid, tid, seconds, nanos, buffer_id, uid = struct.unpack_from("<HHiiIIII", self.pending)
            payload = bytes(self.pending[header_size:size])
            del self.pending[:size]
            if pid <= 0 or tid <= 0 or nanos >= 1_000_000_000 or buffer_id not in (0, 3, 4):
                raise AuditError("unsupported_binary_log_record")
            priority = payload[0]
            split = payload.find(b"\0", 1)
            if not 2 <= priority <= 8 or split < 1 or payload[-1:] != b"\0":
                raise AuditError("unsupported_binary_log_payload")
            try:
                tag = payload[1:split].decode("utf-8", errors="strict")
                message = payload[split + 1:-1].decode("utf-8", errors="strict")
            except UnicodeDecodeError:
                raise AuditError("invalid_log_encoding") from None
            self.consume(uid, pid, seconds * 1_000_000_000 + nanos, tag, message)

    def finish(self):
        if self.pending:
            self.pending.clear()
            raise AuditError("truncated_binary_log_record")


def package_uid(text, package, user):
    rows = re.findall(r"^package:" + re.escape(package) + r" uid:(\d+)\s*$", text, re.M)
    if len(rows) != 1 or int(rows[0]) // 100_000 != user:
        raise AuditError("package_uid_unresolved")
    return int(rows[0])


def process_metadata(text, allowed_uids):
    lines = [line.split() for line in text.splitlines() if line.strip()]
    if not lines or lines[0] != ["PID", "UID", "NAME"]:
        raise AuditError("unsupported_process_metadata")
    result = {}
    for fields in lines[1:]:
        if len(fields) != 3 or not fields[0].isdecimal() or not fields[1].isdecimal():
            raise AuditError("unsupported_process_metadata")
        pid, uid, name = int(fields[0]), int(fields[1]), fields[2]
        if uid not in allowed_uids:
            continue
        role = ("app_client" if name in (APP, TEST) or name.startswith(APP + ":") or name.startswith(TEST + ":")
                else "speech_service" if name == SERVICE or name.startswith(SERVICE + ":") else "unknown_process")
        result[pid] = (uid, role)
    return result


def service_bindings(text, processes, app_uids, service_uids):
    if not text.startswith("ACTIVITY MANAGER SERVICES (dumpsys activity services)"):
        raise AuditError("unsupported_service_metadata")
    bindings = set()
    for block in re.split(r"(?=^\s*\* ServiceRecord\{)", text, flags=re.M):
        header = re.search(r"^\s*\* ServiceRecord\{[^}\n]*\bu(\d+)\s+" + re.escape(COMPONENT) + r"\}", block, re.M)
        if not header:
            continue
        server = re.search(r"\bapp=ProcessRecord\{[^}\n]*\s(\d+):" + re.escape(SERVICE) + r"(?::[^/\s}]+)?/", block)
        if not server:
            continue  # An unbound/stopped service is not coverage evidence.
        server_pid = int(server[1])
        server_identity = processes.get(server_pid)
        if server_identity is None or server_identity[0] not in service_uids or server_identity[0] // 100_000 != int(header[1]):
            continue
        for client in re.finditer(r"Client AppBindRecord\{[^\n]*?ProcessRecord\{[^}\n]*\s(\d+):" + re.escape(APP) + r"(?:\.test)?(?::[^/\s}]+)?/", block):
            client_pid = int(client[1])
            identity = processes.get(client_pid)
            if identity is not None and identity[0] in app_uids:
                bindings.add((server_identity[0], server_pid, identity[0], client_pid))
    return bindings


def service_version(text):
    versions = re.findall(r"\bversionCode=(\d+)\b", text)
    names = re.findall(r"^\s*versionName=([A-Za-z0-9._-]{1,80})\s*$", text, re.M)
    paths = re.findall(r"^\s*codePath=(/[A-Za-z0-9_./-]+)\s*$", text, re.M)
    if len(set(versions)) != 1 or len(set(names)) != 1 or len(set(paths)) != 1:
        raise AuditError("service_version_unresolved")
    return {"package": SERVICE, "component": COMPONENT, "versionCode": int(versions[0]), "versionName": names[0], "codePath": paths[0]}


class Observation:
    def __init__(self, app_uids, service_uids, cursor_ns, *, include_matched_tags=False):
        self.app_uids = set(app_uids)
        self.service_uids = set(service_uids)
        self.allowed = self.app_uids | self.service_uids
        self.cursor_ns = cursor_ns
        self.lock = threading.RLock()
        self.counts = collections.Counter()
        self.matches = collections.Counter()
        self.processes = {}
        self.bindings = set()
        self.seen_service_pids = collections.defaultdict(set)
        self.reasons = set()
        self.records = 0
        self.crashes = 0
        self.sdk = collections.Counter()
        self.durations = {}
        self.fixture = {}
        self.probe_outcome = None
        self.include_matched_tags = include_matched_tags
        self.matched_tags = collections.Counter()
        self.accepted_tags = set()
        self.rejected_tag_count = 0
        self.rejected_tag_reasons = collections.Counter()

    def issue(self, code):
        with self.lock:
            self.reasons.add(code)

    def consume(self, uid, pid, stamp_ns, tag, message):
        with self.lock:
            if uid not in self.allowed:
                self.reasons.add("uid_filter_violation")
                return
            if stamp_ns < self.cursor_ns:
                self.reasons.add("record_before_cursor")
                return
            self.records += 1
            if self.records > MAX_RECORDS:
                raise AuditError("record_limit_exceeded")
            self.counts[(uid, pid)] += 1
            kinds, dropped, crash = classify(tag, message)
            self.matches.update((uid, pid, kind) for kind in kinds)
            tag_kinds = kinds.intersection(TAG_MATCHES)
            if self.include_matched_tags and tag_kinds:
                rejection = matched_tag_rejection_reason(tag)
                if rejection is None and tag not in self.accepted_tags and len(self.accepted_tags) >= MAX_MATCHED_TAGS:
                    rejection = "tag_count_limit"
                if rejection is not None:
                    self.rejected_tag_count += 1
                    self.rejected_tag_reasons[rejection] += 1
                else:
                    self.accepted_tags.add(tag)
                    self.matched_tags.update((uid, pid, tag, kind) for kind in tag_kinds)
            if dropped:
                self.reasons.add("dropped_records_observed")
            self.crashes += int(crash)

    def observe_processes(self, processes, bindings):
        with self.lock:
            if any(server_pid not in processes for _, server_pid, _, _ in self.bindings):
                self.reasons.add("bound_service_process_disappeared")
            for pid, identity in processes.items():
                old = self.processes.get(pid)
                if old is not None and old != identity:
                    self.reasons.add("pid_reused")
                self.processes[pid] = identity
                if identity[1] == "unknown_process":
                    self.reasons.add("unknown_uid_process")
                if identity[1] == "speech_service":
                    self.seen_service_pids[identity[0]].add(pid)
                    if len(self.seen_service_pids[identity[0]]) > 1:
                        self.reasons.add("speech_service_restarted")
            self.bindings.update(bindings)

    def instrumentation(self, line):
        # Only fixed event names and numeric telemetry survive. All other text is discarded.
        outcome = re.fullmatch(r"INSTRUMENTATION_RESULT: outcome=(PASS|FAIL|BLOCKED)\s*", line)
        if outcome:
            self.probe_outcome = outcome[1]
        event = re.fullmatch(r"INSTRUMENTATION_STATUS: event=([a-z0-9_]{1,180})\s*", line)
        if not event:
            return
        value = event[1]
        reads = re.fullmatch(r"owned_fixture_reads_(\d+)_samples_(\d+)", value)
        payload = re.fullmatch(r"owned_fixture_payload_samples_(\d+)_of_(\d+)", value)
        original = re.fullmatch(r"owned_fixture_original_samples_(\d+)", value)
        trimmed = re.fullmatch(r"owned_fixture_trimmed_trailing_zero_samples_(\d+)", value)
        with self.lock:
            if reads:
                self.fixture.update({"readCount": int(reads[1]), "suppliedSamples": int(reads[2])})
            if payload:
                self.fixture.update({"payloadSuppliedSamples": int(payload[1]), "payloadAvailableSamples": int(payload[2])})
            if original:
                self.fixture["originalSamples"] = int(original[1])
            if trimmed:
                self.fixture["trimmedTrailingZeroSamples"] = int(trimmed[1])
            for name, pattern in FIXTURE_MILLIS.items():
                match = re.fullmatch(pattern, value)
                if match:
                    self.fixture[name] = int(match[1])
        for category, pattern in SDK_EVENTS.items():
            if re.fullmatch(pattern, value):
                with self.lock:
                    self.sdk[category] += 1
                    duration = re.search(r"_(\d+)ms$", value)
                    if duration:
                        self.durations[category] = int(duration[1])

    def report(self, probe):
        with self.lock:
            if self.include_matched_tags and probe not in SYNTHETIC_PROBES:
                raise AuditError("synthetic_probe_required")
            reasons = set(self.reasons)
            unknown = [(uid, pid) for uid, pid in self.counts if self.processes.get(pid, (None, None))[0] != uid]
            if unknown:
                reasons.add("unresolved_logged_pid")
            if not self.bindings:
                reasons.add("actual_speech_binding_unobserved")
            if not self.records:
                reasons.add("no_records_observed")
            if self.crashes:
                reasons.add("crash_observed_in_window")
            if self.probe_outcome != "PASS":
                reasons.add("synthetic_probe_not_passed")
            required = {"initialized", "started", "final", "fixture_matched", "stop_confirmed", "close_returned"} if probe.startswith("stt-") else {"initialized", "started", "completed" if probe == "tts-speak" else "stop_confirmed"}
            eof_checks = None
            if probe == "stt-owned-eof":
                required |= {"finite_eof_validated", "functional_pass"}
                eof_checks = finite_eof_checks(self.sdk, self.durations, self.fixture)
                if eof_checks["eofOrdering"]["outcome"] != "PASS":
                    reasons.add("finite_eof_ordering_unconfirmed")
            if any(self.sdk[name] == 0 for name in required):
                reasons.add("required_sdk_activity_missing")
            matches_by_role = {role: collections.Counter() for role in ROLES}
            for (uid, pid, kind), count in self.matches.items():
                identity = self.processes.get(pid)
                role = identity[1] if identity is not None and identity[0] == uid else "unknown_process"
                matches_by_role[role][kind] += count
            matched = bool(self.matches)
            tag_report = {}
            if self.include_matched_tags:
                tags_by_role = collections.defaultdict(collections.Counter)
                for (uid, pid, tag, kind), count in self.matched_tags.items():
                    identity = self.processes.get(pid)
                    role = identity[1] if identity is not None and identity[0] == uid else "unknown_process"
                    tags_by_role[(role, tag)][kind] += count
                tag_report = {"matchedTagObservation": {
                    "scope": "Fresh synthetic probe only; screened implementation identifiers, not a complete service tag inventory or privacy proof",
                    "maximumTags": MAX_MATCHED_TAGS, "maximumTagLength": MAX_MATCHED_TAG_LENGTH,
                    "rejectedTagCount": self.rejected_tag_count,
                    "rejectedTagReasonCounts": dict(sorted(self.rejected_tag_reasons.items())),
                    "counts": [{"role": role, "tag": tag, **{kind: counts[kind] for kind in TAG_MATCHES}}
                               for (role, tag), counts in sorted(tags_by_role.items())],
                }}
            return {
                "outcome": "MATCH_FOUND" if matched else "INCONCLUSIVE" if reasons else "NO_MATCH_OBSERVED",
                "reasonCodes": sorted(reasons), "pidCoverageComplete": not reasons,
                "inspectedRecords": self.records,
                "recordsByProcess": [{"uid": uid, "pid": pid, "count": count} for (uid, pid), count in sorted(self.counts.items())],
                "knownProcesses": [{"pid": pid, "uid": value[0], "role": value[1]} for pid, value in sorted(self.processes.items())],
                "actualBindings": [{"serviceUid": u, "servicePid": p, "clientUid": cu, "clientPid": cp} for u, p, cu, cp in sorted(self.bindings)],
                "matchCounts": {role: {kind: matches_by_role[role][kind] for kind in MATCHES} for role in ROLES},
                "crashIndicators": self.crashes, "sdkEventCounts": dict(self.sdk), "sdkElapsedMs": dict(self.durations),
                "fixtureTelemetry": dict(self.fixture),
                "probeOutcome": self.probe_outcome,
                "scope": "Synthetic exercised paths and bounded window only; not universal privacy or physical-microphone acceptance",
                **(eof_checks or {}),
                **tag_report,
            }


SDK_EVENTS = {
    "initialized": r"owned_input_connected_at_\d+ms|tts_connected",
    "ready": r"owned_input_ready_at_\d+ms|tts_ready",
    "started": r"owned_input_started_at_\d+ms|tts_started",
    "ended": r"owned_input_epd_ended_at_\d+ms",
    "final": r"owned_input_final_at_\d+ms",
    "fixture_matched": r"owned_input_fixture_matched",
    "completed": r"tts_completed",
    "send_requested": r"tts_speak_requested|owned_fixture_recording",
    "stop_confirmed": r"owned_input_stop_confirmed|tts_stopped",
    "stop_confirmed_at": r"owned_input_stop_confirmed_at_\d+ms",
    "close_requested": r"owned_input_close_requested_at_\d+ms",
    "close_returned": r"owned_input_close_returned",
    "ready_to_started": r"owned_input_ready_to_started_\d+ms",
    "ended_to_final": r"owned_input_ended_to_final_\d+ms",
    "late_results_rejected": r"owned_input_late_results_rejected",
    "first_speech_deadline_exceeded": r"owned_input_product_first_speech_deadline_exceeded",
    "final_grace_exceeded": r"owned_input_product_final_grace_exceeded",
    "request_entered": r"owned_input_request_entered_at_\d+ms",
    "eof_observed": r"owned_input_eof_observed_at_\d+ms",
    "capture_stop_finished": r"owned_input_capture_stop_finished_at_\d+ms",
    "complete_audio_send_entered": r"owned_input_complete_audio_send_entered_at_\d+ms",
    "complete_audio_send_returned": r"owned_input_complete_audio_send_returned_at_\d+ms",
    "sdk_ended_callback": r"owned_input_sdk_ended_callback_at_\d+ms",
    "sdk_final_callback": r"owned_input_sdk_final_callback_at_\d+ms",
    "stop_entered": r"owned_input_stop_entered_at_\d+ms",
    "finite_eof_validated": r"owned_input_finite_eof_validated",
    "functional_pass": r"owned_input_functional_pass",
    "product_timing_pass": r"owned_input_product_timing_pass",
    "product_timing_fail": r"owned_input_product_timing_fail",
    "product_timing_unverified": r"owned_input_product_timing_unverified",
}
FIXTURE_MILLIS = {
    "prefixSilenceMs": r"owned_fixture_prefix_silence_(\d+)ms",
    "firstSamplesAtMs": r"owned_fixture_first_samples_at_(\d+)ms",
    "firstNonzeroAtMs": r"owned_fixture_first_nonzero_at_(\d+)ms",
    "eofAtMs": r"owned_fixture_eof_at_(\d+)ms",
    "captureStopAtMs": r"owned_fixture_capture_stop_at_(\d+)ms",
}


def finite_eof_checks(events, elapsed, fixture):
    """Numeric callback-ingress ordering and product timing, separate from function/privacy.

    Millisecond equality cannot prove strict EOF-before-callback ordering.
    completeAudioSend return is a local call boundary, never an SDK flush ACK.
    """
    order_reasons = set()
    if events["finite_eof_validated"] != 1:
        order_reasons.add("finite_eof_validation_unconfirmed")
    ordered = ("eof_observed", "capture_stop_finished", "complete_audio_send_entered", "complete_audio_send_returned")
    needed = (*ordered, "sdk_final_callback")
    if any(events[name] != 1 or name not in elapsed for name in needed):
        order_reasons.add("required_lifecycle_timestamp_missing_or_repeated")
    else:
        times = [elapsed[name] for name in ordered]
        if times != sorted(times):
            order_reasons.add("capture_complete_call_order_invalid")
        if elapsed["eof_observed"] >= elapsed["sdk_final_callback"]:
            order_reasons.add("eof_before_final_unproven")
    if events["sdk_ended_callback"] > 1:
        order_reasons.add("ended_callback_repeated")
    elif events["sdk_ended_callback"] == 1:
        if "sdk_ended_callback" not in elapsed or "eof_observed" not in elapsed or elapsed["eof_observed"] >= elapsed["sdk_ended_callback"]:
            order_reasons.add("eof_before_ended_unproven")
    if not (fixture.get("originalSamples", 0) > fixture.get("trimmedTrailingZeroSamples", 0) > 0):
        order_reasons.add("exact_zero_trim_unconfirmed")
    if fixture.get("eofAtMs") is None:
        order_reasons.add("fixture_eof_unobserved")
    timing_reasons = set()
    timing = "UNVERIFIED"
    first_speech = final_grace = None
    boundaries = ("ready", "started", "ended", "final", "sdk_ended_callback", "sdk_final_callback")
    if any(events[name] != 1 or name not in elapsed for name in boundaries):
        timing_reasons.add("product_timing_boundary_missing_or_repeated")
    else:
        first_speech = elapsed["started"] - elapsed["ready"]
        final_grace = elapsed["final"] - elapsed["ended"]
        if first_speech < 0 or final_grace < 0:
            timing_reasons.add("product_callback_order_invalid")
        elif first_speech > 5_000 or final_grace > 500:
            timing = "FAIL"
            timing_reasons.add("product_timing_limit_exceeded")
        elif events["product_timing_pass"] == 1 and events["product_timing_fail"] == 0 and events["product_timing_unverified"] == 0:
            timing = "PASS"
        else:
            timing_reasons.add("product_timing_pass_unconfirmed")
    # Same-ms/absent EOF evidence cannot produce an overall timing acceptance PASS.
    if order_reasons and timing == "PASS":
        timing = "UNVERIFIED"
        timing_reasons.add("finite_eof_ordering_unconfirmed")
    return {
        "eofOrdering": {"outcome": "PASS" if not order_reasons else "INCONCLUSIVE",
                        "reasonCodes": sorted(order_reasons), "endedCallbackObserved": events["sdk_ended_callback"] > 0,
                        "scope": "Observed EOF, capture stop and synchronous completeAudioSend call boundaries; no SDK flush acknowledgment"},
        "timingAcceptance": {"outcome": timing, "reasonCodes": sorted(timing_reasons),
                             "readyToStartedMs": first_speech, "endedToFinalMs": final_grace,
                             "scope": "Unchanged Ready-to-Started 5000ms and Ended-to-Final 500ms limits; separate from functional probe outcome"},
    }


def positive_control():
    hits, _, _ = classify("SyntheticOnly", 'asrText="지금 지나가는 지역의 이야기를 알려 주세요."\njava.lang.RuntimeException: SYNTHETIC_ONLY\n at test.Class.run(Test.kt:1)')
    return hits == set(MATCHES)


def adb_location():
    local = ""
    path = ROOT / "local.properties"
    if path.is_file():
        for line in path.read_text().splitlines():
            if line.startswith("sdk.dir="):
                local = line.partition("=")[2].replace("\\ ", " ")
    for root in (os.getenv("ANDROID_SDK_ROOT"), os.getenv("ANDROID_HOME"), local, str(Path.home() / "Library/Android/sdk")):
        if root and (Path(root) / "platform-tools/adb").is_file():
            return str(Path(root) / "platform-tools/adb")
    raise AuditError("adb_unavailable")


def read_log_stream(stream, observation, stopping):
    decoder = BinaryDecoder(observation.consume)
    try:
        while data := stream.read1(8192):
            decoder.feed(data)
        decoder.finish()
        if not stopping.is_set():
            observation.issue("log_stream_ended_early")
    except AuditError as failure:
        observation.issue(str(failure))
    except Exception:
        observation.issue("log_stream_read_failed")


def read_stderr(stream, observation):
    # Even an unsupported-option error must not be echoed or written.
    while data := stream.read(4096):
        observation.issue("log_stream_stderr")


def read_instrumentation(stream, observation):
    total = 0
    while line := stream.readline(4097):
        total += len(line)
        if len(line) > 4096 or total > 1024 * 1024:
            observation.issue("instrumentation_output_limit")
            return
        try:
            observation.instrumentation(line.decode("utf-8", errors="strict").rstrip("\r\n"))
        except Exception:
            observation.issue("instrumentation_parse_failed")


def terminate(process):
    if process is not None and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=2)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=2)


def write_report(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=True) + "\n")


def observe(args):
    report = {"trialId": str(uuid.uuid4()), "probe": args.probe, "buildType": "privacy", "matchingStrategy": "synthetic_nfkc_alnum_full_and_min8char_fragments_shape_v2",
              "canaryIds": sorted(CANARIES), "positiveControlPassed": positive_control(),
              "startedAtUtc": datetime.datetime.now(datetime.timezone.utc).isoformat(), "outcome": "INCONCLUSIVE"}
    process = logcat = None
    threads = []
    stopping = threading.Event()
    observation = None
    started = time.monotonic()
    adb_base = None
    user = None
    try:
        if args.probe not in SYNTHETIC_PROBES:
            raise AuditError("synthetic_probe_required")
        if not re.fullmatch(r"emulator-\d+", args.serial):
            raise AuditError("emulator_serial_required")
        adb_base = [adb_location(), "-s", args.serial]
        def adb(*arguments, timeout=4):
            try:
                result = subprocess.run(adb_base + list(arguments), capture_output=True, timeout=timeout, check=False)
                if result.returncode != 0 or len(result.stdout) > 2 * 1024 * 1024:
                    raise AuditError("adb_metadata_failed")
                return result.stdout.decode("utf-8", errors="strict").strip()
            except (OSError, subprocess.SubprocessError, UnicodeError):
                raise AuditError("adb_metadata_failed") from None
        if "pleos" not in adb("shell", "getprop", "ro.build.fingerprint").lower() or adb("shell", "getprop", "ro.build.version.sdk") != "34":
            raise AuditError("pleos_api34_required")
        raw_user = adb("shell", "am", "get-current-user")
        if not raw_user.isdecimal():
            raise AuditError("android_user_unresolved")
        user = int(raw_user)
        paths = [ROOT / "app/build/outputs/apk/privacy/app-privacy.apk", ROOT / "app/build/outputs/apk/androidTest/privacy/app-privacy-androidTest.apk"]
        if not all(path.is_file() for path in paths):
            raise AuditError("matching_privacy_apks_required")
        report.update({"appApk": str(paths[0]), "testApk": str(paths[1]), "appSha256": hashlib.sha256(paths[0].read_bytes()).hexdigest(),
                       "testApkSha256": hashlib.sha256(paths[1].read_bytes()).hexdigest(), "androidUser": user})
        for path in paths:
            if adb("install", "--user", raw_user, "-r", str(path), timeout=25).splitlines()[-1:] != ["Success"]:
                raise AuditError("privacy_install_unconfirmed")
        app_uids = {package_uid(adb("shell", "cmd", "package", "list", "packages", "-U", "--user", raw_user, package), package, user) for package in (APP, TEST)}
        service_uids = {package_uid(adb("shell", "cmd", "package", "list", "packages", "-U", "--user", str(owner), SERVICE), SERVICE, owner) for owner in {0, user}}
        report["serviceArtifact"] = service_version(adb("shell", "dumpsys", "package", SERVICE))
        report["observedUids"] = sorted(app_uids | service_uids)
        # The explicit synthetic test replaces the process, but never uninstalls or clears data/keys.
        adb("shell", "am", "force-stop", "--user", raw_user, APP)
        raw_cursor = adb("shell", "date", "+%s")
        if not raw_cursor.isdecimal():
            raise AuditError("device_cursor_unresolved")
        report["startCursorEpochSeconds"] = int(raw_cursor)
        observation = Observation(app_uids, service_uids, int(raw_cursor) * 1_000_000_000,
                                  include_matched_tags=getattr(args, "include_matched_tags", False))
        def snapshot():
            processes = process_metadata(adb("shell", "ps", "-A", "-o", "PID,UID,NAME"), observation.allowed)
            bindings = service_bindings(adb("shell", "dumpsys", "activity", "services", COMPONENT), processes, app_uids, service_uids)
            observation.observe_processes(processes, bindings)
        logcat = subprocess.Popen(adb_base + ["logcat", "-B", "-b", "main", "-b", "system", "-b", "crash",
                                  "--uid=" + ",".join(str(uid) for uid in sorted(observation.allowed)), "-T", raw_cursor + ".000", "*:V"],
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        for target, stream in ((lambda stream: read_log_stream(stream, observation, stopping), logcat.stdout),
                               (lambda stream: read_stderr(stream, observation), logcat.stderr)):
            thread = threading.Thread(target=target, args=(stream,), daemon=True)
            threads.append(thread); thread.start()
        snapshot()
        time.sleep(0.3)
        if logcat.poll() is not None or observation.reasons:
            raise AuditError("observer_not_ready_before_probe")
        report["observerReadyBeforeProbe"] = True
        process = subprocess.Popen(adb_base + ["shell", "am", "instrument", "-w", "--user", raw_user, "-e", "probe", args.probe,
                                  TEST + "/com.petasos.app.diagnostics.SdkProbeInstrumentation"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        thread = threading.Thread(target=read_instrumentation, args=(process.stdout, observation), daemon=True)
        threads.append(thread); thread.start()
        deadline = time.monotonic() + 90
        while process.poll() is None and time.monotonic() < deadline:
            snapshot()
            if logcat.poll() is not None:
                observation.issue("log_stream_ended_early"); break
            time.sleep(0.25)
        if process.poll() is None:
            observation.issue("synthetic_probe_timeout")
            terminate(process)
            adb("shell", "am", "force-stop", "--user", raw_user, APP)
        elif process.returncode != 0:
            observation.issue("instrumentation_exit_failure")
        report["instrumentationExitCode"] = process.returncode
        post_until = time.monotonic() + 3
        while time.monotonic() < post_until:
            snapshot(); time.sleep(0.25)
        report["postCleanupWindowMs"] = 3_000
    except AuditError as failure:
        if observation:
            observation.issue(str(failure))
        else:
            report["reasonCodes"] = [str(failure)]
    except Exception:
        if observation:
            observation.issue("observer_operation_failed")
        else:
            report["reasonCodes"] = ["observer_operation_failed"]
    finally:
        if process is not None and process.poll() is None:
            terminate(process)
            if adb_base is not None and user is not None:
                try:
                    subprocess.run(adb_base + ["shell", "am", "force-stop", "--user", str(user), APP], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=4)
                except Exception:
                    if observation: observation.issue("device_cleanup_unconfirmed")
        stopping.set()
        terminate(logcat)
        for thread in threads:
            thread.join(timeout=2)
            if thread.is_alive() and observation:
                observation.issue("observer_thread_unconfirmed")
        for child in (process, logcat):
            if child is not None:
                for stream in (child.stdout, child.stderr):
                    if stream is not None:
                        stream.close()
        if observation:
            report.update(observation.report(args.probe))
        if not report["positiveControlPassed"]:
            report["outcome"] = "INCONCLUSIVE"
            report.setdefault("reasonCodes", []).append("positive_control_failed")
        report["logStreamExitCode"] = logcat.returncode if logcat else None
        report["elapsedMs"] = round((time.monotonic() - started) * 1_000)
        report["endedAtUtc"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        write_report(args.output, report)
    print(report["outcome"])
    return {"NO_MATCH_OBSERVED": 0, "MATCH_FOUND": 1, "INCONCLUSIVE": 2}[report["outcome"]]


def summarize_crashes(stream, output):
    counts = {"inspectedRecords": 0, "crashIndicators": 0, "stacktraceIndicators": 0}
    def consume(uid, pid, timestamp, tag, message):
        counts["inspectedRecords"] += 1
        if counts["inspectedRecords"] > MAX_RECORDS:
            raise AuditError("record_limit_exceeded")
        kinds, _, crash = classify(tag, message)
        counts["crashIndicators"] += int(crash)
        counts["stacktraceIndicators"] += int("stacktrace_sink" in kinds)
    decoder = BinaryDecoder(consume)
    reason = None
    try:
        while data := stream.read(8192): decoder.feed(data)
        decoder.finish()
    except AuditError as failure:
        reason = str(failure)
    except Exception:
        reason = "crash_stream_read_failed"
    outcome = "INCONCLUSIVE" if reason else "FAIL" if counts["crashIndicators"] else "PASS"
    write_report(output, {"outcome": outcome, **counts, "reasonCode": reason, "scope": "UID-filtered smoke crash indicators only; no raw logs retained"})
    return 0 if outcome == "PASS" else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    observe_parser = commands.add_parser("observe")
    observe_parser.add_argument("probe", choices=SYNTHETIC_PROBES)
    observe_parser.add_argument("--serial", default="emulator-5554")
    observe_parser.add_argument("--output", type=Path, required=True)
    observe_parser.add_argument("--include-matched-tags", action="store_true",
                                help="Include bounded screened tag counts for this fresh synthetic probe only; never messages")
    summary_parser = commands.add_parser("summarize-crashes")
    summary_parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        return observe(args) if args.command == "observe" else summarize_crashes(sys.stdin.buffer, args.output)
    except Exception:
        print("INCONCLUSIVE")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
