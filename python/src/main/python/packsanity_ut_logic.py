"""Live inventory projections, logic cache signatures, and required storage subscriptions."""
from __future__ import annotations
from collections import Counter
import json
from typing import Any, TYPE_CHECKING
from packsanity_ut_models import TrackerSession
if TYPE_CHECKING:
    from worlds import AutoWorld


def _inventory_payload(world: AutoWorld.World, inventory: Counter[str]) -> list[dict[str, Any]]:
    """Expose every APWorld item so mobile can show acquired and unacquired tracker cards."""
    return [
        {"id": item_id, "name": name, "count": inventory.get(name, 0)}
        for name, item_id in sorted(world.item_name_to_id.items())
    ]


def _logic_signature(
    world: AutoWorld.World,
    session: TrackerSession,
    received_items: list[dict[str, Any]],
    checked_locations: set[int],
    missing_locations: set[int] | None,
) -> tuple[Any, ...]:
    entrance_keys = getattr(world, "found_entrances_datastorage_key", ())
    if isinstance(entrance_keys, str):
        entrance_keys = (entrance_keys,)
    resolved_entrance_keys = sorted(
        key.format(player=session.slot, team=session.team or 0) for key in entrance_keys
    )
    entrance_values = tuple(
        (
            key,
            key in (session.stored_data or {}),
            json.dumps(
                (session.stored_data or {}).get(key),
                sort_keys=True,
                separators=(",", ":"),
            ),
        )
        for key in resolved_entrance_keys
    )
    item_values = tuple(
        (
            int(item.get("item_id", 0)),
            int(item.get("location_id", 0)),
            int(item.get("player", 0)),
            int(item.get("flags", 0)),
        )
        for item in received_items
    )
    return (
        item_values,
        tuple(sorted(checked_locations)),
        tuple(sorted(missing_locations)) if missing_locations is not None else None,
        entrance_values,
    )


def _stored_data_keys(world: AutoWorld.World, session: TrackerSession) -> list[str]:
    keys = getattr(world, "found_entrances_datastorage_key", ())
    if isinstance(keys, str):
        keys = (keys,)
    tracker_world = getattr(world, "tracker_world", {})
    setting = (
        tracker_world.get
        if isinstance(tracker_world, dict)
        else lambda name, default=None: getattr(tracker_world, name, default)
    )
    keys = tuple(keys) + tuple(
        value
        for value in (
            setting("map_page_setting_key"),
            setting("location_setting_key"),
        )
        if isinstance(value, str)
    )
    return sorted({key.format(player=session.slot, team=session.team or 0) for key in keys})

