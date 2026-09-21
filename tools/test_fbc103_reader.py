#!/usr/bin/env python3
from pathlib import Path
from fbc103_reader import N, NODE_RECORD_SIZE, read_fbc103

ROOT = Path(__file__).resolve().parents[1]
nodes = read_fbc103(ROOT / "app/src/main/res/raw/malecns_reduced.bin")
assert len(nodes) == N
assert len({n["bodyId"] for n in nodes}) == N
assert all(n["role"] in range(-128,128) for n in nodes)
print(f"FBC103 reader self-test: PASS ({N} nodes, {NODE_RECORD_SIZE}-byte records)")
