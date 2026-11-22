"""Synthetic importable APWorld; original test code, no external game assets."""
from BaseClasses import Item, ItemClassification, Location, LocationProgressType, Region
from worlds.AutoWorld import World


class FixtureItem(Item):
    game = "PackSanity World"


class FixtureWorld(World):
    game = "PackSanity World"
    ut_can_gen_without_yaml = True
    found_entrances_datastorage_key = None
    item_name_to_id = {"Key": 1001}
    location_name_to_id = {"Chest": 2001}
    location_name_groups = {"Early checks": {"Chest"}}

    @staticmethod
    def interpret_slot_data(slot_data):
        return slot_data

    def create_regions(self):
        slot_data = getattr(self.multiworld, "re_gen_passthrough", {}).get(self.game, {})
        self.require_local_item_origin = slot_data.get("require_local_item_origin", False)
        menu = Region("Menu", self.player, self.multiworld)
        hall = Region("Hall", self.player, self.multiworld)
        self.gateway = menu.connect(hall, rule=lambda state: state.has("Key", self.player))
        hall.locations.append(Location(self.player, "Chest", 2001, hall))
        self.scripted_location = None
        if slot_data.get("exclude_scripted_location"):
            self.scripted_location = Location(self.player, "Scripted location", None, hall)
            hall.locations.append(self.scripted_location)
        self.multiworld.regions.extend([menu, hall])

    def set_rules(self):
        if self.scripted_location is not None:
            self.options.exclude_locations.value.add(self.scripted_location.name)

    def generate_basic(self):
        if self.scripted_location is not None:
            assert self.scripted_location.progress_type == LocationProgressType.EXCLUDED

    def connect_entrances(self):
        slot_data = getattr(self.multiworld, "re_gen_passthrough", {}).get(self.game, {})
        if slot_data.get("deferred_discovery") and self.multiworld.enforce_deferred_connections == "default":
            self.gateway_destination = self.gateway.connected_region
            self.gateway_destination.entrances.remove(self.gateway)
            self.gateway.connected_region = None
            self.found_entrances_datastorage_key = "Door:{player}:{team}"

    def reconnect_found_entrances(self, key, value):
        if value == [105]:
            self.gateway.connect(self.gateway_destination)

    def create_item(self, name):
        return FixtureItem(name, ItemClassification.progression, self.item_name_to_id[name], self.player)

    def create_items(self):
        self.multiworld.itempool.append(self.create_item("Key"))

    def collect(self, state, item):
        if self.require_local_item_origin and (
            item.location is None or item.location.address != 2001
        ):
            return False
        return super().collect(state, item)
