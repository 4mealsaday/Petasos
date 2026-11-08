"""Host-audit regressions: observed Android 14 formats and false-pass cases."""

import importlib.util
from pathlib import Path
import unittest
import tempfile
import json
from unittest.mock import Mock, patch


SPEC = importlib.util.spec_from_file_location("probe_pleos", Path(__file__).parents[1] / "probe-pleos.py")
probe = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(probe)


class BuildVariantTest(unittest.TestCase):
    def test_each_variant_requires_matching_app_and_test_apks(self):
        root = Path("/synthetic-project")
        for variant in ("debug", "privacy"):
            with self.subTest(variant=variant):
                app, test = probe.apk_paths(variant, root)
                self.assertEqual(app, root / f"app/build/outputs/apk/{variant}/app-{variant}.apk")
                self.assertEqual(test, root / f"app/build/outputs/apk/androidTest/{variant}/app-{variant}-androidTest.apk")
                metadata = probe.artifact_metadata(variant, app, test)
                self.assertEqual(metadata, {"buildType": variant, "appApk": str(app), "testApk": str(test)})

    def test_missing_privacy_test_never_selects_debug_test(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            debug_app, debug_test = probe.apk_paths("debug", root)
            privacy_app, privacy_test = probe.apk_paths("privacy", root)
            for path in (debug_app, debug_test, privacy_app):
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b"synthetic-apk")
            self.assertFalse(privacy_test.is_file())
            self.assertNotEqual(privacy_test, debug_test)
            self.assertIn("-PpetasosTestBuildType=privacy", probe.build_instruction("privacy"))
            self.assertIn(":app:assemblePrivacyAndroidTest", probe.build_instruction("privacy"))
            self.assertNotIn("Privacy", probe.build_instruction("debug"))

    def test_variant_evidence_is_separate_and_debug_paths_stay_compatible(self):
        self.assertEqual(probe.evidence_stem("stt-pcm", "debug"), "stt-pcm")
        self.assertEqual(probe.evidence_stem("stt-pcm", "privacy"), "stt-pcm-privacy")

    def test_arbitrary_variants_cannot_select_files_or_run_adb(self):
        for value in ("release", "../debug", "Privacy", "", None):
            with self.subTest(value=value):
                with self.assertRaisesRegex(probe.AuditFailure, "^unsupported_build_type$"):
                    probe.apk_paths(value)
        with patch("sys.argv", ["probe-pleos.py", "stt-pcm", "--build-type", "release"]), \
                patch.object(probe.subprocess, "run") as run, patch("sys.stderr"):
            with self.assertRaises(SystemExit) as error:
                probe.main()
            self.assertEqual(error.exception.code, 2)
            run.assert_not_called()

    def test_privacy_rejects_run_as_modes_before_private_files_or_adb(self):
        for name in probe.RUN_AS_PROBES:
            with self.subTest(probe=name), patch("sys.argv", ["probe-pleos.py", name, "--build-type", "privacy"]), \
                    patch.object(probe.subprocess, "run") as run, patch("sys.stderr"), \
                    patch.object(probe, "read_development_server") as read:
                with self.assertRaises(SystemExit) as error:
                    probe.main()
                self.assertEqual(error.exception.code, 2)
                read.assert_not_called()
                run.assert_not_called()
        self.assertTrue({"stt-pcm", "stt-owned-pcm", "stt-owned-stream", "stt-owned-delayed", "stt-owned-eof", "tts-speak", "tts-stop"}.isdisjoint(probe.RUN_AS_PROBES))


