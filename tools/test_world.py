#!/usr/bin/env python3
"""Real APWorld import, fake generation and update checks using the owned staged adapter."""
import argparse
from contextlib import ExitStack, redirect_stdout
import hashlib
import json
from pathlib import Path
import stat
import subprocess
import sys
from tempfile import TemporaryDirectory
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def load_json(path):
    return json.loads(path.read_text()) if path else {}


def expect(actual, expected, path="state"):
    if isinstance(expected, dict):
        for key, value in expected.items():
            if key not in actual:
                raise RuntimeError(f"{path}.{key} missing")
            expect(actual[key], value, f"{path}.{key}")
    elif actual != expected:
        raise RuntimeError(f"{path} mismatch: expected {expected!r}, actual {actual!r}")


def quiet(call, *args, **kwargs):
    with redirect_stdout(sys.stderr):
        return call(*args, **kwargs)


def archive_world_directory(source: Path, destination: Path) -> Path:
    if not (source / "__init__.py").is_file():
        raise ValueError(f"APWorld package directory must contain __init__.py: {source}")
    package_root = source.name.lower()
    world = destination / f"{package_root}.apworld"
    with zipfile.ZipFile(world, "w", zipfile.ZIP_DEFLATED, strict_timestamps=False) as archive:
        pending = [source]
        while pending:
            for entry in sorted(pending.pop().iterdir()):
                if entry == destination or entry.name in {".git", "__pycache__"}:
                    continue
                mode = entry.lstat().st_mode
                if stat.S_ISLNK(mode):
                    raise ValueError(f"APWorld package directory contains a symbolic link: {entry}")
                if stat.S_ISDIR(mode):
                    pending.append(entry)
                elif not stat.S_ISREG(mode):
                    raise ValueError(f"APWorld package directory contains a special file: {entry}")
                archive.write(entry, f"{package_root}/{entry.relative_to(source).as_posix()}")
    return world


def check_world(args, source: Path):
    with ExitStack() as cleanup:
        world = source
        if source.is_dir():
            destination = Path(cleanup.enter_context(TemporaryDirectory(prefix="packsanity-world-"))).resolve()
            world = archive_world_directory(source, destination)
        gate = subprocess.run([str(ROOT / "cli/build/install/cli/bin/cli"), "inspect-world", str(world)],
                              text=True, capture_output=True, timeout=30)
        if gate.returncode:
            raise RuntimeError((gate.stderr.strip() or gate.stdout.strip()).removeprefix("FAIL "))
        inspected = json.loads(gate.stdout)
        sys.path.insert(0, str(ROOT / "build/python/payload"))
        from packsanity_ut_adapter import MobileUniversalTracker
        tracker = MobileUniversalTracker()
        cleanup.callback(tracker.close)
        metadata = quiet(tracker.load_world, str(world), inspected["root"])
        game = args.game
        if game is None:
            if len(metadata["games"]) != 1:
                raise RuntimeError("Archive registers several games; select --game")
            game = metadata["games"][0]
        if game not in metadata["games"]:
            raise RuntimeError(f"Game {game!r} is absent from {metadata['games']}")
        fixture = load_json(args.fixture)
        slot_data = load_json(args.slot_data) if args.slot_data else fixture.get("slot_data", {})
        result = quiet(tracker.initialize, game=game, slot=args.slot, slot_name=args.slot_name, slot_data=slot_data,
                                    yaml_path=str(args.yaml.resolve()) if args.yaml else None,
                                    external_pack_path=str(args.external_pack.resolve()) if args.external_pack else None)
        updates = fixture.get("updates", [])
        snapshots = [result]
        for update in updates:
            state = update.get("state", update)
            missing_locations = state.get("missing_locations")
            result = quiet(tracker.update, received_items=state.get("received_items", []),
                                    checked_locations=set(state.get("checked_locations", [])),
                                    missing_locations=set(missing_locations) if missing_locations is not None else None,
                                    stored_data=state.get("stored_data", {}), slot_data=state.get("slot_data"))
            snapshots.append(result)
            expect(result, update.get("expect", {}))
        report = {"status": "passed", "world": str(source), "sha256": hashlib.sha256(world.read_bytes()).hexdigest(),
                  "metadata": metadata, "locations": len(result["locations"]), "regions": len(result["regions"]),
                  "inventory": len(result["inventory"]), "maps": len((result.get("map") or {}).get("maps", [])),
                  "state": result, "updates": len(updates)}
        # Use the same normalized archive that was actually imported by the adapter.
        with zipfile.ZipFile(tracker._loaded_package_path) as archive:
            world_names = set(archive.namelist())
            companion_names = set()
            if args.external_pack:
                with zipfile.ZipFile(args.external_pack) as companion:
                    companion_names = set(companion.namelist())
            def validate_image(image):
                if not image:
                    return
                if image.startswith("external-pack/"):
                    if image.removeprefix("external-pack/") not in companion_names:
                        raise RuntimeError(f"Map image not found in companion: {image}")
                elif image not in world_names:
                    raise RuntimeError(f"Map image not found in APWorld: {image}")
            for snapshot in snapshots:
                map_state = snapshot.get("map") or {}
                for map_item in map_state.get("maps", []):
                    validate_image(map_item.get("image_path"))
                for marker in map_state.get("player_markers", []):
                    validate_image(marker.get("icon_path"))
        expect(report, fixture.get("expect", {}), "report")
        print(json.dumps(report, indent=2) if args.json else
              f"PASS {game} locations={report['locations']} regions={report['regions']} maps={report['maps']} updates={len(updates)}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("world", type=Path, help=".apworld archive or package directory containing __init__.py")
    parser.add_argument("--game")
    parser.add_argument("--slot-data", type=Path)
    parser.add_argument("--yaml", type=Path)
    parser.add_argument("--external-pack", type=Path)
    parser.add_argument("--fixture", type=Path, help="JSON full server-state updates and expected output")
    parser.add_argument("--slot", type=int, default=1)
    parser.add_argument("--slot-name", default="PackSanity")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    try:
        check_world(args, args.world.resolve())
    except Exception as error:
        print(f"FAIL {str(error) or type(error).__name__}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
