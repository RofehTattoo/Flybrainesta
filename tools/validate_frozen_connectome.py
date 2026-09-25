#!/usr/bin/env python3
"""Validate the current frozen FBR-10-OLF2-MOTORROUTE FBC103 artifact.

Historical FBR-10 v1.14 validation belongs under docs/history and is never used
as the current release gate.
"""
from __future__ import annotations
import hashlib, json, math, re, struct
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
BIN = ROOT / "app/src/main/res/raw/malecns_reduced.bin"
META = ROOT / "app/src/main/java/com/example/flybrain/GeneratedConnectomeMeta.kt"
REPORT = ROOT / "app/src/main/res/raw/malecns_reduced_report.json"
TOOLS = ROOT / "tools"
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))
from fbc103_reader import read_fbc103, sha256

def meta_int(text: str, name: str) -> int:
    m = re.search(rf"const val {re.escape(name)} = (-?\d+)", text)
    assert m, f"missing metadata constant {name}"
    return int(m.group(1))

def main() -> None:
    meta = META.read_text(encoding="utf-8")
    report = json.loads(REPORT.read_text(encoding="utf-8"))
    m = re.search(r'const val BINARY_SHA256 = "([0-9a-f]{64})"', meta)
    assert m, "missing BINARY_SHA256"
    expected_sha = m.group(1)
    expected_edges = meta_int(meta, "EDGES")
    assert "FBR-10-OLF2-MOTORROUTE" in meta
    assert meta_int(meta, "NEURONS") == 16669
    assert meta_int(meta, "FORMAT_VERSION") == 103
    assert report["reduction"] == "FBR-10-OLF2-MOTORROUTE"
    assert report["neurons_retained"] == 16669
    assert report["edges_retained"] == expected_edges
    assert report["sha256"] == expected_sha
    assert sha256(BIN) == expected_sha
    nodes = read_fbc103(BIN, expected_sha)
    data = BIN.read_bytes()
    n, e = struct.unpack_from("<II", data, 8)
    assert n == 16669 and e == expected_edges
    assert len(data) == 16 + n * 26 + e * 12
    seen = set()
    pos = 16 + n * 26
    contacts = 0
    for i in range(e):
        src, dst, weight = struct.unpack_from("<iif", data, pos + i * 12)
        assert 0 <= src < n and 0 <= dst < n
        assert math.isfinite(weight) and weight > 0 and float(weight).is_integer()
        assert (src, dst) not in seen
        seen.add((src, dst)); contacts += int(weight)
    assert contacts == report["contacts_retained"]
    assert len(nodes) == n
    print("CURRENT FBR-10-OLF2-MOTORROUTE FBC103: PASS")
    print(f"neurons={n} edges={e} contacts={contacts} sha256={expected_sha}")

if __name__ == "__main__":
    main()
