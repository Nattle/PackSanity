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


def function(signature, body):
    definition = ast.parse(f"def {signature}:\n    pass").body[0]
    definition.body = body
    return definition


def map_helpers(client):
    context = next(node for node in client.body if isinstance(node, ast.ClassDef) and node.name == "TrackerGameContext")
    load_map = next(node for node in context.body if isinstance(node, ast.FunctionDef) and node.name == "load_map")
    alias_branch = next(
        node for node in load_map.body
        if isinstance(node, ast.If) and isinstance(node.test, ast.Name) and node.test.id == "poptracker_entrance_mapping"
    )
    matches = (
        ("entrance", assigned(load_map.body, "dcoords")),
        ("entrance", assigned(alias_branch.body, "tempCoords")),
        ("event", assigned(load_map.body, "dlcoords")),
    )
    body = []
    for kind, assignment in matches:
        # Use UT's inner section filter for one section at a time. PackSanity supplies
        # geometry and preserves section labels when two placements share coordinates.
        sections = assignment.value.value.elts[0]
        generator, = sections.generators
        if ast.unparse(generator.target) != "section" or ast.unparse(generator.iter) != "location['sections']":
            raise ValueError("Unexpected TrackerClient map section filter")
        condition, = generator.ifs
        match = ast.Tuple(elts=[ast.Constant(kind), sections.elt], ctx=ast.Load())
        body.append(ast.If(test=condition, body=[ast.Expr(value=ast.Yield(value=match))], orelse=[]))
    match = function(
        "map_graph_matches(section, entrance_cache, current_hidden_entrances, "
        "poptracker_entrance_mapping, event_loc_cache, current_hidden_events)",
        body,
    )
    update = next(node for node in context.body if isinstance(node, ast.FunctionDef) and node.name == "updateTracker")
    classifiers = []
    for kind, loop_var, item_arg in (("entrance", "entrance_name", "temp_entrance"), ("event", "loc", "loc")):
        loop = next(node for node in ast.walk(update)
                    if isinstance(node, ast.For) and isinstance(node.target, ast.Name) and node.target.id == loop_var)
        branch = next(statement for statement in loop.body
                      if isinstance(statement, ast.If) and isinstance(statement.test, ast.Call)
                      and isinstance(statement.test.func, ast.Attribute) and statement.test.func.attr == "can_reach")
        classifiers.append(function(
            f"map_{kind}_status({item_arg}, updateTracker_ret)",
            [branch, ast.parse("return status").body[0]],
        ))
    return (match, *classifiers)


def tracker_core_source(archive_path: Path, pin: dict) -> str:
    release = archive_path.read_bytes()
    if hashlib.sha256(release).hexdigest() != pin["sha256"]:
        raise RuntimeError("Universal Tracker release checksum does not match the runtime lock")
    try:
        with ZipFile(BytesIO(release)) as archive:
            package = ast.parse(archive.read("tracker/__init__.py"))
            module = ast.parse(archive.read("tracker/TrackerCore.py"))
            client = ast.parse(archive.read("tracker/TrackerClient.py"))
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

        generator = next(node for node in methods["run_generator"].body if isinstance(node, ast.Try)).body
        start = generator.index(assigned(generator, "temp_precollect"))
        end = next(index for index, statement in enumerate(generator[start:], start)
                   if isinstance(statement, ast.Assign)
                   and ast.unparse(statement.targets[0]) == "self.multiworld.precollected_items")
        discard_items = function("discard_generated_network_items(self)", generator[start:end + 1])

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
        replay = function("replay_items(self)", [
            *(assigned(update, name) for name in (
                "state", "prog_items", "all_items", "item_id_to_name", "location_id_to_name", "invalid_items"
            )),
            ast.parse(
                'if invalid_items:\n'
                '    raise TrackerAdapterError(f"APWorld \'{self.multiworld.game[self.player_id]}\' '
                'does not define item id {invalid_items[0]}.")'
            ).body[0],
            loop, sweep,
            ast.parse("return state, all_items").body[0],
        ])
        map_routines = map_helpers(client)
    except (AttributeError, IndexError, KeyError, StopIteration, ValueError) as error:
        raise RuntimeError("Pinned Universal Tracker release has an unexpected source layout") from error

    header = f"""# Generated from Universal Tracker {pin['version']} ({pin['sha256']}).
# See notices/UniversalTracker-MIT.txt.
from collections import Counter
from enum import Enum
from BaseClasses import CollectionState, ItemClassification, MultiWorld
from packsanity_ut_models import TrackerAdapterError
from worlds import AutoWorld
from worlds.generic.Rules import exclusion_rules

"""
    functions = "\n\n\n".join(ast.unparse(node) for node in (mode, generation, discard_items, replay, *map_routines))
    return header + functions + "\n"
