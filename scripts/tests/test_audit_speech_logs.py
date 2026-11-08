"""Synthetic host-only checks. No adb, microphone, logcat or device state is accessed."""

import importlib.util
import io
import json
import os
from pathlib import Path
import struct
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("audit_speech_logs", Path(__file__).parents[1] / "audit-speech-logs.py")
audit = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(audit)
APP_UID = 1_010_174
SERVICE_UID = 1_010_142


def record(message="SYNTHETIC_SAFE_STATUS", tag="SyntheticTest", uid=APP_UID, pid=100, header=28, buffer_id=0, nanos=100):
    body = bytes([4]) + tag.encode() + b"\0" + message.encode() + b"\0"
    return struct.pack("<HHiiIIII", len(body), header, pid, pid, 100, nanos, buffer_id, uid) + body


def process_text():
    return f"PID UID NAME\n100 {APP_UID} {audit.APP}\n200 {SERVICE_UID} {audit.SERVICE}\n300 10142 {audit.SERVICE}\n999 1000 unrelated.system"


def service_text(client=100, server=200, user=10):
    return ("ACTIVITY MANAGER SERVICES (dumpsys activity services)\n"
            f"  * ServiceRecord{{abc u{user} {audit.COMPONENT}}}\n"
            f"    app=ProcessRecord{{abc {server}:{audit.SERVICE}/u{user}a142}}\n"
            f"      * Client AppBindRecord{{abc ProcessRecord{{abc {client}:{audit.APP}/u10a174}}}}\n")


def complete_observation():
    value = audit.Observation({APP_UID, APP_UID + 1}, {SERVICE_UID, 10142}, 100_000_000_000)
    processes = audit.process_metadata(process_text(), value.allowed)
    bindings = audit.service_bindings(service_text(), processes, value.app_uids, value.service_uids)
    value.observe_processes(processes, bindings)
    value.consume(APP_UID, 100, 100_000_000_000, "Safe", "SYNTHETIC_SAFE_STATUS")
    value.consume(SERVICE_UID, 200, 100_000_000_000, "Safe", "SYNTHETIC_SAFE_STATUS")
    for event in ("owned_input_connected_at_2ms", "owned_input_ready_at_20ms", "owned_input_started_at_100ms",
                  "owned_input_epd_ended_at_300ms", "owned_input_final_at_400ms", "owned_input_fixture_matched", "owned_input_stop_confirmed", "owned_input_close_returned"):
        value.instrumentation("INSTRUMENTATION_STATUS: event=" + event)
    value.instrumentation("INSTRUMENTATION_RESULT: outcome=PASS")
    return value


def complete_eof_observation():
    value = complete_observation()
    for event in (
        "owned_fixture_original_samples_100000", "owned_fixture_trimmed_trailing_zero_samples_10000",
        "owned_fixture_eof_at_200ms", "owned_input_request_entered_at_10ms",
        "owned_input_eof_observed_at_200ms", "owned_input_capture_stop_finished_at_201ms",
        "owned_input_complete_audio_send_entered_at_202ms", "owned_input_complete_audio_send_returned_at_203ms",
        "owned_input_sdk_ended_callback_at_300ms", "owned_input_sdk_final_callback_at_400ms",
        "owned_input_stop_entered_at_401ms", "owned_input_finite_eof_validated",
        "owned_input_functional_pass", "owned_input_product_timing_pass",
    ):
        value.instrumentation("INSTRUMENTATION_STATUS: event=" + event)
    return value


