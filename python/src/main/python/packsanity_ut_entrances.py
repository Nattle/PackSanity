"""Server-authoritative entrance pairing, discovery, and stored-data reconnection."""
from __future__ import annotations
from typing import Any, TYPE_CHECKING
from packsanity_ut_models import TrackerSession
if TYPE_CHECKING:
    from BaseClasses import CollectionState
    from worlds import AutoWorld


def _entrance_payload(
    world: AutoWorld.World, session: TrackerSession, state: CollectionState
) -> list[dict[str, Any]]:
    """Display server pairings; use the reconstructed graph only for matching access rules."""
    server_pairings = _server_entrance_pairings(world, session)
    if server_pairings is None:
        # A fake generation can create deterministic-looking links from its placeholder seed.
        return []
    seed_pairings = dict(server_pairings)
    decoupled = session.slot_data.get("decoupled")
    direction = None
    if isinstance(decoupled, (bool, int)) and decoupled in (0, 1, False, True):
        direction = "one_way" if decoupled else "coupled"
        if not decoupled:
            targets = list(server_pairings.values())
            if len(targets) == len(set(targets)):
                for source, destination in server_pairings.items():
                    if destination not in seed_pairings:
                        seed_pairings[destination] = source
    reconstructed_pairings = getattr(world, "entrance_pairings", None)
    storage_key_by_source = _entrance_storage_key_by_source(
        world, session, server_pairings
    )
    discovery = _entrance_discovery(
        world, session, seed_pairings, storage_key_by_source
    )
    if direction == "coupled":
        for source, destination in server_pairings.items():
            if discovery.get(source) is True or discovery.get(destination) is True:
                discovery[source] = discovery[destination] = True

    regions = session.multiworld.get_regions(session.player)
    result = []
    for source, destination in seed_pairings.items():
        if not isinstance(source, str) or not isinstance(destination, str):
            continue
        matching_exits = []
        if isinstance(reconstructed_pairings, dict) and reconstructed_pairings.get(source) == destination:
            matching_exits = [
                exit
                for region in regions
                if region.name == source
                for exit in region.exits
                if exit.connected_region is not None and exit.connected_region.name == destination
            ]
        storage_key = storage_key_by_source.get(source, "")
        observed_pairings = (session.entrance_pairings_by_storage_key or {}).get(storage_key)
        observed = (
            (session.entrances_by_storage_key or {}).get(storage_key, [])
            if observed_pairings == server_pairings else []
        )
        if len(matching_exits) == 1:
            reachable = state.can_reach(matching_exits[0])
        elif not matching_exits and discovery.get(source) is True and observed:
            reachable = any(state.can_reach(exit) for exit in observed)
        else:
            reachable = None
        result.append(
            {
                "id": source,
                "source_region": source,
                "destination_region": destination,
                "discovered": discovery.get(source),
                "reachable": reachable,
                "direction": direction,
            }
        )
    return sorted(result, key=lambda entrance: entrance["source_region"])


def _resolved_entrance_keys(world: AutoWorld.World, session: TrackerSession) -> list[str]:
    keys = getattr(world, "found_entrances_datastorage_key", ())
    if isinstance(keys, str):
        keys = (keys,)
    return [key.format(player=session.slot, team=session.team or 0) for key in keys]


def _server_entrance_pairings(
    world: AutoWorld.World, session: TrackerSession
) -> dict[str, str] | None:
    """Find an authoritative slot-data table, never a reconstructed or generated one.

    APWorlds can name the field explicitly. Older worlds may instead register one discovery
    storage key per server pairing. Accept that structural convention only when exactly one
    slot-data object matches the declared keys; ambiguous objects remain unsupported.
    """
    tracker_world = getattr(world, "tracker_world", {})
    configured = (
        tracker_world.get("entrance_pairings_slot_data_key")
        if isinstance(tracker_world, dict)
        else getattr(tracker_world, "entrance_pairings_slot_data_key", None)
    )
    for key in (configured, "entrance_pairings"):
        value = session.slot_data.get(key) if isinstance(key, str) else None
        if isinstance(value, dict):
            return value

    storage_keys = _resolved_entrance_keys(world, session)
    if not storage_keys:
        return None
    candidates = []
    for value in session.slot_data.values():
        if not isinstance(value, dict) or not value:
            continue
        if not all(isinstance(source, str) and isinstance(target, str) for source, target in value.items()):
            continue
        exact_matches = sum(
            any(storage_key.endswith(":" + source) for storage_key in storage_keys)
            for source in value
        )
        if exact_matches > len(value) // 2:
            candidates.append(value)
    return candidates[0] if len(candidates) == 1 else None


