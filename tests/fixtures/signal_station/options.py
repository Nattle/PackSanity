from dataclasses import dataclass

from Options import PerGameCommonOptions, Toggle


class EmergencyPower(Toggle):
    """Make the supply crate available before the station fuse is installed."""

    display_name = "Emergency Power"


@dataclass
class SignalStationOptions(PerGameCommonOptions):
    emergency_power: EmergencyPower
