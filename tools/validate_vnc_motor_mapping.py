#!/usr/bin/env python3
"""Validate that the frozen FBR-10 VNC motor metadata is intact.

This is an audit-only guard. It never assigns new motor roles and never edits FBC103.
The current frozen FBR-10 runtime motor block is expected to contain 708 vnc_motor
neurons with the role census recorded below.
"""
from __future__ import annotations

import argparse
import hashlib
import struct
from collections import Counter, defaultdict
from pathlib import Path

FBC_MAGIC = b"FBC103\x00\x00"
EXPECTED_SHA256 = "bfadc30fd113c25f9711cce6ef8f6b80e9c139fe6d229965a4adabb94d8b4e60"
MOTOR_START = 4278
MOTOR_END = 4986
ROLE = {
    1: "LEG",
    2: "WING",
    3: "HALTERE",
    4: "NECK",
    5: "ABDOMEN",
    6: "JUMP",
    7: "OTHER",
}
EXPECTED_ROLE_COUNTS = {1: 213, 2: 26, 3: 0, 4: 0, 5: 0, 6: 2, 7: 467}
EXPECTED_LEG_SIDES = {-1: 108, 1: 105, 0: 0}
EXPECTED_WING_SIDES = {-1: 13, 1: 13, 0: 0}
EXPECTED_JUMP_SIDES = {-1: 1, 1: 1, 0: 0}


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(8 * 1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def main(root: Path) -> None:
    path = root / "app" / "src" / "main" / "res" / "raw" / "malecns_reduced.bin"
    raw = path.read_bytes()
    assert sha256(path) == EXPECTED_SHA256, "frozen FBC103 SHA-256 changed"
    assert raw[:8] == FBC_MAGIC, "not FBC103"
    n, e = struct.unpack_from("<II", raw, 8)
    assert n == 16669
    assert len(raw) == 16 + n * 26 + e * 12

    role_counts = Counter()
    side_counts = defaultdict(Counter)
    pos = 16
    for i in range(n):
        body, sc, side, ch, motor_role, desc_role, halt_role, rf, rt, re = struct.unpack_from(
            "<qbbbbbbfff", raw, pos
        )
        del body, sc, ch, desc_role, halt_role, rf, rt, re
        pos += 26
        if MOTOR_START <= i < MOTOR_END:
            assert 1 <= motor_role <= 7, f"invalid VNC motor role at node {i}: {motor_role}"
            role_counts[motor_role] += 1
            side_counts[motor_role][side] += 1
        else:
            assert motor_role in (0, 1, 2, 3, 4, 5, 6, 7)

    assert sum(role_counts.values()) == MOTOR_END - MOTOR_START == 708
    assert {r: role_counts[r] for r in EXPECTED_ROLE_COUNTS} == EXPECTED_ROLE_COUNTS
    assert {side: side_counts[1][side] for side in EXPECTED_LEG_SIDES} == EXPECTED_LEG_SIDES
    assert {side: side_counts[2][side] for side in EXPECTED_WING_SIDES} == EXPECTED_WING_SIDES
    assert {side: side_counts[6][side] for side in EXPECTED_JUMP_SIDES} == EXPECTED_JUMP_SIDES

    print(f"FBC103 SHA-256: {EXPECTED_SHA256} (FROZEN)")
    print(f"VNC motor block: {MOTOR_START}:{MOTOR_END} = {MOTOR_END-MOTOR_START} neurons")
    print("Role census:", ", ".join(f"{ROLE[r]}={role_counts[r]}" for r in sorted(ROLE)))
    print("LEG sides: L=108 R=105 U=0")
    print("WING sides: L=13 R=13 U=0")
    print("JUMP sides: L=1 R=1 U=0")
    print("VNC MOTOR MAPPING: OK")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    main(ap.parse_args().root)