class BinaryDecoderTest(unittest.TestCase):
    def test_split_records_use_whole_uid_and_handle_multiline_message(self):
        seen = []
        decoder = audit.BinaryDecoder(lambda *values: seen.append(values))
        data = record("SYNTHETIC_LINE_A\nSYNTHETIC_LINE_B") + record(uid=SERVICE_UID, pid=200, buffer_id=4)
        for byte in data:
            decoder.feed(bytes([byte]))
        decoder.finish()
        self.assertEqual(len(seen), 2)
        self.assertEqual(seen[0][:3], (APP_UID, 100, 100_000_000_100))
        self.assertEqual(seen[1][:2], (SERVICE_UID, 200))

    def test_unsupported_headers_fail_without_attempting_text_fallback(self):
        for header in (0, 20, 24, 32):
            with self.subTest(header=header), self.assertRaisesRegex(audit.AuditError, "^unsupported_binary_log_header$"):
                audit.BinaryDecoder(lambda *args: self.fail()).feed(record(header=header))

    def test_truncated_records_are_inconclusive_not_silently_dropped(self):
        decoder = audit.BinaryDecoder(lambda *args: self.fail())
        decoder.feed(record()[:-1])
        with self.assertRaisesRegex(audit.AuditError, "^truncated_binary_log_record$"):
            decoder.finish()

    def test_invalid_buffer_timestamp_and_identity_fail_closed(self):
        for kwargs in ({"buffer_id": 2}, {"nanos": 1_000_000_000}, {"pid": 0}):
            with self.subTest(kwargs=kwargs), self.assertRaisesRegex(audit.AuditError, "^unsupported_binary_log_record$"):
                audit.BinaryDecoder(lambda *args: self.fail()).feed(record(**kwargs))

    def test_bad_utf8_and_nul_termination_never_escape_as_error_body(self):
        data = record()
        for changed, code in ((data[:-1] + b"X", "unsupported_binary_log_payload"),
                              (data[:30] + b"\xff" + data[31:], "invalid_log_encoding")):
            with self.subTest(code=code), self.assertRaisesRegex(audit.AuditError, "^" + code + "$"):
                audit.BinaryDecoder(lambda *args: self.fail()).feed(changed)

    def test_byte_limit_bounds_memory_and_work(self):
        with patch.object(audit, "MAX_BYTES", 10), self.assertRaisesRegex(audit.AuditError, "^byte_limit_exceeded$"):
            audit.BinaryDecoder(lambda *args: None).feed(record())


class MetadataTest(unittest.TestCase):
    def test_user_10_uid_does_not_become_base_appid_or_user_zero(self):
        self.assertEqual(audit.package_uid(f"package:{audit.APP} uid:{APP_UID}", audit.APP, 10), APP_UID)
        for text in (f"package:{audit.APP} uid:10174", f"package:{audit.APP}.other uid:{APP_UID}",
                     f"package:{audit.APP} uid:{APP_UID}\npackage:{audit.APP} uid:{APP_UID}"):
            with self.assertRaisesRegex(audit.AuditError, "^package_uid_unresolved$"):
                audit.package_uid(text, audit.APP, 10)

    def test_actual_component_requires_matching_server_client_and_user(self):
        identities = audit.process_metadata(process_text(), {APP_UID, SERVICE_UID, 10142})
        self.assertEqual(audit.service_bindings(service_text(), identities, {APP_UID}, {SERVICE_UID, 10142}),
                         {(SERVICE_UID, 200, APP_UID, 100)})
        self.assertEqual(audit.service_bindings(service_text(server=300, user=0), identities, {APP_UID}, {SERVICE_UID, 10142}),
                         {(10142, 300, APP_UID, 100)})
        for text in (service_text(client=101), service_text(server=200, user=0), service_text().replace(".SpeechService", ".OtherService")):
            self.assertFalse(audit.service_bindings(text, identities, {APP_UID}, {SERVICE_UID, 10142}))

    def test_unknown_process_with_known_uid_remains_unknown(self):
        result = audit.process_metadata(f"PID UID NAME\n100 {APP_UID} unrelated.shared.uid", {APP_UID})
        self.assertEqual(result, {100: (APP_UID, "unknown_process")})
        with self.assertRaisesRegex(audit.AuditError, "^unsupported_process_metadata$"):
            audit.process_metadata("Permission denied SYNTHETIC_PRIVATE_TEXT", {APP_UID})

    def test_version_extracts_only_bounded_metadata(self):
        value = audit.service_version("codePath=/system_ext/app/AmbientAiService\nversionCode=4002010 minSdk=34\nversionName=4.2.10-coral-aosp\nPRIVATE_UNRELATED_TEST_LINE")
        self.assertEqual(value["versionCode"], 4002010)
        self.assertEqual(value["versionName"], "4.2.10-coral-aosp")
        self.assertNotIn("PRIVATE", json.dumps(value))
        with self.assertRaises(audit.AuditError):
            audit.service_version("versionName=raw private body")


