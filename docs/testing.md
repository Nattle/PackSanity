# Developer integration checks

`test-pack.sh PACK` and `test-world.sh WORLD.apworld` test the actual library implementations.
They build/bootstrap locally as needed. They use only this repository’s build tasks.
Archive tests require real source/data; parsing a catalog entry is not compatibility evidence.

## PopTracker packs

The pack can be a ZIP or an unpacked directory. Options: `--variant UID`, `--all-variants`,
`--fixture FILE`, `--strict`, `--json`. Unknown variants/fixture events/expectation keys fail.
Normal load diagnostics are printed to stderr and reported in JSON; use `--strict` to fail on them as well. Native Lua
uses 512 MiB and 160 million instructions per host call, lazy modules and in-memory read-only
pack files. A disposable discovery execution and a fresh real session preserve computed and
variant-conditional `Tracker:Add*` registration order. The parser returns data-only models,
including layouts/maps/item states, reference resolution and AP ID mappings.

A scenario file contains `events` and `expect`. Expectations are compared against the final
parsed/native session, not a mock. Object expectations match the specified entries; scalars and
arrays match exactly. An assertion failure includes the key and expected/actual values and exits 1.

```json
{
  "events": [
    {"type":"connected", "player":1, "team":0},
    {"type":"clear", "slot_data":{}},
    {"type":"retrieved", "key":"_read_hints_0_1", "value":[]}
  ],
  "expect": {
    "providerCounts": {"sword":1},
    "mappedLocations": {"12345":["region/chest"]},
    "highlights": {"region/chest":1},
    "unresolvedReferences": []
  }
}
```

Use IDs, keys and names from **your** pack/seed. The example is a schema illustration; it is not
a game fixture. For a hint regression, replay the pack's real hint-storage payload then assert
that the canonical registered section receives the expected highlight. Report section IDs are normalized
to lowercase. Set player/team identity before `clear` when a pack reads `Archipelago.PlayerNumber`. Location-ID mapping
expectations also catch mapping-table aliases that disagree with registered location names.
Unresolved highlight targets always fail. Unresolved reference aliases and parser diagnostics
are reported (and fail with `--strict`). This covers loading/host data; UI reducers and hint halo
rendering remain consuming application responsibilities.

Supported event fields:

| type | fields |
| --- | --- |
| connected | player, optional team/seed/player_aliases/player_games/missing_locations/checked_locations |
| clear | slot_data (object, defaults to empty) |
| item | index, item (numeric ID), name, player |
| location | location (numeric ID), name |
| scout | location, location_name, item, item_name, player |
| retrieved | key, value |
| set_reply | key, value, optional old_value |
| bounced | packet |

Final expectation/report fields: `variant`, `game`, `items`, `maps`, `sections`, `diagnostics`,
`registeredFiles`, `mappedLocations`, `highlights`, `providerCounts`, `unresolvedReferences`,
`missingImages`. Provider-count snapshots include codes requested in `expect.providerCounts`.
Inspect `--json` output to construct a fixture, then verify it against the intended pack behavior;
do not approve a regression merely by copying the wrong result into the expected file.

## APWorlds

The input is a `.apworld` ZIP whose root matches its filename. The structural/version gate is
exactly the extracted Kotlin inspector, including legacy manifest handling, normalization and
AP/container compatibility. The Python import dependency gate accepts only the pinned staged
modules. No arbitrary `pip install` from a submitted world's requirements occurs. The check
fails before pretending that an incompatible import or missing YAML was a successful load.

Use `--slot-data FILE` for the raw JSON object from the seed's Connected packet, `--yaml FILE`
for the original player YAML when required, `--game NAME` for multi-game archives,
`--slot N`, `--slot-name NAME`, and optionally `--external-pack FILE`. The default slot is 1.
A YAML-less world's development defaults may accept `{}`; do not assume that means every seed
reconstructs. Some worlds need version/options/entrance data before they can create their graph.

A world fixture has optional `slot_data`, `updates`, and `expect`. Each update's `state` is a
**complete** accumulated AP snapshot; partial updates must first be accumulated by the caller.
Update `expect` entries compare against that update's result. Final `expect` entries compare
against the report: counts (`locations`, `regions`, `inventory`, `maps`, `updates`)/metadata plus the full `state` returned by the Python adapter.
Object expectations are subsets; arrays/scalars are exact. Example shape:

```json
{
  "slot_data": {},
  "updates": [
    {"state": {"received_items":[{"item_id":1001,"location_id":2001,"player":1,"flags":1}],
               "checked_locations":[2001], "missing_locations":[], "stored_data":{}},
     "expect": {"game":"My Game"}}
  ],
  "expect": {"locations":1, "regions":2}
}
```

Python fake generation runs only `generate_early`, `create_regions`, `create_items`, `set_rules`,
`connect_entrances`, and `generate_basic`. Updates recompute checks/inventory/reachability and
APWorld-declared map/entrance/storage projections. No game-specific loader switches, seed fill,
desktop tracker launcher or network client is introduced. YAML supports the extracted flat
player/game options contract; links/triggers/plando/weighted expansion remain unsupported.

Each Python CLI command runs one world per process. Each native handle owns a
subinterpreter. Close the handle and open a new one to switch archives.
`tests/test_integration.py` exercises native pack callbacks, failure assertions, real synthetic
APWorld import/reconstruction and state replay, malformed/incompatible archives and Python
payload isolation. Selected real archives should additionally be tested with seed-shaped data.

## Native builds

Use `meson setup build/meson`, `meson compile -C build/meson` and
`meson test -C build/meson --print-errorlogs`, or `make test`.
The four native regressions cover limits/recovery, directory loading, ZIP loading and host API
conformance. `test-pack.sh` builds JNI in `build/native-meson`, which integration tests
also use. `test-world.sh` and the world integration tests run with the CPython version in
`python/runtime.lock.json`. Run `python3 python/tools/prepare_runtime.py` to stage the payload,
build CPython, and obtain the local executable path.

`make python-native` uses that executable and its installed headers/library to build
`build/python-native-meson/packsanity-python`. Pass `build/python/host` as its Python home and
`build/python/payload` as its module path. For a custom Meson build, use `-Dpython_bridge=true`
and pass the absolute local interpreter path with `-Dpython`.
Use `meson install -C BUILD_DIR --destdir STAGING_DIR` for a staged installation. Installed
`packsanity-lua.pc` includes the vendored Lua and system dependencies for static linking;
`packsanity-python.pc` is installed only when the embedded bridge is enabled.

For the pinned real-archive C bridge regression, download and hash-check the six entries
in `python/src/test/fixtures/reconstruction-archives.json` into a local archive directory.
Use the local interpreter from `prepare_runtime.py` to run:

```sh
"$python" python/tools/verify_native_reconstruction.py \
  --modules build/python/payload --archives /path/to/archives \
  --report build/verification/reconstruction.json
```

The report records server-shaped non-default options, exact check and inventory snapshots,
slot-data updates, reconnects, failures, archive/version switches and serialized thread changes.
