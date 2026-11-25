"""PopTracker map assets and APWorld player-position callbacks as JSON-only state."""
from __future__ import annotations
import json
import math
from pathlib import PurePosixPath
from types import SimpleNamespace
from typing import Any, TYPE_CHECKING
import zipfile
from packsanity_ut_models import LOGGER, TrackerAdapterError, TrackerSession, tracker_setting
from packsanity_ut_upstream import map_entrance_status, map_event_status, map_graph_matches
if TYPE_CHECKING:
    from BaseClasses import CollectionState
    from worlds import AutoWorld

MAP_MARKER_STATUS = {"passed": "complete", "passable": "available", "impassable": "blocked"}


def _callable_parameter_count(function: Any) -> int | None:
    code = getattr(function, "__code__", None)
    if code is None:
        return None
    count = code.co_argcount
    if getattr(function, "__self__", None) is not None:
        count -= 1
    return count


def _map_state(self, world: AutoWorld.World, session: TrackerSession) -> dict[str, Any] | None:
    """Resolve UT's PopTracker map contract into JSON-only native state."""
    config = getattr(world, "tracker_world", None)
    if config is None or self._loaded_package_path is None or self._loaded_package_root is None:
        return None

    def setting(name: str, default: Any = None) -> Any:
        return tracker_setting(config, name, default)

    package_root = self._loaded_package_root

    external = bool(setting("external_pack_key"))
    folder = setting("map_page_folder")
    folder = folder if isinstance(folder, str) else ""
    if external and session.external_pack_path is None:
        return None
    if not external and not folder:
        return None
    def values(name: str) -> list[str]:
        value = setting(name, [])
        if isinstance(value, str):
            return [value]
        return [entry for entry in value if isinstance(entry, str)] if isinstance(value, (list, tuple)) else []

    if session.map_assets is None:
        map_files = values("map_page_maps")
        location_files = values("map_page_locations")
        external_json = external and not folder

        def read_json(archive: zipfile.ZipFile, relative: str) -> Any:
            path = _map_asset_path(package_root, folder, relative, external_json)
            if external_json:
                path = path.removeprefix("external-pack/")
            return json.loads(archive.read(path))

        try:
            if external and folder:
                # UT uses internal JSON for hybrid packs. Older PackSanity configurations
                # can also declare a folder while storing every definition in the companion.
                with zipfile.ZipFile(self._loaded_package_path) as archive:
                    internal_files = set(archive.namelist())
                external_json = not any(
                    _map_asset_path(package_root, folder, path, False) in internal_files
                    for path in (*map_files, *location_files)
                )
            source = session.external_pack_path if external_json else self._loaded_package_path
            with zipfile.ZipFile(source) as archive:
                maps = [entry for path in map_files for entry in _json_list(read_json(archive, path), "maps")
                        if isinstance(entry, dict)]
                location_nodes = [entry for path in location_files
                                  for entry in _json_list(read_json(archive, path), "locations")]
        except (
            OSError, KeyError, UnicodeDecodeError, zipfile.BadZipFile,
            json.JSONDecodeError, TrackerAdapterError,
        ) as error:
            if external_json:
                LOGGER.warning("Installed companion map pack does not match the APWorld: %s", error)
                session.external_pack_path = None
                return None
            raise TrackerAdapterError(f"Could not load internal Universal Tracker map data: {error}") from error

        name_mapping = setting("poptracker_name_mapping", {})
        if not isinstance(name_mapping, dict):
            name_mapping = {}
        location_name_to_id = world.location_name_to_id
        locations_by_map: dict[str, list[dict[str, Any]]] = {str(entry.get("name", "")): [] for entry in maps}
        sections_by_map = {name: [] for name in locations_by_map}

        def mapped_location_id(group_name: str, section_name: str) -> int | None:
            mapped = name_mapping.get(f"{group_name}/{section_name}", section_name)
            if isinstance(mapped, int):
                return mapped
            return location_name_to_id.get(str(mapped))

        def visit(nodes: list[Any]) -> None:
            for node in nodes:
                if not isinstance(node, dict):
                    continue
                group_name = str(node.get("name", ""))
                sections = node.get("sections", [])
                map_locations = node.get("map_locations", [])
                if isinstance(sections, list) and isinstance(map_locations, list):
                    names = list(dict.fromkeys(
                        section["name"] for section in sections
                        if isinstance(section, dict) and isinstance(section.get("name"), str)
                    ))
                    ids = {
                        location_id
                        for name in names
                        for location_id in [mapped_location_id(group_name, name)]
                        if location_id is not None
                    }
                    for marker in map_locations:
                        if not isinstance(marker, dict):
                            continue
                        map_name = str(marker.get("map", ""))
                        if map_name not in locations_by_map:
                            continue
                        try:
                            x, y = float(marker["x"]), float(marker["y"])
                        except (KeyError, TypeError, ValueError, OverflowError):
                            continue
                        if not math.isfinite(x) or not math.isfinite(y):
                            continue
                        placement = {"x": x, "y": y}
                        size = _positive_float(marker.get("size"), None)
                        if size is not None:
                            placement["size"] = size
                        locations_by_map[map_name].extend(
                            {"location_id": location_id, **placement} for location_id in ids
                        )
                        if names:
                            sections_by_map[map_name].append((placement, names))
                children = node.get("children", [])
                if isinstance(children, list):
                    visit(children)

        visit(location_nodes)
        normalized_maps = []
        for index, entry in enumerate(maps):
            map_id = str(entry.get("name", index))
            image = entry.get("img") or entry.get("image")
            if not isinstance(image, str):
                continue
            try:
                image_path = _map_asset_path(package_root, folder, image, external)
            except TrackerAdapterError:
                if not external:
                    raise
                LOGGER.warning("Installed companion map pack has an invalid image path")
                session.external_pack_path = None
                return None
            location_size = _positive_float(entry.get("location_size"), None)
            border = entry.get("location_border_thickness")
            asset = {
                "id": map_id,
                "image_path": image_path,
                "locations": locations_by_map.get(map_id, []),
                "location_icon_size": _positive_float(
                    entry.get("location_icon_size"), location_size or 65.0
                ),
            }
            if location_size is not None:
                asset["location_size"] = location_size
            if isinstance(border, (int, float)) and 0 <= border < math.inf:
                asset["location_border_thickness"] = border
            normalized_maps.append(asset)
        if external and not normalized_maps:
            LOGGER.warning("Installed companion map pack declares no usable maps")
            session.external_pack_path = None
            return None
        session.map_assets = normalized_maps
        session.map_sections = sections_by_map
        LOGGER.info("Loaded %s static Universal Tracker map assets", len(normalized_maps))

    normalized_maps = session.map_assets

    active_map_id = normalized_maps[0]["id"] if normalized_maps else None
    active_map_index: int | None = 0 if normalized_maps else None
    map_key = setting("map_page_setting_key")
    map_index = setting("map_page_index")
    if isinstance(map_key, str) and callable(map_index):
        resolved_key = map_key.format(player=session.slot, team=session.team or 0)
        if session.stored_data is not None and resolved_key in session.stored_data:
            try:
                selected = int(map_index(session.stored_data[resolved_key]))
                if 0 <= selected < len(normalized_maps):
                    active_map_id = normalized_maps[selected]["id"]
                    active_map_index = selected
            except (TypeError, ValueError, IndexError):
                LOGGER.warning("APWorld map_page_index did not resolve a valid map")

    player_markers = []
    location_key = setting("location_setting_key")
    coordinate_function = setting("location_icon_coords")
    if isinstance(location_key, str) and callable(coordinate_function):
        resolved_key = location_key.format(player=session.slot, team=session.team or 0)
        if session.stored_data is not None and resolved_key in session.stored_data:
            stored_coords = session.stored_data[resolved_key]
            # Current Universal Tracker packs receive the active map index followed by
            # their stored coordinate object. Older packs accepted only coordinates.
            parameter_count = _callable_parameter_count(coordinate_function)
            coords_result = (
                coordinate_function(active_map_index, stored_coords)
                if parameter_count is None or parameter_count >= 2
                else coordinate_function(stored_coords)
            )
            marker_size = (
                normalized_maps[active_map_index].get("location_icon_size", 65.0)
                if active_map_index is not None and active_map_index < len(normalized_maps)
                else 65.0
            )
            player_markers = _normalize_player_markers(
                package_root,
                folder,
                active_map_id,
                marker_size,
                coords_result,
                external=external,
            )

    return {
        "maps": normalized_maps,
        "active_map_id": active_map_id,
        "player_markers": player_markers,
    }