class OwnedPcmEofProbeTest(unittest.TestCase):
    def test_finite_eof_probe_supports_privacy_without_run_as_or_external_inputs(self):
        name = "stt-owned-eof"
        self.assertEqual(probe.PROBES.count(name), 1)
        self.assertNotIn(name, probe.RUN_AS_PROBES)
        self.assertEqual(probe.probe_timeout(name), 90)
        self.assertEqual(probe.read_development_server(name, None), (None, None))
        self.assertIsNone(probe.read_content_bundle(name, None))
        for variant in ("debug", "privacy"):
            with self.subTest(variant=variant):
                self.assertEqual(probe.evidence_stem(name, variant), name + ("-privacy" if variant == "privacy" else ""))

    def test_eof_probe_rejects_external_fixtures_and_secrets_before_read_or_adb(self):
        for variant in ("debug", "privacy"):
            for option in ("--https-fixture-ready", "--development-server-ready", "--device-token-file", "--content-bundle"):
                with self.subTest(variant=variant, option=option), \
                        patch("sys.argv", ["probe-pleos.py", "stt-owned-eof", "--build-type", variant,
                                           option, "/not-a-test-input/private-file"]), \
                        patch.object(probe.subprocess, "run") as run, \
                        patch.object(probe, "private_bytes") as private_read, patch("sys.stderr"):
                    with self.assertRaises(SystemExit) as error:
                        probe.main()
                    self.assertEqual(error.exception.code, 2)
                    private_read.assert_not_called()
                    run.assert_not_called()

    def test_owned_synthetic_pcm_is_labeled_and_eof_scope_does_not_claim_ack(self):
        for name in ("stt-pcm", "stt-owned-pcm", "stt-owned-stream", "stt-owned-delayed", "stt-owned-eof"):
            with self.subTest(probe=name):
                self.assertEqual(probe.probe_evidence_metadata(name)["fixture"], "synthetic_korean_pcm")
        self.assertIsNone(probe.probe_evidence_metadata("stt-owned-listen")["fixture"])
        report = probe.probe_evidence_metadata("stt-owned-eof")
        self.assertIn("finite synthetic input trimmed only at exact trailing-zero samples", report["scope"])
        self.assertIn("actual EOF and completeAudioSend call timestamps", report["scope"])
        self.assertIn("no microphone or SDK flush acknowledgment", report["scope"])
        self.assertIn("unchanged product 5s/500ms timing", report["scope"])


class AutomaticPlanningProbeTest(unittest.TestCase):
    def test_scripted_planning_supports_both_variants_without_run_as(self):
        name = "automatic-planning"
        self.assertEqual(probe.PROBES.count(name), 1)
        self.assertNotIn(name, probe.RUN_AS_PROBES)
        for variant, expected in (("debug", name), ("privacy", name + "-privacy")):
            with self.subTest(variant=variant):
                self.assertEqual(probe.evidence_stem(name, variant), expected)
        self.assertEqual(probe.probe_timeout(name), 90)
        self.assertEqual(probe.read_development_server(name, None), (None, None))
        self.assertIsNone(probe.read_content_bundle(name, None))

    def test_planning_rejects_external_fixture_and_secret_options_before_adb(self):
        for variant in ("debug", "privacy"):
            for option in ("--https-fixture-ready", "--development-server-ready",
                           "--device-token-file", "--content-bundle"):
                with self.subTest(variant=variant, option=option), \
                        patch("sys.argv", ["probe-pleos.py", "automatic-planning", "--build-type", variant,
                                           option, "/not-a-test-input/private-file"]), \
                        patch.object(probe.subprocess, "run") as run, \
                        patch.object(probe, "private_bytes") as private_read, patch("sys.stderr"):
                    with self.assertRaises(SystemExit) as error:
                        probe.main()
                    self.assertEqual(error.exception.code, 2)
                    private_read.assert_not_called()
                    run.assert_not_called()

    def test_planning_report_keeps_scripted_and_actual_boundaries_separate(self):
        report = probe.probe_evidence_metadata("automatic-planning")
        self.assertEqual(report["fixture"], "disposable_database_and_synthetic_test_content")
        self.assertIn("actual Room with synthetic installed content and scripted runtime", report["scope"])
        self.assertIn("region/explicit-interest planning, repeat filtering and playback boundaries", report["scope"])
        self.assertIn("no microphone, model, location SDK or actual audio playback", report["scope"])
        self.assertNotIn("actual Media3 playback", report["scope"])

    def test_existing_report_fixture_contracts_are_preserved(self):
        for name, https, expected in (
                ("location", False, "two_synthetic_coordinates"),
                ("stt-pcm", False, "synthetic_korean_pcm"),
                ("storage", False, "disposable_database_and_synthetic_test_content"),
                ("generation-https", True, "local_https_synthetic_provider_one_day_ca_ephemeral_device_token"),
                ("music-runtime", False, None)):
            with self.subTest(probe=name):
                self.assertEqual(probe.probe_evidence_metadata(name, https)["fixture"], expected)
        self.assertIn("actual Media3 playback", probe.probe_evidence_metadata("music-runtime")["scope"])
        self.assertIn("synthetic provider", probe.probe_evidence_metadata("generation-https", True)["scope"])


