# PackSanity release and Waystone migration

PackSanity will own the portable PopTracker and APWorld loader, its native bridges, and its
interpreter builds. Waystone will link a single pinned PackSanity release across all targets.
Waystone's app will continue to ship the runtime binaries and payload with its installers.
Its source tree can drop the corresponding implementation once Android, iOS, and JVM consume
the same release successfully.

The PackSanity refresh and release tooling are implemented. The
[release handoff](releasing.md) gives the current artifact names, coordinates, and link
configuration. The remaining migration stages apply to Waystone.

## Baseline and refresh

PackSanity's extraction manifest maps 118 paths from Waystone commit
`e075b1e25ceca6d651f27a10dde3b540e13cd36e`. All 118 source/destination pairs still exist.
51 mapped Waystone files changed through `27e6a09e2060444e0209663d55afc9aa4f785d58`.
Use the extraction revision as the merge base for each pair. Compare the extraction with that
base before applying the new delta so namespace, archive injection, logging, and CLI behavior
survive the refresh. Add mappings for newly introduced portable files, record the new reference
revision alongside the original extraction revision, and account for each excluded app-owned
change. The native Lua core is outside the manifest and needs its own comparison.
[Extraction manifest](../extraction-manifest.json) · [Provenance](provenance.md)

| Surface | Port and verification |
| --- | --- |
| Shared Kotlin PopTracker | Bring over the current loader/session/models, new `ItemLayoutGroupCollector` and `PackInformationalItems`, and the changes to JSONC, map/layout parsing, region and unnamed-section identity, aliases, variant overlays, and compatibility advisories. Port the focused parser and session regressions with their changes. |
| Lua runtime and host | Update vendored Lua 5.4.8 to 5.4.9 with its notice. Compare `luaRuntimeNative/src/main/cpp/waystone_lua.{c,h}` against PackSanity's native host and carry over per-refresh budgeting, protected callback behavior, and JNI lifecycle/error semantics. Port the changed fragments in their original order and regenerate `lua/host.lua` from them. Preserve PackSanity's file caps and public C ownership contract. Exercise callback failures, frame delivery, canonical hosted check state, authoritative zero provider counts, and region/location refresh with the Celeste and TUNIC scenarios from Waystone's regression coverage. |
| Shared Kotlin APWorld | Port inspector changes and the complete typed snapshot contract, including the distinction between omitted and empty missing-location lists and complete `slot_data` replay. Keep AP packet accumulation in Waystone. |
| Python and C APWorld | Port adapter 8 semantics and their tests, including pre-generation slot data and hook dispatch, exclusion group expansion, the staged `CommonClient.logger` shim, server check membership, and graph rebuild from changed slot data. Preserve failure recovery and replay inventory, checks, and storage. Port the production subinterpreter C bridge, including overlapping handles and explicit thread-state ownership. Advance PackSanity's runtime ID and stage it from the lock with the payload. Pin AP 0.6.8 and CPython 3.13.15 for parity. |
| Compatibility keys | Keep `min_packsanity_*` / `target_packsanity_*` in the generic loader and retain standard PopTracker metadata. Define a caller-supplied mapping or a Waystone adapter for `*_waystone_*` keys so existing imports retain their advisories and version gates. Test both key families and the precedence when both occur. Keep app-specific ZIP override discovery in Waystone. |

Keep application state, translated diagnostics, Koin and network integration, catalog and
persistence code, and tracker UI projections in Waystone. The app supplies archives, the
resolved paths to native resources, connection snapshots and the logger. Keep serialization
decisions visible in the public API. Document any ABI change and give C callers a versioned
header and matching binaries.

## Build and publish

1. Keep the standalone Gradle/Meson paths as development entry points. Build native Lua from
   the vendored sources. Download and verify the locked CPython source and build the host
   interpreter locally. Reuse that build Python for iOS cross-builds. Keep platform CPython
   scripts and payload staging in PackSanity and move them after comparing Android's packaging
   and iOS's static extension profile with Waystone's production configurations.
