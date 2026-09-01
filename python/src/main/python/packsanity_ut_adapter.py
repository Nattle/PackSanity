"""JSON bridge and serialized session coordinator for the mobile Universal Tracker.

The native host owns networking. Responsibility modules provide package loading, reconstruction,
maps, entrances, and cache projections; this facade retains session ownership and bridge APIs.
"""
from __future__ import annotations
from collections import Counter
from copy import deepcopy
import json
from pathlib import Path
from typing import Any
import zipfile
from BaseClasses import CollectionState, ItemClassification, LocationProgressType
from worlds import AutoWorld
from packsanity_ut_models import LOGGER, TrackerAdapterError, TrackerSession
import packsanity_ut_requirements as requirements
import packsanity_ut_packages as packages
import packsanity_ut_maps as maps
import packsanity_ut_entrances as entrances
import packsanity_ut_reconstruction as reconstruction
import packsanity_ut_logic as logic


class MobileUniversalTracker:
    """Retain one serialized tracker per process/world; changing archives needs a new interpreter."""

    # Delegates preserve the facade's method contract; all mutable session state stays here.
    _read_requirements = staticmethod(requirements._read_requirements)
    _requirement_is_available = staticmethod(requirements._requirement_is_available)
    _version_satisfies = staticmethod(requirements._version_satisfies)
    _version_parts = staticmethod(requirements._version_parts)
    load_world = packages.load_world
    _local_rule_builder_fallback = staticmethod(packages._local_rule_builder_fallback)
    _normalized_package_path = staticmethod(packages._normalized_package_path)
    verify_tracker_package = staticmethod(packages.verify_tracker_package)
    _callable_parameter_count = staticmethod(maps._callable_parameter_count)
    _map_state = maps._map_state
    _normalize_player_markers = maps._normalize_player_markers
    _internal_asset_path = maps._internal_asset_path
    _map_asset_path = maps._map_asset_path
    _positive_float = staticmethod(maps._positive_float)
    _json_list = staticmethod(maps._json_list)
    _entrance_payload = staticmethod(entrances._entrance_payload)
    _resolved_entrance_keys = staticmethod(entrances._resolved_entrance_keys)
    _server_entrance_pairings = staticmethod(entrances._server_entrance_pairings)
    _entrance_discovery = staticmethod(entrances._entrance_discovery)
    _entrance_storage_key_by_source = staticmethod(entrances._entrance_storage_key_by_source)
    _apply_stored_data = entrances._apply_stored_data
    _static_class_attribute = staticmethod(reconstruction._static_class_attribute)
    _build_multiworld = reconstruction._build_multiworld
    _options_namespace = reconstruction._options_namespace
    _load_yaml_options = reconstruction._load_yaml_options
    _inventory_payload = staticmethod(logic._inventory_payload)
    _logic_signature = staticmethod(logic._logic_signature)
    _stored_data_keys = staticmethod(logic._stored_data_keys)

    def __init__(self) -> None:
        self._session: TrackerSession | None = None
        self._loaded_package_root: str | None = None
        self._loaded_package_path: Path | None = None
        self._loaded_package_metadata: dict[str, Any] | None = None

    def initialize(
        self,
        game: str,
        slot: int,
        slot_name: str,
        slot_data: dict[str, Any],
        yaml_path: str | None = None,
        team: int | None = None,
        external_pack_path: str | None = None,
    ) -> dict[str, Any]:
        yaml_options = self._load_yaml_options(yaml_path, game, slot_name) if yaml_path is not None else None
        session = self._reconstruct_session(game, slot, slot_name, slot_data, team, yaml_options)
        if external_pack_path is not None:
            try:
                pack_path = Path(external_pack_path).resolve()
                if pack_path.is_file() and zipfile.is_zipfile(pack_path):
                    session.external_pack_path = pack_path
                else:
                    LOGGER.warning("Cached companion map pack is missing or not a ZIP: %s", pack_path)
            except (OSError, ValueError) as error:
                LOGGER.warning("Could not open cached companion map pack: %s", error)
        self._session = session
        LOGGER.info("Initialized mobile UT tracker for %s slot %s", game, slot_name)
        return self.update([], set(), None)

    def _reconstruct_session(
        self,
        game: str,
        slot: int,
        slot_name: str,
        slot_data: dict[str, Any],
        team: int | None,
        yaml_options: dict[str, Any] | None,
    ) -> TrackerSession:
        if not isinstance(slot_data, dict):
            raise TrackerAdapterError("Slot data must be an object.")
        world_type = AutoWorld.AutoWorldRegister.world_types.get(game)
        if world_type is None:
            raise TrackerAdapterError(f"No loaded APWorld registers game '{game}'.")
        if getattr(world_type, "disable_ut", False):
            raise TrackerAdapterError(f"The '{game}' APWorld has disabled Universal Tracker support.")

        yaml_required = not getattr(world_type, "ut_can_gen_without_yaml", False)
        if yaml_required and yaml_options is None:
            raise TrackerAdapterError(f"The '{game}' APWorld requires the original player YAML.")

        # Hooks may mutate the data they are given. Compare future snapshots against the
        # original server data and keep the parsed YAML available after its file is gone.
        server_slot_data = deepcopy(slot_data)
        slot_data = deepcopy(slot_data)

        def build_session(regen_data, deferred_connections="off"):
            options = deepcopy(yaml_options or {})
            kwargs = {"deferred_connections": deferred_connections} if deferred_connections != "off" else {}
            multiworld = self._build_multiworld(game, slot_name, options, regen_data, **kwargs)
            return TrackerSession(
                game, slot, slot_name, team, server_slot_data, multiworld, yaml_options=yaml_options
            )

        # UT's normal flow runs a static interpret_slot_data hook before YAML-less generation.
        # Other YAML-less worlds receive raw slot data during the first generation, including
        # worlds with an instance hook: their generate_early may require it before the hook runs.
        regen_data = None
        hook = self._static_class_attribute(world_type, "interpret_slot_data")
        static_hook = isinstance(hook, (staticmethod, classmethod))
        if not yaml_required:
            regen_data = world_type.interpret_slot_data(slot_data) if static_hook else slot_data
            if regen_data is None:
                regen_data = slot_data
            LOGGER.debug("Reconstructing %s from %s slot data", game, "interpreted" if static_hook else "raw")

        session = build_session(regen_data)
        reconstruction_data = regen_data

        if not static_hook or yaml_required:
            world = session.multiworld.worlds[session.player]
            world_hook = getattr(world, "interpret_slot_data", None)
            if callable(world_hook):
                returned = world_hook(slot_data)
                if returned is not None:
                    reconstruction_data = returned
                    session = build_session(returned)

        # Some APWorlds expose their server pairing and discovery contract only during deferred
        # reconstruction. Keep the established immediate graph when it already has a pairing
        # contract; otherwise accept a deferred graph only with matching server data and declared
        # storage keys. No game name or locally generated pairing participates in this choice.
        world = session.multiworld.worlds[session.player]
        if reconstruction_data is not None and self._server_entrance_pairings(world, session) is None:
            try:
                deferred_session = build_session(reconstruction_data, deferred_connections="on")
                deferred_world = deferred_session.multiworld.worlds[deferred_session.player]
                if (
                    self._resolved_entrance_keys(deferred_world, deferred_session)
                    and self._server_entrance_pairings(deferred_world, deferred_session) is not None
                ):
                    session = deferred_session
                    LOGGER.info("Using APWorld-declared deferred entrance discovery graph")
            except Exception as error:
                LOGGER.debug("No compatible deferred entrance graph: %s", error)

        # UT 0.3.3 discards generated network items before replay: the server's
        # ReceivedItems already includes starting inventory. Keep code-less events.
        precollected = session.multiworld.precollected_items
        session.multiworld.precollected_items = {
            player: [item for item in items if item.code is None]
            for player, items in precollected.items()
        }
        LOGGER.debug("Removed %s generated network starting items before server replay",
                     sum(len(items) for items in precollected.values())
                     - sum(len(items) for items in session.multiworld.precollected_items.values()))

        return session

    def update(
        self,
        received_items: list[dict[str, Any]],
        checked_locations: set[int],
        missing_locations: set[int] | None,
        stored_data: dict[str, Any] | None = None,
        slot_data: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        session = self._require_session()
        if slot_data is not None and slot_data != session.slot_data:
            LOGGER.info("Rebuilding Universal Tracker graph after slot-data change for %s", session.game)
            previous = session
            try:
                session = self._reconstruct_session(
                    previous.game, previous.slot, previous.slot_name, slot_data,
                    previous.team, previous.yaml_options,
                )
            except Exception as error:
                raise TrackerAdapterError(f"Could not reconstruct from updated slot data: {error}") from error
            session.external_pack_path = previous.external_pack_path
            stored_data = (previous.stored_data or {}) | (stored_data or {})
        self._apply_stored_data(session, stored_data or {})
        world = session.multiworld.worlds[session.player]
        map_state = self._map_state(world, session)
        logic_signature = self._logic_signature(
            world,
            session,
            received_items,
            checked_locations,
            missing_locations,
        )
        if session.logic_signature == logic_signature and session.logic_payload is not None:
            result = dict(session.logic_payload)
            if map_state is not None:
                result["map"] = map_state
            LOGGER.debug(
                "Reused Universal Tracker logic snapshot for storage-only update; markers=%s",
                len(map_state.get("player_markers", [])) if map_state else 0,
            )
            return result

        # Checks and entrances remain useful when the APWorld has no internal map.
        state = CollectionState(
            session.multiworld,
            allow_partial_entrances=bool(self._resolved_entrance_keys(world, session)),
        )

        inventory: Counter[str] = Counter()
        for network_item in received_items:
            item_id = int(network_item["item_id"])
            if item_id <= 0:
                continue
            item_name = world.item_id_to_name.get(item_id)
            if item_name is None:
                raise TrackerAdapterError(f"APWorld '{session.game}' does not define item id {item_id}.")
            item = session.multiworld.create_item(item_name, session.player)
            item.classification |= ItemClassification(int(network_item.get("flags", 0)))
            state.collect(item, True)
            if item.code is not None:
                inventory[item.name] += 1

        state.sweep_for_advancements(
            locations=[location for location in session.multiworld.get_locations(session.player) if not location.address]
        )
        reachable_ids = {
            int(location.address)
            for location in session.multiworld.get_reachable_locations(state, session.player)
            if location.address is not None and not isinstance(location.address, list)
        }
        # An empty server list means the slot has no outstanding checks. Only a
        # snapshot with no membership information falls back to reconstructed IDs.
        server_ids = checked_locations | (missing_locations or set())
        locations = []
        matched_ids = set()
        for location in session.multiworld.get_locations(session.player):
            if location.address is None or isinstance(location.address, list):
                continue
            location_id = int(location.address)
            if missing_locations is not None and location_id not in server_ids:
                continue
            matched_ids.add(location_id)
            locations.append(
                {
                    "id": location_id,
                    "name": location.name,
                    "region": location.parent_region.name if location.parent_region else "",
                    "checked": location_id in checked_locations,
                    "reachable": location_id in reachable_ids,
                    "excluded": location.progress_type == LocationProgressType.EXCLUDED,
                }
            )

        unmatched_ids = server_ids - matched_ids
        if unmatched_ids:
            LOGGER.warning("APWorld %s did not reconstruct %s server locations", session.game, len(unmatched_ids))
            known_names = getattr(world, "location_id_to_name", {})
            locations.extend({
                "id": location_id,
                "name": known_names.get(location_id, ""),
                "region": "",
                "checked": location_id in checked_locations,
                "reachable": None,
                "excluded": False,
            } for location_id in sorted(unmatched_ids))

        regions = [
            {"name": region.name, "reachable": region in state.reachable_regions[session.player]}
            for region in session.multiworld.get_regions(session.player)
        ]
        result = {
            "game": session.game,
            "slot": session.slot,
            "slot_name": session.slot_name,
            "locations": sorted(locations, key=lambda location: (location["region"], location["name"])),
            "inventory": self._inventory_payload(world, inventory),
            "regions": sorted(regions, key=lambda region: region["name"]),
            "entrances": self._entrance_payload(world, session, state),
            "required_stored_data_keys": self._stored_data_keys(world, session),
            "external_map_pack_missing": bool(
                (getattr(world, "tracker_world", {}) or {}).get("external_pack_key")
                if isinstance(getattr(world, "tracker_world", {}), dict)
                else getattr(getattr(world, "tracker_world", None), "external_pack_key", None)
            ) and session.external_pack_path is None,
        }
        session.logic_signature = logic_signature
        session.logic_payload = dict(result)
        result["map"] = map_state
        self._session = session
        return result

    def close(self) -> None:
        self._session = None

    def _require_session(self) -> TrackerSession:
        if self._session is None:
            raise TrackerAdapterError("Universal Tracker session has not been initialized.")
        return self._session


_TRACKER = MobileUniversalTracker()


def initialize_runtime(request_json: str) -> str:
    request = json.loads(request_json)
    result = _TRACKER.initialize(**request)
    return json.dumps(result)


def load_world(request_json: str) -> str:
    request = json.loads(request_json)
    result = _TRACKER.load_world(
        apworld_path=request["apworld_path"],
        package_root=request["package_root"],
    )
    return json.dumps(result)


def load_tracker_package(request_json: str) -> str:
    request = json.loads(request_json)
    return json.dumps(_TRACKER.verify_tracker_package(request["tracker_apworld_path"]))


def update_state(state_json: str) -> str:
    state = json.loads(state_json)
    missing_locations = state.get("missing_locations")
    result = _TRACKER.update(
        received_items=state.get("received_items", []),
        checked_locations=set(state.get("checked_locations", [])),
        missing_locations=set(missing_locations) if missing_locations is not None else None,
        stored_data=state.get("stored_data", {}),
        slot_data=state.get("slot_data"),
    )
    return json.dumps(result)


def shutdown_tracker() -> None:
    _TRACKER.close()