class ClassificationTest(unittest.TestCase):
    def test_synthetic_positive_control_and_normalization(self):
        self.assertTrue(audit.positive_control())
        kinds, _, _ = audit.classify("UnretainedTag", "페타소스   음성 출력 연결을 확인합니다!!!")
        self.assertIn("known_canary", kinds)
        self.assertIn("known_canary", audit.classify("Synthetic", "지금 지나가는 지역…")[0])
        self.assertNotIn("known_canary", audit.classify("Synthetic", "지금 지나가는")[0])

    def test_shape_finds_unrecognized_canary_text_without_storing_it(self):
        value = complete_observation()
        value.consume(SERVICE_UID, 200, 100_000_000_001, "TEST_PRIVATE_TAG", '{"alternatives":[{"transcript":"TEST_PRIVATE_BODY"}]}')
        result = value.report("stt-owned-pcm")
        self.assertEqual(result["outcome"], "MATCH_FOUND")
        self.assertEqual(result["matchCounts"]["speech_service"]["recognition_payload_shape"], 1)
        self.assertNotIn("TEST_PRIVATE", json.dumps(result))
        self.assertNotIn("hash", json.dumps(result))

    def test_stacktrace_and_drop_indicators_are_fixed_counts(self):
        kinds, dropped, crash = audit.classify("chatty", "dropped 23 lines\njava.lang.Exception: TEST_PRIVATE_BODY\n at a.b.C(Source.java:4)")
        self.assertEqual(kinds, {"stacktrace_sink"})
        self.assertTrue(dropped)
        self.assertFalse(crash)


