# PackSanity

PackSanity is a standalone library for loading PopTracker packs and reconstructing Universal Tracker APWorlds. It could be used to embed the tracker runtime in a Kotlin, C application, or use the command-line tools to verify an archive. Applications supply networking, storage, and UI.

## Build from source

Install a full JDK 21, a C compiler, Python 3, Meson 1.3+, and Ninja. Set `JAVA_HOME` to the JDK installation and make `meson`, `ninja`, and `python3` available on your `PATH`.

### Local libraries and CLI

Install Make, then run these commands from the repository root.

```sh
./gradlew :loader:assemble :cli:installDist
make build
```

Gradle builds the Kotlin JVM loader and CLI. Make runs Meson to build the C Lua host in `build/meson`.

- **Lua JNI.** To include JNI, run `make build MESON_ARGS=-Djni=true`. Point `PACKSANITY_NATIVE_DIR` at `build/meson` when running a JVM consumer.
- **CPython bridge.** Install Git and Python 3 with pip, then run `make python-native` to build the optional bridge in `build/python-native-meson`. This prepares the pinned CPython, Archipelago, and Universal Tracker runtime. Set `PACKSANITY_PYTHON` to select the bootstrap interpreter for this build or the APWorld checker. [Runtime pins](python/runtime.lock.json)

For Android and iOS bundles, follow the [release build instructions](docs/releasing.md#build-and-verify).

### Pack checker

Once the required tools are installed, the bundled smoke test can be your first command.

```sh
./test-pack.sh tests/fixtures/minimal --strict
```

`test-pack.sh` builds the CLI with Gradle and the Lua JNI library with Meson in `build/native-meson` before checking the pack.

## Verify an archive

### PopTracker pack

> `./test-pack.sh /path/to/pack.zip --all-variants --strict`

Use a ZIP or an unpacked directory. Pass `--variant UID` in place of `--all-variants` to check one variant.

- Reads `manifest.json`, runs Lua initialization, and parses the items, maps, locations, and layouts registered by the selected variant. [Module lookup and item registration rules](docs/compatibility-matrix.md#poptracker)
- Runs frame handlers, reports callback errors, and checks that active highlights point to registered locations or sections.
- With `--strict`, fails on parser diagnostics, unresolved section references, or missing map images. Successful Archipelago mapping loads are logged at info level.
- With `--fixture`, replays events such as item receipts and hints. A scenario can assert item provider counts, Archipelago location mappings, and hint highlights.

### Pack ZIP

Run the same pack command against the ZIP you plan to ship. The loader checks the archive as it opens it.

- Enforces limits on archive size, entry count, individual file size, and total expanded size.
- Locates the manifest and registered files at the archive root or under a wrapper folder.
- Ignores entries with unsafe paths, such as absolute paths or paths with a `..` segment.

### APWorld

> `./test-world.sh /path/to/world.apworld --slot-data /path/to/slot-data.json`
> `./test-world.sh /path/to/dev-folder --slot-data /path/to/slot-data.json` where you have `__init__.py` for your pack. 


Pass a `.apworld` archive or the source package directory containing `__init__.py`.
Pass the seed's slot data and add `--yaml /path/to/player.yaml` when the world needs the original player options.

- Checks the package structure and validates any declared runtime compatibility versions.
- Imports the world and reconstructs its tracker graph from the seed inputs.
- Checks that referenced map images and marker icons exist.
- With `--fixture`, replays server state and can assert inventory, checks, and reachability.

Both commands return nonzero on failure. Use `--json` for machine-readable reports. [Options and scenario formats](docs/testing.md)

## Embed the library

### Kotlin

Add `dev.nattle.packsanity:loader:<version>` to your Gradle dependencies. For a local build, run `./gradlew :loader:publishToMavenLocal` and use `mavenLocal()`. The default source build targets JVM. Enable Android with `-PenableAndroid=true` or iOS arm64 device and simulator targets with `-PenableIos=true`. [Artifact setup for JVM, Android, and iOS](docs/releasing.md#use-the-artifacts)

- **PopTracker packs.** Use `PackSanityPackParser` and `LuaTrackerPackageLoader` with a `PackageArchive` and a `LuaPackRuntimeFactory`. On JVM, use `NativeLuaPackRuntime`. Keep the Lua session open while applying updates and close it when finished.
- **APWorlds.** Use `EmbeddedUniversalTrackerRuntime` with a `UniversalTrackerJsonRuntime` implementation, or call the Python `MobileUniversalTracker` adapter directly. Accumulate server packets into complete snapshots before passing them to the runtime.

### C and C++

Run `make install` to build and install the Lua host. Use `packsanity/lua_host.h` and the `packsanity-lua` pkg-config package. Run `make python-native` to build the optional CPython bridge exposed by `packsanity/python_host.h`. [Native build options](docs/testing.md#native-builds)

Serialize native calls and use a new handle or process when switching APWorlds. [Supported APIs and limits](docs/compatibility-matrix.md)

## Run regression tests

### Test fixtures

- **Minimal pack** checks that a basic PopTracker pack loads and runs its Lua scripts. [`minimal`](tests/fixtures/minimal)
- **Callback pack** checks that item receipts and hints update the tracker across variants. [`callback-pack`](tests/fixtures/callback-pack)
- **Signal Station pack** tests layouts, settings, map access rules, and successful Archipelago mapping loads under strict checking. [`signal-station`](tests/fixtures/signal-station)
- **Signal Station APWorld** tests rebuilding a tracker from seed data and player settings, loading maps, and replaying progress. [`signal_station`](tests/fixtures/signal_station)
- **Relay Tower APWorld** tests rebuilding without a player YAML file and updating which locations are reachable when seed options change. [`relay_tower`](tests/fixtures/relay_tower)
- **Tiny World APWorld** isolates item and check replay and tests when a world chooses to reveal entrances. [`tiny_world`](tests/fixtures/tiny_world)

[Fixture commands and scenarios](docs/testing.md)

### Run the suites

```sh
./gradlew spotlessApply :loader:jvmTest
make test
python3.13 -m unittest discover -s python/src/test/python
python3.13 tests/test_integration.py
```

The Python tests fetch and stage the locked runtime inputs on first use.

[Third-party notices](THIRD_PARTY_NOTICES.md)
