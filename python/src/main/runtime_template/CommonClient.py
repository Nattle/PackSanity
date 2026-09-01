"""Tracker-only part of Archipelago's client module used during world generation.

The native Archipelago client owns networking and UI. Worlds may still import the
upstream client logger in generation hooks, so expose that stable logging object.
"""

import logging

logger = logging.getLogger("Client")
