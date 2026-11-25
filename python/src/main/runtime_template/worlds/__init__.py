"""Mobile UT world namespace.

Desktop Archipelago scans every bundled and user world while importing this package. Mobile ships
only AutoWorld and generic helpers, then explicitly imports the selected APWorld archive.
"""

from . import AutoWorld
from .AutoWorld import AutoWorldRegister

failed_world_loads: dict[str, str] = {}
