"""Tracker-only compatibility surface for Archipelago's optional ROM patch dependency.

The Universal Tracker never builds or applies ROM patches. Keeping this importable lets APWorlds
declare their desktop patch type without making tracker logic depend on a native bsdiff module.
"""


def _unsupported(*_args, **_kwargs):
    raise RuntimeError("ROM patching is not available in PackSanity's Universal Tracker runtime")


diff = _unsupported
patch = _unsupported
