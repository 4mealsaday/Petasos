#!/usr/bin/env python3
"""Inspect executable DEX instructions, not string-table hits, for SDK logging sinks.

This is artifact evidence for the known client paths, not a general taint analysis or
a certification of the separate Pleos Speech service. No device/logcat access occurs.
"""

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

SPEECH = ("Lai/pleos/playground/stt/", "Lai/pleos/playground/tts/")
APP = "Lcom/petasos/app/"
CLASS = re.compile(r"^  Class descriptor\s+: '([^']+)'$")
MEMBER = re.compile(r"^      (name|type)\s+: '([^']+)'$")
# Match the first instruction delimiter only. A quoted const-string may itself contain
# dexdump-looking text; method/field string-table entries are not executable calls.
INSTRUCTION = r"^[0-9a-f]+:\s+(?:[0-9a-f]{4}\s+)+\|[0-9a-f]+: "
INVOKE = re.compile(INSTRUCTION + r"invoke-[\w/-]+ \{[^}]*\}, (\S+) // method@")
FIELD_ACCESS = re.compile(INSTRUCTION + r"sget-object [^,]+, (Ljava/lang/System;\.(?:out|err):\S+)")
ANNOTATION_MEMBER = re.compile(r"^Annotations on (field|method) #\d+ '([^']+)'$")

# Exact field descriptors observed in the pinned 3.3.1 APK. Name-only preservation
# is insufficient for Gson list elements, primitive values and Parcelable creators.
P = "Lai/pleos/playground/"
STRING = "Ljava/lang/String;"
MODELS = {
    "stt/constant/ConversationInfo": {"conversationId": STRING, "conversationSeq": STRING},
    "stt/constant/SttFinalResult": {"asrText": STRING, "speakerId": STRING, "epdOn": "J", "epdOff": "J"},
    "stt/data/RecognitionFinalResult": {"epdOn": "J", "epdOff": "J", "results": "Ljava/util/List;",
        "speaker": P + "stt/data/Speaker;", "inferenceSource": P + "stt/data/InferenceSource;"},
    "stt/data/RecognitionResult": {"alternatives": "Ljava/util/List;", "languageCode": STRING,
        "speaker": P + "stt/data/Speaker;", "isWakeUpWord": "Z"},
    "stt/data/Alternative": {"transcript": STRING, "confidence": "F", "tokenSequence": STRING},
    "stt/data/Speaker": {"speakerId": STRING},
    "stt/data/InferenceSource": {"ON_DEVICE": P + "stt/data/InferenceSource;", "SERVER": P + "stt/data/InferenceSource;"},
    "stt/data/WakeUpWordConfig": {"name": STRING, "localeCode": STRING, "audio": "[B", "onset": "F",
        "offset": "F", "probability": "F", "version": STRING},
    "stt/data/LMBoostConfig": {"phrases": STRING, "triggers": STRING},
    "caas/service/speech/data/tts/AudioInfo": {"zoneId": "I"},
    "caas/service/speech/data/tts/AudioAttributesParcel": {"CREATOR": P + "caas/service/speech/data/tts/AudioAttributesParcel$CREATOR;"},
}
MODEL_CONSTRUCTORS = {
    "stt/constant/ConversationInfo": {"(Ljava/lang/String;Ljava/lang/String;)V"},
    "stt/constant/SttFinalResult": {"()V"},
    "stt/data/RecognitionFinalResult": {"()V"},
    "stt/data/RecognitionResult": {"()V"},
    "stt/data/Alternative": {"()V"},
    "stt/data/Speaker": {"()V"},
    "stt/data/InferenceSource": {"(Ljava/lang/String;I)V"},
    "stt/data/WakeUpWordConfig": {"()V"},
    "stt/data/LMBoostConfig": {"()V"},
    "caas/service/speech/data/tts/AudioInfo": {"(I)V"},
    "caas/service/speech/data/tts/AudioAttributesParcel": {"(Landroid/os/Parcel;)V", "(Landroid/media/AudioAttributes;)V"},
}


def scope(descriptor):
    if descriptor.startswith(SPEECH):
        return "speech_sdk"
    return "app" if descriptor.startswith(APP) else "other_library"


def sink_kind(target):
    if target.startswith("Landroid/util/Log;."):
        return "android_log"
    if ".printStackTrace:" in target:
        return "stack_trace"
    if target.startswith(("Ljava/io/PrintStream;.print", "Ljava/io/PrintWriter;.print")):
        return "print_stream_or_writer"
    if target.startswith("Ljava/util/logging/Logger;."):
        return "java_logger"
    if target.startswith("Ljava/lang/System;.out:") or target.startswith("Ljava/lang/System;.err:"):
        return "system_stream"
    if target.startswith(("Ljava/lang/reflect/Method;.invoke:", "Ljava/lang/reflect/Constructor;.newInstance:",
                          "Ljava/lang/Class;.forName:", "Ljava/lang/ClassLoader;.loadClass:",
                          "Ldalvik/system/DexClassLoader;.", "Ldalvik/system/InMemoryDexClassLoader;.")):
        return "reflection_or_dynamic_loading"
    if target.startswith(("Ljava/lang/System;.load", "Ljava/lang/Runtime;.load")):
        return "native_loading"
    return None


