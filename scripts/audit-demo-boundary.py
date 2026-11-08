#!/usr/bin/env python3
"""Bounded static inspection of a demo manifest and direct source references.

No APK execution, device access, Kotlin compilation, transitive call analysis or runtime
isolation proof is performed. Reports contain fixed codes, counts and input hashes only.
"""

import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
import xml.etree.ElementTree as ET


ANDROID = "{http://schemas.android.com/apk/res/android}"
PACKAGE = "com.petasos.app.demo"
UI_ACTIVITY = PACKAGE + ".DemoActivity"
PROFILES = ("engine", "ui")
MANIFEST_LIMIT = 1024 * 1024
SOURCE_LIMIT = 512 * 1024
TOTAL_SOURCE_LIMIT = 8 * 1024 * 1024
FILE_LIMIT = 512
SCOPE = "Static demo manifest and direct source-reference inspection; not runtime isolation proof"

# Exact package boundaries and type names. Pure RuntimeSpeech/RuntimeMusic, planners,
# location models, public bundled catalogs and Room stores remain usable.
FORBIDDEN_PACKAGES = (
    "ai.pleos", "com.petasos.app.platform.pleos", "com.petasos.app.platform.media",
    "com.petasos.app.platform.android.summary", "android.speech", "android.location",
    "android.media", "android.webkit", "androidx.media3", "androidx.work",
    "java.net", "javax.net", "okhttp3", "retrofit2", "io.ktor.client",
    "java.lang.reflect", "kotlin.reflect", "dalvik.system",
)
FORBIDDEN_TYPES = frozenset((
    "PetasosApplication", "MainActivity", "PetasosSessionHost", "PetasosMediaService",
    "PlaybackControls", "TripControls", "MusicCreditsControls",
    "SummaryBackground", "SummaryWorker", "SummaryWorkScheduler",
    "PleosRuntimeSpeech", "PleosSpeechInput", "PleosSpeechOutput", "PleosPcmSpeechInput",
    "AndroidPcmRecorder", "SpeechServiceEnvironment", "PcmCaptureSession",
    "PleosLocationSource", "AndroidLocationSource", "PleosAuthorizedLocationSource",
    "LocationAccess", "PleosNavigationSource", "Media3RuntimeMusic", "CoordinatorSessionPlayer",
    "GenerationClient", "GenerationSummaryProvider", "ApiRuntimeGeneration",
    "GenerationTransport", "HttpsGenerationTransport", "DeviceGenerationConfigStore",
    "AuxiliaryRouteClient", "ApiRuntimeResumePlanner",
    "SpeechRecognizer", "RecognizerIntent", "TextToSpeech", "SpeechToText",
    "AudioRecord", "AudioTrack", "MediaRecorder", "MediaPlayer", "ExoPlayer",
    "LocationManager", "FusedLocation", "NaviHelper", "HttpURLConnection",
    "HttpsURLConnection", "URLConnection", "URL", "Socket", "SSLSocket", "OkHttpClient",
    "WebView", "ClassLoader", "DexClassLoader", "InMemoryDexClassLoader",
))
FORBIDDEN_MEMBERS = frozenset((
    "getSystemService", "bindService", "bindIsolatedService", "startService", "startForegroundService",
    "startActivity", "requestPermissions", "createPackageContext", "createContextAsUser",
    "getSharedPreferences", "getContentResolver", "contentResolver", "sendBroadcast", "registerReceiver",
    "forName", "loadClass", "loadLibrary",
))
FORBIDDEN_QUALIFIED = (
    "System.load", "System.getenv", "System.getProperty", "Runtime.getRuntime",
    "java.lang.System.load", "java.lang.System.getenv", "java.lang.System.getProperty", "java.lang.Runtime.getRuntime",
)


class AuditError(Exception):
    """Only fixed codes may cross the tool boundary."""


def digest(data):
    return hashlib.sha256(data).hexdigest()


def read_bounded(path, limit):
    if path.is_symlink() or not path.is_file():
        raise AuditError("INPUT_NOT_REGULAR_FILE")
    with path.open("rb") as source:
        data = source.read(limit + 1)
    if len(data) > limit:
        raise AuditError("INPUT_TOO_LARGE")
    return data


