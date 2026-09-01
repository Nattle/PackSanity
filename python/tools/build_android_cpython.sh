#!/usr/bin/env bash
set -euo pipefail

source_dir=$1
host=$2
build_python=$3
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"

export CONFIG_SITE="$(cd "$(dirname "$0")" && pwd)/android_config.site"
profile="$(shasum -a 256 "$CONFIG_SITE" "$0" | shasum -a 256 | cut -d' ' -f1)"
cd "$source_dir"
build="cross-build/$host"
marker="$build/.packsanity-profile"
if [[ -f "$marker" && "$(cat "$marker")" == "$profile" &&
    -f "$build/prefix/lib/libpython3.13.so" &&
    -f "$build/prefix/include/python3.13/Python.h" &&
    -f "$build/prefix/lib/python3.13/encodings/__init__.py" ]]; then
  echo "Reusing pinned Android CPython: $build/prefix"
  exit 0
fi
# CPython's Android script expects a matching interpreter at cross-build/build/python.
mkdir -p cross-build/build
ln -sfn "$build_python" cross-build/build/python
"$build_python" Android/android.py configure-host --clean "$host"
"$build_python" Android/android.py make-host "$host"
printf '%s\n' "$profile" > "$marker"