class CoverageTest(unittest.TestCase):
    def test_complete_synthetic_exercised_window_can_report_no_match(self):
        report = complete_observation().report("stt-owned-pcm")
        self.assertEqual(report["outcome"], "NO_MATCH_OBSERVED")
        self.assertTrue(report["pidCoverageComplete"])
        self.assertEqual(report["sdkElapsedMs"]["final"], 400)

    def test_each_missing_boundary_is_inconclusive(self):
        for mutation, reason in ((lambda x: x.bindings.clear(), "actual_speech_binding_unobserved"),
                                 (lambda x: x.sdk.clear(), "required_sdk_activity_missing"),
                                 (lambda x: setattr(x, "probe_outcome", "FAIL"), "synthetic_probe_not_passed"),
                                 (lambda x: x.issue("log_stream_ended_early"), "log_stream_ended_early")):
            value = complete_observation(); mutation(value)
            report = value.report("stt-owned-stream")
            self.assertEqual(report["outcome"], "INCONCLUSIVE")
            self.assertIn(reason, report["reasonCodes"])

    def test_service_restart_and_pid_reuse_are_not_hidden_by_uid_filter(self):
        value = complete_observation()
        value.observe_processes({201: (SERVICE_UID, "speech_service")}, set())
        self.assertIn("speech_service_restarted", value.report("stt-owned-pcm")["reasonCodes"])
        value.observe_processes({100: (SERVICE_UID, "speech_service")}, set())
        self.assertIn("pid_reused", value.report("stt-owned-pcm")["reasonCodes"])

    def test_service_crash_or_disappearance_after_success_is_inconclusive(self):
        value = complete_observation()
        value.consume(SERVICE_UID, 200, 100_000_000_001, "libc", "Fatal signal 11 SYNTHETIC_ONLY")
        self.assertEqual(value.report("stt-owned-pcm")["outcome"], "INCONCLUSIVE")
        self.assertIn("crash_observed_in_window", value.report("stt-owned-pcm")["reasonCodes"])
        value = complete_observation()
        value.observe_processes({100: (APP_UID, "app_client")}, set())
        self.assertIn("bound_service_process_disappeared", value.report("stt-owned-pcm")["reasonCodes"])

    def test_unknown_logged_pid_match_is_reported_in_unknown_bucket(self):
        value = complete_observation()
        value.consume(APP_UID, 999, 100_000_000_001, "Synthetic", 'asrText="SYNTHETIC_UNKNOWN_PID"')
        report = value.report("stt-owned-pcm")
        self.assertEqual(report["outcome"], "MATCH_FOUND")
        self.assertFalse(report["pidCoverageComplete"])
        self.assertEqual(report["matchCounts"]["unknown_process"]["recognition_payload_shape"], 1)

    def test_uid_violation_cursor_and_drop_never_become_no_match(self):
        for params, reason in (((999, 999, 100_000_000_001, "tag", "SYNTHETIC"), "uid_filter_violation"),
                               ((APP_UID, 100, 99_000_000_000, "tag", "SYNTHETIC"), "record_before_cursor"),
                               ((APP_UID, 100, 100_000_000_001, "logd", "dropped 5 records"), "dropped_records_observed")):
            value = complete_observation(); value.consume(*params)
            self.assertIn(reason, value.report("stt-owned-delayed")["reasonCodes"])

    def test_instrumentation_does_not_retain_arbitrary_events_or_error_bodies(self):
        value = complete_observation()
        for line in ("INSTRUMENTATION_STATUS: event=private_text_canary", "java.lang.Exception: TEST_PRIVATE_BODY",
                     'INSTRUMENTATION_STATUS: transcript="TEST_PRIVATE_BODY"'):
            value.instrumentation(line)
        self.assertNotIn("PRIVATE", json.dumps(value.report("stt-owned-pcm")))
        self.assertNotIn("private_text", json.dumps(value.report("stt-owned-pcm")))

    def test_fixture_timing_keeps_true_ready_basis_and_explicit_sample_counts(self):
        value = complete_observation()
        for event in ("owned_fixture_prefix_silence_1500ms", "owned_fixture_first_samples_at_10ms",
                      "owned_fixture_first_nonzero_at_1510ms", "owned_fixture_eof_at_3500ms",
                      "owned_fixture_capture_stop_at_3550ms", "owned_fixture_reads_100_samples_160000",
                      "owned_fixture_payload_samples_100000_of_120000", "owned_input_stop_confirmed_at_3600ms",
                      "owned_input_ready_to_started_7000ms", "owned_input_product_first_speech_deadline_exceeded"):
            value.instrumentation("INSTRUMENTATION_STATUS: event=" + event)
        report = value.report("stt-owned-delayed")
        self.assertEqual(report["fixtureTelemetry"], {"prefixSilenceMs": 1500, "firstSamplesAtMs": 10,
            "firstNonzeroAtMs": 1510, "eofAtMs": 3500, "captureStopAtMs": 3550,
            "readCount": 100, "suppliedSamples": 160000, "payloadSuppliedSamples": 100000, "payloadAvailableSamples": 120000})
        self.assertEqual(report["sdkElapsedMs"]["ready_to_started"], 7000)
        self.assertEqual(report["sdkElapsedMs"]["stop_confirmed_at"], 3600)
        self.assertEqual(report["sdkEventCounts"]["first_speech_deadline_exceeded"], 1)

    def test_tts_requires_started_and_terminal_callback(self):
        value = complete_observation(); value.sdk.clear()
        for event in ("tts_connected", "tts_started", "tts_stopped"):
            value.instrumentation("INSTRUMENTATION_STATUS: event=" + event)
        self.assertEqual(value.report("tts-stop")["outcome"], "NO_MATCH_OBSERVED")
        self.assertEqual(value.report("tts-speak")["outcome"], "INCONCLUSIVE")


