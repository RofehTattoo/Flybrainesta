#!/usr/bin/env python3
"""Single authoritative FBC103 record reader for build/validation tools.

FBC103 node record layout (26 bytes):
uint64 bodyId
uint8  superclassCode
int8   somaSideCode
uint8  channelCode
int8   motorRoleCode
int8   descendingRoleCode
int8   haltRoleCode
float32 routeForward
float32 routeTurn
float32 routeEscape

This is the layout emitted by the canonical FBR-10-OLF2-MOTORROUTE builder and consumed by MainActivity. The reader is format-generic; release-specific SHA pinning belongs in the validator, not in the parser.
There is no reserved byte and the first byte after bodyId is the superclass code.
"""
from __future__ import annotations
import hashlib
import struct
from pathlib import Path

N = 16669
HEADER_SIZE = 16
NODE_RECORD_SIZE = 26

def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(8 * 1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()

def read_fbc103(path: Path, expected_sha: str | None = None) -> list[dict]:
    data = path.read_bytes()
    if expected_sha is not None and sha256(path) != expected_sha:
        raise ValueError("FBC103 SHA mismatch")
    if data[:8] != b"FBC103\x00\x00":
        raise ValueError("FBC103 magic mismatch")
    n, edge_count = struct.unpack_from("<II", data, 8)
    if n != N:
        raise ValueError(f"FBC103 neurons={n}, expected {N}")
    nodes_end = HEADER_SIZE + n * NODE_RECORD_SIZE
    if len(data) < nodes_end:
        raise ValueError("FBC103 truncated before node records")

    nodes = []
    off = HEADER_SIZE
    for idx in range(n):
        bid = struct.unpack_from("<Q", data, off)[0]; off += 8
        superclass_code = struct.unpack_from("<B", data, off)[0]; off += 1
        side = struct.unpack_from("<b", data, off)[0]; off += 1
        channel = struct.unpack_from("<B", data, off)[0]; off += 1
        motor_role = struct.unpack_from("<b", data, off)[0]; off += 1
        dn_role = struct.unpack_from("<b", data, off)[0]; off += 1
        halt_role = struct.unpack_from("<b", data, off)[0]; off += 1
        routes = struct.unpack_from("<fff", data, off); off += 12
        nodes.append({
            "index": idx,
            "bodyId": bid,
            "superclassCode": superclass_code,
            "side": side,
            "channel": channel,
            "motorRole": motor_role,
            "descendingRole": dn_role,
            "haltRole": halt_role,
            "routes": routes,
        })

    if off != nodes_end:
        raise ValueError(f"FBC103 node parser offset={off}, expected={nodes_end}")
    ids = [r["bodyId"] for r in nodes]
    if len(set(ids)) != N:
        raise ValueError("FBC103 duplicate bodyId")
    return nodes