class DexInventory:
    def __init__(self):
        self.classes = {}
        self.sinks = Counter()
        self.sdk_calls = Counter()
        self.app_speech_calls = Counter()
        self.invocations = 0
        self.native_methods = []

    def parse(self, lines):
        owner = ""
        section = ""
        name = ""
        signature = ""
        pending_annotations = []
        annotation_target = ("class", "")
        in_annotations = False
        in_code = False
        for line in lines:
            line = line.rstrip("\n")
            if re.match(r"^Class #\d+ annotations:$", line):
                pending_annotations = []
                in_annotations = True
                annotation_target = ("class", "")
                continue
            if in_annotations:
                if line.startswith("Class #"):
                    in_annotations = False
                elif match := ANNOTATION_MEMBER.match(line):
                    annotation_target = (match[1], match[2])
                elif line.startswith("Annotations on "):
                    annotation_target = ("class" if line == "Annotations on class" else "unknown", "")
                elif "Ldalvik/annotation/Signature;" in line or "Lcom/google/gson/annotations/SerializedName;" in line:
                    pending_annotations.append((*annotation_target, line.strip()))
            match = CLASS.match(line)
            if match:
                owner = match[1]
                if owner in self.classes:
                    raise ValueError("duplicate_class_descriptor")
                self.classes[owner] = {"fields": {}, "methods": {}, "annotations": pending_annotations}
                pending_annotations = []
                section = ""
                name = signature = ""
                in_code = False
            elif line.strip() in ("Static fields     -", "Instance fields   -"):
                section = "fields"
            elif line.strip() in ("Direct methods    -", "Virtual methods   -"):
                section = "methods"
            elif match := MEMBER.match(line):
                if match[1] == "name":
                    name = match[2]
                    in_code = False
                elif owner and section:
                    signature = match[2]
                    self.classes[owner][section][name + (":" + signature if section == "methods" else "")] = (
                        {"codeUnits": 0} if section == "methods" else signature
                    )
            elif line.strip() == "code          -" and section == "methods":
                in_code = True
            elif "access        :" in line and "NATIVE" in line and section == "methods":
                self.native_methods.append(owner + "." + name + ":" + signature)
            elif "insns size" in line and section == "methods":
                size = re.search(r": (\d+) 16-bit", line)
                if size:
                    self.classes[owner][section][name + ":" + signature]["codeUnits"] = int(size[1])
            if in_code and (match := INVOKE.match(line)):
                target = match[1]
                self.invocations += 1
                caller = owner + "." + name + ":" + signature
                kind = sink_kind(target)
                if kind:
                    self.sinks[(scope(owner), kind, caller, target)] += 1
                if owner.startswith(SPEECH):
                    self.sdk_calls[(caller, target)] += 1
                if owner.startswith(APP) and target.startswith(SPEECH):
                    self.app_speech_calls[target] += 1
            if in_code and (match := FIELD_ACCESS.match(line)):
                caller = owner + "." + name + ":" + signature
                self.sinks[(scope(owner), "system_stream", caller, match[1])] += 1

    def report(self):
        checks = {}
        for suffix, fields in MODELS.items():
            desc = "Lai/pleos/playground/" + suffix + ";"
            model = self.classes.get(desc, {})
            checks[suffix] = {
                "present": bool(model),
                "fieldsPreserved": all(model.get("fields", {}).get(name) == descriptor for name, descriptor in fields.items()),
                "constructorsPreserved": all("<init>:" + signature in model.get("methods", {})
                                             for signature in MODEL_CONSTRUCTORS[suffix]),
            }
        for suffix, field, generic in (("RecognitionFinalResult", "results", "RecognitionResult"),
                                       ("RecognitionResult", "alternatives", "Alternative")):
            desc = P + "stt/data/" + suffix + ";"
            expected = "Ljava/util/List<" + P + "stt/data/" + generic + ";>;"
            checks["stt/data/" + suffix]["genericListSignaturePreserved"] = any(
                kind == "field" and name == field and "Ldalvik/annotation/Signature;" in value and
                "".join(re.findall(r'"([^"\\]*)"', value)) == expected
                for kind, name, value in self.classes.get(desc, {}).get("annotations", [])
            )
        enum = self.classes.get(P + "stt/data/InferenceSource;", {})
        checks["stt/data/InferenceSource"]["serializedNamesPreserved"] = all(
            any(kind == "field" and name == field and "Lcom/google/gson/annotations/SerializedName;" in value and
                re.search(r'\bvalue="' + expected + r'"(?:\s|$)', value)
                for kind, name, value in enum.get("annotations", []))
            for field, expected in (("ON_DEVICE", "on_device"), ("SERVER", "server"))
        )
        creator = self.classes.get(P + "caas/service/speech/data/tts/AudioAttributesParcel$CREATOR;", {})
        checks["caas/service/speech/data/tts/AudioAttributesParcel"]["creatorClassAndMethodsPreserved"] = all(
            method in creator.get("methods", {}) for method in (
                "createFromParcel:(Landroid/os/Parcel;)" + P + "caas/service/speech/data/tts/AudioAttributesParcel;",
                "newArray:(I)[" + P + "caas/service/speech/data/tts/AudioAttributesParcel;",
            )
        )
        sdk_sinks = [dict(scope=s, kind=k, caller=c, target=t, count=n) for (s, k, c, t), n in sorted(self.sinks.items()) if s == "speech_sdk"]
        all_sink_counts = Counter()
        for (s, k, _, t), n in self.sinks.items():
            all_sink_counts[(s, k, t)] += n
        controls = {}
        for owner, method in (("stt/SpeechToText", "sendAudio"), ("stt/SpeechToText", "completeAudioSend"),
                              ("stt/SpeechToText", "stop"), ("tts/TextToSpeech", "speak"), ("tts/TextToSpeech", "stop")):
            prefix = "Lai/pleos/playground/" + owner + ";." + method + ":"
            controls[owner + "." + method] = sum(n for t, n in self.app_speech_calls.items() if t.startswith(prefix))
        # This check is intentionally narrow: further INFO/WARN/ERROR path review remains necessary.
        known = [x for x in sdk_sinks if x["target"] == "Landroid/util/Log;.d:(Ljava/lang/String;Ljava/lang/String;)I"
                 or (x["kind"] == "stack_trace" and x["target"].endswith(".printStackTrace:()V"))]
        info_helper = sum(n for (_, target), n in self.sdk_calls.items()
                          if target == "Lai/pleos/playground/stt/utils/Log;.c:(Ljava/lang/String;Ljava/lang/String;)V")
        return {
            "classCount": len(self.classes), "executableInvokeCount": self.invocations,
            "knownDebugAndStackSinksAbsentFromSpeechSdk": not known,
            "sttRawInfoHelperCallCount": info_helper,
            "reflectionModelChecks": checks,
            "modelChecksPass": all(all(c.values()) for c in checks.values()),
            "appSpeechControlCallCounts": controls,
            "appSpeechControlsReferenced": all(count > 0 for count in controls.values()),
            "functionalBehaviorVerified": False,
            "speechSdkSinkInventory": sdk_sinks,
            "allExecutableSinkCounts": [dict(scope=s, kind=k, target=t, count=n) for (s, k, t), n in sorted(all_sink_counts.items())],
            "speechSdkNativeMethods": [m for m in self.native_methods if m.startswith(SPEECH)],
            "scope": "all_app_dex_executable_instructions_and_pinned_reflective_models_not_full_taint_analysis",
            "limitations": ["Method/reference preservation does not prove non-no-op behavior; functional SDK probes remain necessary.",
                            "Known sink absence does not prove privacy of INFO/WARN/ERROR arguments or the separate Speech service."],
            "serviceInspected": False,
        }


