"""APWorld registration, archive normalization, and scoped import compatibility."""
from __future__ import annotations
import builtins
from contextlib import contextmanager
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import sys
import tempfile
from typing import Any
import zipfile
import zipimport
from worlds import AutoWorld
from packsanity_ut_models import LOGGER, TrackerAdapterError
from packsanity_ut_requirements import _read_requirements, _requirement_is_available


def load_world(self, apworld_path: str, package_root: str) -> dict[str, Any]:
    """Register exactly one APWorld under ``worlds`` without desktop world discovery.

    The packaged runtime contains only ``worlds.AutoWorld`` and the generic helpers.  This
    mirrors Archipelago's APWorld zip loading convention while deliberately avoiding the
    normal desktop ``worlds`` directory scan and its unrelated game dependencies.
    """
    path = Path(apworld_path).resolve()
    if path.suffix.lower() != ".apworld":
        raise TrackerAdapterError("Universal Tracker world packages must end in .apworld.")
    if not path.is_file():
        raise TrackerAdapterError(f"APWorld package does not exist: {path}")
    if not package_root or "/" in package_root or "\\" in package_root:
        raise TrackerAdapterError("APWorld package root is invalid.")
    path = _normalized_package_path(path, package_root)
    if self._loaded_package_root is not None:
        if self._loaded_package_root == package_root and self._loaded_package_path == path:
            # Closing a tracker session deliberately leaves CPython and the registered APWorld
            # alive. A reconnect creates a fresh native session wrapper, which calls load_world
            # again even though this exact package is already registered in the process-wide
            # adapter. Treat that request as an idempotent lookup; re-importing the module would
            # duplicate AutoWorld registrations and other package-level global mutations.
            if self._loaded_package_metadata is None:
                raise TrackerAdapterError(
                    f"APWorld '{package_root}' is loaded without reusable package metadata."
                )
            LOGGER.info("Reusing loaded mobile UT APWorld %s", path.name)
            return dict(self._loaded_package_metadata)
        if self._loaded_package_root == package_root:
            raise TrackerAdapterError(
                f"A different APWorld archive for '{package_root}' is already loaded. "
                "Create a new interpreter before replacing it."
            )
        raise TrackerAdapterError(
            "An APWorld is already loaded. Create a new interpreter before switching worlds."
        )

    requirements = _read_requirements(path)
    unavailable = [requirement for requirement in requirements if not _requirement_is_available(requirement)]
    if unavailable:
        raise TrackerAdapterError(
            "APWorld requires Python dependencies that are not bundled in this mobile runtime: "
            + ", ".join(unavailable)
        )

    module_name = f"worlds.{package_root}"
    try:
        importer = zipimport.zipimporter(str(path))
        spec = importer.find_spec(module_name)
        if spec is None:
            raise TrackerAdapterError(
                f"APWorld '{path.name}' does not expose package root '{package_root}'."
            )
        module = importlib.util.module_from_spec(spec)
        sys.modules[module_name] = module
        with _local_rule_builder_fallback(path, package_root):
            importer.exec_module(module)
        _apply_world_manifest(path, package_root)
    except TrackerAdapterError:
        raise
    except Exception as error:
        sys.modules.pop(module_name, None)
        raise TrackerAdapterError(f"Could not load APWorld '{path.name}': {error}") from error

    games = sorted(
        game
        for game, world_type in AutoWorld.AutoWorldRegister.world_types.items()
        if world_type.__module__ == module_name or world_type.__module__.startswith(f"{module_name}.")
    )
    if not games:
        raise TrackerAdapterError(f"APWorld '{path.name}' did not register a World class.")
    metadata = {
        "package_root": package_root,
        "games": games,
        "requires_yaml_by_game": {
            game: not bool(getattr(AutoWorld.AutoWorldRegister.world_types[game], "ut_can_gen_without_yaml", False))
            for game in games
        },
        "requirements": requirements,
    }
    self._loaded_package_root = package_root
    self._loaded_package_path = path
    self._loaded_package_metadata = metadata
    LOGGER.info("Loaded mobile UT APWorld %s games=%s", path.name, games)
    return dict(metadata)


def _apply_world_manifest(path: Path, package_root: str) -> None:
    """Mirror upstream manifest registration without scanning unrelated worlds."""
    with zipfile.ZipFile(path) as archive:
        manifest_path = next(
            (name for name in (f"{package_root}/archipelago.json", "archipelago.json")
             if name in archive.namelist()),
            None,
        )
        if manifest_path is None:
            return
        manifest = json.loads(archive.read(manifest_path))
    world_type = AutoWorld.AutoWorldRegister.world_types.get(manifest.get("game"))
    if world_type is not None:
        from Utils import tuplize_version
        world_type.world_version = tuplize_version(manifest.get("world_version", "0.0.0"))
        world_type.manifest = manifest


