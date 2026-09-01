"""Tracker-only fake-generation stages, option values, and bounded player YAML support."""
from __future__ import annotations
from argparse import Namespace
from pathlib import Path
from typing import Any
import yaml
from BaseClasses import CollectionState, MultiWorld
from worlds import AutoWorld
from worlds.generic.Rules import exclusion_rules
from packsanity_ut_models import TrackerAdapterError

GENERATION_STEPS = (
    "generate_early",
    "create_regions",
    "create_items",
    "set_rules",
    "connect_entrances",
    "generate_basic",
)


def _static_class_attribute(world_type: type[Any], name: str) -> Any:
    """Find a class attribute without importing `inspect` or invoking descriptors.

    `inspect.getattr_static` imports `dis`, which imports the optional `_opcode` extension.
    The iOS runtime links extension modules statically and does not expose them as loadable
    resources, so this narrow MRO lookup preserves the needed static/classmethod distinction
    without requiring that desktop-oriented module.
    """
    for candidate in world_type.__mro__:
        if name in candidate.__dict__:
            return candidate.__dict__[name]
    return None


def _build_multiworld(
    self,
    game: str,
    slot_name: str,
    yaml_options: dict[str, Any],
    regen_data: dict[str, Any] | None,
    deferred_connections: str = "off",
) -> MultiWorld:
    world_type = AutoWorld.AutoWorldRegister.world_types[game]
    options = self._options_namespace(world_type, yaml_options)
    multiworld = MultiWorld(1)
    multiworld.generation_is_fake = True
    if regen_data is not None:
        multiworld.re_gen_passthrough = {game: regen_data}
    # During fake reconstruction, worlds must apply the server's declared plando/ER
    # connections immediately. Some UT worlds explicitly gate that replay on "off".
    multiworld.enforce_deferred_connections = deferred_connections
    multiworld.set_seed(0, False, None)
    multiworld.game = {1: game}
    multiworld.player_name = {1: slot_name}
    multiworld.set_options(options)
    multiworld.state = CollectionState(multiworld)
    world = multiworld.worlds[1]

    def expand_exclusions():
        # Archipelago expands LocationSet groups during option verification.
        option = world.options.exclude_locations
        expanded = set()
        for name in option.value:
            expanded.update(world.location_name_groups.get(name, {name}))
        option.value = expanded

    expand_exclusions()
    for step in GENERATION_STEPS:
        if not hasattr(AutoWorld.World, step):
            continue
        AutoWorld.call_all(multiworld, step)
        if step == "set_rules":
            # A world can restore raw exclusions from server slot data during generation.
            expand_exclusions()
            exclusion_rules(multiworld, 1, world.options.exclude_locations.value)
    return multiworld


def _options_namespace(self, world_type: type[AutoWorld.World], values: dict[str, Any]) -> Namespace:
    options = {}
    for name, option_type in world_type.options_dataclass.type_hints.items():
        raw_value = values.get(name, option_type.default)
        options[name] = {1: option_type.from_any(raw_value)}
    return Namespace(**options)


def _load_yaml_options(self, yaml_path: str | None, game: str, slot_name: str) -> dict[str, Any]:
    if yaml_path is None:
        return {}
    try:
        payload = yaml.safe_load(Path(yaml_path).read_text(encoding="utf-8"))
    except (OSError, yaml.YAMLError) as error:
        raise TrackerAdapterError(f"Could not read player YAML: {error}") from error
    if not isinstance(payload, dict):
        raise TrackerAdapterError("Player YAML must contain an object.")
    unsupported_constructs = sorted(
        key
        for key in (
            "linked_options",
            "meta",
            "plando_connections",
            "plando_items",
            "plando_text",
            "requires",
            "trigger",
        )
        if key in payload
    )
    if unsupported_constructs:
        raise TrackerAdapterError(
            "Player YAML uses Archipelago generation constructs not yet supported by the "
            "mobile tracker runtime: " + ", ".join(unsupported_constructs)
        )
    yaml_game = payload.get("game")
    if yaml_game is not None and yaml_game != game:
        raise TrackerAdapterError(f"Player YAML game '{yaml_game}' does not match connected game '{game}'.")
    yaml_name = payload.get("name")
    if yaml_name is not None and yaml_name != slot_name:
        raise TrackerAdapterError(f"Player YAML name '{yaml_name}' does not match connected slot '{slot_name}'.")
    game_options = payload.get(game, {})
    if game_options is None:
        game_options = {}
    if not isinstance(game_options, dict):
        raise TrackerAdapterError(f"Player YAML section '{game}' must contain an object.")
    return {key: value for key, value in payload.items() if key not in {"name", "game"}} | game_options

