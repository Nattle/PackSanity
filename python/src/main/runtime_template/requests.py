"""Prevent desktop-only APWorld maintenance helpers from adding a mobile network dependency."""


def get(*_args, **_kwargs):
    raise RuntimeError("APWorld maintenance HTTP requests are not available in PackSanity")