def _update_graph_markers(
    world: AutoWorld.World, session: TrackerSession, state: CollectionState,
    map_state: dict[str, Any] | None,
) -> None:
    """Resolve map-only sections with live UT entrance and event semantics."""
    if map_state is None or not session.map_sections:
        return
    aliases = tracker_setting(world.tracker_world, "poptracker_entrance_mapping")
    aliases = {name: target for name, target in aliases.items() if isinstance(target, str)} \
        if isinstance(aliases, dict) else {}
    entrances = {entry.name: entry for entry in session.multiworld.get_entrances(session.player)}
    events = {
        entry.name: entry for entry in session.multiworld.get_locations(session.player)
        if entry.address is None and entry.parent_region is not None
    }
    hidden_entrances = getattr(world, "ut_map_page_hidden_entrances", {}) or {}
    hidden_events = getattr(world, "ut_map_page_hidden_events", {}) or {}
    hidden_checks = getattr(world, "ut_map_page_hidden_locations", {}) or {}
    progress = SimpleNamespace(state=state)
    graph = {"entrance": (entrances, map_entrance_status), "event": (events, map_event_status)}
    statuses = {"entrance": {}, "event": {}}

    maps = []
    for asset in map_state["maps"]:
        map_id = asset["id"]
        hidden_entrance_names = set(hidden_entrances.get(map_id, ()))
        hidden_event_names = set(hidden_events.get(map_id, ()))
        hidden_check_ids = set(hidden_checks.get(map_id, ()))
        pins = {}
        for placement, names in session.map_sections.get(map_id, ()):
            for name in names:
                for kind, target in map_graph_matches(
                    {"name": name}, entrances, hidden_entrance_names, aliases, events, hidden_event_names
                ):
                    cache = statuses[kind]
                    if target not in cache:
                        objects, status = graph[kind]
                        cache[target] = MAP_MARKER_STATUS[status(objects[target], progress)]
                    entry = {"id": target, "name": name, "status": cache[target]}
                    key = (kind, placement["x"], placement["y"])
                    pins.setdefault(key, {**placement, "kind": kind, "entries": {}})["entries"][target] = entry
        markers = [{**pin, "entries": list(pin["entries"].values())} for pin in pins.values()]
        updated = dict(asset)
        if markers:
            updated["graph_markers"] = markers
        if hidden_check_ids:
            updated["locations"] = [
                placement for placement in asset["locations"] if placement["location_id"] not in hidden_check_ids
            ]
        maps.append(updated)
    # Keep earlier snapshots intact and reuse the resolved pins on position-only updates.
    map_state["maps"] = maps
    LOGGER.debug("Resolved %s Universal Tracker graph markers", sum(
        len(asset.get("graph_markers", ())) for asset in maps
    ))


