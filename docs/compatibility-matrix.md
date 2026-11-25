# Compatibility matrix

PackSanity targets a headless subset of the upstream contracts. See the
[PopTracker pack interface](https://github.com/black-sliver/PopTracker/blob/master/doc/PACKS.md),
[PopTracker autotracking interface](https://github.com/black-sliver/PopTracker/blob/master/doc/AUTOTRACKING.md),
the integration guides included in the pinned
[Universal Tracker release](https://github.com/FarisTheAncient/Archipelago/releases/tag/Tracker_v0.3.3),
and the Archipelago [APWorld specification](https://github.com/ArchipelagoMW/Archipelago/blob/main/docs/apworld%20specification.md)
and [0.6.8 network protocol](https://github.com/ArchipelagoMW/Archipelago/blob/54803be064fc7e80c4628777ed0b46a9390f255f/docs/network%20protocol.md).

## PopTracker

| API or capability | PackSanity behavior |
| --- | --- |
| Lua runtime | Lua 5.4.9 with memory and instruction budgets. |
| `scripts/init.lua`, `require`, `ScriptHost:LoadScript` | Pack-only script loading. `require` tries a `.lua` file and then a directory's `init.lua` under `scripts/` before trying the archive root. Results are cached by the requested module name. `LoadScript` executes the specified archive script on each call and returns a success boolean. |
| `Tracker:AddItems/Maps/Locations/Layouts/Classes` | The loader resolves paths for the active variant and retains registration order. Successful item registrations create separate providers with distinct model IDs. A missing path, malformed JSON, or wrong top-level JSON type returns `false` and does not become a registration. Declared location parents resolve against locations registered by earlier calls. |
| Tracker objects, providers, rules, code/frame/location handlers | PopTracker 0.36 compatibility surface with bounded refreshes, live provider counts, hosted items, and canonical region access. JSON item codes are case-insensitive. Lua sees the pack's original section path in `FullID`, and state snapshots use stable model IDs. |
| Pack `io` | Read-only access to registered in-memory files. |
| Archipelago callbacks | The host dispatches callbacks and queues outgoing commands. Checks and status updates require `apmanual`. Scouts accept `apmanual` or `aphintgame`. The caller handles networking. |
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
| Imports | Uses staged dependencies. Registration imports can use `worlds.AutoSNIClient` and the pinned Archipelago `Generate.roll_settings` helper. The pure-Python JSON fallback supports `loads` and `dumps` through both `import orjson` and `from orjson import orjson`. |
| Reconstruction | Uses seed slot data and flat player YAML when required. Runs `generate_early`, `create_regions`, `create_items`, `set_rules`, `connect_entrances`, and `generate_basic`. Weighted YAML, triggers, linked options, plando, and seed fill are outside scope. |
| State and maps | Replays inventory, checks, reachability, and world-declared map, entrance, and storage projections. Accepts companion maps, including Universal Tracker's mode with APWorld JSON and companion images. |
| Session lifecycle | One subinterpreter per C handle, with independent overlapping handles and serialized calls. Switch world archives through a new handle or process. |

Consumers accumulate partial network packets. An explicit `missing_locations` list,
including an empty list, makes the checked and missing IDs authoritative. A changed
complete slot-data snapshot rebuilds the graph and replays state before publishing it.
The consumer replaces the item history on `ReceivedItems` index zero and merges check
updates from `RoomUpdate` into its accumulated snapshot before calling the runtime.

Use the upstream lowercase APWorld filename and package root for portable archives.
The loader also accepts legacy mixed casing and normalizes it for embedded imports.

Map assets expose numbered check placements and separate graph markers for entrances and
code-less events. Sections match graph names directly or use `poptracker_entrance_mapping`.
Map-specific visibility lists apply to each marker kind. Graph markers report whether the
entrance is reachable and connected, or the event's region and rule are reachable. World
storage callbacks can change those states without changing server check membership.

Reconstruction uses Universal Tracker's default deferred entrance mode. The APWorld
chooses which entrances to defer and reconnects them through its declared storage
keys and `reconnect_found_entrances` hook. Reachability uses that graph. The entrance
pairing list depends on a supported seed-data pairing table.

Player YAML supports merge keys and rejects duplicate keys within an explicit mapping.
Archipelago verifies YAML location exclusions before generation and applies exclusions
against the generated graph during reconstruction.

APWorld dependency specifiers use PyPA's `packaging` implementation and are
checked against the pinned runtime. Direct URLs and environment markers are rejected.

[Scenarios for verifying a specific pack or seed](testing.md)
