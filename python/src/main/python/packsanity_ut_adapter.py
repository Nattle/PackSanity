"""JSON bridge and serialized session coordinator for the mobile Universal Tracker.

The native host owns networking. Responsibility modules provide package loading, reconstruction,
maps, entrances, and cache projections; this facade retains session ownership and bridge APIs.
"""
from __future__ import annotations
from copy import deepcopy
import json
from pathlib import Path
from typing import Any
import zipfile
from BaseClasses import LocationProgressType
from NetUtils import NetworkItem
from worlds import AutoWorld
from packsanity_ut_core import replay_inventory
from packsanity_ut_upstream import discard_generated_network_items
from packsanity_ut_models import LOGGER, TrackerAdapterError, TrackerSession, tracker_setting
import packsanity_ut_packages as packages
import packsanity_ut_maps as maps
import packsanity_ut_entrances as entrances
import packsanity_ut_reconstruction as reconstruction
import packsanity_ut_logic as logic


class MobileUniversalTracker:
    """Retain one serialized tracker per process/world; changing archives needs a new interpreter."""

    load_world = packages.load_world
    verify_tracker_package = staticmethod(packages.verify_tracker_package)
    _map_state = maps._map_state
    _entrance_payload = staticmethod(entrances._entrance_payload)
    _apply_stored_data = staticmethod(entrances._apply_stored_data)
    _build_multiworld = reconstruction._build_multiworld
    _options_namespace = reconstruction._options_namespace
    _load_yaml_options = reconstruction._load_yaml_options
    _logic_signature = staticmethod(logic._logic_signature)

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
        result = self._project_update(session, [], set(), None)
        self._session = session
        LOGGER.info("Initialized mobile UT tracker for %s slot %s", game, slot_name)
        return result

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

        def build_session(regen_data):
            options = deepcopy(yaml_options or {})
            multiworld = self._build_multiworld(game, slot_name, options, regen_data)
            return TrackerSession(
                game, slot, slot_name, team, server_slot_data, multiworld, yaml_options=yaml_options
            )

        # UT's normal flow runs a static interpret_slot_data hook before YAML-less generation.
        # Other YAML-less worlds receive raw slot data during the first generation, including
        # worlds with an instance hook: their generate_early may require it before the hook runs.
        regen_data = None
        hook = reconstruction._static_class_attribute(world_type, "interpret_slot_data")
        static_hook = isinstance(hook, (staticmethod, classmethod))
        if not yaml_required:
            regen_data = world_type.interpret_slot_data(slot_data) if static_hook else slot_data
            if regen_data is None:
                regen_data = slot_data
            LOGGER.debug("Reconstructing %s from %s slot data", game, "interpreted" if static_hook else "raw")

        session = build_session(regen_data)

        if not static_hook or yaml_required:
            world = session.multiworld.worlds[session.player]
            world_hook = getattr(world, "interpret_slot_data", None)
            if callable(world_hook):
                returned = world_hook(slot_data)
                if returned is not None:
                    session = build_session(returned)

        discard_generated_network_items(session)

        return session

    def update(
        self,
        received_items: list[dict[str, Any]],
        checked_locations: set[int],
        missing_locations: set[int] | None,
        stored_data: dict[str, Any] | None = None,
        slot_data: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        previous = self._require_session()
        session = previous
        next_slot_data = previous.slot_data if slot_data is None else slot_data
        if previous.rebuild_after_failed_update or next_slot_data != previous.slot_data:
            LOGGER.info("Rebuilding Universal Tracker graph for %s", previous.game)
            try:
                session = self._reconstruct_session(
                    previous.game, previous.slot, previous.slot_name, next_slot_data,
                    previous.team, previous.yaml_options,
                )
            except Exception as error:
                raise TrackerAdapterError(f"Could not reconstruct from updated slot data: {error}") from error
            session.external_pack_path = previous.external_pack_path
        accepted_storage = dict(previous.stored_data or {})
        previous_pack = previous.external_pack_path
        storage = stored_data or {}
        if session is not previous:
            storage = accepted_storage | storage
        try:
            self._apply_stored_data(session, storage)
            result = self._project_update(session, received_items, checked_locations, missing_locations)
        except Exception:
            if session is previous:
                session.stored_data = accepted_storage
                session.external_pack_path = previous_pack
                # A world hook may have modified its graph before raising.
                session.rebuild_after_failed_update = True
            raise
        self._session = session
        return result

    def _project_update(
        self,
        session: TrackerSession,
        received_items: list[dict[str, Any]],
        checked_locations: set[int],
        missing_locations: set[int] | None,
    ) -> dict[str, Any]:
        world = session.multiworld.worlds[session.player]
        map_state = self._map_state(world, session)
        network_items = tuple(
            NetworkItem(
                int(item["item_id"]), int(item.get("location_id", 0)),
                int(item.get("player", 0)), int(item.get("flags", 0)),
            )
            for item in received_items
        )
        logic_signature = self._logic_signature(
            world,
            session,
            network_items,
            checked_locations,
            missing_locations,
        )
        if session.logic_signature == logic_signature and session.logic_payload is not None:
            result = dict(session.logic_payload)
            previous_map = result["map"]
            if map_state is not None and previous_map is not None and "maps" in previous_map:
                map_state["maps"] = previous_map["maps"]
            result["map"] = map_state
            LOGGER.debug(
                "Reused Universal Tracker logic snapshot for storage-only update; markers=%s",
                len(map_state.get("player_markers", [])) if map_state else 0,
            )
            return result

        state, inventory = replay_inventory(
            session.multiworld, session.player, session.slot, network_items
        )
        maps._update_graph_markers(world, session, state, map_state)
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
        external_pack_key = tracker_setting(getattr(world, "tracker_world", None), "external_pack_key")
        result = {
            "game": session.game,
            "slot": session.slot,
            "slot_name": session.slot_name,
            "locations": sorted(locations, key=lambda location: (location["region"], location["name"])),
            "inventory": logic._inventory_payload(world, inventory),
            "regions": sorted(regions, key=lambda region: region["name"]),
            "entrances": self._entrance_payload(world, session, state),
            "required_stored_data_keys": logic._stored_data_keys(world, session),
            "external_map_pack_missing": bool(external_pack_key) and session.external_pack_path is None,
        }
        result["map"] = map_state
        session.logic_signature = logic_signature
        session.logic_payload = dict(result)
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
