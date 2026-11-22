"""Adapt Universal Tracker's staged routines to PackSanity's single-world runtime."""
from __future__ import annotations
from argparse import Namespace
from collections import Counter
from collections.abc import Sequence
from types import SimpleNamespace
from typing import Any

from BaseClasses import CollectionState, MultiWorld
from NetUtils import NetworkItem
import packsanity_ut_upstream as upstream
from packsanity_ut_models import TrackerAdapterError


def _verify_exclusions(multiworld: MultiWorld) -> None:
    world = multiworld.worlds[1]
    exclusions = world.options.exclude_locations
    if exclusions.value:
        try:
            exclusions.verify(world, multiworld.player_name[1], multiworld.plando_options)
        except ModuleNotFoundError as error:
            if error.name != "jellyfish":
                raise
            invalid = ", ".join(sorted(exclusions.value - world.location_names))
            raise TrackerAdapterError(f"Unknown excluded locations: {invalid}") from error


def _expand_exclusion_groups(multiworld: MultiWorld) -> None:
    world = multiworld.worlds[1]
    exclusions = world.options.exclude_locations
    exclusions.value = {
        location for name in exclusions.value for location in world.location_name_groups.get(name, {name})
    }


def fake_generate(
    game: str,
    slot_name: str,
    options: Namespace,
    re_gen_passthrough: dict[str, Any] | None,
) -> MultiWorld:
    args = Namespace(**vars(options))
    args.multi, args.race, args.outputname = 1, False, None
    args.game, args.name = {1: game}, {1: slot_name}
    tracker = SimpleNamespace(
        re_gen_passthrough={game: re_gen_passthrough} if re_gen_passthrough is not None else None,
        enforce_deferred_connections=upstream.DeferredEntranceMode.default,
        verify_exclusions=_verify_exclusions,
        expand_exclusion_groups=_expand_exclusion_groups,
    )
    return upstream.TMain(tracker, args, seed=0)


def replay_inventory(
    multiworld: MultiWorld,
    player_id: int,
    slot: int,
    items: Sequence[NetworkItem],
) -> tuple[CollectionState, Counter[str]]:
    known_items = multiworld.worlds[player_id].item_id_to_name
    for item in items:
        if item.item > 0 and known_items.get(item.item) is None:
            raise TrackerAdapterError(
                f"APWorld '{multiworld.game[player_id]}' does not define item id {item.item}."
            )
    tracker = SimpleNamespace(
        multiworld=multiworld,
        player_id=player_id,
        slot=slot,
        tracker_items_received=items,
        manual_items=(),
        enforce_deferred_connections=upstream.DeferredEntranceMode.default,
    )
    return upstream.replay_items(tracker)
