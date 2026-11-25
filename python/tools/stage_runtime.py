#!/usr/bin/env python3
"""Stages the tracker-only Python payload from pinned source checkouts.

This intentionally performs no network access and never invokes pip. Build automation supplies
verified CPython/AP/dependency source directories, then packages this output as Android assets or
iOS framework resources.
"""

from __future__ import annotations

import argparse
import ast
import json
import shutil
from pathlib import Path

from stage_tracker_core import tracker_core_source

CORE_FILES = ("BaseClasses.py", "NetUtils.py", "Options.py", "Utils.py", "settings.py")
CORE_DIRECTORIES = ("worlds/generic", "rule_builder")
DEPENDENCY_PATHS = ("yaml", "schema", "typing_extensions.py", "pathspec", "packaging")


def copy_file(source: Path, destination: Path) -> None:
    if not source.is_file():
        raise RuntimeError(f"Required runtime input is missing: {source}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)


def copy_tree(source: Path, destination: Path) -> None:
    if not source.is_dir():
        raise RuntimeError(f"Required runtime input is missing: {source}")
    shutil.copytree(source, destination, dirs_exist_ok=True, ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))


def stage_option_rolling(source: Path, destination: Path) -> None:
    """Keep the upstream option helper available to APWorld registration imports."""
    text = source.read_text(encoding="utf-8")
    names = (
        "interpret_on_off", "get_choice_legacy", "get_choice", "update_weights",
        "roll_linked_options", "roll_triggers", "handle_option", "roll_settings", "roll_alttp_settings",
    )
    functions = {node.name: node for node in ast.parse(text).body if isinstance(node, ast.FunctionDef)}
    header = """# Staged from the pinned Archipelago Generate.py. See notices/Archipelago-MIT.txt.
from __future__ import annotations

import argparse
import copy
import logging
import random
from collections import Counter
from itertools import chain
from typing import Any

import Options
import Utils
from BaseClasses import PlandoOptions
from Utils import __version__, tuplize_version, version_tuple
"""
    definitions = "\n\n\n".join(ast.get_source_segment(text, functions[name]) for name in names)
    destination.write_text(header + "\n\n" + definitions + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--archipelago-source", type=Path, required=True)
    parser.add_argument("--tracker-archive", type=Path, required=True)
    parser.add_argument("--cpython-source", type=Path, required=True)
    parser.add_argument("--site-packages", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    root = Path(__file__).resolve().parents[1]
    runtime_lock = json.loads((root / "runtime.lock.json").read_text())
    tracker_module = tracker_core_source(args.tracker_archive, runtime_lock["universalTracker"])
    output = args.output.resolve()
    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True)
    (output / "packsanity-runtime-id.txt").write_text(runtime_lock["runtimeId"], encoding="utf-8")
    (output / "packsanity_ut_upstream.py").write_text(tracker_module, encoding="utf-8")
    dependencies = runtime_lock["purePythonDependencies"]
    (output / "packsanity_runtime_dependencies.py").write_text(f"DEPENDENCIES = {dependencies!r}\n", encoding="utf-8")

    for relative in CORE_FILES:
        copy_file(args.archipelago_source / relative, output / relative)
    stage_option_rolling(args.archipelago_source / "Generate.py", output / "Generate.py")
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

    notices = output / "notices"
    copy_file(args.archipelago_source / "LICENSE", notices / "Archipelago-MIT.txt")
    copy_file(root / "licenses/UniversalTracker-MIT.txt", notices / "UniversalTracker-MIT.txt")
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
    copy_tree(args.site_packages / f"packaging-{dependencies['packaging']}.dist-info/licenses", notices / "packaging")


if __name__ == "__main__":
    main()