def audit(apk, dexdump):
    inventory = DexInventory()
    dex_info = []
    with tempfile.TemporaryDirectory(prefix="petasos-dex-") as folder, zipfile.ZipFile(apk) as archive:
        names = sorted(n for n in archive.namelist() if re.fullmatch(r"classes(?:\d+)?\.dex", n))
        if not names:
            raise ValueError("apk_has_no_dex")
        for name in names:
            content = archive.read(name)
            path = Path(folder) / name
            path.write_bytes(content)
            dex_info.append({"name": name, "sha256": hashlib.sha256(content).hexdigest()})
            with subprocess.Popen([str(dexdump), "-a", "-d", str(path)], stdout=subprocess.PIPE,
                                  stderr=subprocess.DEVNULL, text=True, errors="replace") as process:
                inventory.parse(process.stdout)
                if process.wait() != 0:
                    raise ValueError("dexdump_failed")
            path.unlink()
    result = inventory.report()
    result.update({"apk": str(apk.resolve()), "apkSha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
                   "dexFiles": dex_info, "dexdump": str(dexdump.resolve()),
                   "dexdumpSha256": hashlib.sha256(dexdump.read_bytes()).hexdigest()})
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--dexdump", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = audit(args.apk, args.dexdump)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({k: report[k] for k in ("apkSha256", "classCount", "executableInvokeCount",
                                           "knownDebugAndStackSinksAbsentFromSpeechSdk", "sttRawInfoHelperCallCount", "modelChecksPass")}))


if __name__ == "__main__":
    main()