2. Stage a release candidate in a disposable Maven repository, using the same files and versions
   that CI will release. Publish the root `dev.nattle.packsanity:loader` KMP module and its JVM,
   Android, `iosArm64`, and `iosSimulatorArm64` variants together. Align Kotlin toolchains for
   the first integration (Waystone currently uses 2.4.21 and PackSanity uses 2.4.20).
   Use a separate `dev.nattle.packsanity:runtime-android` AAR for JNI, arm64-v8a Lua and CPython
   libraries, the trimmed standard library, adapter modules, and the packaged runtime ID.
   Move PackSanity-owned Java runtime glue and the Android asset installer into the AAR.
   Validate the Gradle metadata and exact variant resolution in a clean consumer.
   [Kotlin multiplatform publication](https://kotlinlang.org/docs/multiplatform/multiplatform-publish-lib-setup.html)
3. Build iOS device and simulator slices on macOS. Ship a GitHub Release asset with a Lua/C
   bridge static library or XCFramework, exported headers, CPython framework slices, staged
   `lib` and `modules` trees, and the runtime ID. Put each slice in an explicitly labeled
   device/simulator path. Preserve CPython's static extension profile and have Waystone sign
   the selected framework when assembling the app. Include matching host native packages for
   JVM tests, beginning with the macOS and Linux architectures used by CI. Smoke each package
   in an independent checkout using the public JVM/C API.
   [Apple XCFramework packaging](https://developer.apple.com/documentation/xcode/creating-a-multi-platform-binary-framework-bundle)
4. Publish from one PackSanity tag after all variants and consumer smoke checks pass.
   Choose Maven Central for Gradle artifacts once its namespace is verified and the signing
   credentials are available. Use GitHub Release assets for native bundles, or for the whole
   first release as a Maven-layout archive if registry setup is still pending. Attach a manifest
   containing the release/version, build commit, artifact checksums, ABI/target matrix,
   interpreter pins, adapter version, and payload runtime ID. Include PackSanity, Lua, CPython,
   Archipelago and packaged dependency notices in the corresponding distributions.
   [Central Publisher Portal](https://central.sonatype.org/publish/publish-portal-guide/) · [GitHub Releases](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases)

## Switch Waystone and remove its source copies

1. Set one release version in Waystone's dependency catalog. Resolve the loader in
   `shared/commonMain` and the Android runtime AAR in `androidMain`. Route existing
   `AndroidLuaPackRuntime` and `AndroidUniversalTrackerJsonRuntime` adapters to the AAR.
   Preserve the existing worker serialization, diagnostics, and app cache upgrade behavior.
2. Teach Gradle to download and verify the pinned native archives into its build cache.
   Wire the iOS Kotlin/Native cinterop and linker to PackSanity's headers and binary slices.
   Replace Xcode's Lua compilation phase with the resolved static library. Point the UT
   copy/sign phase and Swift/Kotlin resource locators at the staged framework and payload
   for the selected architecture/platform. Use the same verified binaries for the JVM native
   test adapters. Make every consumer fail at configuration or build time on a missing or
   mismatched release. Wire `project.yaml`, the `kotlin-build` descriptors and
   `scripts/prepare-kotlin-layout.py` into the same resolved layout or document the unsupported
   difference in the Toolchain trial guide. Keep Gradle as the release entry point.
3. Run the candidate through a source-free Waystone build before deleting the former
   implementation. Disable the in-tree sources during the build, check dependency resolution
   and symbol provenance, then remove the portable Kotlin parser, models, rules, archive
   contracts and UT bridge types from Waystone where they are supplied by the dependency.
   Remove the Lua fragments and vendored Lua and the now-redundant `luaRuntimeNative`,
   `luaRuntimeAndroid`, `universalTrackerRuntime`, and `universalTrackerRuntimeAndroid`
   implementation and build wiring. Migrate their test and release checks to the new paths.
   Verify a fresh Waystone checkout builds with PackSanity absent locally. Keep any app-side
   extensions as thin adapters and retain the required third-party notices in the app.
4. Land the app dependency flip and source removal together after the release acceptance checks.
   Pin the prior compatible artifact version for future rollback. The first migration can fall
   back to the last pre-migration Waystone revision if the initial release fails.

## Acceptance checks

- In PackSanity, run `./gradlew spotlessApply :loader:jvmTest`, `make test`, the adapter suite,
  and both integration runners. Execute the refreshed Lua regressions against the relevant
  versioned real packs, including actual callbacks and rule results. APWorld verification must
  hit the production C API under the pinned local CPython with exact normal-server location IDs,
  checked and inventory states, non-default slot data/YAML, slot-update rebuild, reconnect,
  archive/version switches, failure recovery, overlapping handles and serialized thread changes.
  Use Waystone's pinned Librarian/TTYD archive scenarios as lifecycle coverage and record the
  archive hashes and interpreter/payload versions.
- In Waystone, run `./gradlew spotlessApply`,
  `./gradlew test -PexcludeLocalPacksCorpus=true` and the canonical
  `:universalTrackerRuntime:testUniversalTrackerPython` suite or its successor before removing
  that project. Run Android release APK/device and iOS device/simulator checks for both runtimes.
  Test the production asset installer with old cache, warm reuse, and interrupted extraction.
  Inspect the Android and iOS release artifacts for the pinned ABI, native libraries, staged
  adapter, notices, runtime ID, and iOS resource restrictions. Record host, packaged-artifact,
  and device evidence separately.
- On a clean Waystone checkout, run the release builds with dependency caches initially empty
  and PackSanity absent from the workspace. Check the tracked file list, Gradle dependency
  graph, and Xcode build input paths to confirm all shipped portable runtime implementation
  comes from the release artifact. Update the Waystone compatibility audit and the PackSanity
  matrix with the exercised coverage.

The refresh, published candidate, and downstream cutover are separate milestones. A local
Maven publication alone exercises only dependency resolution for the variants it contains.