def ui_activity_checks(activity):
    """One explicit launcher only. No aliases, additional entry filters or hidden hooks."""
    failures = set()
    if activity.tag != "activity" or activity.attrib != {
            ANDROID + "name": UI_ACTIVITY, ANDROID + "exported": "true"}:
        failures.add("MANIFEST_UI_ACTIVITY")
    filters = list(activity)
    if len(filters) != 1 or filters[0].tag != "intent-filter" or filters[0].attrib:
        failures.add("MANIFEST_UI_LAUNCHER_FILTER")
    else:
        entries = list(filters[0])
        expected = {("action", "android.intent.action.MAIN"), ("category", "android.intent.category.LAUNCHER")}
        actual = {(node.tag, node.get(ANDROID + "name")) for node in entries}
        if len(entries) != 2 or actual != expected or any(
                set(node.attrib) != {ANDROID + "name"} or list(node) for node in entries):
            failures.add("MANIFEST_UI_LAUNCHER_FILTER")
    return failures


def manifest_checks(data, profile="engine"):
    if profile not in PROFILES:
        raise AuditError("PROFILE_UNSUPPORTED")
    if len(data) > MANIFEST_LIMIT:
        raise AuditError("INPUT_TOO_LARGE")
    try:
        document = data.decode("utf-8-sig")
    except UnicodeDecodeError:
        raise AuditError("MANIFEST_ENCODING_INVALID") from None
    if "\x00" in document:
        raise AuditError("MANIFEST_ENCODING_INVALID")
    # ElementTree does not fetch external entities; reject declarations as well so
    # neither internal expansion nor a different XML backend can widen the contract.
    if re.search(r"<!\s*(?:DOCTYPE|ENTITY)\b", document, re.IGNORECASE):
        raise AuditError("XML_DECLARATION_FORBIDDEN")
    try:
        root = ET.fromstring(document)
    except ET.ParseError:
        raise AuditError("MANIFEST_XML_INVALID") from None
    nodes = list(root.iter())
    if len(nodes) > 2048:
        raise AuditError("MANIFEST_NODE_LIMIT")
    failures = set()
    if root.tag != "manifest" or root.get("package") != PACKAGE:
        failures.add("MANIFEST_PACKAGE")
    if any(name.rsplit("}", 1)[-1].startswith("sharedUser") for name in root.attrib):
        failures.add("MANIFEST_SHARED_UID")
    if any(node.tag == "queries" for node in nodes):
        failures.add("MANIFEST_QUERIES")
    if any(node.tag == "instrumentation" for node in nodes):
        failures.add("MANIFEST_INSTRUMENTATION")
    permissions = [node for node in nodes if node.tag in ("permission", "permission-tree", "permission-group")
                   or (isinstance(node.tag, str) and node.tag.startswith("uses-permission"))]
    if permissions:
        failures.add("MANIFEST_PERMISSIONS")
    components = [node for node in nodes if node.tag in ("activity", "activity-alias", "service", "provider", "receiver")]
    if (profile == "engine" and components) or (profile == "ui" and (
            len(components) != 1 or components[0].tag != "activity")):
        failures.add("MANIFEST_COMPONENTS")
    apps = root.findall("application")
    if len(apps) != 1:
        failures.add("MANIFEST_APPLICATION_COUNT")
    else:
        app = apps[0]
        if app.get(ANDROID + "name") != "android.app.Application":
            failures.add("MANIFEST_APPLICATION_CLASS")
        if app.get(ANDROID + "allowBackup") != "false":
            failures.add("MANIFEST_BACKUP")
        if app.get(ANDROID + "usesCleartextTraffic") != "false":
            failures.add("MANIFEST_CLEARTEXT")
        if profile == "engine":
            if list(app):
                failures.add("MANIFEST_APPLICATION_CHILDREN")
        elif len(app) != 1 or app[0].tag != "activity":
            failures.add("MANIFEST_APPLICATION_CHILDREN")
        else:
            failures.update(ui_activity_checks(app[0]))
    if any(ANDROID + "networkSecurityConfig" in node.attrib for node in nodes):
        failures.add("MANIFEST_NETWORK_CONFIG")
    if any(any(ANDROID + name in node.attrib for name in ("appComponentFactory", "backupAgent", "process"))
           for node in nodes):
        failures.add("MANIFEST_EXECUTION_HOOK")
    # Input must be a final merged manifest, not an overlay with unprocessed tools rules.
    if any(any(name.startswith("{http://schemas.android.com/tools}") for name in node.attrib) for node in nodes):
        failures.add("MANIFEST_UNMERGED")
    if any(node.tag not in {"uses-sdk", "uses-feature", "supports-screens", "application"} for node in root):
        failures.add("MANIFEST_UNEXPECTED_ELEMENT")
    return {"codes": sorted(failures), "permissionCount": len(permissions), "componentCount": len(components)}


