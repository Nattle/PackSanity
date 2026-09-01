"""Small pure-Python fallback for APWorld metadata that imports orjson.

PackSanity only needs JSON-compatible world metadata and slot data; native encoder-specific options
remain deliberately unsupported on mobile.
"""

import json


def loads(value):
    return json.loads(value)


def dumps(value, *, default=None, option=None):
    del option
    return json.dumps(value, default=default, separators=(",", ":")).encode("utf-8")
