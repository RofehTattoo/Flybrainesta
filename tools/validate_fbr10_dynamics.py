#!/usr/bin/env python3
"""Validate FBD105 reference-like signed dynamics against current FBC103."""
from __future__ import annotations
import argparse, hashlib, json, math, struct
from pathlib import Path

FBC=b"FBC103\x00\x00"; FBD=b"FBD105\x00\x00"
EXPECTED_N=16669

def sha256(p:Path)->str:
    h=hashlib.sha256()
    with p.open("rb") as f:
        for c in iter(lambda:f.read(8*1024*1024),b""): h.update(c)
    return h.hexdigest()

def main(root:Path):
    structural=root/"app/src/main/res/raw/malecns_reduced.bin"
    dyn=root/"app/src/main/res/raw/malecns_fbr10_dynamics.bin"
    if not dyn.exists(): raise SystemExit("FBD105 artifact is not present; regenerate it from the pinned MaleCNS NT table before release.")
    sb=structural.read_bytes(); db=dyn.read_bytes()
    assert sb[:8]==FBC and db[:8]==FBD
    report=root/"build/FBR10_DYNAMICS_REPORT.json"
    r=json.loads(report.read_text(encoding="utf-8")) if report.exists() else {}
    expected_sha=r.get("source_structural_sha256")
    if not isinstance(expected_sha,str) or len(expected_sha)!=64:
        raise AssertionError("FBD105 report missing pinned source_structural_sha256")
    assert sha256(structural)==expected_sha
    sn,se=struct.unpack_from("<II",sb,8); dn,de=struct.unpack_from("<II",db,8)
    assert sn==dn==EXPECTED_N
    assert len(sb)==16+sn*26+se*12
    assert len(db)==16+dn+de*12
    signs=db[16:16+dn]
    assert all(x in (0,255,1) for x in signs)
    pos=16+dn; pos_edges=neg_edges=0; last=(-1,-1); abs_max=0.0
    for i in range(de):
        src,dst,w=struct.unpack_from("<iif",db,pos); pos+=12
        assert 0<=src<dn and 0<=dst<dn and w!=0
        sign=-1 if signs[src]==255 else (1 if signs[src]==1 else 0)
        assert sign!=0 and ((w>0)==(sign>0))
        key=(dst,src); assert key>last; last=key
        abs_max=max(abs_max,abs(w))
        if w>0: pos_edges+=1
        else: neg_edges+=1
    assert pos==len(db)
    # Raw MaleCNS contact counts are multiplied by 0.275 mV. There is no
    # scientifically justified universal upper bound of 100 mV here: a large
    # retained contact count can legitimately produce a larger edge weight.
    # Keep the meaningful lower-bound and finiteness checks, and report the
    # measured maximum so unexpected magnitudes remain visible in CI.
    assert math.isfinite(abs_max) and abs_max >= 0.275, (
        f"FBD105 maximum absolute edge weight is invalid: {abs_max!r} mV"
    )
    if report.exists():
        assert r.get("source_structural_sha256")==expected_sha
        assert r.get("format")=="FBD105"
        assert r.get("structural_edges")==se
        assert r.get("signed_edges")==de
        assert r.get("weight_definition", "").startswith("signed raw retained MaleCNS contact count")
        assert abs(float(r.get("w_syn_mv",0)) - 0.275) < 1e-9
        assert abs(float(r.get("w_syn_reduced_mv",0)) - 0.275) < 1e-9
        assert abs(float(r.get("density_compensation",0)) - 1.0) < 1e-9
        assert "nt_predicted_fallback_signs_used" in r
        assert "nt_unresolved_bodies" in r
        assert "nt_resolution_policy" in r
        assert "predicted_nt fallback" in r["nt_resolution_policy"]
    assert pos_edges>0 and neg_edges>0
    print(f"FBC103: {sn} neurons / {se} structural edges / {expected_sha}")
    print(f"FBD105: {dn} neurons / {de} signed edges / +{pos_edges} / -{neg_edges}")
    print(f"FBD105 max |edge weight|: {abs_max:.6g} mV")
    print("FBD105 provenance: PASS")

if __name__=="__main__":
    ap=argparse.ArgumentParser(); ap.add_argument("--root",type=Path,default=Path(__file__).resolve().parents[1]); main(ap.parse_args().root)
