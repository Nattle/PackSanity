# Provenance and extraction boundary

The portable implementation was extracted from Waystone's working tree at the commit recorded
in `extraction-manifest.json`. That file records source/destination paths. The source is the
original application loader implementation, not PopTracker application code or desktop UT code.
The repository's MIT license covers PackSanity's authored loading/adapter/testing code.

Changes made during extraction:

- Kotlin namespace is `dev.nattle.packsanity`; loader/parser models stay structurally intact.
  Internal Lua adapter names use `PackSanity*`; public Tracker/ScriptHost/Archipelago names remain.
- The current nine-fragment Lua host replaces the earlier rough boot-only host. The fragments
  remain concatenated in the original order into one lexical chunk. JVM/mobile shared source
  embeds that chunk without a Compose resource dependency; calendar substitution occurs at load.
- Directory development packs use the same expanded-entry limits and reject symbolic links.
  CLI scenarios can supply an Archipelago connection identity before callbacks.
- Default logging is an injectable no-op `PopLogger`; app logging stays with consuming apps.
- Native Lua is compiled from the copied official Lua 5.4.8 sources, with their original notice.
  The existing PackSanity C ABI keeps its bounded allocator and lazy-module API. JNI is copied
  from the production native adapter and resolves this repo's native library. Lua print output
  uses stderr so developer JSON remains parseable. The C handle is opaque; new-allocation
  accounting follows Lua's type-tag allocator convention, and initialization allocation failures
  return NULL under a protected Lua call.
- Python modules use the `packsanity_ut_*` namespace. AP reconstruction, YAML, map, entrance,
  dependency and state semantics come from the current tracker-only adapter. The C JSON bridge
  imports that namespace. Runtime logging, temporary cache paths and integration-stub errors
  use PackSanity branding. Source lock/bootstrap/staging is owned here.
- App-specific `ImportedTrackerPackage`, MVI/connection combiners and AP packet accumulation
  stay outside the library; callers provide complete typed snapshots. The obsolete runtime spike
  is omitted. Parser tests retain library-level cases; app reducer/UI cases remain outside this library.
- Custom manifest fields and Kotlin properties use PackSanity names. Packs using the source
  application's custom version/compatibility keys must rename those keys. Standard PopTracker
  metadata remains unchanged. Override archives are caller supplied, without an application-specific
  filename convention in this library.

PackSanity owns its loading contract independently. Consuming applications should pin a published
version, rerun relevant real pack/world scenarios on their native targets, and retain UI/network/
storage integration outside the library.

Third-party inputs remain separately licensed. Archipelago core is selected by an exact commit;
CPython by source URL and SHA-256; approved Python dependencies by locked version. Bootstrap
fetches those sources into `build/python/inputs`; staging copies their license notices into the
payload. No real external pack archives or proprietary game data are checked into this repo.

## PackSanity 0.3 refresh

`parityReferenceCommit` records the Waystone reference
`27e6a09e2060444e0209663d55afc9aa4f785d58`. The original extraction commit remains the
baseline for the file mapping. The portable updates include Lua 5.4.9, PopTracker 0.36
session and parser changes, and Universal Tracker adapter 8 with per-handle subinterpreters.
PackSanity retains its independent logging, CLI conventions, protected Lua setup, C lazy
module entry point, and capability keys. Legacy Waystone capability keys serve as fallbacks.
The informational item classifier accepts a bounded PNG decoding callback. The JVM CLI
supplies ImageIO and Compose remains with its app consumer. The Android bridge and installer
originate in the same Waystone revision and ship in PackSanity's runtime AAR.
