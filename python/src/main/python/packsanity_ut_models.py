"""Shared adapter error and flow-owned session data; no networking or reconstruction."""
from __future__ import annotations
from dataclasses import dataclass
import logging
from pathlib import Path
from typing import Any, TYPE_CHECKING
if TYPE_CHECKING:
    from BaseClasses import MultiWorld

LOGGER = logging.getLogger("packsanity.universal_tracker")

class TrackerAdapterError(Exception):
    """Expected APWorld compatibility or reconstruction failure."""


@dataclass
class TrackerSession:
    game: str
    slot: int
    slot_name: str
    team: int | None
    slot_data: dict[str, Any]
    multiworld: MultiWorld
    player: int = 1
    stored_data: dict[str, Any] | None = None
    map_assets: list[dict[str, Any]] | None = None
    logic_signature: tuple[Any, ...] | None = None
    logic_payload: dict[str, Any] | None = None
    entrances_by_storage_key: dict[str, list[Any]] | None = None
    entrance_pairings_by_storage_key: dict[str, dict[str, str]] | None = None
    external_pack_path: Path | None = None
    yaml_options: dict[str, Any] | None = None

