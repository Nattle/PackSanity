#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ "$#" -eq 0 ]; then
    echo "usage: $0 WORLD.apworld [--game NAME] [--slot-data JSON] [--yaml YAML] [--fixture JSON] [--json]" >&2
    exit 2
fi
bootstrap_python=${PACKSANITY_PYTHON:-python3}
if [ "$1" = "--help" ] || [ "$1" = "-h" ]; then
    exec "$bootstrap_python" -I -S "$root/tools/test_world.py" --help
fi
python=$("$bootstrap_python" "$root/python/tools/prepare_runtime.py")
"$root/gradlew" -p "$root" :cli:installDist :cli:writeJavaHome >&2
export JAVA_HOME=$(cat "$root/build/java-home.txt")
exec "$python" -I -S "$root/tools/test_world.py" "$@"
