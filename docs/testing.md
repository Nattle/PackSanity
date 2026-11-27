# Verify packs and worlds

Run the commands from any working directory with paths relative to that directory.
Use `--json` for machine-readable stdout. Failures return a nonzero exit code and a
message on stderr.

## Signal Station fixtures

The original Signal Station fixtures share a game name, item IDs, check IDs, and map
assets. Run the pack through both variants and the APWorld through the five-snapshot
replay used by the integration suite.

```sh
./test-pack.sh tests/fixtures/signal-station --all-variants \
  --fixture tests/scenarios/signal-station-pack.json

./test-world.sh tests/fixtures/signal_station --slot 4 --slot-name StationTester \
  --yaml tests/scenarios/signal-station-player.yaml \
  --slot-data tests/scenarios/signal-station-slot-data.json \
  --fixture tests/scenarios/signal-station-world.json
```

| Configuration | Fixture coverage |
| --- | --- |
| PopTracker | Manifest and variant metadata, settings, all seven JSON item types, a hosted Lua item, a pack settings popup, and Archipelago item, check, storage, scout, and bounce callbacks. The survey terminal covers a cross-file parent, an unnamed grouped section, map-pin visibility, and Lua, count, optional, and inspect rules. The pack scenario expects the parser's two mapping diagnostics and empty missing-image and unresolved-reference lists. |
| Universal Tracker | Internal, external, and hybrid map loading, check and entrance aliases, map-specific visibility, automatic map selection, multiple position markers on the active map, deferred entrance and event markers, and slot-data reconstruction. The fixture declares both desktop navigation options in their respective modes. PackSanity exercises the map state while map navigation rendering belongs to the desktop client. |
| Archipelago | APWorld source metadata, inherited common YAML options, a game-specific toggle, item and location tables, regions, entrances, an event, and seed-shaped slot data. The replay verifies YAML exclusion groups, server check membership, inventory, and reachability before and after reconstruction. |

The hosted Lua item uses `PotentialCodes`, available in PopTracker 0.35.4 and later.

For companion testing, ZIP the contents of `tests/fixtures/signal-station` at the
archive root and pass the ZIP to `--external-pack`. Add `"map_source": "external"`
or `"map_source": "hybrid"` to a copy of the slot data and run the world checker with
that copy and the player YAML. The integration suite verifies that the crate pin
comes from the companion JSON in external mode and from the APWorld JSON in hybrid mode.

[Upstream interfaces and the headless compatibility scope](compatibility-matrix.md)

## Upstream contracts covered by the regression tests

