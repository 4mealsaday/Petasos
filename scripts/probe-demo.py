#!/usr/bin/env python3
"""Run the demo-only engine and UI instrumentation. Never targets or reconfigures the product package."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
APP = "com.petasos.app.demo"
RUNNER = APP + ".test/com.petasos.app.demo.DemoScenarioInstrumentation"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--artifacts-dir", type=Path, default=ROOT / "artifacts/milestone-33/demo-session")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-[0-9]+", args.serial):
        parser.error("An explicitly selected emulator serial is required")
    sdk = Path(os.environ.get("ANDROID_SDK_ROOT", str(Path.home() / "Library/Android/sdk")))
    adb = [str(sdk / "platform-tools/adb"), "-s", args.serial]
    app_apk = ROOT / "app/build/outputs/apk/demo/app-demo.apk"
    test_apk = ROOT / "app/build/outputs/apk/androidTest/demo/app-demo-androidTest.apk"
    args.artifacts_dir.mkdir(parents=True, exist_ok=True)
    result = {"outcome": "FAIL", "package": APP,
              "scope": "Separate demo UI, actual CarUx/core/Room, public assets, synthetic inputs and silent outputs; not live driving or human acceptance"}
    stage = "preflight"
    def run(command, timeout=90):
        completed = subprocess.run(command, capture_output=True, text=True, timeout=timeout)
        if completed.returncode != 0:
            raise RuntimeError("DEMO_COMMAND_FAILED")
        return completed.stdout
    try:
        result["appSha256"] = hashlib.sha256(app_apk.read_bytes()).hexdigest()
        result["testApkSha256"] = hashlib.sha256(test_apk.read_bytes()).hexdigest()
        # Inspect APK identity before installation; never send a generic product probe to this variant.
        aapt = str(sdk / "build-tools/35.0.0/aapt")
        for path, package in ((app_apk, APP), (test_apk, APP + ".test")):
            badging = run([aapt, "dump", "badging", str(path)])
            if not re.search(r"^package: name='" + re.escape(package) + r"' ", badging, re.M):
                raise RuntimeError("DEMO_PACKAGE_MISMATCH")
        fingerprint = run(adb + ["shell", "getprop", "ro.build.fingerprint"]).strip().lower()
        if "pleos" not in fingerprint or run(adb + ["shell", "getprop", "ro.build.version.sdk"]).strip() != "34":
            raise RuntimeError("DEMO_TARGET_MISMATCH")
        user = run(adb + ["shell", "am", "get-current-user"]).strip()
        if not user.isdecimal():
            raise RuntimeError("DEMO_USER_UNAVAILABLE")
        result["androidUser"] = int(user)
        stage = "install"
        for path in (app_apk, test_apk):
            if "Success" not in run(adb + ["install", "--user", user, "-r", str(path)]):
                raise RuntimeError("DEMO_INSTALL_FAILED")
        stage = "instrumentation"
        output = run(adb + ["shell", "am", "instrument", "--user", user, "-w", "-r", RUNNER])
        # Store only known result fields, never raw framework output, exceptions or logcat.
        fields = dict(re.findall(r"^INSTRUMENTATION_RESULT: ([a-z_]+)=(.*)$", output, re.M))
        for key in ("stage", "reason", "events", "scope"):
            value = fields.get(key, "")
            if re.fullmatch(r"[A-Za-z0-9_,]*", value):
                result[key] = value
        for key in ("elapsed_ms", "failure_line"):
            if fields.get(key, "").isdecimal():
                result[key] = int(fields[key])
        if fields.get("outcome") != "PASS" or not re.search(r"^INSTRUMENTATION_CODE: -1\s*$", output, re.M):
            raise RuntimeError("DEMO_PROBE_FAILED")
        result["outcome"] = "PASS"
    except (OSError, RuntimeError, subprocess.SubprocessError):
        result["failureStage"] = stage
    destination = args.artifacts_dir / "demo-session.json"
    destination.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({k: result[k] for k in ("outcome", "stage", "failureStage", "elapsed_ms", "failure_line") if k in result}))
    return 0 if result["outcome"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
