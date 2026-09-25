#!/usr/bin/env python3
"""Validate the generated FBR-10-OLF2-MOTORROUTE release graph and its ORN-preservation invariants."""
from __future__ import annotations
import argparse, hashlib, json, math, struct
from pathlib import Path

TARGET = 16669
TARGET_ORNS = 264
EXPECTED_ORN_TYPE_LABELS = 53
EXPECTED_ORN_TYPE_ENTRY_NERVE_PAIRS = 54
EXPECTED_UNTYPED_ORN_BODY_IDS = {242812, 242908, 488209, 956041}
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
    edge_start = 16 + n * NODE_SIZE
    seen_edges = set()
    contact_total = 0
    for i in range(e):
        src, dst, weight = struct.unpack_from('<iif', b, edge_start + i * EDGE_SIZE)
        assert 0 <= src < n and 0 <= dst < n, (i, src, dst)
        assert math.isfinite(weight) and weight > 0 and float(weight).is_integer(), (
            i, src, dst, weight
        )
        assert (src, dst) not in seen_edges, (i, src, dst)
        seen_edges.add((src, dst))
        contact_total += int(weight)
    assert len(seen_edges) == e
    d=json.loads(rep.read_text(encoding='utf-8'))
    assert d.get('reduction') == 'FBR-10-OLF2-MOTORROUTE', d.get('reduction')
    assert d.get('neurons_retained') == TARGET
    assert d.get('edge_weight_definition') == 'raw positive MaleCNS contact counts; neurotransmitter sign is applied only in FBD105'
    assert d.get('candidate_edges_between_retained_neurons') == e
    assert d.get('contacts_retained') == contact_total
    assert d.get('olfactory_orns_source') == 2639
    assert d.get('olfactory_orns_retained') == TARGET_ORNS
    assert set(d.get('untyped_orn_body_ids_source', [])) == EXPECTED_UNTYPED_ORN_BODY_IDS
    assert set(d.get('untyped_orn_body_ids_retained', [])) == EXPECTED_UNTYPED_ORN_BODY_IDS
    assert d.get('olfactory_orn_types_source') == EXPECTED_ORN_TYPE_LABELS
    assert d.get('olfactory_orn_types_retained') == EXPECTED_ORN_TYPE_LABELS
    source_pairs = d.get('olfactory_orn_type_entry_nerve_pairs_source')
    retained_pairs = d.get('olfactory_orn_type_entry_nerve_pairs_retained')
    assert isinstance(source_pairs, list), 'source ORN type/entryNerve pairs must be a list'
    assert isinstance(retained_pairs, list), 'retained ORN type/entryNerve pairs must be a list'
    assert len(source_pairs) == EXPECTED_ORN_TYPE_ENTRY_NERVE_PAIRS
    assert len(retained_pairs) == EXPECTED_ORN_TYPE_ENTRY_NERVE_PAIRS

    def pair_key(row):
        assert isinstance(row, dict), row
        typ = str(row.get('type', '')).strip()
        nerve = str(row.get('entryNerve', '')).strip().upper()
        assert typ and nerve, row
        return (typ, nerve)

    source_pair_set = {pair_key(row) for row in source_pairs}
    retained_pair_set = {pair_key(row) for row in retained_pairs}
    assert len(source_pair_set) == EXPECTED_ORN_TYPE_ENTRY_NERVE_PAIRS
    assert len(retained_pair_set) == EXPECTED_ORN_TYPE_ENTRY_NERVE_PAIRS
    assert source_pair_set == retained_pair_set, (
        source_pair_set - retained_pair_set,
        retained_pair_set - source_pair_set,
    )
    assert d.get('olfactory_route_forward_source_nonzero',0) > 0
    assert d.get('olfactory_route_forward_selected_nonzero',0) > 0
    assert d.get('edges_retained') == e
    actual=sha256(fbc)
    assert len(actual)==64
    assert d.get('sha256') == actual, (d.get('sha256'), actual)
    print('FBR-10-OLF2-MOTORROUTE CONNECTOME AUDIT: PASS')
    print('neurons:',n,'edges:',e,'retained ORNs:',d['olfactory_orns_retained'],'ORN types:',d['olfactory_orn_types_retained'])
    print('FBC-OLF2 SHA-256:',actual)

if __name__=='__main__': main()