def source_tokens(text, java=False):
    """Small lexical scanner, not a Kotlin parser. Comments/literals are not API references.

    Kotlin string-template expressions *are* executable code and are scanned recursively.
    Exact escaped identifiers, import aliases and qualified names are retained as tokens.
    """
    if java and re.search(r"\\u+[0-9a-fA-F]{4}", text):
        raise AuditError("SOURCE_UNSUPPORTED_UNICODE_ESCAPE")
    result = []
    length = len(text)

    def scan(start, template=False, depth=0):
        if depth > 32:
            raise AuditError("SOURCE_NESTING_LIMIT")
        index, braces = start, 0
        while index < length:
            char = text[index]
            if char.isspace():
                index += 1
            elif text.startswith("//", index):
                end = text.find("\n", index + 2)
                index = length if end == -1 else end + 1
            elif text.startswith("/*", index):
                index += 2
                nesting = 1
                while index < length and nesting:
                    if text.startswith("/*", index):
                        nesting += 1; index += 2
                    elif text.startswith("*/", index):
                        nesting -= 1; index += 2
                    else:
                        index += 1
                if nesting:
                    raise AuditError("SOURCE_LEXICAL_INVALID")
            elif char in ("\"", "'"):
                quote = '"""' if text.startswith('"""', index) else char
                index += len(quote)
                result.append("<literal>")
                while index < length:
                    if text.startswith(quote, index):
                        index += len(quote)
                        break
                    if quote != '"""' and text[index] == "\\":
                        index += 2
                    elif not java and char == '"' and text.startswith("${", index):
                        index = scan(index + 2, template=True, depth=depth + 1)
                    elif not java and char == '"' and text[index] == "$":
                        match = re.compile(r"[A-Za-z_][A-Za-z_0-9]*").match(text, index + 1)
                        if match:
                            result.append(match.group()); index += 1 + len(match.group())
                        else:
                            index += 1
                    else:
                        index += 1
                else:
                    raise AuditError("SOURCE_LEXICAL_INVALID")
                result.append("<literal>")
            elif char == "`":
                end = text.find("`", index + 1)
                if end < 0 or "\n" in text[index + 1:end]:
                    raise AuditError("SOURCE_LEXICAL_INVALID")
                result.append(text[index + 1:end]); index = end + 1
            elif char.isalpha() or char in "_$":
                end = index + 1
                while end < length and (text[end].isalnum() or text[end] in "_$"):
                    end += 1
                result.append(text[index:end]); index = end
            else:
                if template and char == "}" and braces == 0:
                    return index + 1
                if char == "{":
                    braces += 1
                elif char == "}":
                    braces -= 1
                result.append(char); index += 1
        if template:
            raise AuditError("SOURCE_LEXICAL_INVALID")
        return index

    scan(0)
    return result


def source_checks(data, java=False):
    try:
        tokens = source_tokens(data.decode("utf-8"), java)
    except UnicodeDecodeError:
        raise AuditError("SOURCE_ENCODING_INVALID") from None
    failures = set()
    if FORBIDDEN_TYPES.intersection(tokens):
        failures.add("SOURCE_FORBIDDEN_TYPE")
    if FORBIDDEN_MEMBERS.intersection(tokens):
        failures.add("SOURCE_FORBIDDEN_MEMBER")
    # Inspect token sequences so whitespace/comments cannot conceal a qualified import
    # or call; substring matches such as RuntimeSpeech/URLLabel do not cause a hit.
    chains = []
    for index, token in enumerate(tokens):
        # Match the complete qualified name, not an inner suffix: the safe app package
        # platform.android.location must not be mistaken for the Android location API.
        if not token.isidentifier() or (index > 0 and tokens[index - 1] == "."):
            continue
        parts = [token]
        cursor = index + 1
        while cursor + 1 < len(tokens) and tokens[cursor] == "." and (
                tokens[cursor + 1].isidentifier() or tokens[cursor + 1] == "*"):
            parts.append(tokens[cursor + 1]); cursor += 2
        chains.append(".".join(parts))
    for name in chains:
        if any(name == prefix or name.startswith(prefix + ".") for prefix in FORBIDDEN_PACKAGES):
            failures.add("SOURCE_FORBIDDEN_PACKAGE")
        if name.endswith(".*") and any(prefix.startswith(name[:-1]) for prefix in FORBIDDEN_PACKAGES):
            failures.add("SOURCE_FORBIDDEN_PACKAGE")
        if any(name == prefix or name.startswith(prefix + ".") for prefix in FORBIDDEN_QUALIFIED):
            failures.add("SOURCE_FORBIDDEN_MEMBER")
    return sorted(failures)


