#!/usr/bin/env python3
"""Validate the generated FBR-10-OLF1 release graph and its ORN-preservation invariants."""
from __future__ import annotations
import argparse, hashlib, json, struct
from pathlib import Path

TARGET = 16669
TARGET_ORNS = 264
EXPECTED_ORN_TYPE_LABELS = 53
EXPECTED_ORN_TYPE_ENTRY_NERVE_PAIRS = 54
NODE_SIZE = 26
EDGE_SIZE = 12
MAGIC = b"FBC103\x00\x00"


def sha256(p: Path) -> str:
    h=hashlib.sha256()
    with p.open('rb') as f:
        for chunk in iter(lambda:f.read(1024*1024), b''):
            h.update(chunk)
    return h.hexdigest()


def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--fbc103', required=True)
    ap.add_argument('--report', required=True)
    a=ap.parse_args()
    fbc=Path(a.fbc103); rep=Path(a.report)
    b=fbc.read_bytes()
    assert b[:8] == MAGIC, 'FBC103 magic mismatch'
    n,e=struct.unpack_from('<II',b,8)
    assert n == TARGET, (n,TARGET)
    assert len(b) == 16 + n*NODE_SIZE + e*EDGE_SIZE, (len(b), n, e)
    d=json.loads(rep.read_text(encoding='utf-8'))
    assert d.get('reduction') == 'FBR-10-OLF1', d.get('reduction')
    assert d.get('neurons_retained') == TARGET
    assert d.get('olfactory_orns_source') == 2639
    assert d.get('olfactory_orns_retained') == TARGET_ORNS
    assert d.get('olfactory_orn_types_source') == EXPECTED_ORN_TYPE_LABELS
    assert d.get('olfactory_orn_types_retained') == EXPECTED_ORN_TYPE_LABELS
    assert d.get('olfactory_orn_type_entry_nerve_pairs_source') == EXPECTED_ORN_TYPE_ENTRY_NERVE_PAIRS
    assert d.get('olfactory_orn_type_entry_nerve_pairs_retained') == EXPECTED_ORN_TYPE_ENTRY_NERVE_PAIRS
    assert d.get('olfactory_route_forward_source_nonzero',0) > 0
    assert d.get('olfactory_route_forward_selected_nonzero',0) > 0
    assert d.get('edges_retained') == e
    actual=sha256(fbc)
    assert len(actual)==64
    assert d.get('sha256') == actual, (d.get('sha256'), actual)
    print('FBR-10-OLF1 CONNECTOME AUDIT: PASS')
    print('neurons:',n,'edges:',e,'retained ORNs:',d['olfactory_orns_retained'],'ORN types:',d['olfactory_orn_types_retained'])
    print('FBC-OLF1 SHA-256:',actual)

if __name__=='__main__': main()
