#!/usr/bin/env python3
"""Stages the tracker-only Python payload from pinned source checkouts.

This intentionally performs no network access and never invokes pip. Build automation supplies
verified CPython/AP/dependency source directories, then packages this output as Android assets or
iOS framework resources.
"""

from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path
import zipfile


CORE_FILES = ("BaseClasses.py", "NetUtils.py", "Options.py", "Utils.py", "settings.py")
CORE_DIRECTORIES = ("worlds/generic", "rule_builder")
DEPENDENCY_PATHS = ("yaml", "schema", "typing_extensions.py", "pathspec")
ARCHIPELAGO_REPOSITORY = "https://github.com/ArchipelagoMW/Archipelago"


def copy_file(source: Path, destination: Path) -> None:
    if not source.is_file():
        raise RuntimeError(f"Required runtime input is missing: {source}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)


def copy_tree(source: Path, destination: Path) -> None:
    if not source.is_dir():
        raise RuntimeError(f"Required runtime input is missing: {source}")
    shutil.copytree(source, destination, dirs_exist_ok=True, ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))


def package_bundled_worlds(archipelago_source: Path, imports_file: Path, output: Path) -> None:
    """Create portable APWorld archives from the pinned upstream source tree.

    Archipelago's built-in worlds are directories, whereas the mobile loader deliberately accepts
    APWorld ZIPs. Keep the source directory name in imports.json because it need not match the
    published archive name (for example, osrs -> old_school_runescape.apworld).
    """
    records = json.loads(imports_file.read_text())
    worlds = [
        record for record in records
        if record.get("githubUrl") == ARCHIPELAGO_REPOSITORY
        and record.get("bundledWorldDirectory")
        and record.get("assetName", "").endswith(".apworld")
    ]
    destination = output / "bundled-apworlds"
    destination.mkdir(parents=True, exist_ok=True)
    for world in worlds:
        source = archipelago_source / "worlds" / world["bundledWorldDirectory"]
        if not source.is_dir():
            raise RuntimeError(f"Bundled Archipelago world is missing: {source}")
        archive_name = world["assetName"]
        package_root = archive_name.removesuffix(".apworld").lower()
        with zipfile.ZipFile(destination / archive_name, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for path in sorted(source.rglob("*")):
                if path.is_file() and "__pycache__" not in path.parts and path.suffix != ".pyc":
                    archive.write(path, Path(package_root) / path.relative_to(source))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--archipelago-source", type=Path, required=True)
    parser.add_argument("--cpython-source", type=Path, required=True)
    parser.add_argument("--site-packages", type=Path, required=True)
    parser.add_argument("--imports", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    root = Path(__file__).resolve().parents[1]
    output = args.output.resolve()
    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True)
    runtime_lock = json.loads((Path(__file__).resolve().parents[1] / "runtime.lock.json").read_text())
    (output / "packsanity-runtime-id.txt").write_text(runtime_lock["runtimeId"], encoding="utf-8")

    for relative in CORE_FILES:
        copy_file(args.archipelago_source / relative, output / relative)
    copy_file(args.archipelago_source / "worlds/AutoWorld.py", output / "worlds/AutoWorld.py")
    copy_file(args.archipelago_source / "Fill.py", output / "Fill.py")
    copy_file(args.archipelago_source / "worlds/Files.py", output / "worlds/Files.py")
    copy_tree(args.archipelago_source / "worlds/_bizhawk", output / "worlds/_bizhawk")
    # APWorlds can import these registration APIs without the desktop launcher or SNI client.
    for relative in ("worlds/LauncherComponents.py", "worlds/AutoSNIClient.py"):
        copy_file(args.archipelago_source / relative, output / relative)
    for relative in CORE_DIRECTORIES:
        copy_tree(args.archipelago_source / relative, output / relative)
    copy_tree(root / "src/main/runtime_template/worlds", output / "worlds")
    # Registration imports need the mobile-only shims, never host-installed packages.
    for source in sorted((root / "src/main/runtime_template").glob("*.py")):
        copy_file(source, output / source.name)
    # This is a public Archipelago module used by APWorlds. It must come from the same
    # pinned upstream revision as the rest of the core; PackSanity does not maintain a fork.
    copy_file(args.archipelago_source / "entrance_rando.py", output / "entrance_rando.py")
    # The bridge entry module delegates to responsibility modules. Stage the complete source
    # payload on both platforms, without developer bytecode or cached interpreter artifacts.
    for source in sorted((root / "src/main/python").glob("*.py")):
        copy_file(source, output / source.name)
    for relative in DEPENDENCY_PATHS:
        source = args.site_packages / relative
        if source.is_dir():
            copy_tree(source, output / relative)
        else:
            copy_file(source, output / relative)
    package_bundled_worlds(args.archipelago_source, args.imports, output)

    notices = output / "notices"
    copy_file(args.archipelago_source / "LICENSE", notices / "Archipelago-MIT.txt")
    copy_file(args.cpython_source / "LICENSE", notices / "CPython-PSF-2.txt")
    copy_file(
        args.site_packages / "pyyaml-6.0.3.dist-info/licenses/LICENSE",
        notices / "PyYAML-MIT.txt",
    )
    copy_file(
        args.site_packages / "schema-0.7.8.dist-info/licenses/LICENSE-MIT",
        notices / "schema-MIT.txt",
    )
    copy_file(
        args.site_packages / "typing_extensions-4.15.0.dist-info/licenses/LICENSE",
        notices / "typing-extensions-PSF-2.txt",
    )
    copy_file(
        args.site_packages / "pathspec-1.0.4.dist-info/licenses/LICENSE",
        notices / "pathspec-MPL-2.txt",
    )


if __name__ == "__main__":
    main()
