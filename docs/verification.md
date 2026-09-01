# Standalone extraction verification

Verified on macOS arm64 on 2026-10-06. These checks prove this extraction and its developer
commands; they do not certify every pack, APWorld, seed, mobile runtime binding, or UI rendering.
No external author archives are committed. The extraction revision and source paths are in
`extraction-manifest.json`.

| Boundary | Evidence |
| --- | --- |
| Kotlin parser/models/archive gates/typed Python JSON bridge | 97 `:loader:jvmTest` tests passed. |
| Python reconstruction, dependency gates, maps/entrances/cache and isolated staging | 38 unittest cases passed under `python/src/test/python`. |
| Native Lua sandbox and C loading | Four `make test`/Meson cases passed, including instruction/memory failure and recovery. |
| Developer integrations | 20 `tests/test_integration.py` cases passed with native Lua and actual staged APWorld imports; includes expected failures, identity-dependent hints, directory/ZIP variants, YAML, companion images, casing and process isolation. |
| Standalone scripts | Copied final source outside the sibling workspace to `/tmp/PackSanity standalone audit`; both scripts passed from another working directory. Python sources changed before the final run, causing a fresh independent AP/CPython bootstrap under that copy. |
| Kotlin library consumption | Published to Maven local; a separate Gradle project consumed `dev.nattle.packsanity:loader:0.2.0-SNAPSHOT`, loaded a pack through native Lua, and ran an item callback. |
| Native library consumption | Installed Meson build with pkg-config metadata; an independent C++ program linked/called both public C ABIs. |
| Mobile shared source | `-PenableIos=true :loader:compileKotlinIosArm64 :loader:compileKotlinIosSimulatorArm64` passed. Native runtime/platform app binding validation remains a downstream migration gate. |
| Embedded CPython | `packsanity-python` reconstructed the real pinned Death's Door world and replayed item/check JSON through the exported C API. |

## Meson conversion verification

Revalidated after replacing the native build with Meson 1.12.1 / Ninja 1.13.2 on macOS arm64:
97 Kotlin tests, 38 Python tests, 20 integration tests and all four native regressions passed.
Both default static and shared Lua builds passed the native regressions. JNI and the optional
Python 3.13 embedding bridge compiled; a local-prefix installation supplied headers, libraries,
executables, notices and pkg-config metadata to an independent C++/Meson consumer, which called
both C APIs. The installed Python executable reconstructed Death's Door 0.2.1 and verified
267 locations, 173 regions, Bomb count one and location 1,001 checked after replay.
Meson 1.3.2 also configured both bridges and passed the native tests. Build directories with
spaces and the tests-disabled option were checked. Platform builds beyond this macOS arm64
environment remain downstream validation work.

## Locally compiled CPython verification

On macOS arm64, a fresh PackSanity source copy in a path containing spaces fetched
the locked AP 0.6.8 and CPython 3.13.15 sources using bootstrap Python 3.15.2.
PackSanity compiled vendored Lua 5.4.8 and installed CPython 3.13.15 under the copy's
`build/python/host`. The 97 Kotlin tests, four native Lua tests, 38 adapter tests,
and 20 integration tests passed. `make python-native` compiled against the local
CPython with Meson 1.12.1 and 1.3.2.

From outside that copy, `test-world.sh` reconstructed the pinned Death's Door
0.2.1 archive described below. The CLI and C bridge returned identical full state
after receiving Bomb (item 210) and checking Grove of Spirits - Door (location
1,001). The CLI reused the existing CPython build. The pinned Emerald 2.1.1 pack
also passed the three-variant hint callback scenario below. These checks cover
standalone host loading. See the [migration plan](waystone-migration.md) for the
current Waystone parity refresh, mobile build, and published artifact requirements.

## PackSanity 0.3 release candidate

The `0.3.0-rc.1` build targets Waystone revision
`27e6a09e2060444e0209663d55afc9aa4f785d58`. It uses Lua 5.4.9, CPython 3.13.15,
AP 0.6.8, adapter 8, and runtime ID `packsanity-ut-runtime-3`.

- Host verification passed 106 Kotlin tests, four native Lua tests, 61 Python tests
  including the C lifecycle and Android installer/worker suites, and 20 full integration tests.
- Emerald 2.1.1 reproduced one provider count from the Bulbasaur hint in all three
  variants. TUNIC `TunicTracker.zip` (SHA-256
  `1bfb8aeb5e2adfdd4c0f95cb327ac740157130ccb2f117cfc8c5677d3fc203d9`) switched
  `scavboss` from one to zero after a storage reply, Scavenger Queen check
  509342635, and a storage reset.
- The pinned native verifier passed nine Librarian/TTYD reconstructions across six
  archives, comparing exact server check and inventory states, slot-data graph
  rebuilds, overlap, reconnect, and serialized worker changes. The manifest under
  `python/src/test/fixtures` identifies the archives and options. The generated
  evidence is in `build/verification/reconstruction.json`.
- A separate consumer resolved the Maven tarball and executed Lua through the JVM host
  bundle. It compiled Kotlin and C interop for both iOS targets, linked the device slice,
  and ran Lua and two CPython sessions from the simulator slice on iOS 27.1. The Android
  APK resolved the AAR, extracted and reused its payload, and ran both native bridges
  with APWorld inventory and check replay on a 16 KiB arm64 emulator.

The hosted Linux build and physical device coverage remain separate release evidence.
See [building and consuming releases](releasing.md) for the verified artifact layout.

## Selected real archives

- Emerald `Archipelago-Emerald-AP-Tracker-v.2.1.1.zip`, SHA-256
  `8303f1f697af28bcbf3333376752556d1c94f71d73bd1401336907a431c4879f`:
  all three variants loaded with 1,923 items, 378 maps and 6,950 sections. A connected/clear/
  retrieved-hint scenario for location 3,870,001 activated `pokedex_bulbasaur` in each variant.
  The report exposes 15 unresolved section references, including Artisan Cave, Matt and Regi
  hint aliases. Requiring an empty unresolved-reference list correctly failed. These remain
  reported pack-data issues; the extraction does not rewrite an author's data to hide them.
- Pseudoregalia `pseudoregalia_brooty.zip`, SHA-256
  `8d7970690c717615633eaa5888529da5add90130f55de8990de6d8ad9f7e3f3f`:
  native loading produced 34 items, nine maps and 154 sections.
- Death's Door 0.2.1 from the checksum-locked fixture in `python/runtime.lock.json`:
  slot data `{"APWorldVersion":"0.2.1"}` reconstructed 267 locations and 173 regions.
  Receiving item 210 and checking location 1,001 updated Bomb to count one and marked
  Grove of Spirits - Door checked. Both the Python command and embedded C bridge passed. This version declares no internal maps.
- The available Death's Door 0.3.0 development archive also reconstructed 267 locations and
  305 regions and 18 maps with validated internal image paths; item/check replay also passed. The pinned 0.2.1 scenario is the reproducible baseline.

## Re-run

```sh
./gradlew spotlessApply :loader:jvmTest :loader:publishToMavenLocal
make test
python3.13 -m unittest discover -s python/src/test/python
python3.13 tests/test_integration.py
```

For a real pack use `./test-pack.sh ARCHIVE --all-variants --json`, then replay your actual
callbacks with `--fixture`; use `--strict` or explicit expectations to fail on reported data
issues. For a world use `./test-world.sh WORLD.apworld --slot-data SLOT_DATA.json --fixture
SCENARIO.json --json`. Scenario JSON and supported boundaries are documented in `testing.md`.

Application dependency migration and native platform binding validation remain downstream work.
