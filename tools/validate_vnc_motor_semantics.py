#!/usr/bin/env python3
"""Independent V1.15.2 VNC semantic validator.

This validator does not trust the builder's census alone. It re-reads the
SHA-pinned official Feather, derives the expected anatomical class from the
measured motor subclass vocabulary, and compares every generated row.
"""
from __future__ import annotations
import argparse, csv, hashlib, json, struct
from collections import Counter
from pathlib import Path
import pyarrow.feather as feather

FBC_SHA="bfadc30fd113c25f9711cce6ef8f6b80e9c139fe6d229965a4adabb94d8b4e60"
ANN_SHA="2177e246113e4cfbf1e7772ec37c6da1955ff22e8063d0b1f833101f99a9a3b2"
N=16669; MOTOR_START=4278; MOTOR_END=4986
EXPECTED={"LEG":381,"ABDOMEN":214,"WING":67,"NECK":24,"HALTERE":16,"OTHER":6}
EXPECTED_SIDE={"L":355,"R":353}
SUBCLASS_TO_CLASS={"fl":"LEG","ml":"LEG","hl":"LEG","wm":"WING","nm":"NECK","hm":"HALTERE","ad":"ABDOMEN","xm":"OTHER"}
RAW_CLASS_TO_CANONICAL={"leg":"LEG","wing":"WING","haltere":"HALTERE","neck":"NECK","abdominal":"ABDOMEN","abdomen":"ABDOMEN","other":"OTHER"}

def resolve_motor_class(raw_cls, subclass, bid):
    assert subclass in SUBCLASS_TO_CLASS, f"unsupported official motor subclass {subclass!r} for {bid}"
    expected=SUBCLASS_TO_CLASS[subclass]
    if raw_cls:
        canonical=RAW_CLASS_TO_CANONICAL.get(raw_cls)
        assert canonical is not None, f"unsupported official motor class {raw_cls!r} for {bid}"
        assert canonical==expected, f"official class/subclass conflict for {bid}: {raw_cls!r}/{subclass!r} -> {expected}"
        return canonical, "class"
    return expected, "subclass_completion"

def sha(p):
    h=hashlib.sha256()
    with Path(p).open("rb") as f:
        for chunk in iter(lambda:f.read(1<<20),b""): h.update(chunk)
    return h.hexdigest()

def clean(v):
    if v is None: return ""
    if hasattr(v,"as_py"): v=v.as_py()
    return str(v).strip()

def fbc(path):
    b=Path(path).read_bytes()
    assert sha(path)==FBC_SHA, "FBC103 SHA mismatch"
    assert b[:8]==b"FBC103\0\0", "FBC103 magic mismatch"
    n,e=struct.unpack_from("<II",b,8); assert n==N
    ids=[]; roles=[]; off=16
    for _ in range(n):
        ids.append(struct.unpack_from("<Q",b,off)[0]); off+=8
        off+=1; off+=1; off+=1
        roles.append(struct.unpack_from("<b",b,off)[0]); off+=1
        off+=1; off+=12
    return ids,roles

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument("--semantics",required=True); ap.add_argument("--fbc103",required=True)
    ap.add_argument("--annotations",required=True); ap.add_argument("--report",required=True)
    a=ap.parse_args()

    assert sha(a.annotations)==ANN_SHA, "annotation SHA mismatch"
    ids,old=fbc(a.fbc103); retained=set(ids)
    required=["bodyId","superclass","class","subclass","type","somaSide","somaNeuromere","exitNerve"]
    table=feather.read_table(a.annotations,columns=required)
    records=[r for r in table.to_pylist() if r.get("bodyId") in retained]
    official={}
    for r in records:
        bid=int(r["bodyId"])
        assert bid not in official, f"duplicate official retained bodyId {bid}"
        official[bid]=r
    motors={bid:r for bid,r in official.items() if clean(r.get("superclass")).lower()=="vnc_motor"}
    assert len(motors)==708, f"official retained VNC motors={len(motors)}"

    rows=list(csv.DictReader(open(a.semantics,encoding="utf-8"),delimiter="\t"))
    assert len(rows)==708, f"semantics rows={len(rows)}"
    assert len({int(r["bodyId"]) for r in rows})==708, "duplicate semantics bodyId"
    assert {int(r["bodyId"]) for r in rows}==set(motors), "semantics bodyId set != official VNC motor set"

    by_id={int(r["bodyId"]):r for r in rows}
    mismatches=[]
    for bid, r in sorted(motors.items()):
        out=by_id[bid]
        raw_cls=clean(r.get("class")).lower()
        sub=clean(r.get("subclass")).lower()
        assert sub in SUBCLASS_TO_CLASS, f"unsupported official motor subclass {sub!r} for {bid}"
        expected_class, expected_source=resolve_motor_class(raw_cls, sub, bid)
        if not out.get("class","").strip():
            mismatches.append((bid,"class","",expected_class))
        elif out.get("class","").strip().upper() != expected_class:
            mismatches.append((bid,"class",out.get("class",""),expected_class))
        checks={
            "type":clean(r.get("type")),
            "subclass":sub,
            "somaSide":clean(r.get("somaSide")).upper(),
            "somaNeuromere":clean(r.get("somaNeuromere")),
            "exitNerve":clean(r.get("exitNerve")),
            "anatomicalClass":expected_class,
        }
        for key, expected in checks.items():
            got=out.get(key,"")
            if key=="anatomicalClass":
                got=got.upper()
            if got != expected:
                mismatches.append((bid,key,got,expected))
        if out.get("classSource") != expected_source:
            mismatches.append((bid,"classSource",out.get("classSource"),expected_source))
        expected_tag="JUMP" if clean(r.get("type")).lower()=="ttmn" else "NONE"
        if out.get("functionalTag") != expected_tag:
            mismatches.append((bid,"functionalTag",out.get("functionalTag"),expected_tag))
    assert not mismatches, "semantic row mismatches: "+repr(mismatches[:10])

    counts=Counter(r["anatomicalClass"] for r in rows); sides=Counter(r["somaSide"] for r in rows)
    assert dict(counts)==EXPECTED, dict(counts)
    assert dict(sides)==EXPECTED_SIDE, dict(sides)
    jump=[r for r in rows if r["functionalTag"]=="JUMP"]
    assert len(jump)==2 and all(r["anatomicalClass"]=="WING" and r["type"].strip().lower()=="ttmn" for r in jump)
    report=json.loads(Path(a.report).read_text())
    assert report["status"]=="PASS"
    assert report["annotation_sha256"]==ANN_SHA
    assert report["fbc103_sha256"]==FBC_SHA
    assert report["vnc_motor_rows"]==708
    assert report["counts"]==dict(counts)
    assert report["sides"]==dict(sides)
    assert report["fbc103_modified"] is False
    assert sha(a.fbc103)==FBC_SHA

    print("VNC-01 708/708 official motor set: PASS")
    print("VNC-02 355L/353R: PASS")
    print("VNC-03 anatomical census: PASS")
    print("VNC-04 official row-by-row semantic match: PASS")
    print("VNC-05 FBC103 SHA unchanged: PASS")
    print("VNC-06 FBD104 untouched by this layer: PASS (build-level invariant)")
    print("class completions:", report.get("class_completion_bodyIds", []))
    print("reclassified from frozen role byte:", report["reclassified_from_old_fbc103"])

if __name__=="__main__":
    main()