def _normalize_player_markers(
    package_root: str,
    folder: str,
    active_map_id: str | None,
    marker_size: float,
    coords_result: Any,
    external: bool = False,
) -> list[dict[str, Any]]:
    raw_markers = (
        coords_result
        if isinstance(coords_result, (list, tuple))
        and coords_result
        and isinstance(coords_result[0], (list, tuple))
        else [coords_result]
    )
    markers = []
    for raw_marker in raw_markers:
        if not isinstance(raw_marker, (list, tuple)) or len(raw_marker) < 2:
            continue
        try:
            x, y = float(raw_marker[0]), float(raw_marker[1])
        except (TypeError, ValueError):
            continue
        if x < 0 or y < 0 or not math.isfinite(x) or not math.isfinite(y):
            continue
        icon_path = None
        if len(raw_marker) >= 3 and isinstance(raw_marker[2], str) and raw_marker[2]:
            icon_path = _map_asset_path(package_root, folder, raw_marker[2], external)
        markers.append(
            {
                "x": x,
                "y": y,
                "map_id": active_map_id,
                "icon_path": icon_path,
                "size": marker_size,
            }
        )
    return markers


def _map_asset_path(package_root: str, folder: str, relative: str, external: bool) -> str:
    root = PurePosixPath("external-pack") if external else PurePosixPath(package_root, folder.strip("/"))
    path = root / relative.lstrip("/")
    if ".." in path.parts or (external and path == root):
        kind = "External" if external else "Universal Tracker"
        raise TrackerAdapterError(f"{kind} map asset path escapes its package: {relative}")
    return path.as_posix()


def _positive_float(value: Any, default: float | None) -> float | None:
    try:
        parsed = float(value)
        return parsed if parsed > 0 and math.isfinite(parsed) else default
    except (TypeError, ValueError, OverflowError):
        return default


def _json_list(value: Any, key: str) -> list[Any]:
    if isinstance(value, list):
        return value
    if isinstance(value, dict) and isinstance(value.get(key), list):
        return value[key]
    return []

