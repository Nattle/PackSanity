#!/usr/bin/env python3
"""Fetch and verify the pinned, build-time Universal Tracker inputs."""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request
from importlib.metadata import distributions
from pathlib import Path


def run(*command: str) -> None:
    subprocess.run(command, check=True)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def extract_source_archive(archive: Path, destination: Path) -> None:
    """Extract a source tarball safely on Python versions before tarfile's filter API."""
    destination = destination.resolve()
    with tarfile.open(archive) as source:
        for member in source.getmembers():
            target = (destination / member.name).resolve()
            try:
                target.relative_to(destination)
            except ValueError:
                raise RuntimeError(f"Unsafe CPython archive member: {member.name}")
            if member.isdev():
                raise RuntimeError(f"Unsafe CPython archive member: {member.name}")
        source.extractall(destination)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--lock", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--python", default="python3")
    args = parser.parse_args()

    lock = json.loads(args.lock.read_text())
    output = args.output.resolve()
    cpython = lock["cpython"]
    archipelago = lock["archipelago"]
    cpython_root = output / f"Python-{cpython['version']}"
    ap_root = output / f"Archipelago-{archipelago['version']}"
    site_packages = output / "site-packages"
    output.mkdir(parents=True, exist_ok=True)

    if not cpython_root.is_dir():
        with tempfile.TemporaryDirectory(dir=output) as temporary:
            archive = Path(temporary) / "cpython.tar.xz"
            urllib.request.urlretrieve(cpython["sourceUrl"], archive)
            actual = sha256(archive)
            if actual != cpython["sha256"]:
                raise RuntimeError(f"CPython checksum mismatch: expected {cpython['sha256']}, got {actual}")
            extract_source_archive(archive, Path(temporary))
            extracted = Path(temporary) / f"Python-{cpython['version']}"
            if not extracted.is_dir():
                raise RuntimeError("CPython archive did not contain its expected source directory")
            shutil.move(str(extracted), cpython_root)

    # Never link macOS DBM implementations into a cross-compiled mobile interpreter.
    # `Setup.local` is CPython's documented per-build module override mechanism.
    (cpython_root / "Modules" / "Setup.local").write_text("*disabled*\n_dbm\n_gdbm\n")

    if not ap_root.is_dir():
        run("git", "clone", "--depth", "1", "--branch", archipelago["version"], archipelago["gitUrl"], str(ap_root))
    actual_commit = subprocess.check_output(
        ["git", "-C", str(ap_root), "rev-parse", "HEAD"], text=True
    ).strip()
    if actual_commit != archipelago["commit"]:
        raise RuntimeError(f"Archipelago revision mismatch: expected {archipelago['commit']}, got {actual_commit}")

    tracker = lock["universalTracker"]
    tracker_archive = output / f"UniversalTracker-{tracker['version']}.apworld"
    if not tracker_archive.is_file() or sha256(tracker_archive) != tracker["sha256"]:
        with tempfile.TemporaryDirectory(dir=output) as temporary:
            download = Path(temporary) / "tracker.apworld"
            urllib.request.urlretrieve(tracker["sourceUrl"], download)
            if sha256(download) != tracker["sha256"]:
                raise RuntimeError("Universal Tracker release checksum does not match the runtime lock")
            download.replace(tracker_archive)

    dependencies = lock["purePythonDependencies"]
    installed = {
        distribution.metadata["Name"].lower().replace("-", "_"): distribution.version
        for distribution in distributions(path=[str(site_packages)])
    } if site_packages.is_dir() else {}
    expected = {
        name.lower().replace("-", "_"): version
        for name, version in dependencies.items()
    }
    if any(installed.get(name) != version for name, version in expected.items()):
        site_packages.mkdir(parents=True, exist_ok=True)
        run(
            args.python,
            "-m",
            "pip",
            "install",
            "--disable-pip-version-check",
            "--no-compile",
            "--upgrade",
            "--target",
            str(site_packages),
            *(f"{name}=={version}" for name, version in dependencies.items()),
        )
        # The staged runtime is platform-neutral; PyYAML falls back to its Python loader.
        for extension in (site_packages / "yaml").glob("_yaml*.so"):
            extension.unlink()


if __name__ == "__main__":
    main()
