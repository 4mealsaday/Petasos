"""DEX audit false-pass/false-hit regressions using Android dexdump-shaped text only."""

import hashlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SPEC = importlib.util.spec_from_file_location("audit_speech_apk", Path(__file__).parents[1] / "audit-speech-apk.py")
audit = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(audit)
P = "Lai/pleos/playground/"
SDK = P + "stt/service/SttCallback;"
APP = "Lcom/petasos/app/platform/pleos/speech/PleosPcmSpeechInput;"
LOG = "Landroid/util/Log;.d:(Ljava/lang/String;Ljava/lang/String;)I"
INFO = P + "stt/utils/Log;.c:(Ljava/lang/String;Ljava/lang/String;)V"


def instruction(opcode):
    return "1b3a96: 6e30 6711 1002                         |000d: " + opcode


def call(target, opcode="invoke-virtual"):
    return instruction(opcode + " {v0, v1, v2}, " + target + " // method@1167")


def dump(owner, fields=None, methods=None, annotations=(), number=0):
    lines = []
    if annotations:
        lines += [f"Class #{number} annotations:", *annotations, ""]
    lines += [f"Class #{number}            -", f"  Class descriptor  : '{owner}'", "  Instance fields   -"]
    for name, descriptor in (fields or {}).items():
        lines += [f"      name          : '{name}'", f"      type          : '{descriptor}'"]
    lines += ["  Direct methods    -"]
    for name, signature, body in methods or []:
        lines += [f"      name          : '{name}'", f"      type          : '{signature}'",
                  "      access        : 0x0001 (PUBLIC)", "      code          -",
                  "      insns size    : 6 16-bit code units", *body]
    return lines


def generic(field, target, kind="field"):
    return [f"Annotations on {kind} #12 '{field}'",
            '  VISIBILITY_SYSTEM Ldalvik/annotation/Signature; value={ "Ljava/util/List<" "' +
            P + "stt/data/" + target + ';" ">;" }']


def enum_names(first="on_device", second="server"):
    lines = []
    for field, value in (("ON_DEVICE", first), ("SERVER", second)):
        lines += [f"Annotations on field #5 '{field}'",
                  f'  VISIBILITY_RUNTIME Lcom/google/gson/annotations/SerializedName; value="{value}"']
    return lines


def model_check(suffix, annotations=(), fields=None, methods=None):
    inv = audit.DexInventory()
    inv.parse(dump(P + suffix + ";", fields or {}, methods, annotations))
    return inv.report()["reflectionModelChecks"][suffix]