class FiniteEofTelemetryTest(unittest.TestCase):
    def test_known_numeric_lifecycle_marks_keep_function_timing_and_privacy_separate(self):
        value = complete_eof_observation()
        report = value.report("stt-owned-eof")
        self.assertEqual(report["outcome"], "NO_MATCH_OBSERVED")
        self.assertEqual(report["probeOutcome"], "PASS")
        self.assertEqual(report["fixtureTelemetry"]["originalSamples"], 100000)
        self.assertEqual(report["fixtureTelemetry"]["trimmedTrailingZeroSamples"], 10000)
        self.assertEqual(report["sdkElapsedMs"]["complete_audio_send_entered"], 202)
        self.assertEqual(report["sdkElapsedMs"]["complete_audio_send_returned"], 203)
        self.assertEqual(report["sdkElapsedMs"]["sdk_ended_callback"], 300)
        self.assertEqual(report["eofOrdering"]["outcome"], "PASS")
        self.assertEqual(report["timingAcceptance"]["outcome"], "PASS")
        self.assertEqual(report["timingAcceptance"]["readyToStartedMs"], 80)
        self.assertEqual(report["timingAcceptance"]["endedToFinalMs"], 100)
        self.assertIn("no SDK flush acknowledgment", report["eofOrdering"]["scope"])

    def test_missing_ended_cannot_be_reported_as_timing_pass(self):
        for names in (("ended",), ("sdk_ended_callback",), ("ended", "sdk_ended_callback")):
            with self.subTest(names=names):
                value = complete_eof_observation()
                for name in names:
                    value.sdk.pop(name); value.durations.pop(name)
                report = value.report("stt-owned-eof")
                self.assertEqual(report["probeOutcome"], "PASS")
                self.assertEqual(report["timingAcceptance"]["outcome"], "UNVERIFIED")
                self.assertIn("product_timing_boundary_missing_or_repeated", report["timingAcceptance"]["reasonCodes"])

    def test_equal_millisecond_eof_and_sdk_callback_does_not_prove_order(self):
        for name in ("sdk_ended_callback", "sdk_final_callback"):
            with self.subTest(callback=name):
                value = complete_eof_observation(); value.durations[name] = 200
                report = value.report("stt-owned-eof")
                self.assertEqual(report["probeOutcome"], "PASS")
                self.assertEqual(report["eofOrdering"]["outcome"], "INCONCLUSIVE")
                self.assertEqual(report["timingAcceptance"]["outcome"], "UNVERIFIED")
                self.assertEqual(report["outcome"], "INCONCLUSIVE")

    def test_timestamps_without_exactly_one_device_eof_proof_do_not_pass(self):
        for count in (0, 2):
            with self.subTest(proof_count=count):
                value = complete_eof_observation(); value.sdk["finite_eof_validated"] = count
                report = value.report("stt-owned-eof")
                self.assertEqual(report["probeOutcome"], "PASS")
                self.assertEqual(report["eofOrdering"]["outcome"], "INCONCLUSIVE")
                self.assertIn("finite_eof_validation_unconfirmed", report["eofOrdering"]["reasonCodes"])
                self.assertEqual(report["timingAcceptance"]["outcome"], "UNVERIFIED")
                self.assertEqual(report["outcome"], "INCONCLUSIVE")

    def test_missing_or_repeated_completion_and_untrimmed_fixture_fail_closed(self):
        for name in ("eof_observed", "capture_stop_finished", "complete_audio_send_entered", "complete_audio_send_returned", "sdk_final_callback"):
            for count in (0, 2):
                with self.subTest(name=name, count=count):
                    value = complete_eof_observation(); value.sdk[name] = count
                    self.assertEqual(value.report("stt-owned-eof")["eofOrdering"]["outcome"], "INCONCLUSIVE")
        for field, content in (("originalSamples", 0), ("trimmedTrailingZeroSamples", 0), ("trimmedTrailingZeroSamples", 100000)):
            value = complete_eof_observation(); value.fixture[field] = content
            self.assertIn("exact_zero_trim_unconfirmed", value.report("stt-owned-eof")["eofOrdering"]["reasonCodes"])

    def test_wrong_local_call_order_or_absent_fixture_eof_is_not_accepted(self):
        value = complete_eof_observation(); value.durations["capture_stop_finished"] = 204
        self.assertIn("capture_complete_call_order_invalid", value.report("stt-owned-eof")["eofOrdering"]["reasonCodes"])
        value = complete_eof_observation(); value.fixture.pop("eofAtMs")
        self.assertIn("fixture_eof_unobserved", value.report("stt-owned-eof")["eofOrdering"]["reasonCodes"])

    def test_functional_success_does_not_override_existing_timing_limits(self):
        for name, stamp in (("started", 5021), ("final", 801)):
            with self.subTest(name=name):
                value = complete_eof_observation(); value.durations[name] = stamp
                report = value.report("stt-owned-eof")
                self.assertEqual(report["probeOutcome"], "PASS")
                self.assertEqual(report["timingAcceptance"]["outcome"], "FAIL")
                self.assertIn("product_timing_limit_exceeded", report["timingAcceptance"]["reasonCodes"])

    def test_unknown_or_negative_timestamps_and_ack_claims_are_not_retained(self):
        value = complete_eof_observation()
        for event in ("owned_input_complete_audio_send_returned_at_-1ms", "owned_input_sdk_flush_ack_at_999ms",
                      "owned_fixture_original_samples_private_body", "owned_input_private_text_canary"):
            value.instrumentation("INSTRUMENTATION_STATUS: event=" + event)
        report = value.report("stt-owned-eof")
        self.assertEqual(report["sdkElapsedMs"]["complete_audio_send_returned"], 203)
        rendered = json.dumps(report)
        self.assertNotIn("sdk_flush_ack", rendered)
        self.assertNotIn("private_body", rendered)
        self.assertNotIn("private_text_canary", rendered)


