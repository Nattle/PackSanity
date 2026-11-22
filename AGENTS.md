# PackSanity agent guide

Read README.md, docs/testing.md and docs/compatibility-matrix.md before changing loading behavior.
This repo owns the portable loading/runtime layer. App UI, network clients, catalogs,
platform preferences and application MVI belong outside it. Do not add sibling-checkout paths,
application UI dependencies or game-specific loader switches. Synthetic game names belong only
to tests. Preserve the public PopTracker APIs, APWorld gates, standard PopTracker metadata and PackSanity capability keys.

- `loader/src/commonMain`: archive contracts/safety, parser, models, Lua session and APWorld APIs.
- `native`: bounded Lua C implementation, owned Lua sources, JNI and CPython JSON boundary.
- `meson.build`, `meson.options`: native targets, bridge options, tests and pkg-config installation; Kotlin builds stay in Gradle.
- `lua/poptracker_host`: ordered fragments of one lexical Lua chunk; never require separately.
- `python/src/main`: tracker-only APWorld import/reconstruction/projections and runtime templates.
- `python/runtime.lock.json`: source/core/dependency selection. Preserve notices when staging.
- `test-pack.sh`, `test-world.sh`: developer-facing integrations; no silently skipped failures.

This repo is standalone. Do not use external apps or their test helpers as build or test dependencies.
Changes must be original or properly attributed/licensed. Maintain THIRD_PARTY_NOTICES.md and source
licenses. Never distribute another author's real pack fixture without permission.

Validate changes with `./gradlew spotlessApply :loader:jvmTest`, `make test`, and
`python3.13 -m unittest discover -s python/src/test/python`, and
`python3.13 tests/test_integration.py`. Run Gradle builds sequentially. Validate runtime changes
with a relevant real archive when available, plus a scenario that actually tests affected behavior.
For APWorlds, use seed-shaped slot data/YAML where reconstruction requires it. A package listing
or a successful import alone does not prove reconstruction, maps or state replay.
Keep native calls and world sessions serialized; a world switch needs a new interpreter/process.