class ExecutableInstructionTest(unittest.TestCase):
    def test_real_normal_range_and_empty_register_invokes_are_counted(self):
        inv = audit.DexInventory()
        inv.parse(dump(SDK, methods=[("onResponse", "()V", [call(LOG), call(INFO, "invoke-static/range"),
            instruction("invoke-static {}, " + INFO + " // method@1167")])]))
        result = inv.report()
        self.assertEqual(result["executableInvokeCount"], 3)
        self.assertFalse(result["knownDebugAndStackSinksAbsentFromSpeechSdk"])
        self.assertEqual(result["sttRawInfoHelperCallCount"], 2)

    def test_method_table_and_constant_strings_are_not_executable_invokes(self):
        inv = audit.DexInventory()
        bait = "|0000: invoke-static {v0}, " + LOG + " // method@0001"
        inv.parse(dump(SDK, methods=[("onResponse", "()V", [
            '1b3a96: 1a00 0011                              |0000: const-string v0, "' + bait + '" // string@0001',
            '  string #12: "' + bait + '"',
            "  method #12: " + LOG,
        ])]))
        self.assertEqual(inv.report()["executableInvokeCount"], 0)
        self.assertTrue(inv.report()["knownDebugAndStackSinksAbsentFromSpeechSdk"])

    def test_instruction_shaped_annotation_and_no_code_method_are_not_calls(self):
        inv = audit.DexInventory()
        inv.parse(dump(SDK, annotations=["Annotations on class", call(LOG)]))
        inv.parse(dump(P + "tts/Unused;", methods=[]) + [
            "      name          : 'nativeOnly'", "      type          : '()V'",
            "      access        : 0x0101 (PUBLIC NATIVE)", call(LOG)])
        self.assertEqual(inv.report()["executableInvokeCount"], 0)
        self.assertEqual(inv.report()["speechSdkNativeMethods"], [P + "tts/Unused;.nativeOnly:()V"])

    def test_system_stream_access_counts_only_opcode_not_quoted_text(self):
        real = "sget-object v0, Ljava/lang/System;.out:Ljava/io/PrintStream; // field@0001"
        inv = audit.DexInventory()
        inv.parse(dump(SDK, methods=[("method", "()V", [instruction(real),
            instruction('const-string v0, "|0000: ' + real + '" // string@0002')])]))
        sinks = inv.report()["speechSdkSinkInventory"]
        self.assertEqual(len(sinks), 1)
        self.assertEqual(sinks[0]["kind"], "system_stream")
        self.assertEqual(sinks[0]["count"], 1)

    def test_same_sink_in_app_or_other_library_is_not_claimed_as_speech_sdk(self):
        inv = audit.DexInventory()
        for owner in (APP, "Lother/Library;"):
            inv.parse(dump(owner, methods=[("log", "()V", [call(LOG)])]))
        result = inv.report()
        self.assertTrue(result["knownDebugAndStackSinksAbsentFromSpeechSdk"])
        self.assertEqual({s["scope"] for s in result["allExecutableSinkCounts"]}, {"app", "other_library"})

    def test_info_helper_matches_exact_sdk_target_and_signature_only(self):
        inv = audit.DexInventory()
        targets = [INFO, INFO.replace(";.c:", ";.cExtra:"), INFO.replace("stt/", "tts/"),
                   INFO.replace(")V", ")I"), INFO.replace("utils/Log", "utils/Logger")]
        inv.parse(dump(SDK, methods=[("response", "()V", [call(t) for t in targets])]))
        inv.parse(dump(APP, methods=[("response", "()V", [call(INFO)])]))
        self.assertEqual(inv.report()["sttRawInfoHelperCallCount"], 1)

    def test_sink_inventory_keeps_info_warn_reflection_native_and_stack_calls_visible(self):
        inv = audit.DexInventory()
        inv.parse(dump(SDK, methods=[("method", "()V", [call(t) for t in (
            "Landroid/util/Log;.i:(Ljava/lang/String;Ljava/lang/String;)I",
            "Ljava/lang/Throwable;.printStackTrace:()V",
            "Ljava/lang/Class;.forName:(Ljava/lang/String;)Ljava/lang/Class;",
            "Ljava/lang/System;.loadLibrary:(Ljava/lang/String;)V",
        )])]))
        result = inv.report()
        self.assertFalse(result["knownDebugAndStackSinksAbsentFromSpeechSdk"])
        self.assertEqual({s["kind"] for s in result["speechSdkSinkInventory"]},
                         {"android_log", "stack_trace", "reflection_or_dynamic_loading", "native_loading"})
        self.assertFalse(result["serviceInspected"])
        self.assertFalse(result["functionalBehaviorVerified"])

    def test_app_control_reference_counts_do_not_claim_functional_sdk_behavior(self):
        inv = audit.DexInventory()
        targets = [P + "stt/SpeechToText;.sendAudio:([B)V", P + "stt/SpeechToText;.completeAudioSend:()V",
                   P + "stt/SpeechToText;.stop:()V", P + "tts/TextToSpeech;.speak:(Ljava/lang/String;)V",
                   P + "tts/TextToSpeech;.stop:()V"]
        inv.parse(dump(APP, methods=[("controls", "()V", [call(t) for t in targets])]))
        result = inv.report()
        self.assertTrue(result["appSpeechControlsReferenced"])
        self.assertEqual(set(result["appSpeechControlCallCounts"].values()), {1})
        self.assertFalse(result["functionalBehaviorVerified"])
        empty = audit.DexInventory().report()
        self.assertFalse(empty["appSpeechControlsReferenced"])


