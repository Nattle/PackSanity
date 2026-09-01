#!/usr/bin/env python3
"""Stage the locked APWorld runtime and print the path to its locally built CPython."""
import argparse
import fcntl
import hashlib
import json
from pathlib import Path
import subprocess
import sys

from build_host_python import build_host_python

ROOT = Path(__file__).resolve().parents[2]


def prepare(force):
    python = ROOT / "python"
    build = ROOT / "build/python"
    inputs = build / "inputs"
    payload = build / "payload"
    lock_path = python / "runtime.lock.json"
    lock = json.loads(lock_path.read_text())
    cpython = lock["cpython"]
    cpython_source = inputs / f"Python-{cpython['version']}"
    imports = python / "imports.json"
    bootstrap = python / "tools/bootstrap_runtime.py"
    stage = python / "tools/stage_runtime.py"
    digest = hashlib.sha256()
    sources = [lock_path, imports, bootstrap, stage, *sorted((python / "src/main").rglob("*.py"))]
    for source in sources:
        digest.update(source.relative_to(ROOT).as_posix().encode())
        digest.update(source.read_bytes())
    stamp = build / "payload.sha256"
    signature = digest.hexdigest()
    if (force or not stamp.is_file() or stamp.read_text().strip() != signature
            or not (payload / "packsanity_ut_adapter.py").is_file() or not cpython_source.is_dir()):
        stamp.unlink(missing_ok=True)
        subprocess.run(
            [sys.executable, str(bootstrap), "--lock", str(lock_path),
             "--output", str(inputs), "--python", sys.executable],
            stdout=sys.stderr, check=True,
        )
        subprocess.run(
            [sys.executable, str(stage),
             "--archipelago-source", str(inputs / f"Archipelago-{lock['archipelago']['version']}"),
             "--cpython-source", str(cpython_source), "--site-packages", str(inputs / "site-packages"),
             "--imports", str(imports), "--output", str(payload)],
            stdout=sys.stderr, check=True,
        )
        stamp.write_text(signature + "\n")
    return build_host_python(cpython_source, build, cpython)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--force", action="store_true", help="restage the runtime payload")
    args = parser.parse_args()
    build = ROOT / "build/python"
    build.mkdir(parents=True, exist_ok=True)
    with (build / "prepare.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        print(prepare(args.force))


if __name__ == "__main__":
    main()
