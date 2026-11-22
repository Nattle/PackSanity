"""Stage the headless routines from PackSanity's pinned Universal Tracker release."""

import ast
import hashlib
from io import BytesIO
from pathlib import Path
from zipfile import ZipFile


def assigned(statements, name):
    return next(
        statement for statement in statements
        if isinstance(statement, ast.Assign)
        and any(isinstance(target, ast.Name) and target.id == name for target in statement.targets)
    )


def tracker_core_source(archive_path: Path, pin: dict) -> str:
    release = archive_path.read_bytes()
    if hashlib.sha256(release).hexdigest() != pin["sha256"]:
        raise RuntimeError("Universal Tracker release checksum does not match the runtime lock")
    try:
        with ZipFile(BytesIO(release)) as archive:
            package = ast.parse(archive.read("tracker/__init__.py"))
            module = ast.parse(archive.read("tracker/TrackerCore.py"))
        mode = next(node for node in package.body if isinstance(node, ast.ClassDef) and node.name == "DeferredEntranceMode")
        core = next(node for node in module.body if isinstance(node, ast.ClassDef) and node.name == "TrackerCore")
        methods = {node.name: node for node in core.body if isinstance(node, ast.FunctionDef)}

        generation = methods["TMain"]
        stages = next(statement for statement in generation.body if isinstance(statement, ast.For))
        set_rules = next(
            statement for statement in stages.body
            if isinstance(statement, ast.If) and ast.unparse(statement.test) == "step == 'set_rules'"
        )
        # Desktop UT gets verified YAML options from Archipelago's generator.
        generation.body.insert(generation.body.index(stages), ast.parse("self.verify_exclusions(multiworld)").body[0])
        # Worlds can restore group names during generation. Let UT's exclusion_rules
        # resolve the expanded names against the generated graph, including scripted locations.
        set_rules.body.insert(0, ast.parse("self.expand_exclusion_groups(multiworld)").body[0])

        update = methods["updateTracker"].body
        loop = next(statement for statement in update if isinstance(statement, ast.For))
        collect = next(statement for statement in loop.body if isinstance(statement, ast.Try))
        sweep = next(
            statement for statement in update
            if isinstance(statement, ast.Expr) and isinstance(statement.value, ast.Call)
            and isinstance(statement.value.func, ast.Attribute)
            and statement.value.func.attr == "sweep_for_advancements"
        )
        # Let item collection errors reach the native caller. UT's desktop path logs them
        # and continues, which would publish a partial snapshot here.
        loop.body = collect.body
        replay = ast.parse("def replay_items(self):\n    return state, all_items").body[0]
        replay.body[:0] = [
            *(assigned(update, name) for name in ("state", "prog_items", "all_items", "item_id_to_name", "location_id_to_name")),
            loop, sweep,
        ]
    except (KeyError, StopIteration) as error:
        raise RuntimeError("Pinned Universal Tracker release has an unexpected TrackerCore layout") from error

    header = f"""# Generated from Universal Tracker {pin['version']} ({pin['sha256']}).
# See notices/UniversalTracker-MIT.txt.
from collections import Counter
from enum import Enum
from BaseClasses import CollectionState, ItemClassification, MultiWorld
from worlds import AutoWorld
from worlds.generic.Rules import exclusion_rules

"""
    functions = "\n\n\n".join(ast.unparse(node) for node in (mode, generation, replay))
    return header + functions + "\n"