class OutputSafetyTest(unittest.TestCase):
    def test_smoke_summary_has_counts_only_and_parser_failure_is_inconclusive(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "counts.json"
            result = audit.summarize_crashes(io.BytesIO(record("FATAL EXCEPTION: TEST_PRIVATE_BODY\njava.lang.Exception: TEST_PRIVATE_BODY")), output)
            self.assertEqual(result, 1)
            self.assertNotIn("TEST_PRIVATE", output.read_text())
            self.assertEqual(json.loads(output.read_text())["crashIndicators"], 1)
            self.assertEqual(audit.summarize_crashes(io.BytesIO(b"raw private text"), output), 1)
            self.assertEqual(json.loads(output.read_text())["outcome"], "INCONCLUSIVE")
            self.assertNotIn("raw private", output.read_text())

    def test_microphone_probe_is_rejected_before_any_adb_lookup(self):
        for probe in ("stt-owned-listen", "stt-listen", "app-playback", "location"):
            with self.subTest(probe=probe), \
                    patch("sys.argv", ["audit-speech-logs.py", "observe", probe, "--output", "unused.json"]), \
                    patch.object(audit, "adb_location") as adb, patch("sys.stderr"):
                with self.assertRaises(SystemExit) as failure:
                    audit.main()
                self.assertEqual(failure.exception.code, 2)
                adb.assert_not_called()

    def test_eof_synthetic_probe_is_allowed_without_executing_a_device(self):
        self.assertEqual(audit.SYNTHETIC_PROBES.count("stt-owned-eof"), 1)
        with patch("sys.argv", ["audit-speech-logs.py", "observe", "stt-owned-eof", "--output", "unused.json"]), \
                patch.object(audit, "observe", return_value=0) as observe, patch.object(audit, "adb_location") as adb:
            self.assertEqual(audit.main(), 0)
            self.assertEqual(observe.call_args.args[0].probe, "stt-owned-eof")
            adb.assert_not_called()

    def test_direct_observer_also_rejects_nonsynthetic_probe_before_adb(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(audit, "adb_location") as adb, \
                patch("sys.stdout", new_callable=io.StringIO):
            output = Path(directory) / "report.json"
            result = audit.observe(SimpleNamespace(probe="stt-owned-listen", serial="emulator-5554", output=output))
            self.assertEqual(result, 2)
            report = json.loads(output.read_text())
            self.assertEqual(report["outcome"], "INCONCLUSIVE")
            self.assertIn("synthetic_probe_required", report["reasonCodes"])
            adb.assert_not_called()


class FakeDevicePipelineTest(unittest.TestCase):
    def test_binary_observer_and_instrumentation_pipeline_only_persist_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative in ("privacy/app-privacy.apk", "androidTest/privacy/app-privacy-androidTest.apk"):
                path = root / "app/build/outputs/apk" / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b"SYNTHETIC_APK")
            fake = root / "fake-adb"
            fake.write_text("#!" + sys.executable + "\n" + r'''
import struct, sys, time
args = sys.argv[3:]
if args == ['shell','getprop','ro.build.fingerprint']: print('synthetic/pleos/API34')
elif args == ['shell','getprop','ro.build.version.sdk']: print('34')
elif args == ['shell','am','get-current-user']: print('10')
elif args[0] == 'install': print('Success')
elif args[:4] == ['shell','cmd','package','list']:
    uid = 1010175 if args[-1].endswith('.test') else 1010174 if args[-1] == 'com.petasos.app' else 10142 if args[-2] == '0' else 1010142
    print('package:' + args[-1] + ' uid:' + str(uid))
elif args == ['shell','dumpsys','package','ai.pleos.playground.caas']:
    print('codePath=/system_ext/app/AmbientAiService\nversionCode=4002010 minSdk=34\nversionName=4.2.10-coral-aosp')
elif args[:3] == ['shell','am','force-stop']: pass
elif args == ['shell','date','+%s']: print('100')
elif args[0] == 'logcat':
    assert '-B' in args and '--uid=10142,1010142,1010174,1010175' in args and args[-1] == '*:V'
    payload = bytes([4]) + b'SyntheticTag\0SYNTHETIC_SAFE_STATUS\0'
    value = struct.pack('<HHiiIIII',len(payload),28,200,200,100,1,0,1010142) + payload
    for _ in range(100):
        sys.stdout.buffer.write(value); sys.stdout.buffer.flush(); time.sleep(.1)
elif args == ['shell','ps','-A','-o','PID,UID,NAME']:
    print('PID UID NAME\n100 1010174 com.petasos.app\n200 1010142 ai.pleos.playground.caas\n300 10142 ai.pleos.playground.caas')
elif args[:4] == ['shell','dumpsys','activity','services']:
    print('ACTIVITY MANAGER SERVICES (dumpsys activity services)\n * ServiceRecord{a u10 ai.pleos.playground.caas/.service.speech.SpeechService}\n app=ProcessRecord{a 200:ai.pleos.playground.caas/u10a142}\n * Client AppBindRecord{a ProcessRecord{a 100:com.petasos.app/u10a174}}')
elif args[:3] == ['shell','am','instrument']:
    for event in ('owned_input_connected_at_2ms','owned_input_started_at_100ms','owned_input_final_at_400ms','owned_input_fixture_matched','owned_input_stop_confirmed','owned_input_close_returned'):
        print('INSTRUMENTATION_STATUS: event=' + event)
    print('INSTRUMENTATION_RESULT: outcome=PASS')
    print('SYNTHETIC_PRIVATE_UNRECOGNIZED_INSTRUMENTATION_OUTPUT')
else: raise SystemExit(4)
''')
            fake.chmod(0o700)
            output = root / "report.json"
            with patch.object(audit, "ROOT", root), patch.object(audit, "adb_location", return_value=str(fake)), patch("sys.stdout", new_callable=io.StringIO) as stdout:
                result = audit.observe(SimpleNamespace(probe="stt-owned-pcm", serial="emulator-5554", output=output))
            self.assertEqual(result, 0)
            self.assertEqual(stdout.getvalue().strip(), "NO_MATCH_OBSERVED")
            report = json.loads(output.read_text())
            self.assertTrue(report["observerReadyBeforeProbe"])
            self.assertTrue(report["pidCoverageComplete"])
            self.assertGreater(report["inspectedRecords"], 0)
            self.assertEqual(report["actualBindings"][0]["serviceUid"], SERVICE_UID)
            self.assertNotIn("SYNTHETIC_PRIVATE", output.read_text())
            self.assertNotIn("SYNTHETIC_SAFE_STATUS", output.read_text())


if __name__ == "__main__":
    unittest.main()