class LocalCommandsProbeTest(unittest.TestCase):
    def test_disposable_commands_probe_needs_no_debug_access_or_external_config(self):
        name = "local-commands"
        self.assertEqual(probe.PROBES.count(name), 1)
        self.assertNotIn(name, probe.RUN_AS_PROBES)
        self.assertEqual(probe.probe_timeout(name), 90)
        self.assertEqual(probe.read_development_server(name, None), (None, None))
        self.assertIsNone(probe.read_content_bundle(name, None))
        for variant in ("debug", "privacy"):
            self.assertEqual(probe.evidence_stem(name, variant), name + ("-privacy" if variant == "privacy" else ""))

    def test_external_fixture_and_secret_options_fail_before_read_or_adb(self):
        for variant in ("debug", "privacy"):
            for option in ("--https-fixture-ready", "--development-server-ready",
                           "--device-token-file", "--content-bundle"):
                with self.subTest(variant=variant, option=option), \
                        patch("sys.argv", ["probe-pleos.py", "local-commands", "--build-type", variant,
                                           option, "/not-a-test-input/private-file"]), \
                        patch.object(probe.subprocess, "run") as run, \
                        patch.object(probe, "private_bytes") as private_read, patch("sys.stderr"):
                    with self.assertRaises(SystemExit) as error:
                        probe.main()
                    self.assertEqual(error.exception.code, 2)
                    private_read.assert_not_called()
                    run.assert_not_called()

    def test_report_does_not_claim_live_speech_or_model_acceptance(self):
        report = probe.probe_evidence_metadata("local-commands")
        self.assertEqual(report["fixture"], "disposable_database_and_synthetic_test_content")
        self.assertIn("actual Room/Keystore", report["scope"])
        self.assertIn("scripted input, output and generation", report["scope"])
        self.assertIn("no microphone, audio playback, location or external model", report["scope"])


class RegionalContentProbeTest(unittest.TestCase):
    def test_disposable_regional_content_supports_privacy_without_private_inputs(self):
        name = "regional-content"
        self.assertEqual(probe.PROBES.count(name), 1)
        self.assertNotIn(name, probe.RUN_AS_PROBES)
        self.assertEqual(probe.read_development_server(name, None), (None, None))
        self.assertIsNone(probe.read_content_bundle(name, None))
        for variant in ("debug", "privacy"):
            with self.subTest(variant=variant):
                self.assertEqual(probe.evidence_stem(name, variant), name + ("-privacy" if variant == "privacy" else ""))

    def test_external_fixtures_and_secret_inputs_are_rejected_before_read_or_adb(self):
        for variant in ("debug", "privacy"):
            for option in ("--https-fixture-ready", "--development-server-ready", "--device-token-file", "--content-bundle"):
                with self.subTest(variant=variant, option=option), \
                        patch("sys.argv", ["probe-pleos.py", "regional-content", "--build-type", variant,
                                           option, "/not-a-test-input/private-file"]), \
                        patch.object(probe.subprocess, "run") as run, \
                        patch.object(probe, "private_bytes") as private_read, patch("sys.stderr"):
                    with self.assertRaises(SystemExit) as error:
                        probe.main()
                    self.assertEqual(error.exception.code, 2)
                    private_read.assert_not_called()
                    run.assert_not_called()

    def test_evidence_identifies_synthetic_content_and_does_not_claim_live_acceptance(self):
        report = probe.probe_evidence_metadata("regional-content")
        self.assertEqual(report["fixture"], "disposable_database_and_synthetic_test_content")
        self.assertIn("actual Room v1-to-v2 migration", report["scope"])
        self.assertIn("reviewed textual region bindings with synthetic content", report["scope"])
        self.assertIn("no location, audio, consent change or model", report["scope"])
        self.assertNotIn("actual SDK callbacks", report["scope"])
        self.assertNotIn("actual packaged official NGII geometry", report["scope"])


class LandmarkStorageProbeTest(unittest.TestCase):
    def test_reviewed_landmarks_use_disposable_data_without_private_or_external_inputs(self):
        name = "landmark-storage"
        self.assertEqual(probe.PROBES.count(name), 1)
        self.assertNotIn(name, probe.RUN_AS_PROBES)
        self.assertEqual(probe.read_development_server(name, None), (None, None))
        self.assertIsNone(probe.read_content_bundle(name, None))
        self.assertEqual(probe.probe_timeout(name), 90)
        report = probe.probe_evidence_metadata(name)
        self.assertEqual(report["fixture"], "disposable_database_and_synthetic_test_content")
        self.assertIn("coordinate and story association with synthetic content", report["scope"])
        self.assertIn("no location, audio, consent change or model", report["scope"])
        for variant in ("debug", "privacy"):
            for option in ("--https-fixture-ready", "--development-server-ready", "--device-token-file", "--content-bundle"):
                with self.subTest(variant=variant, option=option), \
                        patch("sys.argv", ["probe-pleos.py", name, "--build-type", variant,
                                           option, "/not-a-test-input/private-file"]), \
                        patch.object(probe.subprocess, "run") as run, \
                        patch.object(probe, "private_bytes") as private_read, patch("sys.stderr"):
                    with self.assertRaises(SystemExit) as error:
                        probe.main()
                    self.assertEqual(error.exception.code, 2)
                    private_read.assert_not_called()
                    run.assert_not_called()