def audit(manifest, source_dir, profile="engine"):
    report = {"formatVersion": 1, "status": "FAIL", "scope": SCOPE,
              "runtimeIsolationVerified": False, "transitiveCallsInspected": False,
              "profile": profile if profile in PROFILES else "unsupported",
              "expectedPackage": PACKAGE, "manifestSha256": None, "sourceFiles": [], "violations": []}
    try:
        data = read_bounded(manifest, MANIFEST_LIMIT)
        report["manifestSha256"] = digest(data)
        manifest_result = manifest_checks(data, profile)
        report["manifest"] = {key: value for key, value in manifest_result.items() if key != "codes"}
        report["violations"].extend({"code": code} for code in manifest_result["codes"])
        if source_dir.is_symlink() or not source_dir.is_dir():
            raise AuditError("SOURCE_DIRECTORY_INVALID")
        paths, visited = [], 0
        for directory, directories, files in os.walk(source_dir, followlinks=False):
            visited += len(directories) + len(files)
            if visited > FILE_LIMIT:
                raise AuditError("SOURCE_ENTRY_LIMIT")
            for name in directories + files:
                if (Path(directory) / name).is_symlink():
                    raise AuditError("SOURCE_SYMLINK")
            paths.extend(Path(directory) / name for name in files if Path(name).suffix in (".kt", ".java"))
        if not paths:
            raise AuditError("SOURCE_FILES_MISSING")
        total = 0
        for index, path in enumerate(sorted(paths), 1):
            data = read_bounded(path, SOURCE_LIMIT)
            total += len(data)
            if total > TOTAL_SOURCE_LIMIT:
                raise AuditError("SOURCE_TOTAL_LIMIT")
            entry = {"index": index, "sha256": digest(data), "bytes": len(data),
                     "relativePathSha256": digest(path.relative_to(source_dir).as_posix().encode("utf-8"))}
            report["sourceFiles"].append(entry)
            report["violations"].extend({"code": code, "sourceIndex": index}
                                        for code in source_checks(data, path.suffix == ".java"))
        report["sourceBytes"] = total
    except AuditError as error:
        report["violations"].append({"code": str(error)})
    except (OSError, ValueError):
        report["violations"].append({"code": "INPUT_UNAVAILABLE"})
    report["sourceFileCount"] = len(report["sourceFiles"])
    report["violationCounts"] = dict(sorted(Counter(item["code"] for item in report["violations"]).items()))
    report["status"] = "FAIL" if report["violations"] else "PASS"
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--source-dir", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--profile", choices=PROFILES, default="engine",
                        help="engine forbids all components; ui allows only the exact DemoActivity launcher")
    args = parser.parse_args(argv)
    report = audit(args.manifest, args.source_dir, args.profile)
    temporary = None
    try:
        destination = args.output.resolve()
        if destination == args.manifest.resolve() or destination.is_relative_to(args.source_dir.resolve()):
            raise AuditError("OUTPUT_OVERLAPS_INPUT")
        if args.output.is_symlink():
            raise AuditError("OUTPUT_SYMLINK")
        destination.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=destination.parent,
                                         prefix=".demo-audit-", delete=False) as output:
            temporary = Path(output.name)
            json.dump(report, output, ensure_ascii=True, indent=2, sort_keys=True)
            output.write("\n")
        temporary.replace(destination)
    except (AuditError, OSError, ValueError):
        if temporary is not None:
            temporary.unlink(missing_ok=True)
        print(json.dumps({"status": "FAIL", "code": "REPORT_WRITE_REJECTED", "scope": SCOPE}))
        return 2
    print(json.dumps({"status": report["status"], "profile": report["profile"], "sourceFileCount": report["sourceFileCount"],
                      "violationCounts": report["violationCounts"], "runtimeIsolationVerified": False}))
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