class ReflectiveModelTest(unittest.TestCase):
    def test_field_generic_signature_is_preserved_for_each_exact_list(self):
        for suffix, field, target in (("RecognitionFinalResult", "results", "RecognitionResult"),
                                      ("RecognitionResult", "alternatives", "Alternative")):
            with self.subTest(suffix=suffix):
                self.assertTrue(model_check("stt/data/" + suffix, generic(field, target))["genericListSignaturePreserved"])

    def test_getter_constructor_or_other_field_signature_cannot_mask_erased_list_field(self):
        for kind, member in (("method", "<init>"), ("method", "getResults"), ("field", "unrelated")):
            with self.subTest(kind=kind, member=member):
                result = model_check("stt/data/RecognitionFinalResult", generic(member, "RecognitionResult", kind))
                self.assertFalse(result["genericListSignaturePreserved"])

    def test_nested_or_wrong_generic_type_does_not_match_substrings(self):
        variants = [generic("results", "Alternative"),
                    ["Annotations on field #12 'results'", '  VISIBILITY_SYSTEM Ldalvik/annotation/Signature; value={ "Ljava/util/List<Ljava/util/List<' + P + 'stt/data/RecognitionResult;>;>;" }']]
        for annotations in variants:
            self.assertFalse(model_check("stt/data/RecognitionFinalResult", annotations)["genericListSignaturePreserved"])

    def test_annotations_do_not_leak_to_next_class_or_next_dex(self):
        inv = audit.DexInventory()
        inv.parse(dump(P + "stt/data/Other;", annotations=generic("results", "RecognitionResult")))
        inv.parse(dump(P + "stt/data/RecognitionFinalResult;"))
        self.assertFalse(inv.report()["reflectionModelChecks"]["stt/data/RecognitionFinalResult"]["genericListSignaturePreserved"])
        inv = audit.DexInventory()
        inv.parse(["Class #1 annotations:", *generic("results", "RecognitionResult")])
        inv.parse(dump(P + "stt/data/RecognitionFinalResult;"))
        self.assertFalse(inv.report()["reflectionModelChecks"]["stt/data/RecognitionFinalResult"]["genericListSignaturePreserved"])

    def test_enum_serialized_names_belong_to_exact_fields(self):
        suffix = "stt/data/InferenceSource"
        self.assertTrue(model_check(suffix, enum_names())["serializedNamesPreserved"])
        self.assertFalse(model_check(suffix, enum_names("server", "on_device"))["serializedNamesPreserved"])
        self.assertFalse(model_check(suffix, [x.replace("Annotations on field", "Annotations on method") for x in enum_names()])["serializedNamesPreserved"])
        self.assertFalse(model_check(suffix, enum_names("on_device_extra", "server"))["serializedNamesPreserved"])

    def test_field_name_with_wrong_type_and_absent_constructor_fail_preservation(self):
        suffix = "stt/constant/SttFinalResult"
        fields = {"asrText": "Ljava/lang/String;", "speakerId": "Ljava/lang/String;", "epdOn": "J", "epdOff": "J"}
        valid = model_check(suffix, fields=fields, methods=[("<init>", "()V", [])])
        self.assertTrue(valid["fieldsPreserved"]); self.assertTrue(valid["constructorsPreserved"])
        wrong = model_check(suffix, fields={**fields, "epdOn": "I"})
        self.assertFalse(wrong["fieldsPreserved"]); self.assertFalse(wrong["constructorsPreserved"])

    def test_parcelable_creator_requires_exact_type_class_and_typed_methods(self):
        suffix = "caas/service/speech/data/tts/AudioAttributesParcel"
        desc = P + suffix + ";"
        creator = P + suffix + "$CREATOR;"
        inv = audit.DexInventory()
        inv.parse(dump(desc, fields={"CREATOR": creator}, methods=[("<init>", "(Landroid/os/Parcel;)V", []), ("<init>", "(Landroid/media/AudioAttributes;)V", [])]))
        self.assertFalse(inv.report()["reflectionModelChecks"][suffix]["creatorClassAndMethodsPreserved"])
        inv.parse(dump(creator, methods=[("createFromParcel", "(Landroid/os/Parcel;)" + desc, []), ("newArray", "(I)[" + desc, [])]))
        check = inv.report()["reflectionModelChecks"][suffix]
        self.assertTrue(all(check.values()))
        inv.classes[desc]["fields"]["CREATOR"] = "Landroid/os/Parcelable$Creator;"
        self.assertFalse(inv.report()["reflectionModelChecks"][suffix]["fieldsPreserved"])

    def test_missing_models_fail_overall_check_even_when_no_sink_exists(self):
        result = audit.DexInventory().report()
        self.assertTrue(result["knownDebugAndStackSinksAbsentFromSpeechSdk"])
        self.assertFalse(result["modelChecksPass"])


