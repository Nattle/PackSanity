"""Build the locked CPython for PackSanity's host tools and native bridge."""
import hashlib
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tempfile


def build_host_python(source, build_root, cpython):
    prefix = build_root / "host"
    openssl = os.environ.get("PACKSANITY_OPENSSL")
    if openssl is None and sys.platform == "darwin" and shutil.which("brew"):
        candidate = subprocess.check_output(
            ["brew", "--prefix", "openssl@3"], text=True,
            env=dict(os.environ, HOMEBREW_NO_AUTO_UPDATE="1"),
        ).strip()
        if Path(candidate, "include/openssl/ssl.h").is_file():
            openssl = candidate
    signature = hashlib.sha256(
        f"{cpython['sha256']}\n{prefix}\n{openssl}".encode() + Path(__file__).read_bytes()
    ).hexdigest()
    executable = prefix / "bin/python3.13"
    stamp = prefix / ".build-id"
    if executable.is_file() and stamp.is_file() and stamp.read_text().strip() == signature:
        return executable

    build = build_root / "host-build"
    for path in (build, prefix):
        if path.exists():
            shutil.rmtree(path)
    build.mkdir()
    env = dict(os.environ, CFLAGS=f"{os.environ.get('CFLAGS', '')} -fPIC".strip())
    jobs = os.environ.get("PACKSANITY_BUILD_JOBS", str(min(os.cpu_count() or 1, 8)))

    log = build / "build.log"

    def compile_and_install(build_dir, source_dir, install_prefix):
        configure = [
            source_dir / "configure", f"--prefix={install_prefix}",
            "--disable-test-modules", "--without-ensurepip",
        ]
        if openssl:
            configure += [f"--with-openssl={openssl}", "--with-openssl-rpath=auto"]
        with log.open("w") as output:
            for command in (configure, ["make", "-j", jobs], ["make", "install"]):
                try:
                    subprocess.run(command, cwd=build_dir, env=env, stdout=output,
                                   stderr=subprocess.STDOUT, check=True)
                except subprocess.CalledProcessError:
                    print(f"Failed: {shlex.join(map(str, command))}. See {log}", file=sys.stderr)
                    raise

    print(f"Building CPython {cpython['version']} (log: {log})", file=sys.stderr)
    if any(character.isspace() for character in str(build_root)):
        # The upstream Makefile expands source and install paths without shell quoting.
        with tempfile.TemporaryDirectory(prefix="packsanity-cpython-", dir="/tmp") as temporary:
            scratch = Path(temporary)
            (scratch / "source").symlink_to(source, target_is_directory=True)
            prefix.mkdir()
            (scratch / "host").symlink_to(prefix, target_is_directory=True)
            (scratch / "build").mkdir()
            compile_and_install(scratch / "build", scratch / "source", scratch / "host")
            # Meson obtains the include and library paths from this install metadata.
            for path in (prefix / "lib/python3.13").glob("_sysconfigdata_*.py"):
                path.write_text(path.read_text().replace(str(scratch / "host"), str(prefix)))
            for path in (prefix / "lib/pkgconfig").glob("*.pc"):
                path.write_text(path.read_text().replace(str(scratch / "host"), "${pcfiledir}/../.."))
    else:
        compile_and_install(build, source, prefix)
    actual = subprocess.check_output(
        [executable, "-I", "-S", "-c", "import zipfile, zlib; import sys; print(sys.version.split()[0])"],
        text=True,
    ).strip()
    if actual != cpython["version"]:
        raise RuntimeError(f"Built CPython {actual}; expected {cpython['version']}")
    stamp.write_text(signature + "\n")
    return executable
