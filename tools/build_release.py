#!/usr/bin/env python3
"""Build a PackSanity Maven repository and native bundles from this checkout."""
from __future__ import annotations

import argparse
import hashlib
from io import BytesIO
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import tarfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "build"
PYTHON = BUILD / "python"
LOCK = json.loads((ROOT / "python/runtime.lock.json").read_text())
PYTHON_VERSION = LOCK["cpython"]["version"]
PYTHON_SERIES = ".".join(PYTHON_VERSION.split(".")[:2])
SOURCE = PYTHON / "inputs" / f"Python-{PYTHON_VERSION}"
NATIVE = ROOT / "native"
LUA = NATIVE / "vendor/lua-5.4.9"
ANDROID_HOST = "aarch64-linux-android"
ANDROID_NDK = "27.3.13750724"  # Also pinned by CPython 3.13's Android environment script.
ANDROID_CMAKE = "3.22.1"


def run(*command, **options):
    return subprocess.run(command, cwd=ROOT, check=True, **options)


def output(*command):
    return subprocess.check_output(command, cwd=ROOT, text=True).strip()


def reset(path):
    if path.exists():
        shutil.rmtree(path)
    path.mkdir(parents=True)


def runtime_files(directory, payload):
    shutil.copytree(payload, directory / "modules", ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))


def stdlib(source, output_dir, *, mobile_ios):
    excluded = {"test", "idlelib", "turtledemo", "__phello__", "ensurepip", "venv", "pydoc_data"}
    if mobile_ios:
        excluded.add("lib-dynload")

    def ignore(directory, names):
        return {
            name for name in names
            if name == "__pycache__" or name.endswith(".pyc")
            or (Path(directory) == source and (name in excluded or name.startswith("config-")))
        }

    shutil.copytree(source, output_dir, ignore=ignore)


def notices(directory):
    target = directory / "notices"
    target.mkdir(parents=True, exist_ok=True)
    for name in ("LICENSE", "THIRD_PARTY_NOTICES.md"):
        shutil.copy2(ROOT / name, target / name)


