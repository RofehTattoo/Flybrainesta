#!/usr/bin/env python3
"""Validate FBD104 against the current FBR-10-OLF1 structural artifact."""
from __future__ import annotations
import argparse, hashlib, json, struct
from pathlib import Path

FBC=b"FBC103\x00\x00"; FBD=b"FBD104\x00\x00"
EXPECTED_FBC_SHA="0044ab166af3439f2b86d4e6c5897481a1c3f28a58b6afb2c4f761489b276bbf"
EXPECTED_N=16669

def sha256(p:Path)->str:
    h=hashlib.sha256()
    with p.open("rb") as f:
        for c in iter(lambda:f.read(8*1024*1024),b""): h.update(c)
    return h.hexdigest()

def main(root:Path):
    structural=root/"app/src/main/res/raw/malecns_reduced.bin"
    dyn=root/"app/src/main/res/raw/malecns_fbr10_dynamics.bin"
    if not dyn.exists():
        raise SystemExit("FBD104 artifact is not present; regenerate it from the pinned MaleCNS NT table before release.")
    sb=structural.read_bytes(); db=dyn.read_bytes()
    assert sb[:8]==FBC and db[:8]==FBD
    assert sha256(structural)==EXPECTED_FBC_SHA
    sn,se=struct.unpack_from("<II",sb,8); dn,de=struct.unpack_from("<II",db,8)
    assert sn==dn==EXPECTED_N
    assert len(sb)==16+sn*26+se*12
    assert len(db)==16+dn+de*12
    signs=db[16:16+dn]
    assert all(x in (0,255,1) for x in signs)
    pos=16+dn; pos_edges=neg_edges=0; last=(-1,-1)
    for i in range(de):
        src,dst,w=struct.unpack_from("<iif",db,pos); pos+=12
        assert 0<=src<dn and 0<=dst<dn and w!=0
        sign=-1 if signs[src]==255 else (1 if signs[src]==1 else 0)
        assert sign!=0 and ((w>0)==(sign>0))
        key=(dst,src); assert key>last; last=key
        if w>0: pos_edges+=1
        else: neg_edges+=1
    assert pos==len(db)
    report=root/"build/FBR10_DYNAMICS_REPORT.json"
    if report.exists():
        r=json.loads(report.read_text(encoding="utf-8"))
        assert r.get("source_structural_sha256")==EXPECTED_FBC_SHA
        assert r.get("structural_release_id")=="FBR-10-OLF1"
        assert r.get("structural_edges")==se
        assert r.get("signed_edges")==de
    assert pos_edges>0 and neg_edges>0
    print(f"FBR-10-OLF1 FBC103: {sn} neurons / {se} structural edges / {EXPECTED_FBC_SHA}")
    print(f"FBD104: {dn} neurons / {de} signed edges / +{pos_edges} / -{neg_edges}")
    print("FBD104 provenance: PASS")

if __name__=="__main__":
    ap=argparse.ArgumentParser(); ap.add_argument("--root",type=Path,default=Path(__file__).resolve().parents[1]); main(ap.parse_args().root)
