# Compatibility matrix

## PopTracker

| API or capability | PackSanity behavior |
| --- | --- |
| Lua runtime | Lua 5.4.9 with memory and instruction budgets. |
| `scripts/init.lua`, `require` | Registered pack modules with slash and dotted aliases. |
| `Tracker:AddItems/Maps/Locations/Layouts/Classes` | The loader parses files registered by the selected variant and retains registration order. |
| Tracker objects, providers, rules, code/frame/location handlers | PopTracker 0.36 compatibility surface with bounded refreshes, live provider counts, hosted items, and canonical region access. JSON item codes are case-insensitive. |
| Pack `io` | Read-only access to registered in-memory files. |
| Archipelago callbacks | The host dispatches callbacks and queues outgoing commands. The caller handles networking. |
| `ScriptHost:RunScriptAsync`, `RunStringAsync` | Run synchronously. |
| `AutoTracker` memory reads and watches | Reads return `0`. Watch registration returns `false`. |
| `ScriptHost` memory and variable watches | Watch registration returns `false`. |
| `Tracker:OpenLink` | Returns `false`. |
| Native modules, `dofile`, `loadfile`, filesystem module search | Unavailable. |
| Pack-driven layout mutation and custom UI clicks | Outside the data model. |

Pack-facing APIs use the standard PopTracker names. The library uses `PackSanity*` for
host adapter functions. In addition to standard PopTracker metadata, manifests can set
`min_packsanity_version`, `target_packsanity_version`,
`min_packsanity_compatibility_level`, and `target_packsanity_compatibility_level`.
Consumers supply any override archives.

## APWorlds

| Capability | PackSanity behavior |
| --- | --- |
| Archive and version gates | Validates package structure and declared container and AP versions. [Supported Archipelago core version](../python/runtime.lock.json) |
| Imports | Uses staged dependencies. The core includes `worlds.AutoSNIClient` for registration imports. The pure-Python JSON fallback supports `loads` and `dumps` through both `import orjson` and `from orjson import orjson`. |
| Reconstruction | Uses seed slot data and flat player YAML when required. Runs `generate_early`, `create_regions`, `create_items`, `set_rules`, `connect_entrances`, and `generate_basic`. Weighted YAML, triggers, linked options, plando, and seed fill are outside scope. |
| State and maps | Replays inventory, checks, reachability, and world-declared map, entrance, and storage projections. Accepts a companion map archive. |
| Session lifecycle | One subinterpreter per C handle, with independent overlapping handles and serialized calls. Switch world archives through a new handle or process. |

Consumers accumulate partial network packets. An explicit `missing_locations` list,
including an empty list, makes the checked and missing IDs authoritative. A changed
complete slot-data snapshot rebuilds the graph and replays state before publishing it.
[Scenarios for verifying a specific pack or seed](testing.md)
