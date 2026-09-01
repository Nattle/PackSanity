# Building and consuming PackSanity releases

The first release candidate uses version `0.3.0-rc.1` and UT runtime ID
`packsanity-ut-runtime-3`. Build the artifacts together from one PackSanity revision.

```sh
export ANDROID_HOME=/path/to/android/sdk
python3 tools/build_release.py --version 0.3.0-rc.1
python3 tools/verify_release.py build/release/dist
```

The full build uses macOS with Xcode, a full JDK 21, Meson, Ninja, and a bootstrap Python 3
with pip. Install Android SDK 37, NDK 27.3.13750724, and CMake 3.22.1.
The verifier uses Python 3.12 or newer. Pass `--android-device` and `--ios-simulator` to
exercise both native bridges with an installed APK and a booted iOS simulator. The verifier
copies the consumer to a temporary checkout and resolves PackSanity from the release tarballs.

## Release contents and publication

| Asset | Contents |
| --- | --- |
| `packsanity-<version>-maven.tar.gz` | Gradle Maven repository layout with KMP metadata, JVM, Android, iOS device, and iOS simulator loader variants and the Android runtime AAR |
| `packsanity-<version>-ios.tar.gz` | C headers and arm64 device/simulator static Lua and Python bridge libraries, CPython framework, standard library and modules |
| `packsanity-<version>-lua-<platform>.tar.gz` | Host Lua libraries, JNI library, headers, and pkg-config metadata |
| `manifest.json` | SHA-256 of each asset, target list, runtime pins, base commit and source tree digest |

Use the `Build PackSanity release candidate` workflow to stage macOS/mobile and Linux x86_64
outputs. The Linux job also runs the host Lua suite via Meson. The same host bundle can be
built locally with `python3 tools/build_release.py --version <version> --host-only`.

Attach the Maven and native tarballs and the manifest to the matching GitHub Release tag.
Use the separate Linux host manifest with its tarball when publishing outputs from both jobs.
Keep the version and checksums immutable after publication. Gradle consumers can use the
extracted Maven-layout bundle immediately. Maven Central can host the same Gradle publications
after namespace and signing setup. [Kotlin publication guide](https://kotlinlang.org/docs/multiplatform/multiplatform-publish-lib-setup.html)

## Waystone dependency wiring

1. Extract the Maven-layout bundle after verifying its SHA-256 against the pinned release
   manifest. Configure the extracted directory as a Maven repository scoped to
   `dev.nattle.packsanity`. Add `dev.nattle.packsanity:loader:<version>` to `commonMain`.
2. Add `dev.nattle.packsanity:runtime-android:<version>` to `androidMain`. Use
   `dev.nattle.packsanity.lua.LuaNativeRuntime` and
   `dev.nattle.packsanity.ut.PythonNativeRuntime`. Resolve Python paths from
   `PythonRuntimeAssets(context).install()`. The AAR carries its payload as a single archive
   to preserve underscore-prefixed directories through APK packaging. It stages native
   libraries for arm64-v8a with 16 KiB ELF alignment. [Android page-size guidance](https://developer.android.com/guide/practices/page-sizes)
3. Extract the iOS asset and use `include/packsanity/lua_host.h` and
   `include/packsanity/python_host.h` for cinterop. Link the matching `iphoneos` or
   `iphonesimulator` slice's two static libraries and link `Python.framework` with its
   slice directory as the framework search path. Embed and sign the framework in the app.
   Install that slice's `lib` and `modules` folders in the app's UT resource directory.
   Give the C bridge that directory as Python home and its `modules` subdirectory as the
   module path.
4. JVM tests can set `PACKSANITY_NATIVE_DIR` to the matching host bundle's `lib` folder.
   This is where the published JVM loader finds the JNI library. Waystone can supply the
   existing PNG transparency check to `PackSanityPackParser(hasOnlyTransparentPixels = ...)`
   to preserve informational item filtering in the mobile UI.
5. Run Waystone's Gradle release and runtime smoke gates using only the resolved artifacts
   as release inputs. Remove the runtime source copies and obsolete build tasks in that change.

The disposable consumer in `tests/consumer` contains Gradle examples for dependency
resolution, iOS C interop, and Android/JVM API calls.
