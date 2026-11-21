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

The release workflow builds the bundles in CI. A full build from source requires macOS
with Xcode, JDK 21, Meson, Ninja, Python 3 with pip, and
Android SDK 37 with build tools 37.0.0, NDK 27.3.13750724, and CMake 3.22.1.
The verifier requires Python 3.12 or newer. [Release workflow](../.github/workflows/build-release.yml)

```sh
export ANDROID_HOME=/path/to/android/sdk
python3 tools/build_release.py --version 0.1.1
python3 tools/verify_release.py build/release/dist
```

The verifier checks hashes and uses the artifacts in a temporary consumer project.
Pass `--android-device SERIAL` or `--ios-simulator UUID` to run the matching native
runtime smoke check. Use `build_release.py --host-only` for just the host Lua bundle.
Publish the assets and their manifests together under the matching version tag.
