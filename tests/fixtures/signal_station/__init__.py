"""An original, small world for PackSanity's reconstruction and map scenarios."""

from BaseClasses import Item, ItemClassification, Region
from settings import FilePath, Group
from worlds.AutoWorld import World

from .options import SignalStationOptions


GAME = "Signal Station"
ITEMS = {"Fuse": 920001, "Receiver": 920002, "Battery Cell": 920003}
LOCATIONS = {"Supply Crate": 920101, "Control Panel": 920102, "Spare Parts": 920103}
ENTRANCE = "Workshop Door"
EVENT = "Power Restored"
ENTRANCE_KEY = "SignalStation:{team}:{player}:entrances"
EVENT_KEY = "SignalStation:{team}:{player}:events"
MAPS = ("Yard", "Workshop")


def map_page_index(data: str) -> int:
    return MAPS.index(data) if data in MAPS else 0


def location_icon_coords(
    index: int | None, data: dict | None,
) -> list[tuple[int, int, str]] | None:
    if index is None or not isinstance(data, dict) or data.get("map") != MAPS[index]:
        return None
    markers = [(data["x"], data["y"], "images/player.png")]
    if "beacon" in data:
        beacon = data["beacon"]
        markers.append((beacon["x"], beacon["y"], "images/beacon.png"))
    return markers


class SignalStationItem(Item):
    game = GAME


class SignalStationSettings(Group):
    class TrackerPack(FilePath):
        required = False

    ut_pack_path: TrackerPack | str = TrackerPack()


class SignalStationWorld(World):
    game = GAME
    options_dataclass = SignalStationOptions
    options: SignalStationOptions
    settings: SignalStationSettings
    item_name_to_id = ITEMS
    location_name_to_id = LOCATIONS
    location_name_groups = {"Optional checks": {"Spare Parts"}}

    ut_can_gen_without_yaml = False
    found_entrances_datastorage_key = (ENTRANCE_KEY, EVENT_KEY)
    ut_map_page_hidden_locations = {"Yard": [LOCATIONS["Spare Parts"]]}
    ut_map_page_hidden_entrances = {"Workshop": [ENTRANCE]}
    ut_map_page_hidden_events = {"Yard": [EVENT]}
    tracker_maps = {
        "map_page_folder": "tracker",
        "map_page_maps": ["maps/yard.json", "maps/workshop.json"],
        "map_page_locations": ["locations/yard.json", "locations/workshop.json"],
        "map_page_layouts": "layouts/tracker.json",
        "map_page_setting_key": "SignalStation:{team}:{player}:map",
        "map_page_index": map_page_index,
        "poptracker_name_mapping": {"Loading Bay/Crate": LOCATIONS["Supply Crate"]},
        "poptracker_entrance_mapping": {"Service Door": ENTRANCE},
        "location_setting_key": "SignalStation:{team}:{player}:position",
        "location_icon_coords": location_icon_coords,
        "entrance_pairings_slot_data_key": "entrance_links",
    }

    @staticmethod
    def interpret_slot_data(slot_data):
        return slot_data

    def generate_early(self):
        seed_data = (getattr(self.multiworld, "re_gen_passthrough", None) or {}).get(self.game, {})
        self.door_requires_fuse = seed_data.get("door_requires_fuse", True)
        self.entrance_pairings = seed_data.get("entrance_links", {"Menu": "Workshop"})
        # The fixture selects companion modes through slot data so one world can test all three.
        source = seed_data.get("map_source", "internal")
        self.tracker_world = dict(self.tracker_maps)
        if source in {"external", "hybrid"}:
            self.tracker_world["external_pack_key"] = "ut_pack_path"
            if source == "external":
                self.tracker_world.pop("map_page_folder")
                self.tracker_world.pop("map_page_layouts")
                self.tracker_world["map_page_groups"] = [("Signal Station", list(MAPS))]

    def fill_slot_data(self):
        return {
            "door_requires_fuse": self.door_requires_fuse,
            "entrance_links": self.entrance_pairings,
            "decoupled": True,
        }

    def create_regions(self):
        yard = Region("Menu", self.player, self.multiworld)
        workshop = Region("Workshop", self.player, self.multiworld)
        yard.add_locations({"Supply Crate": LOCATIONS["Supply Crate"]})
        workshop.add_locations({name: LOCATIONS[name] for name in ("Control Panel", "Spare Parts")})
        workshop.add_event(EVENT, EVENT)
        self.multiworld.regions.extend([yard, workshop])

    def create_item(self, name):
        classification = ItemClassification.progression if name == "Fuse" else ItemClassification.filler
        return SignalStationItem(name, classification, ITEMS[name], self.player)

    def create_items(self):
        self.multiworld.itempool.extend(self.create_item(name) for name in ITEMS)

    def set_rules(self):
        self.get_location("Supply Crate").access_rule = (
            lambda state: bool(self.options.emergency_power) or state.has("Fuse", self.player)
        )
        self.get_location("Control Panel").access_rule = lambda state: state.has(EVENT, self.player)
        self.multiworld.completion_condition[self.player] = lambda state: state.has(EVENT, self.player)

    def connect_entrances(self):
        yard = self.get_region("Menu")
        self.workshop_target = self.get_region(self.entrance_pairings[yard.name])
        self.workshop_door = yard.create_exit(ENTRANCE)
        self.workshop_door.access_rule = (
            lambda state: not self.door_requires_fuse or state.has("Fuse", self.player)
        )
        self.defer_discovery = getattr(self.multiworld, "enforce_deferred_connections", "off") != "off"
        if self.defer_discovery:
            self.get_location(EVENT).access_rule = lambda _: False
        else:
            self.workshop_door.connect(self.workshop_target)

    def reconnect_found_entrances(self, key, value):
        if not self.defer_discovery:
            return
        if key.endswith(":entrances") and "Menu" in (value or []):
            if self.workshop_door.connected_region is None:
                self.workshop_door.connect(self.workshop_target)
        if key.endswith(":events") and EVENT in (value or []):
            self.get_location(EVENT).access_rule = lambda _: True
