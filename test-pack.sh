#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ "$#" -eq 0 ]; then
    echo "usage: $0 PACK_DIRECTORY_OR_ZIP [--variant UID | --all-variants] [--fixture JSON] [--strict] [--json]" >&2
    exit 2
fi
if [ "$1" = "--help" ] || [ "$1" = "-h" ]; then
    cat <<'USAGE'
usage: test-pack.sh PACK_DIRECTORY_OR_ZIP [--variant UID | --all-variants] [--fixture JSON] [--strict] [--json]
Run native Lua registration discovery, parse the registered pack, and exercise callbacks.
--fixture replays connection/item/hint events and asserts the resulting model/runtime state.
--strict fails on diagnostics, unresolved references or missing map images.
See docs/testing.md for scenarios and pass criteria.
USAGE
    exit 0
fi
# Build chatter stays on stderr so --json stdout can be consumed by another tool.
"$root/gradlew" -p "$root" :cli:installDist :cli:writeJavaHome >&2
export JAVA_HOME=$(cat "$root/build/java-home.txt")
meson setup --reconfigure "$root/build/native-meson" "$root" -Djni=true >&2
meson compile -C "$root/build/native-meson" -j 4 >&2
export PACKSANITY_NATIVE_DIR="$root/build/native-meson"
exec "$root/cli/build/install/cli/bin/cli" "$@"
