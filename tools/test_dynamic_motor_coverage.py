#!/usr/bin/env python3
"""Validate that the generated signed dynamics still exposes sensor->DN and DN->LEG paths.

This is a release-integrity test, not a behavioral benchmark. It never invents edges
and does not require the Android runtime. When FBD105 is present, the test parses the
actual generated dynamic graph; otherwise it checks the source contract so local
bootstrap trees can still run the static suite.
"""
from __future__ import annotations

import math
import struct
from collections import deque
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/com/example/flybrain/MainActivity.kt").read_text(encoding="utf-8")
BUILDER = (ROOT / "tools/build_fbr10_dynamics.py").read_text(encoding="utf-8")

assert "FBD105" in BUILDER
assert "W_SYN_FULL_MV = 0.275" in BUILDER
assert "W_SYN_REDUCED_MV = W_SYN_FULL_MV" in BUILDER
assert "signed raw retained MaleCNS contact count" in BUILDER
assert "MOTOR_LEG" in MAIN
assert "legRateHz" in MAIN
assert "relaxMotorActivation" in MAIN
assert "approachAction" not in MAIN[MAIN.index("private fun driveBody"):MAIN.index("private fun runNeuralSimulation")]

fbc = ROOT / "app/src/main/res/raw/malecns_reduced.bin"
fbd = ROOT / "app/src/main/res/raw/malecns_fbr10_dynamics.bin"
if not fbd.exists():
    print("DYNAMIC MOTOR COVERAGE: SOURCE CONTRACT PASS (FBD105 not present locally; CI will parse generated artifact)")
    raise SystemExit(0)

raw = fbc.read_bytes()
assert raw[:8] == b"FBC103\x00\x00"
n, e = struct.unpack_from("<II", raw, 8)
assert n == 16669
assert len(raw) == 16 + n * 26 + e * 12
nodes = []
for i in range(n):
    body, sc, side, ch, motor_role, desc_role, halt_role, rf, rt, re = struct.unpack_from(
        "<qbbbbbbfff", raw, 16 + 26 * i
    )
    nodes.append((body, sc, ch, motor_role))

# Canonical FBC builder uses channel=1 for olfactory and superclass text encoded as
# descending_neuron/vnc_motor in metadata role records. The role byte is authoritative
# for motor identity; for DNs the canonical superclass code is reconstructed from the
# source label in build_connectome.py, so use code/name-independent block inspection
# from the generated metadata ranges when available.
olf = {i for i, x in enumerate(nodes) if x[2] == 1}
leg = {i for i, x in enumerate(nodes) if x[3] == 1}
# The canonical builder retains every DN and VNC motor; the DN block is between the
# generated DESC_START/DESC_END constants. Read them without importing Kotlin.
meta = (ROOT / "app/src/main/java/com/example/flybrain/GeneratedConnectomeMeta.kt").read_text(encoding="utf-8")
import re
m1 = re.search(r"const val DESC_START = (\d+)", meta)
m2 = re.search(r"const val DESC_END = (\d+)", meta)
assert m1 and m2
DN = set(range(int(m1.group(1)), int(m2.group(1))))
assert DN

# Parse FBD105 edges and build compact adjacency + incoming counts.
db = fbd.read_bytes()
assert db[:8] == b"FBD105\x00\x00"
n2, e2 = struct.unpack_from("<II", db, 8)
assert n2 == n
assert len(db) == 16 + n2 + e2 * 12
signs = db[16:16 + n2]
out = [[] for _ in range(n2)]
leg_in = 0
leg_exc_in = 0
for k in range(e2):
    s, t, w = struct.unpack_from("<iif", db, 16 + n2 + 12 * k)
    assert 0 <= s < n2 and 0 <= t < n2 and math.isfinite(w) and w != 0
    assert signs[s] in (0, 1, 255)
    assert (w > 0) == (signs[s] == 1)
    out[s].append(t)
    if t in leg:
        leg_in += 1
        if w > 0:
            leg_exc_in += 1

assert leg_in > 0, "FBD105 contains no signed dynamic input to any LEG MN"
assert leg_exc_in > 0, "FBD105 contains no excitatory signed input to any LEG MN"

# Reachability within three directed synaptic hops. This is a topology/dynamics-layer
# integrity test only; it does not assert that the path is sufficient to make a neuron spike.
def reaches(starts: set[int], targets: set[int], max_hops: int) -> bool:
    q = deque((s, 0) for s in starts)
    seen = set(starts)
    while q:
        node, hop = q.popleft()
        if node in targets:
            return True
        if hop >= max_hops:
            continue
        for nxt in out[node]:
            if nxt not in seen:
                seen.add(nxt)
                q.append((nxt, hop + 1))
    return False

assert reaches(olf, DN, 3), "No signed dynamic path ORN->DN within 3 hops"
assert reaches(DN, leg, 3), "No signed dynamic path DN->LEG within 3 hops"
print(f"DYNAMIC MOTOR COVERAGE: PASS (FBD105 edges={e2}, LEG dynamic inputs={leg_in}, excitatory LEG inputs={leg_exc_in})")
