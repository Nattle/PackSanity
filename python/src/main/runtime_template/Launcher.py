"""Keep desktop launcher registration importable in the tracker-only mobile runtime."""


def launch(*_args, **_kwargs):
    raise RuntimeError("Desktop process launching is not available in PackSanity")