class ApkBoundaryTest(unittest.TestCase):
    def test_duplicate_descriptor_rejected_within_or_across_dex_parse(self):
        for combined in (True, False):
            with self.subTest(combined=combined):
                inv = audit.DexInventory()
                with self.assertRaisesRegex(ValueError, "^duplicate_class_descriptor$"):
                    if combined: inv.parse(dump(SDK) + dump(SDK))
                    else: inv.parse(dump(SDK)); inv.parse(dump(SDK))

    def fake_process(self, lines, exit_code=0):
        class Process:
            stdout = io.StringIO("\n".join(lines))
            def __enter__(self): return self
            def __exit__(self, *args): self.stdout.close()
            def wait(self): return exit_code
        return Process()

    def test_audit_reads_all_numbered_dex_and_hashes_exact_artifacts(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); apk = root / "app.apk"; tool = root / "dexdump"
            tool.write_bytes(b"synthetic executable identity")
            with zipfile.ZipFile(apk, "w") as z:
                z.writestr("classes.dex", b"first"); z.writestr("classes2.dex", b"second")
                z.writestr("classes13.dex", b"thirteenth"); z.writestr("assets/classes.dex", b"not executable")
            seen = []
            def process(args, **kwargs):
                seen.append(Path(args[-1]).name)
                self.assertEqual(args[1:3], ["-a", "-d"])
                self.assertEqual(kwargs["stderr"], audit.subprocess.DEVNULL)
                owner = SDK if Path(args[-1]).name == "classes13.dex" else "Lsample/" + str(len(seen)) + ";"
                return self.fake_process(dump(owner, methods=[("m", "()V", [call(LOG)])]))
            with patch.object(audit.subprocess, "Popen", side_effect=process): result = audit.audit(apk, tool)
            self.assertEqual(set(seen), {"classes.dex", "classes2.dex", "classes13.dex"})
            self.assertEqual(result["classCount"], 3)
            self.assertFalse(result["knownDebugAndStackSinksAbsentFromSpeechSdk"])
            self.assertEqual(result["apkSha256"], hashlib.sha256(apk.read_bytes()).hexdigest())
            self.assertEqual(result["dexdumpSha256"], hashlib.sha256(tool.read_bytes()).hexdigest())
            self.assertEqual({d["name"]: d["sha256"] for d in result["dexFiles"]}["classes13.dex"], hashlib.sha256(b"thirteenth").hexdigest())

    def test_nonzero_dexdump_exit_cannot_produce_success_report(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "app.apk"
            with zipfile.ZipFile(apk, "w") as z: z.writestr("classes.dex", b"invalid dex")
            with patch.object(audit.subprocess, "Popen", return_value=self.fake_process(dump(SDK), 1)):
                with self.assertRaisesRegex(ValueError, "^dexdump_failed$"): audit.audit(apk, Path(directory) / "dexdump")

    def test_apk_without_executable_dex_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "app.apk"
            with zipfile.ZipFile(apk, "w") as z: z.writestr("assets/classes.dex", b"not executable")
            with patch.object(audit.subprocess, "Popen") as process:
                with self.assertRaisesRegex(ValueError, "^apk_has_no_dex$"): audit.audit(apk, Path(directory) / "dexdump")
                process.assert_not_called()


if __name__ == "__main__":
    unittest.main()
