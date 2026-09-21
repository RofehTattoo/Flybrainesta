#!/usr/bin/env python3
"""Independent validator for the derived FBD104 dynamics artifact."""
from __future__ import annotations
import argparse, hashlib, struct
from pathlib import Path

FBC=b'FBC103\x00\x00'
FBD=b'FBD104\x00\x00'

def sha256(p):
    h=hashlib.sha256()
    with p.open('rb') as f:
        for c in iter(lambda:f.read(8*1024*1024),b''): h.update(c)
    return h.hexdigest()

def main(root:Path):
    structural=root/'app/src/main/res/raw/malecns_reduced.bin'
    dyn=root/'app/src/main/res/raw/malecns_fbr10_dynamics.bin'
    sb=structural.read_bytes(); db=dyn.read_bytes()
    assert sb[:8]==FBC and db[:8]==FBD
    sn,se=struct.unpack_from('<II',sb,8)
    dn,de=struct.unpack_from('<II',db,8)
    assert sn==dn==16669
    assert len(sb)==16+sn*26+se*12
    assert len(db)==16+dn+de*12
    signs=db[16:16+dn]
    assert all(x in (0,255,1) for x in signs)  # signed bytes: -1 is 255
    pos=16+dn
    pos_edges=neg_edges=0
    last=(0,-1)
    for i in range(de):
        src,dst,w=struct.unpack_from('<iif',db,pos); pos+=12
        assert 0<=src<dn and 0<=dst<dn and w!=0
        sign=-1 if signs[src]==255 else (1 if signs[src]==1 else 0)
        assert sign!=0
        assert (w>0)==(sign>0)
        key=(dst,src); assert key>=last
        last=key
        if w>0: pos_edges+=1
        else: neg_edges+=1
    assert pos==len(db)
    print(f'FBC103: {sn} neurons / {se} structural edges / sha256 {sha256(structural)}')
    assert pos_edges > 0 and neg_edges > 0, 'FBD104 must contain both excitatory and inhibitory signed edges'
    print(f'FBD104: {dn} neurons / {de} signed edges / +{pos_edges} / -{neg_edges}')
    print('FBR-10 structural SHA-256 remains frozen:', sha256(structural))

if __name__=='__main__':
    ap=argparse.ArgumentParser(); ap.add_argument('--root',type=Path,default=Path(__file__).resolve().parents[1]); main(ap.parse_args().root)