class RecoveryMusicGapProbeTest(unittest.TestCase):
    def test_recovery_probe_preserves_fixture_and_no_capture_boundary(self):
        name = "recovery-music-gap"
        self.assertEqual(probe.PROBES.count(name), 1)
        self.assertNotIn(name, probe.RUN_AS_PROBES)
        self.assertEqual(probe.probe_timeout(name), 90)
        report = probe.probe_evidence_metadata(name)
        self.assertEqual(report["fixture"], "disposable_database_and_synthetic_test_content")
        self.assertIn("durable story/music completion recovery", report["scope"])
        self.assertIn("no location, microphone, audio or external model", report["scope"])
        for option in ("--https-fixture-ready", "--development-server-ready", "--device-token-file", "--content-bundle"):
            with self.subTest(option=option), \
                    patch("sys.argv", ["probe-pleos.py", name, "--build-type", "privacy", option, "/private-input"]), \
                    patch.object(probe.subprocess, "run") as run, \
                    patch.object(probe, "private_bytes") as read, patch("sys.stderr"):
                with self.assertRaises(SystemExit) as error:
                    probe.main()
                self.assertEqual(error.exception.code, 2)
                run.assert_not_called()
                read.assert_not_called()


class RegionPolicyStorageProbeTest(unittest.TestCase):
    def test_policy_install_probe_keeps_product_approval_and_capture_outside_scope(self):
        name = "region-policy-storage"
        self.assertEqual(probe.PROBES.count(name), 1)
        self.assertNotIn(name, probe.RUN_AS_PROBES)
        self.assertEqual(probe.probe_timeout(name), 90)
        report = probe.probe_evidence_metadata(name)
        self.assertEqual(report["fixture"], "official_packaged_geometry_disposable_database_public_synthetic_coordinates")
        self.assertIn("explicitly synthetic region policy reviews", report["scope"])
        self.assertIn("no product policy approval, consent change, location subscription, audio or model", report["scope"])
        for variant in ("debug", "privacy"):
            self.assertEqual(probe.evidence_stem(name, variant), name + ("-privacy" if variant == "privacy" else ""))

    def test_private_review_or_fixture_options_cannot_modify_product_data(self):
        for option in ("--https-fixture-ready", "--development-server-ready", "--device-token-file", "--content-bundle"):
            with self.subTest(option=option), \
                    patch("sys.argv", ["probe-pleos.py", "region-policy-storage", "--build-type", "privacy", option, "/private-review"]), \
                    patch.object(probe.subprocess, "run") as run, \
                    patch.object(probe, "private_bytes") as read, patch("sys.stderr"):
                with self.assertRaises(SystemExit) as error:
                    probe.main()
                self.assertEqual(error.exception.code, 2)
                run.assert_not_called()
                read.assert_not_called()


class ProductLocationProbeTest(unittest.TestCase):
    def test_actual_location_uses_existing_consent_with_bounded_privacy_compatible_scope(self):
        name = "app-location-live"
        self.assertEqual(probe.PROBES.count(name), 1)
        self.assertNotIn(name, probe.RUN_AS_PROBES)
        self.assertEqual(probe.probe_timeout(name), 120)
        self.assertEqual(probe.read_development_server(name, None), (None, None))
        self.assertIsNone(probe.read_content_bundle(name, None))
        report = probe.probe_evidence_metadata(name)
        self.assertIsNone(report["fixture"])
        for boundary in ("existing saved location consent and permissions", "real Activity onStop/return",
                         "Home or moveTaskToBack", "explicitly marked Activity close/reopen fallback with observed onStop and onDestroy",
                         "close/reopen does not prove Home/background acceptance",
                         "no coordinate output, permission grant, consent change, Virtual Console or model call",
                         "storage quiescence is not a vendor unregister acknowledgment"):
            self.assertIn(boundary, report["scope"])
        for variant in ("debug", "privacy"):
            self.assertEqual(probe.evidence_stem(name, variant), name + ("-privacy" if variant == "privacy" else ""))

    def test_live_location_cannot_accept_coordinate_fixtures_or_private_inputs(self):
        for variant in ("debug", "privacy"):
            for option in ("--https-fixture-ready", "--development-server-ready", "--device-token-file", "--content-bundle"):
                with self.subTest(variant=variant, option=option), \
                        patch("sys.argv", ["probe-pleos.py", "app-location-live", "--build-type", variant,
                                           option, "/not-a-test-input/private-file"]), \
                        patch.object(probe.subprocess, "run") as run, \
                        patch.object(probe, "private_bytes") as private_read, patch("sys.stderr"):
                    with self.assertRaises(SystemExit) as error:
                        probe.main()
                    self.assertEqual(error.exception.code, 2)
                    private_read.assert_not_called()
                    run.assert_not_called()


