#!/usr/bin/env python3
from pathlib import Path
from fbc103_reader import N, NODE_RECORD_SIZE, read_fbc103

ROOT = Path(__file__).resolve().parents[1]
nodes = read_fbc103(ROOT / "app/src/main/res/raw/malecns_reduced.bin")
assert len(nodes) == N
assert len({n["bodyId"] for n in nodes}) == N
assert all(0 <= n["superclassCode"] <= 255 for n in nodes)
assert all(n["side"] in (-1, 0, 1) for n in nodes)
assert all(n["channel"] in range(5) for n in nodes)
assert all(n["motorRole"] in range(8) for n in nodes)
assert all(n["descendingRole"] in range(5) for n in nodes)
assert all(n["haltRole"] in range(4) for n in nodes)
print(f"FBC103 reader self-test: PASS ({N} nodes, {NODE_RECORD_SIZE}-byte records)")
