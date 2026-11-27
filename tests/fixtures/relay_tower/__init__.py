"""A small YAML-free APWorld with an instance hook that decodes seed options."""

from dataclasses import dataclass

from BaseClasses import Item, ItemClassification, Region
from Options import PerGameCommonOptions, Range
from worlds.AutoWorld import World


class LiftCells(Range):
    """Number of charged cells needed to operate the tower lift."""

    display_name = "Lift Cells"
    range_start = 1
    range_end = 2
    default = 1


@dataclass
class RelayTowerOptions(PerGameCommonOptions):
    lift_cells: LiftCells


class RelayTowerItem(Item):
    game = "Relay Tower"


class RelayTowerWorld(World):
    game = RelayTowerItem.game
    options_dataclass = RelayTowerOptions
    options: RelayTowerOptions
    ut_can_gen_without_yaml = True
    item_name_to_id = {"Charged Cell": 921001, "Wrench": 921002}
    location_name_to_id = {"Supply Bin": 921101, "Tool Rack": 921102, "Relay Cabinet": 921103}

    def interpret_slot_data(self, slot_data):
        seed_settings = slot_data.get("settings", {})
        cells = seed_settings.get("lift_cells", self.options.lift_cells.value)
        return {"options": {"lift_cells": LiftCells.from_any(cells).value}}

    def generate_early(self):
        data = (getattr(self.multiworld, "re_gen_passthrough", None) or {}).get(self.game, {})
        cells = data.get("options", {}).get("lift_cells")
        if cells is not None:
            self.options.lift_cells = LiftCells.from_any(cells)

    def fill_slot_data(self):
        return {"settings": self.options.as_dict("lift_cells")}

    def create_item(self, name):
        classification = ItemClassification.progression if name == "Charged Cell" else ItemClassification.filler
        return RelayTowerItem(name, classification, self.item_name_to_id[name], self.player)

    def create_items(self):
        self.multiworld.itempool.extend(self.create_item(name) for name in ("Charged Cell", "Charged Cell", "Wrench"))

    def create_regions(self):
        depot = Region("Menu", self.player, self.multiworld)
        tower = Region("Tower", self.player, self.multiworld)
        depot.add_locations({name: self.location_name_to_id[name] for name in ("Supply Bin", "Tool Rack")})
        tower.add_locations({"Relay Cabinet": self.location_name_to_id["Relay Cabinet"]})
        self.multiworld.regions.extend([depot, tower])
        depot.connect(tower, "Tower Lift", lambda state: state.has("Charged Cell", self.player, self.options.lift_cells.value))
        self.multiworld.completion_condition[self.player] = lambda state: state.can_reach_region("Tower", self.player)
