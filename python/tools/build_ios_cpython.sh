#!/usr/bin/env bash
# Build the two arm64 CPython framework slices used by PackSanity. This wrapper is intentionally
# offline: source, dependency flags, and matching build-Python are supplied by the release build.
set -euo pipefail

source_dir=""
build_python=""
output_dir=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --source) source_dir="$2"; shift 2 ;;
    --build-python) build_python="$2"; shift 2 ;;
    --output) output_dir="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [ -z "$source_dir" ] || [ -z "$build_python" ] || [ -z "$output_dir" ]; then
  echo "--source, --build-python, and --output are required" >&2
  exit 2
fi
if [[ "$build_python" != */* ]]; then
  build_python="$(command -v "$build_python" || true)"
fi
if [ ! -x "$source_dir/configure" ]; then
  echo "CPython source is unavailable" >&2
  exit 2
fi

if ! "$build_python" -c 'import sys; raise SystemExit(sys.version_info[:2] != (3, 13))'; then
  echo "The pinned CPython 3.13 build interpreter is required" >&2
  exit 2
fi

host_arch="$(uname -m)"
case "$host_arch" in
  arm64) build_host="arm64-apple-darwin" ;;
  x86_64) build_host="x86_64-apple-darwin" ;;
  *) echo "unsupported macOS build host: $host_arch" >&2; exit 2 ;;
esac

# The CPython iOS compiler shims keep machine-local Xcode paths out of sysconfig data.
export PATH="$source_dir/iOS/Resources/bin:/usr/bin:/bin:/usr/sbin:/sbin:/Library/Apple/usr/bin"
export CONFIG_SITE="$(cd "$(dirname "$0")" && pwd)/android_config.site"

verify_static_module_initializers() {
  config_file="$1"
  framework_binary="$2"
  expected_initializers="$({
    sed -nE 's/^extern PyObject\* PyInit_([[:alnum:]_]+)\(void\);/\1/p' "$config_file" | sort -u
  })"
  if [ -z "$expected_initializers" ]; then
    echo "CPython iOS module table has no static extension initializers: $config_file" >&2
    return 1
  fi

  missing_initializers="$(
    comm -23 \
      <(printf '%s\n' "$expected_initializers" | sort -u) \
      <(nm -gU "$framework_binary" | sed -nE 's/^.* _?PyInit_([[:alnum:]_]+)$/\1/p' | sort -u)
  )"
  if [ -n "$missing_initializers" ]; then
    echo "CPython iOS framework is missing statically linked standard-library modules:" >&2
    printf '%s\n' "$missing_initializers" | sed 's/^/  /' >&2
    return 1
  fi
}

build_slice() {
  platform="$1"
  host="$2"
  build_dir="$output_dir/build-$platform"
  framework_dir="$output_dir/$platform/Python.framework"
  static_profile_marker="$output_dir/$platform/.packsanity-static-modules-v4"
  compiler_name="arm64-apple-ios-clang"
  if [ "$platform" = "iphonesimulator" ]; then
    compiler_name="arm64-apple-ios-simulator-clang"
  fi
  compiler="$source_dir/iOS/Resources/bin/$compiler_name"
  if [ ! -x "$compiler" ]; then
    echo "CPython iOS compiler shim is unavailable: $compiler" >&2
    exit 2
  fi
  if [ -d "$framework_dir" ]; then
    if [ -d "$output_dir/$platform/lib" ] \
      && [ -f "$static_profile_marker" ] \
      && ! find "$output_dir/$platform/lib" -type f -name '*.so' -print -quit | grep -q . \
      && verify_static_module_initializers "$build_dir/Modules/config.c" "$framework_dir/Python"; then
      echo "Reusing existing CPython iOS framework slice: $framework_dir"
      return
    fi
    echo "Removing stale or non-static CPython iOS slice: $output_dir/$platform"
    rm -rf "$output_dir/$platform"
  fi
  if [ -d "$build_dir" ]; then
    echo "Removing stale generated CPython iOS build tree: $build_dir"
    rm -rf "$build_dir"
  fi
  mkdir -p "$build_dir" "$output_dir/$platform"
  (
    cd "$build_dir"
    echo "Configuring CPython for $platform..."
    "$source_dir/configure" \
      --enable-framework="$output_dir/$platform" \
      --disable-test-modules \
      --host="$host" \
      --build="$build_host" \
      --with-build-python="$build_python"

    # `makesetup` reads Setup.local before Setup.stdlib, so a static directive in
    # Setup.local is overridden by the generated stdlib profile. Rewrite that final
    # profile so iOS has no dynamically loaded extensions in its resource payload.
    echo "Statically linking standard-library extensions in Modules/Setup.stdlib..."
    make Modules/Setup.stdlib
    sed -i '' 's/^\*shared\*/\*static\*/' Modules/Setup.stdlib
    # Regenerate the module table now. Calling make on Makefile recursively remakes it through
    # Makefile.pre, while config.status does not instantiate Makefile. Run CPython's generator
    # directly against the configured profiles instead.
    "$source_dir/Modules/makesetup" -c "$source_dir/Modules/config.c.in" -m Makefile.pre \
      -s Modules Modules/Setup.local Modules/Setup.stdlib Modules/Setup.bootstrap \
      "$source_dir/Modules/Setup"
    mv config.c Modules/config.c
    # CPython's iOS framework link recipe does not use MODLIBS by default. Static
    # extensions such as _sqlite3 and zlib need those SDK libraries at framework
    # link time, so retain the generated module flags in that recipe.
    sed -i '' 's/-all_load \$(LIBRARY)/-all_load \$(LIBRARY) \$(MODLIBS)/' Makefile
    if ! grep -Eq '^MODSHARED_NAMES=[[:space:]]*$' Makefile; then
      echo "CPython iOS module table still builds standard-library extensions as shared" >&2
      exit 1
    fi
    if ! sed -nE 's/^extern PyObject\* PyInit_([[:alnum:]_]+)\(void\);/\1/p' Modules/config.c | grep -q .; then
      echo "CPython iOS module table is missing static extension initialization" >&2
      exit 1
    fi

    echo "Building CPython for $platform..."
    make CC="$compiler" -j"$(sysctl -n hw.ncpu)"
    echo "Installing CPython for $platform..."
    make CC="$compiler" install
  )
  if [ ! -d "$framework_dir" ] || [ ! -d "$output_dir/$platform/lib" ]; then
    echo "CPython iOS install did not produce Python.framework and lib for $platform" >&2
    exit 1
  fi
  # CPython's install target copies its intermediate shared-module artifacts even
  # after the static module table has been linked into the framework. They are
  # redundant and cannot be placed in an App Store application's resources.
  rm -rf "$output_dir/$platform/lib/python3.13/lib-dynload"
  FOUND_SO=$(find "$output_dir/$platform/lib" -type f -name '*.so' -print || true)
  if [ -n "$FOUND_SO" ]; then
    echo "CPython iOS install retained loadable extension modules for $platform:" >&2
    echo "$FOUND_SO" >&2
    exit 1
  fi
  if ! verify_static_module_initializers "$build_dir/Modules/config.c" "$framework_dir/Python"; then
    exit 1
  fi
  touch "$static_profile_marker"
}

build_slice iphoneos arm64-apple-ios15.0
build_slice iphonesimulator arm64-apple-ios15.0-simulator
