# Third-party notices

## Lua 5.4.9 (vendored source)

Copyright © 1994–2026 Lua.org, PUC-Rio.

PackSanity compiles the unmodified Lua 5.4.9 sources in `native/vendor/lua-5.4.9`.
Their original copyright and license are also present in the source distribution.

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
associated documentation files (the “Software”), to deal in the Software without restriction,
including without limitation the rights to use, copy, modify, merge, publish, distribute,
sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions: the above copyright notice and this
permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING
BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.


## CPython, Archipelago and Python dependencies

`python/runtime.lock.json` pins CPython 3.13.15 (PSF license), Archipelago 0.6.8 (MIT),
PyYAML 6.0.3 (MIT), schema 0.7.8 (MIT), typing-extensions 4.15.0 (PSF-2),
pathspec 1.0.4 (MPL-2), and packaging 26.1 (Apache-2.0 or BSD-2-Clause).
Sources/dependencies are fetched at build time, not copied from a
consumer checkout. `python/tools/stage_runtime.py` retains their original notices in the
staged payload's `notices/` folder. Distribute those notices with any runtime bundle.
Pathspec source is staged unmodified; preserve its source and MPL obligations when distributing.
The trimmed Archipelago payload is upstream source with a selected-world namespace and narrowly
scoped tracker-only import templates, not the upstream desktop tracker application.

Kotlin, kotlinx.coroutines and kotlinx.serialization dependencies use the Apache 2.0 license.
Their published artifacts are resolved through Gradle; retain relevant dependency notices in
binary distributions. The Gradle wrapper is provided under the Gradle Apache 2.0 license.

The Android AAR also includes libraries built using CPython's pinned Android dependency
inputs for OpenSSL 3.0.21, SQLite 3.50.4, libffi 3.4.4, bzip2 1.0.8 and XZ Utils 5.4.6.
Their notices are in the AAR under `assets/packsanity/notices/`.

## Universal Tracker 0.3.3 (MIT)

The build verifies the pinned Universal Tracker release archive and stages
the headless generation, item replay, and event sweep routines from
`TrackerCore.py`. PackSanity also adapts the entrance storage-key handling
from `TrackerClient.py`. [Universal Tracker 0.3.3 release](https://github.com/FarisTheAncient/Archipelago/releases/tag/Tracker_v0.3.3)

The [upstream license](python/licenses/UniversalTracker-MIT.txt) is included in
the staged runtime as `notices/UniversalTracker-MIT.txt`.
