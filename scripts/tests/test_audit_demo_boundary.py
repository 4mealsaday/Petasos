"""Static demo-boundary false-pass/false-hit checks; no APK, device or real private inputs."""

import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location("audit_demo_boundary", Path(__file__).parents[1] / "audit-demo-boundary.py")
audit = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(audit)


def manifest(extra="", app_extra="", app_attributes="", package="com.petasos.app.demo", root_attributes=""):
    return (f'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}" {root_attributes}>'
            f'<uses-sdk android:minSdkVersion="28" android:targetSdkVersion="34"/>{extra}'
            '<application android:name="android.app.Application" android:allowBackup="false" '
            f'android:usesCleartextTraffic="false" {app_attributes}>{app_extra}</application></manifest>').encode()


def launcher(name="com.petasos.app.demo.DemoActivity", exported="true", extra="", filter_extra=""):
    return (f'<activity android:name="{name}" android:exported="{exported}">'
            '<intent-filter><action android:name="android.intent.action.MAIN"/>'
            f'<category android:name="android.intent.category.LAUNCHER"/>{filter_extra}</intent-filter>'
            f'{extra}</activity>')


class DemoBoundaryTest(unittest.TestCase):
    def test_plain_engine_manifest_and_pure_ports_pass_with_limited_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "manifest.xml"; path.write_bytes(manifest())
            source = root / "source"; source.mkdir()
            (source / "Engine.kt").write_text('''package com.petasos.app.demo
import com.petasos.app.runtime.*
import com.petasos.app.presentation.PlaybackPresentationMapper
import com.petasos.app.platform.android.content.BundledStoryCatalog
import com.petasos.app.platform.android.location.BundledRegionCatalog
class DemoSilentSpeech : RuntimeSpeech
val name = "PetasosSessionHost android.speech.SpeechRecognizer"
/* PleosLocationSource /* nested comment */ GenerationClient */
// import java.net.URL
''')
            report = audit.audit(path, source)
            self.assertEqual(report["status"], "PASS")
            self.assertEqual(report["profile"], "engine")
            self.assertEqual(report["sourceFileCount"], 1)
            self.assertFalse(report["runtimeIsolationVerified"])
            self.assertFalse(report["transitiveCallsInspected"])

    def test_package_shared_uid_application_and_hooks_are_rejected(self):
        cases = ((manifest(package="com.petasos.app"), "MANIFEST_PACKAGE"),
                 (manifest(root_attributes='android:sharedUserId="same"'), "MANIFEST_SHARED_UID"),
                 (manifest().replace(b"android.app.Application", b"com.petasos.app.PetasosApplication"), "MANIFEST_APPLICATION_CLASS"),
                 (manifest().replace(b'allowBackup="false"', b'allowBackup="true"'), "MANIFEST_BACKUP"),
                 (manifest(app_attributes='android:appComponentFactory="example.Factory"'), "MANIFEST_EXECUTION_HOOK"),
                 (manifest(app_attributes='android:networkSecurityConfig="@xml/debug"'), "MANIFEST_NETWORK_CONFIG"))
        for content, expected in cases:
            with self.subTest(expected=expected):
                self.assertIn(expected, audit.manifest_checks(content)["codes"])

    def test_all_components_permissions_queries_and_initializers_fail_closed(self):
        for component in ("activity", "activity-alias", "service", "provider", "receiver"):
            with self.subTest(component=component):
                self.assertIn("MANIFEST_COMPONENTS", audit.manifest_checks(
                    manifest(app_extra=f'<{component} android:name="example.Component"/>'))["codes"])
        for permission in ("uses-permission", "uses-permission-sdk-23", "permission", "permission-tree", "permission-group"):
            self.assertIn("MANIFEST_PERMISSIONS", audit.manifest_checks(
                manifest(extra=f'<{permission} android:name="example.Permission"/>'))["codes"])
        self.assertIn("MANIFEST_QUERIES", audit.manifest_checks(manifest(extra="<queries/>"))["codes"])
        self.assertIn("MANIFEST_APPLICATION_CHILDREN", audit.manifest_checks(
            manifest(app_extra='<meta-data android:name="initializer" android:value="true"/>'))["codes"])
        self.assertIn("MANIFEST_INSTRUMENTATION", audit.manifest_checks(
            manifest(extra='<instrumentation android:name="example.Runner"/>'))["codes"])

    def test_alias_wildcard_qualified_and_unqualified_real_api_references_fail(self):
        snippets = (
            "import com.petasos.app.platform.android.runtime.PetasosSessionHost as Safe",
            "import com.petasos.app.platform.pleos.location.*",
            "import com.petasos.app.platform.*",
            "val output = com . petasos . app . platform . pleos . speech . PleosRuntimeSpeech()",
            "val host = `PetasosSessionHost`", "val player = Media3RuntimeMusic()",
            "import java /* ignored */ . net . URL as Destination",
            "import com.petasos.app.generation.DeviceGenerationConfigStore",
            "val source = AndroidLocationSource()", "val client = AuxiliaryRouteClient()",
            "context.getSystemService(name)", "Class.forName(name)",
        )
        for snippet in snippets:
            with self.subTest(snippet=snippet):
                self.assertTrue(audit.source_checks(snippet.encode()))
        for snippet in ("class RuntimeSpeech", "val URLLabel = 1", "val GenerationClientLabel = 2",
                        "import com.petasos.app.location.LocationFreshness", "val elapsed = System.currentTimeMillis()"):
            self.assertEqual(audit.source_checks(snippet.encode()), [])

    def test_literals_do_not_match_but_kotlin_template_expressions_do(self):
        safe = '''val note = "GenerationClient \\" literal" // PetasosSessionHost
val raw = """android.location.LocationManager and ${1 + 2}"""
'''
        self.assertEqual(audit.source_checks(safe.encode()), [])
        for snippet in ('val s = "${GenerationClient()}"',
                        'val s = """${if (true) { PetasosSessionHost() } else { null }}"""',
                        'val s = "${"nested ${android.speech.SpeechRecognizer}"}"'):
            self.assertTrue(audit.source_checks(snippet.encode()))

    def test_naver_sdk_references_fail_but_offline_map_factory_passes(self):
        for snippet in (
                "import com.naver.maps.map.MapView",
                "import com.naver.maps.map.NaverMapSdk as PublicMap",
                "import com.naver.maps.*",
                "val map = com /* split */ . naver . maps . map . MapView(context)"):
            with self.subTest(snippet=snippet):
                self.assertIn("SOURCE_FORBIDDEN_PACKAGE", audit.source_checks(snippet.encode()))
        offline = '''package com.petasos.app.presentation.map
import android.content.Context
internal object ProductMapBackendFactory {
    fun create(context: Context, onReady: () -> Unit, onFailure: () -> Unit): JourneyMapBackend? = null
}
'''
        self.assertEqual(audit.source_checks(offline.encode()), [])

    def test_malformed_oversized_entity_and_lexical_inputs_never_pass(self):
        for content, code in ((b"<manifest", "MANIFEST_XML_INVALID"),
                              (b'<!DOCTYPE x [<!ENTITY y "z">]><manifest/>', "XML_DECLARATION_FORBIDDEN"),
                              ('<!DOCTYPE x [<!ENTITY y "z">]><manifest/>'.encode("utf-16"), "MANIFEST_ENCODING_INVALID"),
                              (b" " * (audit.MANIFEST_LIMIT + 1), "INPUT_TOO_LARGE")):
            with self.assertRaisesRegex(audit.AuditError, code):
                audit.manifest_checks(content)
        for source in (b'val s = "unterminated', b"/* unterminated", b'val s = "${missing"', b"\xff"):
            with self.assertRaises(audit.AuditError):
                audit.source_checks(source)
        with self.assertRaisesRegex(audit.AuditError, "SOURCE_UNSUPPORTED_UNICODE_ESCAPE"):
            audit.source_checks(br"class \u0050etasosSessionHost {}", java=True)

    def test_missing_sources_symlinks_and_limits_fail_without_input_text(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); path = root / "manifest.xml"; path.write_bytes(manifest())
            source = root / "source"; source.mkdir()
            self.assertIn("SOURCE_FILES_MISSING", audit.audit(path, source)["violationCounts"])
            outside = root / "secret-synthetic.kt"; outside.write_text("SENSITIVE_CANARY")
            link = source / "alias.kt"; link.symlink_to(outside)
            report = audit.audit(path, source)
            self.assertIn("SOURCE_SYMLINK", report["violationCounts"])
            self.assertNotIn("SENSITIVE_CANARY", json.dumps(report))
            self.assertNotIn("secret-synthetic", json.dumps(report))
            link.unlink()
            (source / "Engine.kt").write_bytes(b" " * (audit.SOURCE_LIMIT + 1))
            self.assertIn("INPUT_TOO_LARGE", audit.audit(path, source)["violationCounts"])

    def test_cli_report_redacts_values_and_cannot_overwrite_inputs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); path = root / "manifest.xml"
            path.write_bytes(manifest(extra='<uses-permission android:name="SENSITIVE_CANARY"/>'))
            source = root / "source"; source.mkdir()
            code = source / "Engine.kt"; code.write_text("class Engine")
            output = root / "result.json"
            args = ["--manifest", str(path), "--source-dir", str(source), "--output", str(output)]
            with patch("sys.stdout", new_callable=io.StringIO) as stdout:
                self.assertEqual(audit.main(args), 1)
            serialized = output.read_text()
            self.assertIn("MANIFEST_PERMISSIONS", serialized)
            self.assertNotIn("SENSITIVE_CANARY", serialized + stdout.getvalue())
            with patch("sys.stdout", new_callable=io.StringIO):
                self.assertEqual(audit.main(args[:-1] + [str(code)]), 2)
            self.assertEqual(code.read_text(), "class Engine")

    def test_ui_profile_requires_exactly_one_launcher_and_preserves_engine_default(self):
        content = manifest(app_extra=launcher())
        self.assertEqual(audit.manifest_checks(content, "ui")["codes"], [])
        self.assertEqual(audit.manifest_checks(content, "ui")["componentCount"], 1)
        self.assertIn("MANIFEST_COMPONENTS", audit.manifest_checks(content)["codes"])
        self.assertEqual(audit.manifest_checks(manifest())["codes"], [])
        self.assertIn("MANIFEST_COMPONENTS", audit.manifest_checks(manifest(), "ui")["codes"])
        with self.assertRaisesRegex(audit.AuditError, "PROFILE_UNSUPPORTED"):
            audit.manifest_checks(content, "future")

    def test_ui_rejects_wrong_activity_missing_export_or_additional_components(self):
        for content in (launcher(name="com.petasos.app.MainActivity"),
                        launcher(name="com.petasos.app.demo.OtherActivity"),
                        launcher(name=".DemoActivity"), launcher(exported="false"),
                        launcher().replace(' android:exported="true"', ''),
                        launcher().replace(' android:exported="true"', ' android:exported="true" android:permission="example"')):
            with self.subTest(content=content):
                self.assertIn("MANIFEST_UI_ACTIVITY", audit.manifest_checks(manifest(app_extra=content), "ui")["codes"])
        for tag in ("activity", "activity-alias", "service", "provider", "receiver"):
            extra = f'<{tag} android:name="example.Exported" android:exported="true"/>'
            self.assertIn("MANIFEST_COMPONENTS", audit.manifest_checks(
                manifest(app_extra=launcher() + extra), "ui")["codes"])

    def test_ui_rejects_other_filters_metadata_data_duplicates_and_filter_attributes(self):
        variants = (
            launcher(filter_extra='<action android:name="android.intent.action.VIEW"/>'),
            launcher(filter_extra='<category android:name="android.intent.category.DEFAULT"/>'),
            launcher(filter_extra='<data android:scheme="https"/>'),
            launcher(filter_extra='<action android:name="android.intent.action.MAIN"/>'),
            launcher(extra='<intent-filter><action android:name="android.intent.action.VIEW"/></intent-filter>'),
            launcher(extra='<meta-data android:name="distractionOptimized" android:value="true"/>'),
            launcher().replace('<intent-filter>', '<intent-filter android:priority="100">'),
            launcher().replace('android.intent.action.MAIN', 'android.intent.action.VIEW'),
            launcher().replace('<category android:name="android.intent.category.LAUNCHER"/>', ''),
        )
        for content in variants:
            with self.subTest(content=content):
                self.assertIn("MANIFEST_UI_LAUNCHER_FILTER", audit.manifest_checks(manifest(app_extra=content), "ui")["codes"])

    def test_ui_does_not_relax_permission_query_or_product_reference_restrictions(self):
        for extra, expected in (
                ('<uses-permission android:name="android.permission.INTERNET"/>', "MANIFEST_PERMISSIONS"),
                ('<uses-permission android:name="android.permission.RECORD_AUDIO"/>', "MANIFEST_PERMISSIONS"),
                ('<queries/>', "MANIFEST_QUERIES")):
            self.assertIn(expected, audit.manifest_checks(manifest(extra=extra, app_extra=launcher()), "ui")["codes"])
        safe = b'''import android.app.Activity
import android.os.Handler
import java.util.concurrent.Executor
import com.petasos.app.platform.automotive.CarPolicySource
import com.petasos.app.platform.automotive.VehicleUxPolicy
import com.petasos.app.presentation.PlaybackPresentationRenderer
'''
        self.assertEqual(audit.source_checks(safe), [])
        self.assertIn("SOURCE_FORBIDDEN_TYPE", audit.source_checks(safe + b"\nval host = PetasosSessionHost()"))

    def test_cli_ui_profile_is_explicit_and_records_profile_in_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); path = root / "manifest.xml"
            path.write_bytes(manifest(app_extra=launcher()))
            source = root / "source"; source.mkdir()
            (source / "DemoActivity.kt").write_text("class DemoActivity : android.app.Activity()")
            output = root / "result.json"
            args = ["--manifest", str(path), "--source-dir", str(source), "--output", str(output)]
            with patch("sys.stdout", new_callable=io.StringIO):
                self.assertEqual(audit.main(args), 1)
                self.assertEqual(audit.main(args + ["--profile", "ui"]), 0)
            report = json.loads(output.read_text())
            self.assertEqual(report["profile"], "ui")
            self.assertFalse(report["runtimeIsolationVerified"])


if __name__ == "__main__":
    unittest.main()
