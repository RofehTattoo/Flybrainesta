#!/usr/bin/env python3
"""Dependency-free tests for V1.15.2 motor semantic normalization."""

SUBCLASS_TO_CLASS = {
    "fl": "LEG", "ml": "LEG", "hl": "LEG",
    "wm": "WING", "nm": "NECK", "hm": "HALTERE",
    "ad": "ABDOMEN", "xm": "OTHER",
}
RAW_CLASS_TO_CANONICAL = {
    "leg": "LEG", "wing": "WING", "haltere": "HALTERE",
    "neck": "NECK", "abdominal": "ABDOMEN", "abdomen": "ABDOMEN",
    "other": "OTHER",
}

def resolve(raw_cls, subclass, body_id=1):
    assert subclass in SUBCLASS_TO_CLASS
    expected = SUBCLASS_TO_CLASS[subclass]
    if raw_cls:
        canonical = RAW_CLASS_TO_CANONICAL.get(raw_cls)
        assert canonical is not None
        assert canonical == expected, (body_id, raw_cls, subclass, expected)
        return canonical, "class"
    return expected, "subclass_completion"

assert resolve("", "ad") == ("ABDOMEN", "subclass_completion")
assert resolve("abdominal", "ad") == ("ABDOMEN", "class")
assert resolve("abdomen", "ad") == ("ABDOMEN", "class")
assert resolve("leg", "fl") == ("LEG", "class")
assert resolve("", "wm") == ("WING", "subclass_completion")
assert resolve("", "xm") == ("OTHER", "subclass_completion")
assert resolve("wing", "wm") == ("WING", "class")
assert resolve("haltere", "hm") == ("HALTERE", "class")
assert resolve("neck", "nm") == ("NECK", "class")
try:
    resolve("wing", "ad", 164190)
except AssertionError:
    pass
else:
    raise AssertionError("class/subclass conflict was accepted")
try:
    resolve("", "unknown", 999)
except AssertionError:
    pass
else:
    raise AssertionError("unsupported subclass was accepted")
print("VNC semantic normalization unit tests: PASS")