class ProductMusicProbeTest(unittest.TestCase):
    def test_actual_music_probe_needs_no_private_input_and_allows_full_track(self):
        self.assertEqual(probe.PROBES.count("app-music"), 1)
        self.assertNotIn("app-music", probe.RUN_AS_PROBES)
        self.assertEqual(probe.probe_timeout("app-music"), 190)
        self.assertEqual(probe.read_development_server("app-music", None), (None, None))
        self.assertIsNone(probe.read_content_bundle("app-music", None))
        report = probe.probe_evidence_metadata("app-music")
        self.assertIsNone(report["fixture"])
        self.assertIn("licensed bundled music", report["scope"])
        self.assertIn("no microphone, model, synthetic tone or location opt-in", report["scope"])

    def test_external_fixture_options_fail_before_private_read_or_adb(self):
        for option in ("--https-fixture-ready", "--development-server-ready", "--device-token-file", "--content-bundle"):
            with self.subTest(option=option), patch("sys.argv", ["probe-pleos.py", "app-music", "--build-type", "privacy",
                    option, "/not-a-test-input/private-file"]), patch.object(probe.subprocess, "run") as run, \
                    patch.object(probe, "private_bytes") as private_read, patch("sys.stderr"):
                with self.assertRaises(SystemExit) as error:
                    probe.main()
                self.assertEqual(error.exception.code, 2)
                private_read.assert_not_called()
                run.assert_not_called()


class DevelopmentGenerationTransferTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.directory = Path(self.temporary.name)
        self.token = self.directory / "token"
        self.token.write_bytes(b"test-private-token-canary-1234567890")
        self.token.chmod(0o600)
        self.ready = self.directory / "ready.json"
        self.document = {"port": 8443, "serverOrigin": "https://localhost:8443",
                         "scope": "debug_loopback_real_configured_provider", "tokenFile": str(self.token)}
        self.write_ready()

    def write_ready(self):
        self.ready.write_text(json.dumps(self.document))
        self.ready.chmod(0o600)

    def tearDown(self):
        self.temporary.cleanup()

    def test_only_provision_reads_private_token_and_live_needs_no_plaintext(self):
        _, token = probe.read_development_server("generation-provision", self.ready)
        self.assertEqual(token, self.token.read_bytes())
        self.token.unlink()
        _, token = probe.read_development_server("generation-live", self.ready)
        self.assertIsNone(token)

    def test_planning_and_product_probes_use_installed_encrypted_token(self):
        self.token.unlink()
        for name in ("generation-planning-live", "app-story-generation"):
            ready, token = probe.read_development_server(name, self.ready)
            self.assertEqual(ready["serverOrigin"], "https://localhost:8443")
            self.assertIsNone(token)
            with self.assertRaises(probe.AuditFailure):
                probe.read_development_server(name, self.ready, self.token)
            with self.assertRaises(probe.AuditFailure):
                probe.read_development_server(name, None)

    def test_inputs_cannot_be_used_by_other_probes(self):
        with self.assertRaises(probe.AuditFailure):
            probe.read_development_server("tts-speak", self.ready)
        with self.assertRaises(probe.AuditFailure):
            probe.read_development_server("generation-live", self.ready, self.token)
        with self.assertRaises(probe.AuditFailure):
            probe.read_development_server("generation-provision", None)

    def test_remote_origin_wrong_scope_and_invalid_ports_fail_before_adb(self):
        for field, value in (("serverOrigin", "https://remote.invalid:8443"), ("port", True),
                             ("port", 0), ("scope", "synthetic-provider-real-https")):
            previous = self.document[field]
            self.document[field] = value
            self.write_ready()
            with self.assertRaises(probe.AuditFailure):
                probe.read_development_server("generation-provision", self.ready)
            self.document[field] = previous

    def test_nonprivate_and_symlink_tokens_fail_without_value_in_error(self):
        self.token.chmod(0o644)
        with self.assertRaises(probe.AuditFailure) as failure:
            probe.read_development_server("generation-provision", self.ready)
        self.assertNotIn("canary", str(failure.exception))
        self.token.chmod(0o600)
        link = self.directory / "link"
        link.symlink_to(self.token)
        with self.assertRaises(probe.AuditFailure):
            probe.read_development_server("generation-provision", self.ready, link)

    def test_env_or_oversized_token_cannot_be_copied_to_device(self):
        for value in (b"PRIVATE_KEY=not-a-device-token\nOTHER_KEY=private", b"x" * 4097):
            self.token.write_bytes(value)
            with self.assertRaises(probe.AuditFailure):
                probe.read_development_server("generation-provision", self.ready)

    def test_existing_matching_reverse_reused_but_different_target_not_replaced(self):
        adb = Mock(return_value="host-serial tcp:8443 tcp:8443")
        probe.ensure_development_reverse(adb, 8443)
        adb.assert_called_once_with("reverse", "--list")
        adb = Mock(return_value="host-serial tcp:8443 tcp:9000")
        with self.assertRaises(probe.AuditFailure):
            probe.ensure_development_reverse(adb, 8443)
        self.assertEqual(adb.call_count, 1)
        adb = Mock(return_value="")
        probe.ensure_development_reverse(adb, 8443)
        adb.assert_called_with("reverse", "--no-rebind", "tcp:8443", "tcp:8443")

    def test_probe_outer_timeout_preserves_product_deadline(self):
        self.assertEqual(probe.probe_timeout("generation-live"), 120)
        self.assertEqual(probe.probe_timeout("music-runtime"), 120)
        for name in ("generation-planning-live", "app-story-generation", "story-generation-storage", "resume-context"):
            self.assertEqual(probe.probe_timeout(name), 120)
        self.assertEqual(probe.probe_timeout("stt-owned-listen"), 90)
        self.assertEqual(probe.probe_timeout("stt-owned-stream"), 90)
        self.assertEqual(probe.probe_timeout("stt-owned-delayed"), 90)
        self.assertEqual(probe.probe_timeout("location"), 50)


