"""Synthetic importable APWorld; original test code, no external game assets."""
from BaseClasses import Item, ItemClassification, Location, Region
from worlds.AutoWorld import World


class FixtureItem(Item):
    game = "PackSanity World"


class FixtureWorld(World):
    game = "PackSanity World"
    ut_can_gen_without_yaml = True
    item_name_to_id = {"Key": 1001}
    location_name_to_id = {"Chest": 2001}

    @staticmethod
    def interpret_slot_data(slot_data):
        return slot_data

    def create_regions(self):
        menu = Region("Menu", self.player, self.multiworld)
        hall = Region("Hall", self.player, self.multiworld)
        menu.connect(hall, rule=lambda state: state.has("Key", self.player))
        hall.locations.append(Location(self.player, "Chest", 2001, hall))
        self.multiworld.regions.extend([menu, hall])

    def create_item(self, name):
        return FixtureItem(name, ItemClassification.progression, self.item_name_to_id[name], self.player)

    def create_items(self):
        self.multiworld.itempool.append(self.create_item("Key"))
