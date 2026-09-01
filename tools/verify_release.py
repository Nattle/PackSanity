#!/usr/bin/env python3
"""Resolve and exercise release artifacts from a disposable consumer checkout."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tarfile
import tempfile
import time
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def run(*command, **options):
    return subprocess.run(command, check=True, text=True, **options)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dist", type=Path)
    parser.add_argument("--android-device", help="adb serial for APK runtime smoke")
    parser.add_argument("--ios-simulator", help="booted iOS simulator UUID for native smoke")
    args = parser.parse_args()
    dist = args.dist.resolve()
    manifest = json.loads((dist / "manifest.json").read_text())
    architecture = {"arm64": "aarch64", "AMD64": "x86_64"}.get(platform.machine(), platform.machine())
    host = f"{platform.system().lower()}-{architecture}"
    with tempfile.TemporaryDirectory(prefix="packsanity-consumer-") as directory:
        consumer = Path(directory)
        for artifact in manifest["artifacts"]:
            source = dist / artifact["file"]
            if hashlib.sha256(source.read_bytes()).hexdigest() != artifact["sha256"]:
                raise RuntimeError(f"Checksum mismatch for {source}")
            target = next((name for name in ("maven", "ios") if source.name.endswith(f"-{name}.tar.gz")), "host")
            if target == "host" and host not in artifact["targets"]:
                continue
            with tarfile.open(source) as bundle:
                bundle.extractall(consumer / target, filter="data")
        project = consumer / "project"
        shutil.copytree(ROOT / "tests/consumer", project)
        assets = project / "android-smoke/src/main/assets"
        assets.mkdir()
        world = assets / "tiny_world.apworld"
        with zipfile.ZipFile(world, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.write(ROOT / "tests/fixtures/tiny_world/__init__.py", "tiny_world/__init__.py")
        run(ROOT / "gradlew", "-p", project,
            f"-PpacksanityRepository={consumer / 'maven'}",
            f"-PpacksanityVersion={manifest['version']}",
            f"-PpacksanityNativeDir={consumer / 'host/lib'}",
            f"-PpacksanityIosHeaders={consumer / 'ios/include'}",
            ":loader-smoke:runSmoke", ":loader-smoke:compileKotlinIosArm64",
            ":loader-smoke:compileKotlinIosSimulatorArm64", ":android-smoke:assembleDebug")
        ios = consumer / "ios"
        for sdk, triple in (("iphoneos", "arm64-apple-ios15.0"), ("iphonesimulator", "arm64-apple-ios15.0-simulator")):
            target = ios / sdk
            smoke = target / "native-smoke"
            run("xcrun", "--sdk", sdk, "clang", "-target", triple, "-I", ios / "include",
                project / "native_smoke.c", target / "libpacksanity_lua.a", target / "libpacksanity_python.a",
                "-F", target, "-framework", "Python", "-Wl,-rpath,@executable_path", "-o", smoke)
            if sdk == "iphonesimulator" and args.ios_simulator:
                run("xcrun", "simctl", "spawn", args.ios_simulator, smoke, target, target / "modules", world)
        if args.android_device:
            sdk = Path(os.environ["ANDROID_HOME"])
            adb = (sdk / "platform-tools/adb", "-s", args.android_device)
            apk = project / "android-smoke/build/outputs/apk/debug/android-smoke-debug.apk"
            run(*adb, "install", "-r", apk)
            start = run(*adb, "shell", "date", r"+%m-%d\ %H:%M:%S.000", capture_output=True).stdout.strip()
            run(*adb, "shell", "am", "start", "-S", "-n", "dev.nattle.packsanity.consumer/.SmokeActivity")
            for _ in range(45):
                logs = run(*adb, "logcat", "-d", "-T", start, "-s", "PackSanitySmoke", capture_output=True).stdout
                if "PACKSANITY_ANDROID_FAILED" in logs:
                    raise RuntimeError(logs)
                if "PACKSANITY_ANDROID_OK" in logs:
                    print(logs)
                    break
                time.sleep(1)
            else:
                raise RuntimeError("Android consumer did not report a result")


if __name__ == "__main__":
    main()
