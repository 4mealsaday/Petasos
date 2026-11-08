"""Privacy smoke CLI exercised against a disposable fake adb, never an Android device."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

SCRIPTS = Path(__file__).parents[1]


class PrivacySmokeTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        scripts = self.root / "scripts"
        scripts.mkdir()
        for name in ("smoke-pleos.sh", "audit-speech-logs.py"):
            shutil.copyfile(SCRIPTS / name, scripts / name)
        self.script = scripts / "smoke-pleos.sh"
        self.apk = self.root / "app/build/outputs/apk/privacy/app-privacy.apk"
        self.apk.parent.mkdir(parents=True)
        self.apk.write_bytes(b"SYNTHETIC_PRIVACY_APK_ONLY")
        self.sdk = self.root / "sdk"
        (self.sdk / "platform-tools").mkdir(parents=True)
        fake = self.sdk / "platform-tools/adb"
        fake.write_text("#!" + sys.executable + "\n" + r'''
import os, struct, sys
from pathlib import Path
args = sys.argv[1:]
assert args[:2] == ['-s', 'emulator-5554']
args = args[2:]
with Path(os.environ['FAKE_ADB_CALLS']).open('a') as out: out.write(' '.join(args) + '\n')
if args == ['get-state']: print('device')
elif args == ['shell', 'getprop', 'ro.build.fingerprint']: print('synthetic/pleos/API34')
elif args == ['shell', 'getprop', 'ro.build.version.sdk']: print('34')
elif args == ['shell', 'am', 'get-current-user']: print('10')
elif args[0] == 'install':
    assert args[1:4] == ['--user','10','-r'] and args[4].endswith('/privacy/app-privacy.apk')
    print('Success\nSYNTHETIC_PRIVATE_INSTALL_OUTPUT')
elif args[:4] == ['shell','cmd','package','list']: print('package:com.petasos.app uid:1010174')
elif args == ['shell','date','+%s']: print('100')
elif args == ['shell','am','force-stop','--user','10','com.petasos.app']: pass
elif args[:4] == ['shell','am','start','-W']: print('Status: ok\nSYNTHETIC_PRIVATE_LAUNCH_OUTPUT')
elif args == ['shell','ps','-A','-o','PID,UID,NAME']: print('PID UID NAME\n100 1010174 com.petasos.app')
elif args == ['shell','dumpsys','activity','activities']: print('mResumedActivity: ActivityRecord{abc u10 com.petasos.app/.MainActivity t1}')
elif args[0] == 'logcat':
    assert '-B' in args and '--uid=1010174' in args and args[-1] == '*:V'
    message = b'SYNTHETIC_SAFE_STATUS'
    if os.environ.get('FAKE_CRASH') == '1': message = b'Fatal signal 11 SYNTHETIC_PRIVATE_CRASH_OUTPUT'
    payload = bytes([6]) + b'libc\0' + message + b'\0'
    sys.stdout.buffer.write(struct.pack('<HHiiIIII',len(payload),28,100,100,100,1,4,1010174) + payload)
else: raise SystemExit(3)
''')
        fake.chmod(0o700)
        self.artifacts = self.root / "evidence"
        self.env = dict(os.environ, ANDROID_SDK_ROOT=str(self.sdk), PETASOS_ARTIFACTS_DIR=str(self.artifacts),
                        FAKE_ADB_CALLS=str(self.root / "calls.txt"))
        self.env.pop("PETASOS_APK", None)

    def tearDown(self):
        self.temporary.cleanup()

    def run_smoke(self, *arguments):
        return subprocess.run(["bash", str(self.script), *arguments], env=self.env, capture_output=True, text=True, timeout=15)

    def test_privacy_variant_records_exact_apk_and_counts_without_raw_logs_or_screenshot(self):
        result = self.run_smoke("--build-type", "privacy")
        self.assertEqual(result.returncode, 0, result.stderr)
        evidence = "\n".join(path.read_text() for path in self.artifacts.iterdir())
        self.assertNotIn("SYNTHETIC_PRIVATE", evidence + result.stdout + result.stderr)
        self.assertIn("build_type=privacy", evidence)
        self.assertIn("apk=" + str(self.apk), evidence)
        self.assertIn("apk_sha256=", evidence)
        self.assertFalse((self.artifacts / "petasos-emulator.png").exists())
        self.assertFalse((self.artifacts / "app.log").exists())
        self.assertFalse((self.artifacts / "crash.log").exists())
        self.assertEqual(json.loads((self.artifacts / "crash-counts.json").read_text())["outcome"], "PASS")

    def test_privacy_crash_fails_with_counts_but_never_raw_body(self):
        self.env["FAKE_CRASH"] = "1"
        result = self.run_smoke("emulator-5554", "--build-type", "privacy")
        self.assertEqual(result.returncode, 1)
        evidence = "\n".join(path.read_text() for path in self.artifacts.iterdir())
        self.assertNotIn("SYNTHETIC_PRIVATE", evidence + result.stdout + result.stderr)
        self.assertEqual(json.loads((self.artifacts / "crash-counts.json").read_text())["crashIndicators"], 1)

    def test_unknown_variant_and_privacy_apk_override_fail_before_adb(self):
        for arguments in (("--build-type", "release"), ("--build-type", "privacy", "--unknown")):
            self.assertNotEqual(self.run_smoke(*arguments).returncode, 0)
        self.env["PETASOS_APK"] = str(self.root / "app-debug.apk")
        self.assertNotEqual(self.run_smoke("--build-type", "privacy").returncode, 0)
        self.assertFalse((self.root / "calls.txt").exists())


if __name__ == "__main__":
    unittest.main()