class ContentBundleTransferTest(unittest.TestCase):
    def test_persistent_install_requires_explicit_bundle(self):
        with self.assertRaisesRegex(probe.AuditFailure, "^content_install_local_requires_reviewed_bundle$"):
            probe.read_content_bundle("content-install-local", None)
        self.assertIsNone(probe.read_content_bundle("content-install", None))

    def test_transfer_only_accepts_content_modes_and_preserves_reviewed_bytes(self):
        path = Path(__file__).parents[2] / "app/src/test/resources/content/test-bundle.json"
        self.assertEqual(probe.read_content_bundle("content-install-local", path), path.read_bytes())
        self.assertEqual(probe.read_content_bundle("content-runtime", path), path.read_bytes())
        self.assertIsNone(probe.read_content_bundle("content-runtime", None))
        with self.assertRaisesRegex(probe.AuditFailure, "^content_bundle_not_applicable$"):
            probe.read_content_bundle("tts-speak", path)

    def test_planning_probe_requires_explicit_reviewed_bundle(self):
        with self.assertRaises(probe.AuditFailure):
            probe.read_content_bundle("generation-planning-live", None)
        path = Path(__file__).parents[2] / "app/src/test/resources/content/test-bundle.json"
        self.assertEqual(probe.read_content_bundle("generation-planning-live", path), path.read_bytes())
        with self.assertRaises(probe.AuditFailure):
            probe.read_content_bundle("app-story-generation", path)

    def test_wrong_file_and_oversized_contents_never_reach_adb(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "input.json"
            for value in (b"PRIVATE_KEY=not-a-bundle", b'{"serviceKey":"private"}', b"x" * (256 * 1024 + 1)):
                path.write_bytes(value)
                with self.assertRaises(probe.AuditFailure) as error:
                    probe.read_content_bundle("content-install-local", path)
                self.assertNotIn("private", str(error.exception))

    def test_v2_transfer_preserves_exact_reviewed_bytes_and_region_bindings(self):
        fixture = Path(__file__).parents[2] / "app/src/test/resources/content/test-bundle.json"
        document = json.loads(fixture.read_bytes())
        document["formatVersion"] = 2
        document["regionBindings"] = [{
            "storyId": document["stories"][0]["storyId"], "regionId": "synthetic-region",
            "regionRevisionId": "synthetic-region-r1", "regionCode": "01234", "datasetVersion": "synthetic-v1",
            "regionName": "합성 지역", "regionSourceRevisionId": "synthetic-geometry-r1",
            "associationFactRevisionId": document["facts"][0]["factRevisionId"],
            "verifiedAt": 1_794_000_000_000, "reviewBasis": "합성 명시적 지역 소속 검토",
        }]
        raw = (json.dumps(document, ensure_ascii=False, indent=3) + "\n").encode("utf-8")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "reviewed-v2.json"
            path.write_bytes(raw)
            for name in ("content-install", "content-install-local", "content-runtime", "generation-planning-live"):
                with self.subTest(probe=name):
                    transferred = probe.read_content_bundle(name, path)
                    self.assertEqual(transferred, raw)
                    self.assertEqual(json.loads(transferred)["regionBindings"], document["regionBindings"])

    def test_transfer_enforces_distinct_exact_v1_v2_root_shapes(self):
        fixture = Path(__file__).parents[2] / "app/src/test/resources/content/test-bundle.json"
        v1 = json.loads(fixture.read_bytes())
        v2 = {**v1, "formatVersion": 2, "regionBindings": []}
        rejected = [
            {**v1, "regionBindings": []},
            {**v1, "formatVersion": 2},
            {**v1, "regions": []},
            {**v2, "regionPolicy": {"approved": True}},
            {key: value for key, value in v2.items() if key != "facts"},
        ]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "input.json"
            for document in (v1, v2):
                raw = json.dumps(document).encode()
                path.write_bytes(raw)
                self.assertEqual(probe.read_content_bundle("content-install-local", path), raw)
            for index, document in enumerate(rejected):
                with self.subTest(case=index):
                    path.write_text(json.dumps(document))
                    with self.assertRaisesRegex(probe.AuditFailure, "^invalid_content_bundle_shape$"):
                        probe.read_content_bundle("content-install-local", path)

    def test_transfer_rejects_unknown_boolean_and_noninteger_format_versions(self):
        fixture = Path(__file__).parents[2] / "app/src/test/resources/content/test-bundle.json"
        document = json.loads(fixture.read_bytes())
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "input.json"
            for version in (0, 3, -1, True, False, 1.0, 2.0, "1", "2", None):
                with self.subTest(version=version):
                    document["formatVersion"] = version
                    path.write_text(json.dumps(document))
                    with self.assertRaisesRegex(probe.AuditFailure, "^invalid_content_bundle_shape$"):
                        probe.read_content_bundle("content-install-local", path)


def event(kind, stamp=1, source="VOICE_RECOGNITION", session=241):
    return (f"11-06 18:15:14:{stamp:03d} rec {kind} riid:295 uid:1010142 "
            f"session:{session} src:{source} not silenced pack:{probe.CAAS}")


def dump(events=(), active=False):
    configuration = "riid 55; active? true\n"  # Unrelated hotword has no configuration.
    if active:
        configuration += (
            "riid 295; active? true\n"
            "  session:241 -- source client=VOICE_RECOGNITION, dev=1ch 16000Hz "
            f"ENCODING_PCM_16BIT -- uid:1010142 -- patch:89 -- pack:{probe.CAAS} "
            "-- format client=1ch 16000Hz ENCODING_PCM_16BIT -- silenced:false\n")
    return ("RecordActivityMonitor dump time: test\n" + configuration +
            "Events log: recording activity\n" + "\n".join(events) + "\nAudioDeviceBroker:\n")


class RecordingAuditTest(unittest.TestCase):
    def setUp(self):
        self.baseline = probe.audio_snapshot(dump())
        self.ready = {"pid": 5786, "audio": probe.audio_snapshot(dump([event("update")], active=True))}
        self.closed = [{"pid": 5786, "samePidAliveBeforeAndAfter": True,
                        "activeCaas": False, "petasosBindingPresent": False} for _ in range(3)]

    def audit(self, history, closed=None, active=False):
        return probe.audit_audio_lifecycle(
            "stt-close", self.baseline, probe.audio_snapshot(dump(history, active)),
            self.ready, self.closed if closed is None else closed)

    def test_actual_active_configuration_ignores_unrelated_hotword(self):
        self.assertEqual(self.ready["audio"]["activeRecordings"],
                         [{"recordingId": 295, "uid": 1010142, "sessionId": 241}])

    def test_update_is_not_start_but_active_ready_then_stop_is_proof(self):
        result = self.audit([event("update"), event("stop", 2)])
        self.assertTrue(result["passed"])
        self.assertEqual(result["events"][0]["event"], "update")

    def test_stop_before_ready_does_not_count(self):
        self.ready["audio"] = probe.audio_snapshot(dump([event("stop")], active=True))
        self.assertFalse(self.audit([event("stop")])["passed"])

    def test_wrong_session_stop_fails(self):
        self.assertFalse(self.audit([event("update"), event("stop", 2, session=242)])["passed"])

    def test_start_or_update_after_stop_fails(self):
        for last in ("start", "update"):
            with self.subTest(last=last):
                self.assertFalse(self.audit([event("update"), event("stop", 2), event(last, 3)])["passed"])

    def test_retained_binding_or_changed_process_fails(self):
        for field, value in (("petasosBindingPresent", True), ("pid", 5787),
                             ("samePidAliveBeforeAndAfter", False), ("activeCaas", True)):
            samples = [dict(sample) for sample in self.closed]
            samples[1][field] = value
            with self.subTest(field=field):
                self.assertFalse(self.audit([event("update"), event("stop", 2)], samples)["passed"])

    def test_pcm_rejects_recording_from_other_source(self):
        result = probe.audit_audio_lifecycle(
            "stt-pcm", self.baseline,
            probe.audio_snapshot(dump([event("update", source="MIC"), event("stop", 2, source="MIC")])),
            None, [])
        self.assertFalse(result["passed"])

    def test_pcm_with_no_recording_is_valid(self):
        self.assertTrue(probe.audit_audio_lifecycle(
            "stt-pcm", self.baseline, self.baseline, None, [])["passed"])

    def test_unknown_caas_event_and_malformed_configuration_fail_closed(self):
        with self.assertRaises(probe.AuditFailure):
            probe.audio_snapshot(dump([event("unknown")]))
        with self.assertRaises(probe.AuditFailure):
            probe.audio_snapshot(dump(active=True).replace("uid:1010142", "uid:unknown"))

    def test_missing_dump_sections_fail_closed(self):
        with self.assertRaises(probe.AuditFailure):
            probe.audio_snapshot("Permission denied")
        with self.assertRaises(probe.AuditFailure):
            probe.service_binding_present("Permission denied", 5786)

    def test_binding_matches_exact_probe_pid(self):
        services = ("ACTIVITY MANAGER SERVICES (dumpsys activity services)\n"
                    "* Client AppBindRecord{fdb2749 ProcessRecord{2c37066 5786:com.petasos.app/u10a174}}")
        self.assertTrue(probe.service_binding_present(services, 5786))
        self.assertFalse(probe.service_binding_present(services, 5787))

    def test_process_must_be_alive_with_exact_name(self):
        self.assertTrue(probe.process_alive("PID NAME\n5786 com.petasos.app", 5786))
        self.assertFalse(probe.process_alive("PID NAME", 5786))
        self.assertFalse(probe.process_alive("PID NAME\n5786 com.petasos.app.test", 5786))


class TtsBindingAuditTest(unittest.TestCase):
    def setUp(self):
        self.ready = {"pid": 5786, "samePidAliveBeforeAndAfter": True,
                      "petasosBindingPresent": True}
        self.closed = [{"pid": 5786, "samePidAliveBeforeAndAfter": True,
                        "petasosBindingPresent": False} for _ in range(3)]

    def test_live_binding_then_three_unbound_observations_pass(self):
        result = probe.audit_tts_binding_lifecycle(self.ready, self.closed)
        self.assertTrue(result["passed"])
        self.assertFalse(result["playerActivityVerified"])
        self.assertIn("does not prove silence", result["limitation"])

    def test_absent_initial_binding_cannot_prove_cleanup(self):
        self.ready["petasosBindingPresent"] = False
        self.assertFalse(probe.audit_tts_binding_lifecycle(self.ready, self.closed)["passed"])

    def test_missing_ready_observation_fails(self):
        self.assertFalse(probe.audit_tts_binding_lifecycle(None, self.closed)["passed"])

    def test_ready_process_must_be_alive(self):
        self.ready["samePidAliveBeforeAndAfter"] = False
        self.assertFalse(probe.audit_tts_binding_lifecycle(self.ready, self.closed)["passed"])

    def test_process_exit_replacement_or_retained_binding_fails(self):
        for field, value in (("samePidAliveBeforeAndAfter", False), ("pid", 5787),
                             ("petasosBindingPresent", True)):
            samples = [dict(sample) for sample in self.closed]
            samples[1][field] = value
            with self.subTest(field=field):
                self.assertFalse(probe.audit_tts_binding_lifecycle(self.ready, samples)["passed"])

    def test_all_three_closed_observations_required(self):
        for samples in ([], self.closed[:1], self.closed[:2], self.closed * 2):
            with self.subTest(count=len(samples)):
                self.assertFalse(probe.audit_tts_binding_lifecycle(self.ready, samples)["passed"])


if __name__ == "__main__":
    unittest.main()