- Pack-relative ZIP lookup follows PopTracker's variant-before-root search. PackSanity
  prefers exact spelling within each search location for deterministic case collisions.
  The Pikmin 2 tracker prompted the case-folded image lookup regression.
  [PopTracker 0.36.0 pack lookup](https://github.com/black-sliver/PopTracker/blob/575ee4b29be8e555b7bfa4895e4206f246b399be/src/core/pack.cpp#L221)
- Lua section lookup selects the parent before the section, including sectionless
  parents and unnamed checks. The same regression runs through both pack checkers.
  [PopTracker 0.36.0 location lookup](https://github.com/black-sliver/PopTracker/blob/575ee4b29be8e555b7bfa4895e4206f246b399be/src/core/tracker.cpp#L619)
- The native host suite checks integer item activation, elapsed frame seconds, and
  long-form PackIO reads. Pseudoregalia 0.2.0 uses `*all` to load its logic data.
  [Item activation](https://github.com/black-sliver/PopTracker/blob/575ee4b29be8e555b7bfa4895e4206f246b399be/src/core/jsonitem.cpp#L455)
  [Frame callbacks](https://github.com/black-sliver/PopTracker/blob/575ee4b29be8e555b7bfa4895e4206f246b399be/src/core/scripthost.cpp#L517)
  [PackIO reads](https://github.com/black-sliver/PopTracker/blob/575ee4b29be8e555b7bfa4895e4206f246b399be/src/luasandbox/luapackio.cpp#L224)
- Universal Tracker's default entrance mode lets the APWorld choose whether to defer
  connections. The world replays cover both an opted-in world that reconnects through
  storage and a world that keeps its graph connected despite reporting seed pairings.
  [Universal Tracker 0.3.3 integration guides](https://github.com/FarisTheAncient/Archipelago/releases/tag/Tracker_v0.3.3)

## Relay Tower fixture

Relay Tower reconstructs from slot data without player YAML. Its instance
`interpret_slot_data` hook decodes the lift's cell requirement into a typed
Archipelago option. The replay checks reachability with one and two cells,
then replaces the seed options and verifies the rebuilt graph against the
new inventory and check snapshot.

```sh
./test-world.sh tests/fixtures/relay_tower --fixture tests/scenarios/relay-tower-world.json
```

## PopTracker packs

```sh
./test-pack.sh tests/fixtures/callback-pack --all-variants \
  --fixture tests/scenarios/example-pack.json --json
```

Pass a ZIP or unpacked directory. The default is the pack's default variant. Use
`--variant UID` to choose one variant or `--all-variants` to check each variant.
The checker discovers Lua registrations, starts a fresh session, runs frame handlers,
and reparses files registered by callbacks. Callback errors and unresolved active
highlight targets fail the check. `--strict` also fails on parser diagnostics,
unresolved section references, and missing map images.

A scenario contains `events` and `expect`. Events run in order and expectations apply
to each selected variant's final report. Object expectations match specified entries.
Arrays and scalars must match exactly. Use the pack's numeric AP IDs and registered
section names. Set identity with `connected` before replaying callbacks that use the
player or team number. [Example with a provider count, location mapping, and hint highlight](../tests/scenarios/example-pack.json)

| Event `type` | Fields |
| --- | --- |
| `connected` | `player`, optional `team`, `seed`, `player_aliases`, `player_games`, `missing_locations`, `checked_locations` |
| `clear` | `slot_data` (defaults to `{}`) |
| `item` | `index`, `item`, `name`, `player` |
| `location` | `location`, `name` |
| `scout` | `location`, `location_name`, `item`, `item_name`, `player` |
| `retrieved` | `key`, `value` |
| `set_reply` | `key`, `value`, optional `old_value` |
| `bounced` | `packet` |

The JSON report contains `status`, `pack`, and a `variants` array. Per-variant fields
available in `expect` are `variant`, `game`, `items`, `maps`, `sections`, `diagnostics`,
`registeredFiles`, `mappedLocations`, `highlights`, `providerCounts`, `unresolvedReferences`,
and `missingImages`. Provider counts are queried for codes listed in
`expect.providerCounts`.

## APWorlds

```sh
./test-world.sh /path/to/world.apworld --slot-data /path/to/slot-data.json \
  --yaml /path/to/player.yaml --fixture /path/to/scenario.json --json
./test-world.sh /path/to/my_world --slot-data /path/to/slot-data.json
```

Use a `.apworld` archive with a package root matching the filename stem, or pass the
source package directory containing `__init__.py`. The checker stages directory inputs
as temporary APWorld archives with the lowercase folder name as the package root. It skips
`.git` and `__pycache__` entries and rejects symbolic links inside the package. In JSON
output, `world` identifies the source directory and `sha256` hashes the temporary archive.

Pass the seed's raw Connected packet `slot_data` object to `--slot-data` and the original player
YAML to `--yaml` when required. Use `--game NAME` for archives that register multiple
games, `--slot N` and `--slot-name NAME` to select player identity, and
`--external-pack FILE` to supply a companion PopTracker map ZIP. The default slot is
`1` and the default name is `PackSanity`.

The check validates archive and runtime compatibility, imports the world, reconstructs
its tracker graph, and verifies that referenced map images and marker icons exist in
the APWorld or companion ZIP. An explicit `--external-pack` fails verification if the
world cannot use that ZIP. Run third-party APWorlds in a disposable environment.
They execute Python code with the process's filesystem and network access.

A scenario can supply initial `slot_data`, `updates`, and final `expect`.
`--slot-data` takes precedence over the scenario's initial data. Each update's `state`
is the complete accumulated server snapshot with `received_items`, `checked_locations`,
and `stored_data`. Supply `missing_locations` for authoritative check membership, even
when it is an empty list. Include `slot_data` in an update to replace the complete
options snapshot and exercise reconstruction with the new data.

An update's `expect` matches its returned state. The final `expect` matches the report,
which includes `metadata`, count fields `locations`, `regions`, `inventory`, `maps`, and
`updates`, plus the full `state`. Object expectations match a subset recursively.
Arrays and scalars must match exactly.
[Example with item and check replay](../tests/scenarios/example-world.json)

## Native builds

Run `make test` for Lua limits, archive loading, and host API conformance. Meson options
include `-Djni=true`, `-Dpython_bridge=true`, `-Dtests=false`, and
`-Ddefault_library=shared`. JNI needs a full JDK in `JAVA_HOME`. The default Lua library
is static. Install it with `make install` and use `dependency('packsanity-lua', static: true)`
from Meson. Use `INSTALL_ARGS='--destdir /path/to/staging'` for a staged install.

`make python-native` builds the CPython bridge in `build/python-native-meson`. For a custom
Meson build, enable `-Dpython_bridge=true` and pass the absolute interpreter path returned by
`python3 python/tools/prepare_runtime.py` to `-Dpython`. Installation then includes the
`packsanity-python` pkg-config package. Pass `build/python/host` as Python home and
`build/python/payload` as the module path. Set `PACKSANITY_OPENSSL` to a compatible
OpenSSL installation prefix if the CPython build needs it.

## Verify the C bridge with real archives

Download the listed archives and save each under its `archiveName` in one directory.
The verifier checks SHA-256 before using each archive and tests reconstruction, exact
check and inventory state, slot-data rebuilds, reconnects, archive switches, and
overlapping handles. [Archive URLs, filenames, and checksums](../python/src/test/fixtures/reconstruction-archives.json)

```sh
runtime_python=$(python3 python/tools/prepare_runtime.py)
"$runtime_python" python/tools/verify_native_reconstruction.py \
  --modules build/python/payload --archives /path/to/archives \
  --report build/verification/reconstruction.json
```
