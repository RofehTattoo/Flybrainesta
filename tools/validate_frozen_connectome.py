#!/usr/bin/env python3
"""Validate the frozen FlyBrain FBR-10 FBC103 artifact without NumPy.

All node parsing is delegated to the single authoritative FBC103 reader used
by the VNC semantic builder/validator. Edge validation remains local here
because it is a structural invariant of the frozen artifact.
"""
from __future__ import annotations

from pathlib import Path
import hashlib
import json
import struct
import sys

ROOT = Path(__file__).resolve().parents[1]
BIN = ROOT / 'app/src/main/res/raw/malecns_reduced.bin'
META = ROOT / 'app/src/main/java/com/example/flybrain/GeneratedConnectomeMeta.kt'
REPORT = ROOT / 'app/src/main/res/raw/malecns_reduced_report.json'
EXPECTED_SHA = 'bfadc30fd113c25f9711cce6ef8f6b80e9c139fe6d229965a4adabb94d8b4e60'
EXPECTED_N = 16669
EXPECTED_E = 2064951
HEADER = 16
NODE = 26
EDGE = 12

# tools/ is not a package; make the shared reader importable when invoked as a
# standalone script from the repository root or by GitHub Actions.
TOOLS = Path(__file__).resolve().parent
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))
from fbc103_reader import FBC_SHA, N, NODE_RECORD_SIZE, read_fbc103, sha256  # noqa: E402


def validate_edges(path: Path, expected_edges: int, node_count: int) -> None:
    data = path.read_bytes()
    n, e = struct.unpack_from('<II', data, 8)
    assert n == node_count and e == expected_edges, (n, e)
    nodes_end = HEADER + node_count * NODE_RECORD_SIZE
    edges_end = nodes_end + expected_edges * EDGE
    assert len(data) == edges_end, f'bad size: {len(data)} != {edges_end}'

    raw = memoryview(data)[nodes_end:edges_end]
    seen = set()
    min_pre = min_post = None
    max_pre = max_post = None
    unpack = struct.Struct('<iii').unpack_from
    for i in range(expected_edges):
        pre, post, weight = unpack(raw, i * EDGE)
        assert 0 <= pre < node_count and 0 <= post < node_count, (
            f'edge {i} endpoint out of bounds: {pre}->{post}'
        )
        assert weight > 0, f'edge {i} has non-positive structural weight: {weight}'
        key = (pre << 32) | (post & 0xffffffff)
        assert key not in seen, f'duplicate directed edge at index {i}: {pre}->{post}'
        seen.add(key)
        min_pre = pre if min_pre is None else min(min_pre, pre)
        max_pre = pre if max_pre is None else max(max_pre, pre)
        min_post = post if min_post is None else min(min_post, post)
        max_post = post if max_post is None else max(max_post, post)

    assert len(seen) == expected_edges
    assert min_pre == 0 and min_post == 0
    assert max_pre == node_count - 1 and max_post == node_count - 1


def main() -> None:
    # Shared parser performs the authoritative SHA/magic/node-count/node-record
    # parsing and bodyId uniqueness checks.
    nodes = read_fbc103(BIN)
    assert FBC_SHA == EXPECTED_SHA
    assert N == EXPECTED_N
    assert NODE_RECORD_SIZE == NODE
    assert len(nodes) == EXPECTED_N

    validate_edges(BIN, EXPECTED_E, EXPECTED_N)

    text = META.read_text(encoding='utf-8')
    for token in (
        'FLYBRAIN_VERSION = "1.14"',
        'FLYBRAIN_VERSION_CODE = 114',
        'NEURONS = 16669',
        'EDGES = 2064951',
        'CONTACTS_RETAINED = 16783932L',
        'FROZEN',
    ):
        assert token in text, f'missing meta token: {token}'

    r = json.loads(REPORT.read_text(encoding='utf-8'))
    assert r['status'] == 'FROZEN'
    assert r['neurons_retained'] == EXPECTED_N
    assert r['edges_retained'] == EXPECTED_E
    assert r['sha256'] == EXPECTED_SHA
    assert sha256(BIN) == EXPECTED_SHA

    print('FROZEN CONNECTOME: PASS')
    print(f'FBR-10 / FBC103 / neurons={EXPECTED_N} / edges={EXPECTED_E} / sha256={EXPECTED_SHA}')
    print('FBC103 shared reader: PASS')
    print('FBC103 structural edges: PASS')


if __name__ == '__main__':
    main()
