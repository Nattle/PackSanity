# Compatibility matrix

This is the PackSanity 0.1 loading contract. “Supported” means the synthetic test surface exercises
the behavior; it does not mean every real pack using that API is guaranteed to work.

| Area | Status | Notes |
| --- | --- | --- |
| Lua runtime | Supported | Vendored official Lua 5.4.9; bounded heap and instruction count per host call. |
| `scripts/init.lua`, `require` | Supported | Hosts register pack modules; slash and dotted aliases are a host policy. |
| JSON item-code lookup | Supported | Matches PopTracker's case-insensitive JSON-item-code quirk. |
| `Tracker:AddItems/Maps/Locations/Layouts/Classes` | Supported | Registrations are observable through the `PackSanity*` adapter functions. |
| Tracker objects, providers, rules, code/frame/location handlers | Supported | PopTracker 0.36 compatibility surface with refresh budgets, live provider counts, hosted items, and canonical region access. |
| Read-only pack `io` | Supported | Registered in-memory files only; no device filesystem. |
| Archipelago callbacks and command extraction | Supported | The host creates callbacks and queues PopTracker-facing commands; networking belongs to the embedding application. |
| `ScriptHost:RunScriptAsync`, `RunStringAsync` | Supported synchronously | No background execution is created. |
| `AutoTracker` memory reads/watches | Intentionally unavailable | Reads return `0`; watches return `false`; no local-game process is attached. |
| `ScriptHost` memory/variable watches | Intentionally unavailable | Return `false`; a future supported provider may extend this deliberately. |
| `Tracker:OpenLink` | Intentionally unavailable | Returns `false`; hosts do not open external URLs. |
| Native Lua modules, `dofile`, `loadfile`, filesystem module search | Intentionally unavailable | Removed from the sandbox. |
| Pack-driven layout mutation / custom UI clicks | Not yet modeled | Outside this host's data-only boundary. |

The machine-facing adapter names intentionally use `PackSanity*`. `Tracker`, `ScriptHost`,
`AutoTracker`, and `Archipelago` remain the pack-facing compatibility names.

`tools/test_pack.py` is an activation harness: it resolves the outer pack manifest, stages
neutral JSON/JSONC items and locations for literal and conventional `Tracker:Add*` registrations,
then boots a clean host session. It intentionally does not claim to be a complete renderer or
gameplay simulation.


The primary `test-pack.sh` command additionally uses the extracted shared parser and dynamic
registration discovery rather than the legacy literal-registration boot runner. It parses
items, locations, maps, layouts and AP mappings; executes the selected variant in a fresh native
runtime; exposes callback/state/highlight checks and loading diagnostics. Some parser diagnostics
are informational; `--strict` intentionally treats all diagnostics as requiring review.

The APWorld path includes package/AP/container checks, the fixed dependency gate, YAML-less or
flat-YAML tracker reconstruction, received-item/check snapshots, reachability, internal/companion
map resources, and APWorld-declared entrance/storage behavior. It supports AP 0.6.8 and the
optional upstream tracker package pin in `UniversalTrackerPackage.kt`; it does not claim compatibility with arbitrary
future APWorld/AP versions. Weighted YAML, seed fill, desktop UI/networking and dependency
installation from pack requirements are outside the supported boundary. See docs/testing.md.

The Python bridge opens one subinterpreter per handle. Serialized closes, reconnects,
archive/version switches through new handles, and independent overlapping handles are supported.
An explicit missing-location list, including an empty list, makes checked plus missing IDs
authoritative. A changed complete slot-data snapshot rebuilds and replays state against the
candidate graph before publication. The caller accumulates partial network packets.
