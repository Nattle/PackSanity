"""PopTracker map assets and APWorld player-position callbacks as JSON-only state."""
from __future__ import annotations
import json
import math
from pathlib import Path
from typing import Any, TYPE_CHECKING
import zipfile
from packsanity_ut_models import LOGGER, TrackerAdapterError, TrackerSession
if TYPE_CHECKING:
    from worlds import AutoWorld


def _callable_parameter_count(function: Any) -> int | None:
    code = getattr(function, "__code__", None)
    if code is None:
        return None
    count = code.co_argcount
    if getattr(function, "__self__", None) is not None:
        count -= 1
    return count


def _map_state(self, world: AutoWorld.World, session: TrackerSession) -> dict[str, Any] | None:
    """Resolve UT's internal PopTracker map contract into JSON-only native state."""
    config = getattr(world, "tracker_world", None)
    if config is None or self._loaded_package_path is None or self._loaded_package_root is None:
        return None

    def setting(name: str, default: Any = None) -> Any:
        return config.get(name, default) if isinstance(config, dict) else getattr(config, name, default)

    external = bool(setting("external_pack_key"))
    folder = setting("map_page_folder")
    if external and session.external_pack_path is None:
        return None
    if not external and (not isinstance(folder, str) or not folder):
        return None
    if external:
        folder = ""

    def values(name: str) -> list[str]:
        value = setting(name, [])
        if isinstance(value, str):
            return [value]
        return [entry for entry in value if isinstance(entry, str)] if isinstance(value, (list, tuple)) else []

    def read_json(relative: str) -> Any:
        archive_path = self._map_asset_path(folder, relative, external)
        archive_path = archive_path.removeprefix("external-pack/") if external else archive_path
        with zipfile.ZipFile(session.external_pack_path if external else self._loaded_package_path) as archive:
            return json.loads(archive.read(archive_path))

    if session.map_assets is None:
        try:
            maps = [entry for path in values("map_page_maps") for entry in self._json_list(read_json(path), "maps")]
            location_nodes = [entry for path in values("map_page_locations") for entry in self._json_list(read_json(path), "locations")]
        except (
            OSError, KeyError, UnicodeDecodeError, zipfile.BadZipFile,
            json.JSONDecodeError, TrackerAdapterError,
        ) as error:
            if external:
                LOGGER.warning("Installed companion map pack does not match the APWorld: %s", error)
                session.external_pack_path = None
                return None
            raise TrackerAdapterError(f"Could not load internal Universal Tracker map data: {error}") from error

        name_mapping = setting("poptracker_name_mapping", {})
        if not isinstance(name_mapping, dict):
            name_mapping = {}
        location_name_to_id = world.location_name_to_id
        locations_by_map: dict[str, list[dict[str, Any]]] = {str(entry.get("name", "")): [] for entry in maps}

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
                    ids = {
                        location_id
                        for section in sections
                        if isinstance(section, dict) and isinstance(section.get("name"), str)
                        for location_id in [mapped_location_id(group_name, section["name"])]
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
                        except (KeyError, TypeError, ValueError):
                            continue
                        locations_by_map[map_name].extend(
                            {"location_id": location_id, "x": x, "y": y} for location_id in ids
                        )
                children = node.get("children", [])
                if isinstance(children, list):
                    visit(children)

        visit(location_nodes)
        normalized_maps = []
        for index, entry in enumerate(maps):
            if not isinstance(entry, dict):
                continue
            map_id = str(entry.get("name", index))
            image = entry.get("img") or entry.get("image")
            if not isinstance(image, str):
                continue
            try:
                image_path = self._map_asset_path(folder, image, external)
            except TrackerAdapterError:
                if not external:
                    raise
                LOGGER.warning("Installed companion map pack has an invalid image path")
                session.external_pack_path = None
                return None
            location_size = self._positive_float(entry.get("location_size"), 65.0)
            normalized_maps.append(
                {
                    "id": map_id,
                    "image_path": image_path,
                    "locations": locations_by_map.get(map_id, []),
                    "location_icon_size": self._positive_float(
                        entry.get("location_icon_size"), location_size
                    ),
                }
            )
        if external and not normalized_maps:
            LOGGER.warning("Installed companion map pack declares no usable maps")
            session.external_pack_path = None
            return None
        session.map_assets = normalized_maps
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
            parameter_count = self._callable_parameter_count(coordinate_function)
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
            player_markers = self._normalize_player_markers(
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


def _normalize_player_markers(
    self,
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
            icon_path = self._map_asset_path(folder, raw_marker[2], external)
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


def _internal_asset_path(self, folder: str, relative: str) -> str:
    parts = [self._loaded_package_root, folder.strip("/"), relative.lstrip("/")]
    path = Path(*[part for part in parts if part]).as_posix()
    if any(part == ".." for part in Path(path).parts):
        raise TrackerAdapterError(f"Universal Tracker map asset path escapes its package: {relative}")
    return path


def _map_asset_path(self, folder: str, relative: str, external: bool) -> str:
    if external:
        path = Path(relative.lstrip("/")).as_posix()
        if not path or any(part in ("..", ".") for part in Path(path).parts):
            raise TrackerAdapterError(f"External map asset path escapes its package: {relative}")
        return f"external-pack/{path}"
    return self._internal_asset_path(folder, relative)


def _positive_float(value: Any, default: float) -> float:
    try:
        parsed = float(value)
        return parsed if parsed > 0 and math.isfinite(parsed) else default
    except (TypeError, ValueError):
        return default


def _json_list(value: Any, key: str) -> list[Any]:
    if isinstance(value, list):
        return value
    if isinstance(value, dict) and isinstance(value.get(key), list):
        return value[key]
    return []

