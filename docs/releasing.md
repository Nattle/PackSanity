# Build and use release artifacts

## Artifact contents

| Asset | Contents |
| --- | --- |
| `packsanity-<version>-maven.tar.gz` | Maven repository for the Kotlin loader (JVM, Android, iOS device and simulator) and the Android runtime AAR |
| `packsanity-<version>-ios.tar.gz` | C headers, arm64 device and simulator static bridge libraries, CPython framework, standard library, and modules |
| `packsanity-<version>-lua-<platform>.tar.gz` | Host Lua and JNI libraries, C headers, and pkg-config metadata |
| `manifest.json` | Artifact SHA-256 checksums, target list, runtime pins, and source identifiers |

## Use the artifacts

Verify the archive checksums against the release manifest and use the same version for
all platforms. Extract the Maven bundle and configure it as a repository for
`dev.nattle.packsanity`. Add `dev.nattle.packsanity:loader:<version>` to the consumer's
`commonMain` dependencies.

- **JVM.** Set `PACKSANITY_NATIVE_DIR` to the matching host bundle's `lib` folder.
- **Android.** Add `dev.nattle.packsanity:runtime-android:<version>` to `androidMain`.
  Use `dev.nattle.packsanity.lua.LuaNativeRuntime` and
  `dev.nattle.packsanity.ut.PythonNativeRuntime`. Resolve Python paths with
  `PythonRuntimeAssets(context).install()`. The AAR includes the runtime for
  `arm64-v8a` with 16 KiB ELF alignment.
- **iOS.** Use `include/packsanity/lua_host.h` and `include/packsanity/python_host.h` for
  C interop. Link both static libraries and `Python.framework` from the matching
  `iphoneos` or `iphonesimulator` slice. Embed and sign the framework. Copy the slice's
  `lib` and `modules` into an app resource directory. Give the C bridge that directory as
  Python home and its `modules` subdirectory as the module path.

[Consumer examples for Gradle, JVM, iOS, and Android](../tests/consumer/)

## Build and verify

Build the mobile and macOS bundles locally on macOS with Xcode, JDK 21, Meson, Ninja,
Python 3.13 with pip, and Android SDK 37 with build tools 37.0.0, NDK 27.3.13750724,
and CMake 3.22.1. Set `JAVA_HOME` to the JDK installation.

```sh
export ANDROID_HOME=/path/to/android/sdk
./scripts/release.sh --version 0.1.3
```

The script runs the source tests, writes the bundles to `build/release/dist`, and
verifies their checksums and integration with a temporary consumer project. For
device or simulator smoke tests, run `python3.13 tools/verify_release.py build/release/dist`
with `--android-device SERIAL` or `--ios-simulator UUID`.

On an x86_64 Linux machine or VM, check out the same commit and run
`./scripts/release.sh --version 0.1.3 --host-only`. With a C compiler, JDK 21,
Meson, and Ninja installed, it builds and tests the Lua and JNI libraries and writes
the bundle and manifest to `build/release/dist-host-linux-x86_64`.

The release workflow runs on manual dispatch. Select `mobile`, `linux-host`, or
`all` and download the resulting artifacts. To build just the Linux bundle for a
pushed tag, dispatch the [release workflow](../.github/workflows/build-release.yml)
at that tag.

```sh
gh workflow run build-release.yml --ref v0.1.3 -f version=0.1.3 -f target=linux-host
```

## Stage the release

Build both sets of bundles from a clean checkout of the same commit and push its
version tag. Copy the Linux output directory alongside the mobile and macOS
output, then use the authenticated GitHub CLI to create a draft release with both
manifests and all archives.

```sh
version=0.1.3
dist=build/release/dist
linux_dist=build/release/dist-host-linux-x86_64
cp "$linux_dist/manifest.json" "$linux_dist/manifest-linux.json"
gh release create "v$version" --draft --verify-tag --title "PackSanity $version" \
  "$dist"/packsanity-"$version"-*.tar.gz "$dist/manifest.json" \
  "$linux_dist/packsanity-$version-lua-linux-x86_64.tar.gz" "$linux_dist/manifest-linux.json"
```