def _entrance_discovery(
    world: AutoWorld.World,
    session: TrackerSession,
    pairings: dict[str, str],
    by_source: dict[str, str],
) -> dict[str, bool | None]:
    keys = _resolved_entrance_keys(world, session)
    values = session.stored_data or {}
    discovery: dict[str, bool | None] = {}
    list_keys = [key for key in keys if isinstance(values.get(key), list)]
    if list_keys:
        found = {name for key in list_keys for name in values[key] if isinstance(name, str)}
        for source in pairings:
            discovery[source] = source in found

    for source, key in by_source.items():
        if key in values and not isinstance(values[key], list):
            discovery[source] = bool(values[key])
    return discovery


def _entrance_storage_key_by_source(
    world: AutoWorld.World, session: TrackerSession, pairings: dict[str, str]
) -> dict[str, str]:
    keys = _resolved_entrance_keys(world, session)
    direct = {
        source: matching[0]
        for source in pairings
        if len(matching := [key for key in keys if key.endswith(":" + source)]) == 1
    }
    if not pairings:
        return direct

    # Some worlds register source and target storage keys while iterating their server
    # pairing table. Use the order only after the overwhelming majority of source keys
    # independently match, so exceptional key spellings can still be associated safely.
    stride = 1 if len(keys) == len(pairings) else 2 if len(keys) == 2 * len(pairings) else 0
    if stride:
        aligned = list(zip(pairings, keys[::stride]))
        exact = sum(key.endswith(":" + source) for source, key in aligned)
        target_exact = (
            sum(
                keys[index * 2 + 1].endswith(":" + destination)
                for index, destination in enumerate(pairings.values())
            )
            if stride == 2 else len(pairings)
        )
        threshold = max(2, (len(pairings) * 4 + 4) // 5)
        if exact >= threshold and target_exact >= threshold:
            by_source = dict(aligned)
            if stride == 2:
                by_source.update(
                    (destination, keys[index * 2 + 1])
                    for index, destination in enumerate(pairings.values())
                    if destination not in by_source
                )
            return by_source
    return direct


def _apply_stored_data(self, session: TrackerSession, values: dict[str, Any]) -> None:
    world = session.multiworld.worlds[session.player]
    reconnect = getattr(world, "reconnect_found_entrances", None)
    if session.stored_data is None:
        session.stored_data = {}
    keys = getattr(world, "found_entrances_datastorage_key", ())
    if isinstance(keys, str):
        keys = (keys,)
    entrance_keys = {key.format(player=session.slot, team=session.team or 0) for key in keys}
    for key, value in values.items():
        changed = session.stored_data.get(key) != value
        session.stored_data[key] = value
        if changed and key in entrance_keys and callable(reconnect):
            disconnected = (
                [exit for exit in session.multiworld.get_entrances(session.player)
                 if exit.connected_region is None]
                if value else []
            )
            reconnect(key, value)
            newly_connected = [exit for exit in disconnected if exit.connected_region is not None]
            if newly_connected:
                if session.entrances_by_storage_key is None:
                    session.entrances_by_storage_key = {}
                session.entrances_by_storage_key[key] = newly_connected
                if session.entrance_pairings_by_storage_key is None:
                    session.entrance_pairings_by_storage_key = {}
                session.entrance_pairings_by_storage_key[key] = dict(
                    self._server_entrance_pairings(world, session) or {}
                )