@contextmanager
def _local_rule_builder_fallback(path: Path, package_root: str):
    """Let APWorlds with a bundled legacy rule_builder use their own fallback import.

    Some APWorlds catch ModuleNotFoundError from `from rule_builder import ...` and then
    import `.rule_builder`. The pinned core has a newer rule_builder package, so a missing
    legacy export raises ImportError instead and prevents that package-owned fallback.
    Apply the compatibility rule only while importing an APWorld that bundles this module.
    """
    with zipfile.ZipFile(path) as archive:
        has_local_fallback = f"{package_root}/rule_builder.py" in archive.namelist()
    if not has_local_fallback:
        yield
        return

    original_import = builtins.__import__

    def import_with_local_fallback(name, globals=None, locals=None, fromlist=(), level=0):
        imported = original_import(name, globals, locals, fromlist, level)
        if (
            level == 0
            and name == "rule_builder"
            and fromlist
            and any(member != "*" and not hasattr(imported, member) for member in fromlist)
        ):
            LOGGER.debug("Using APWorld-bundled rule_builder fallback for %s", package_root)
            raise ModuleNotFoundError("No compatible core rule_builder", name="rule_builder")
        return imported

    builtins.__import__ = import_with_local_fallback
    try:
        yield
    finally:
        builtins.__import__ = original_import


def _normalized_package_path(path: Path, package_root: str) -> Path:
    """Return a private lowercase archive when a legacy APWorld uses casing inconsistently.

    Archipelago package imports are case-sensitive and frozen Python rejects uppercase
    APWorld names. Keep the original user archive untouched; only rewrite the one root
    path segment needed for the private runtime copy.
    """
    try:
        with zipfile.ZipFile(path) as archive:
            roots = {
                name.split("/", 1)[0]
                for name in archive.namelist()
                if name.endswith("/__init__.py")
                and name.split("/", 1)[0].casefold() == package_root.casefold()
            }
            if len(roots) != 1:
                raise TrackerAdapterError(
                    f"APWorld '{path.name}' does not expose package root '{package_root}'."
                )
            archive_root = roots.pop()
            if archive_root == package_root and path.name == f"{package_root}.apworld":
                return path
            identity = f"{path.resolve()}:{path.stat().st_mtime_ns}:{path.stat().st_size}"
            digest = hashlib.sha256(identity.encode()).hexdigest()[:16]
            cache_directory = Path(tempfile.gettempdir()) / "packsanity-apworlds"
            cache_directory.mkdir(parents=True, exist_ok=True)
            normalized = cache_directory / f"{package_root}-{digest}.apworld"
            if normalized.is_file():
                return normalized
            with zipfile.ZipFile(normalized, "w", zipfile.ZIP_DEFLATED) as destination:
                for entry in archive.infolist():
                    name = entry.filename
                    if name == archive_root or name.startswith(f"{archive_root}/"):
                        name = package_root + name[len(archive_root):]
                    destination.writestr(name, archive.read(entry.filename))
            LOGGER.info("Normalized legacy APWorld casing %s to %s", path.name, normalized.name)
            return normalized
    except TrackerAdapterError:
        raise
    except (OSError, zipfile.BadZipFile) as error:
        raise TrackerAdapterError(f"Could not normalize APWorld '{path.name}': {error}") from error


def verify_tracker_package(tracker_apworld_path: str) -> dict[str, Any]:
    """Validate the installed upstream UT package without importing desktop-only modules."""
    path = Path(tracker_apworld_path)
    if path.name != "tracker.apworld" or not path.is_file():
        raise TrackerAdapterError("The installed Universal Tracker package is missing or invalid.")
    try:
        with zipfile.ZipFile(path) as archive:
            manifest = json.loads(archive.read("tracker/archipelago.json"))
            archive.getinfo("tracker/TrackerCore.py")
            init_source = archive.read("tracker/__init__.py").decode("utf-8")
    except (OSError, KeyError, zipfile.BadZipFile, json.JSONDecodeError) as error:
        raise TrackerAdapterError(f"Could not verify Universal Tracker package: {error}") from error
    if manifest.get("game") != "Universal Tracker":
        raise TrackerAdapterError("Installed tracker.apworld is not a Universal Tracker package.")
    version_match = re.search(r'^UT_VERSION\s*=\s*["\']v([^"\']+)["\']', init_source, re.MULTILINE)
    if version_match is None:
        raise TrackerAdapterError("Installed tracker.apworld does not declare a Universal Tracker version.")
    return {
        "game": manifest["game"],
        "container_version": manifest.get("version"),
        "version": version_match.group(1),
    }

