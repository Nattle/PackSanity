"""Live inventory projections, logic cache signatures, and required storage subscriptions."""
from __future__ import annotations
from collections import Counter
import json
from typing import Any, TYPE_CHECKING
from packsanity_ut_entrances import _resolved_entrance_keys
from packsanity_ut_models import TrackerSession, tracker_setting
if TYPE_CHECKING:
    from NetUtils import NetworkItem
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
    received_items: tuple[NetworkItem, ...],
    checked_locations: set[int],
    missing_locations: set[int] | None,
) -> tuple[Any, ...]:
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
        for key in sorted(_resolved_entrance_keys(world, session))
    )
    return (
        received_items,
        tuple(sorted(checked_locations)),
        tuple(sorted(missing_locations)) if missing_locations is not None else None,
        entrance_values,
    )


def _stored_data_keys(world: AutoWorld.World, session: TrackerSession) -> list[str]:
    keys = set(_resolved_entrance_keys(world, session))
    tracker_world = getattr(world, "tracker_world", None)
    keys.update(
        value.format(player=session.slot, team=session.team or 0)
        for value in (
            tracker_setting(tracker_world, "map_page_setting_key"),
            tracker_setting(tracker_world, "location_setting_key"),
        )
        if isinstance(value, str)
    )
    return sorted(keys)

