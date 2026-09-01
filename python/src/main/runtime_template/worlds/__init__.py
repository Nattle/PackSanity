"""Mobile UT world namespace.

Desktop Archipelago scans every bundled and user world while importing this package. Mobile ships
only AutoWorld and generic helpers, then explicitly imports the selected APWorld archive.
"""

from . import AutoWorld

failed_world_loads: list[str] = []