def android(python, payload, sdk):
    prefix = SOURCE / "cross-build" / ANDROID_HOST / "prefix"
    cmake = sdk / f"cmake/{ANDROID_CMAKE}/bin/cmake"
    ninja = cmake.with_name("ninja")
    ndk = sdk / f"ndk/{ANDROID_NDK}"
    run("bash", "python/tools/build_android_cpython.sh", SOURCE, ANDROID_HOST, python,
        env=dict(os.environ, ANDROID_HOME=str(sdk)))
    build = BUILD / "release-inputs/android-cmake"
    run(cmake, "-S", "runtime-android/src/main/cpp", "-B", build, "-G", "Ninja",
        f"-DCMAKE_MAKE_PROGRAM={ninja}",
        f"-DCMAKE_TOOLCHAIN_FILE={ndk}/build/cmake/android.toolchain.cmake",
        "-DCMAKE_BUILD_TYPE=Release", "-DANDROID_ABI=arm64-v8a", "-DANDROID_PLATFORM=android-27",
        "-DANDROID_STL=none", f"-DPYTHON_PREFIX={prefix}")
    run(cmake, "--build", build)
    staged = BUILD / "release-inputs/android"
    reset(staged)
    assets = staged / "assets/packsanity"
    assets.mkdir(parents=True)
    runtime_tree = BUILD / "release-inputs/android-payload"
    reset(runtime_tree)
    runtime_files(runtime_tree, payload)
    notices(assets)
    stdlib(prefix / f"lib/python{PYTHON_SERIES}", runtime_tree / f"python/{ANDROID_HOST}/lib/python{PYTHON_SERIES}",
           mobile_ios=False)
    jni = staged / "jni/arm64-v8a"
    jni.mkdir(parents=True)
    for library in ("libpacksanity_lua_jni.so", "libpacksanity_python.so"):
        shutil.copy2(build / library, jni / library)
    for library in (f"libpython{PYTHON_SERIES}.so", "libcrypto_python.so", "libssl_python.so", "libsqlite3_python.so"):
        shutil.copy2(prefix / "lib" / library, jni / library)
    shutil.copytree(ROOT / "runtime-android/notices", assets / "notices", dirs_exist_ok=True)
    shutil.copy2(payload / "packsanity-runtime-id.txt", assets)
    # AAPT may omit underscore-prefixed directories during application asset merging.
    # Keep the Python filesystem layout inside a single asset that survives that merge.
    with zipfile.ZipFile(assets / "runtime.zip", "w", zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(runtime_tree.rglob("*")):
            if path.is_file():
                archive.write(path, path.relative_to(runtime_tree))
    readelf = next(ndk.glob("toolchains/llvm/prebuilt/*/bin/llvm-readelf"))
    for library in sorted([*jni.glob("*.so"), *runtime_tree.rglob("*.so")]):
        segments = output(readelf, "-lW", library)
        alignments = [int(line.split()[-1], 16) for line in segments.splitlines()
                      if line.strip().startswith("LOAD ")]
        if not alignments or min(alignments) < 16384:
            raise RuntimeError(f"Android library needs 16 KiB load alignment: {library}")


def ios(python, payload, destination):
    frameworks = PYTHON / "ios"
    run("bash", "python/tools/build_ios_cpython.sh", "--source", SOURCE,
        "--build-python", python, "--output", frameworks)
    shutil.copytree(NATIVE / "include", destination / "include")
    notices(destination)
    excluded = {"lua", "luac", "liolib", "loslib", "ldblib", "linit"}
    lua_sources = [source for source in sorted(LUA.glob("*.c")) if source.stem not in excluded]
    for sdk, triple in (("iphoneos", "arm64-apple-ios15.0"), ("iphonesimulator", "arm64-apple-ios15.0-simulator")):
        target = destination / sdk
        target.mkdir(parents=True)
        runtime_files(target, payload)
        shutil.copytree(frameworks / sdk / "Python.framework", target / "Python.framework", symlinks=True)
        stdlib(frameworks / sdk / f"lib/python{PYTHON_SERIES}", target / f"lib/python{PYTHON_SERIES}",
               mobile_ios=True)
        if any(path.suffix in {".so", ".dylib"} for p in (target / "lib", target / "modules") for path in p.rglob("*")):
            raise RuntimeError(f"Unexpected executable resource in {target}")
        sdkroot = output("xcrun", "--sdk", sdk, "--show-sdk-path")
        objects = BUILD / f"release-inputs/{sdk}-objects"
        reset(objects)
        libraries = {
            "libpacksanity_lua.a": [NATIVE / "src/lua_host.c", *lua_sources],
            "libpacksanity_python.a": [NATIVE / "src/python_host.c"],
        }
        for name, sources in libraries.items():
            outputs = []
            for source in sources:
                obj = objects / f"{source.stem}.o"
                run("xcrun", "--sdk", sdk, "clang", "-target", triple,
                    "-isysroot", sdkroot,
                    "-O2", "-I", NATIVE / "include", "-I", LUA, "-DLUA_USE_C89",
                    "-I", target / "Python.framework/Headers", "-c", source, "-o", obj)
                outputs.append(obj)
            run("xcrun", "libtool", "-static", "-o", target / name, *outputs)


def host(destination):
    build = BUILD / "release-inputs/host-meson"
    java_home = os.environ.get("JAVA_HOME") or (BUILD / "java-home.txt").read_text().strip()
    environment = dict(os.environ, JAVA_HOME=java_home)
    run("meson", "setup", "--reconfigure", build, ROOT, "--prefix=/",
        "--libdir=lib", "-Dpkgconfig.relocatable=true", "-Djni=true", "-Dpython_bridge=false", env=environment)
    run("meson", "compile", "-C", build)
    run("meson", "test", "-C", build, "--print-errorlogs")
    run("meson", "install", "-C", build, "--destdir", destination)


def archive(directory, target):
    with tarfile.open(target, "w:gz", compresslevel=6) as bundle:
        for entry in sorted(directory.iterdir()):
            bundle.add(entry, arcname=entry.name)


def release_manifest(release, version, files):
    git_files = subprocess.check_output(["git", "ls-files", "-co", "--exclude-standard", "-z"], cwd=ROOT)
    digest = hashlib.sha256()
    for name in sorted(set(git_files.split(b"\0")) - {b""}):
        path = ROOT / os.fsdecode(name)
        if path.is_file():
            digest.update(name + b"\0" + hashlib.sha256(path.read_bytes()).digest())
    data = {
        "version": version,
        "sourceBaseCommit": output("git", "rev-parse", "HEAD"),
        "sourceTreeSha256": digest.hexdigest(),
        "runtimeId": LOCK["runtimeId"],
        "cpython": PYTHON_VERSION,
        "archipelago": LOCK["archipelago"],
        "universalTracker": LOCK["universalTracker"],
        "adapter": LOCK["adapter"],
        "lua": "5.4.9",
        "artifacts": [
            {"file": path.name, "targets": targets, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
            for path, targets in files
        ],
    }
    (release / "manifest.json").write_text(json.dumps(data, indent=2) + "\n")


def verify_android_aar(release, version):
    aar = release / f"maven/dev/nattle/packsanity/runtime-android/{version}/runtime-android-{version}.aar"
    with zipfile.ZipFile(aar) as bundle:
        prefix = "assets/packsanity/"
        if bundle.read(prefix + "packsanity-runtime-id.txt").decode() != LOCK["runtimeId"]:
            raise RuntimeError("AAR contains a mismatched runtime ID")
        with zipfile.ZipFile(BytesIO(bundle.read(prefix + "runtime.zip"))) as runtime:
            for name in (f"modules/{LOCK['adapter']['entryModule']}.py", "modules/CommonClient.py",
                         "modules/packsanity_ut_upstream.py", "modules/notices/UniversalTracker-MIT.txt",
                         "modules/notices/CPython-PSF-2.txt",
                         f"python/{ANDROID_HOST}/lib/python{PYTHON_SERIES}/zipfile/_path/__init__.py"):
                runtime.getinfo(name)
        for name in ("libpacksanity_lua_jni.so", "libpacksanity_python.so", f"libpython{PYTHON_SERIES}.so"):
            bundle.getinfo("jni/arm64-v8a/" + name)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--host-only", action="store_true", help="Build the Lua host bundle for this OS and architecture")
    parser.add_argument("--android-sdk", type=Path, default=os.getenv("ANDROID_HOME", os.getenv("ANDROID_SDK_ROOT")))
    parser.add_argument("--verify", action="store_true", help="Run source tests and verify the full release in a consumer project")
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9][A-Za-z0-9.-]*)?", args.version):
        parser.error("Use a version such as 0.1.0")
    if not args.host_only and (sys.platform != "darwin" or not args.android_sdk):
        parser.error("A full release requires macOS with Xcode and an Android SDK (use --android-sdk)")

    verify = args.verify and not args.host_only
    if verify:
        os.environ["ANDROID_HOME"] = str(args.android_sdk.resolve())
        bootstrap = os.environ.setdefault("PACKSANITY_PYTHON", sys.executable)
        run("./gradlew", "spotlessCheck", ":loader:jvmTest")
        run("make", "test")
        python = output(bootstrap, "python/tools/prepare_runtime.py")
        run(python, "-m", "unittest", "discover", "-s", "python/src/test/python")
        run(python, "tests/test_integration.py")

    # Keep multiple versions and repeated builds separate from old Gradle metadata.
    release = BUILD / "release"
    distribution = release / ".dist-building"
    reset(distribution)
    files = []
    machine = {"arm64": "aarch64", "AMD64": "x86_64"}.get(platform.machine(), platform.machine())
    host_target = f"{platform.system().lower()}-{machine}"
    host_tree = BUILD / "release-inputs/host-install"
    reset(host_tree)
    if not args.host_only:
        bootstrap = os.environ.get("PACKSANITY_PYTHON", sys.executable)
        python = output(bootstrap, "python/tools/prepare_runtime.py")
        android(python, PYTHON / "payload", args.android_sdk.resolve())
        ios_tree = BUILD / "release-inputs/ios"
        reset(ios_tree)
        ios(python, PYTHON / "payload", ios_tree)
        ios_archive = distribution / f"packsanity-{args.version}-ios.tar.gz"
        archive(ios_tree, ios_archive)
        files.append((ios_archive, ["iosArm64", "iosSimulatorArm64"]))
        reset(release / "maven")
        run("./gradlew", "-PenableAndroid=true", "-PenableIos=true", f"-PreleaseVersion={args.version}",
            ":cli:writeJavaHome", ":loader:publishAllPublicationsToReleaseStagingRepository",
            ":runtime-android:publishAllPublicationsToReleaseStagingRepository",
            env=dict(os.environ, ANDROID_HOME=str(args.android_sdk.resolve())))
        verify_android_aar(release, args.version)
        maven_archive = distribution / f"packsanity-{args.version}-maven.tar.gz"
        archive(release / "maven", maven_archive)
        files.append((maven_archive, ["kotlinMultiplatform", "jvm", "android-arm64-v8a", "iosArm64", "iosSimulatorArm64"]))
    elif not os.environ.get("JAVA_HOME"):
        run("./gradlew", ":cli:writeJavaHome")
    host(host_tree)
    host_archive = distribution / f"packsanity-{args.version}-lua-{host_target}.tar.gz"
    archive(host_tree, host_archive)
    files.append((host_archive, [host_target]))
    release_manifest(distribution, args.version, files)
    final = release / (f"dist-host-{host_target}" if args.host_only else "dist")
    if final.exists():
        shutil.rmtree(final)
    distribution.rename(final)
    if verify:
        run(sys.executable, "tools/verify_release.py", final)
    print(final)


if __name__ == "__main__":
    main()
