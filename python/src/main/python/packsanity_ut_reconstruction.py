"""Tracker-only fake-generation stages, option values, and bounded player YAML support."""
from __future__ import annotations
from argparse import Namespace
from collections.abc import Hashable
from pathlib import Path
from typing import Any
import yaml
from BaseClasses import MultiWorld
from worlds import AutoWorld
from packsanity_ut_core import fake_generate
from packsanity_ut_models import TrackerAdapterError


class _PlayerYamlLoader(yaml.SafeLoader):
    """Check explicit keys before PyYAML resolves merges and overrides."""

    _merge_key = object()

    def __init__(self, stream):
        super().__init__(stream)
        self._checked_mappings = set()

    def flatten_mapping(self, node):
        # PyYAML can visit a mapping both as an alias and as a merge source.
        if node not in self._checked_mappings:
            self._checked_mappings.add(node)
            keys = set()
            for key_node, _ in node.value:
                if key_node.tag == "tag:yaml.org,2002:merge":
                    key = self._merge_key
                elif key_node.tag == "tag:yaml.org,2002:value":
                    key = key_node.value
                else:
                    key = self.construct_object(key_node)
                if not isinstance(key, Hashable):
                    raise yaml.constructor.ConstructorError(
                        "while constructing a mapping", node.start_mark, "found unhashable key", key_node.start_mark
                    )
                if key in keys:
                    raise KeyError(f"Duplicate key {key_node.value} found in YAML.")
                # Match Archipelago's equivalent-key check for its +option syntax.
                name = str(key)
                if (name.startswith("+") and name[1:] in keys) or f"+{name}" in keys:
                    raise KeyError(f"Equivalent key {key} found in YAML.")
                keys.add(key)
        super().flatten_mapping(node)


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
) -> MultiWorld:
    world_type = AutoWorld.AutoWorldRegister.world_types[game]
    options = self._options_namespace(world_type, yaml_options)
    return fake_generate(game, slot_name, options, regen_data)


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
        payload = yaml.load(Path(yaml_path).read_text(encoding="utf-8"), Loader=_PlayerYamlLoader)
    except (OSError, KeyError, yaml.YAMLError) as error:
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

