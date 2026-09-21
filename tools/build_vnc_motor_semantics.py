#!/usr/bin/env python3
"""Build the V1.15.2 VNC motor semantics layer from official MaleCNS annotations.

This script never rewrites FBC103. It reads the frozen FBR-10 binary only to
recover the retained bodyIds and previous role bytes for audit comparison.
The official annotation Feather is the sole source of anatomical class/side/
type/subclass/neuromere/exit-nerve data.
"""
from __future__ import annotations
import argparse, csv, hashlib, json, struct
from collections import Counter
from pathlib import Path
import pyarrow.feather as feather

FBC_SHA = "bfadc30fd113c25f9711cce6ef8f6b80e9c139fe6d229965a4adabb94d8b4e60"
ANN_SHA = "2177e246113e4cfbf1e7772ec37c6da1955ff22e8063d0b1f833101f99a9a3b2"
N = 16669
MOTOR_START, MOTOR_END = 4278, 4986
EXPECTED = {"leg":381, "abdominal":214, "wing":67, "neck":24, "haltere":16, "other":6}
EXPECTED_SIDE = {"L":355, "R":353}
ROLE_NAMES = {1:"LEG",2:"WING",3:"HALTERE",4:"NECK",5:"ABDOMEN",6:"OTHER",7:"OLD_OTHER",0:"NON_MOTOR"}


def sha256(p: Path) -> str:
    h=hashlib.sha256()
    with p.open('rb') as f:
        for b in iter(lambda:f.read(8*1024*1024), b''): h.update(b)
    return h.hexdigest()


def clean(v):
    if v is None: return ""
    try:
        if hasattr(v, 'as_py'): v=v.as_py()
    except Exception: pass
    return str(v).strip()


def read_fbc(path: Path):
    b=path.read_bytes()
    if sha256(path)!=FBC_SHA: raise SystemExit("FBC103 SHA mismatch")
    if b[:8] != b'FBC103\x00\x00': raise SystemExit("FBC103 magic mismatch")
    n,e=struct.unpack_from('<II', b, 8)
    if n != N: raise SystemExit(f"FBC103 neurons={n}, expected {N}")
    body=[]; old=[]; side=[]
    off=16
    for _ in range(n):
        bid=struct.unpack_from('<Q', b, off)[0]; off+=8
        off += 1 # channel
        s=struct.unpack_from('<b', b, off)[0]; off+=1
        off += 1 # reserved
        r=struct.unpack_from('<b', b, off)[0]; off+=1
        off += 1 # dn role
        off += 1 # halt role
        off += 12 # three route floats
        body.append(bid); side.append(s); old.append(r)
    return body, old, side


def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--annotations', required=True)
    ap.add_argument('--fbc103', required=True)
    ap.add_argument('--output', required=True)
    ap.add_argument('--report', required=True)
    args=ap.parse_args()
    ann_path=Path(args.annotations); fbc=Path(args.fbc103)
    if sha256(ann_path)!=ANN_SHA: raise SystemExit("official annotation SHA mismatch")
    body, old_role, old_side=read_fbc(fbc)
    retained=set(body)
    # Do NOT use the pandas-converting Feather convenience reader here: that convenience API converts
    # through pandas and makes pandas an implicit runtime dependency. The
    # V1.15.2 audit only needs PyArrow, so keep the data Arrow-native.
    required=['bodyId','superclass','class','subclass','type','somaSide','somaNeuromere','exitNerve']
    table=feather.read_table(ann_path, columns=required)
    missing=[c for c in required if c not in table.column_names]
    if missing: raise SystemExit(f"missing official columns: {missing}")
    records=table.to_pylist()
    records=[r for r in records if r.get('bodyId') in retained]
    by_body={}
    for r in records:
        bid=int(r['bodyId'])
        if bid in by_body:
            raise SystemExit(f"duplicate official annotation bodyId {bid}")
        by_body[bid]=r
    motors=[r for r in by_body.values()
            if clean(r.get('superclass')).lower() == 'vnc_motor']
    if len(motors)!=708: raise SystemExit(f"FBR-10 VNC motor count={len(motors)}, expected 708")
    old={int(b):(int(r),int(s)) for b,r,s in zip(body,old_role,old_side)}
    rows=[]
    for r in motors:
        bid=int(r['bodyId']); cls=clean(r.get('class')).lower()
        if cls not in EXPECTED: raise SystemExit(f"unsupported official motor class {cls!r} for {bid}")
        side=clean(r.get('somaSide')).upper()
        if side not in ('L','R'): raise SystemExit(f"invalid motor side {side!r} for {bid}")
        typ=clean(r.get('type')); func='JUMP' if typ.lower() == 'ttmn' else 'NONE'
        oldr=old[bid][0]
        expected_role={'leg':1,'wing':2,'haltere':3,'neck':4,'abdominal':5,'other':6}[cls]
        rows.append({
            'bodyId':bid,'type':typ,'class':cls,'subclass':clean(r.get('subclass')),
            'somaSide':side,'somaNeuromere':clean(r.get('somaNeuromere')),
            'exitNerve':clean(r.get('exitNerve')),'anatomicalClass':cls.upper(),
            'functionalTag':func,'currentFBC103Role':ROLE_NAMES.get(oldr,'UNKNOWN'),
            'currentFBC103RoleCode':oldr,'semanticRoleCode':expected_role,
            'discrepancy': 'MATCH' if oldr==expected_role else 'RECLASSIFIED'
        })
    rows.sort(key=lambda x:x['bodyId'])
    counts=Counter(x['class'] for x in rows); sides=Counter(x['somaSide'] for x in rows)
    if dict(counts)!=EXPECTED: raise SystemExit(f"class census {dict(counts)} != {EXPECTED}")
    if dict(sides)!=EXPECTED_SIDE: raise SystemExit(f"side census {dict(sides)} != {EXPECTED_SIDE}")
    out=Path(args.output); out.parent.mkdir(parents=True,exist_ok=True)
    with out.open('w',newline='',encoding='utf-8') as f:
        w=csv.DictWriter(f,fieldnames=list(rows[0]),delimiter='\t',lineterminator='\n'); w.writeheader(); w.writerows(rows)
    report={
        'version':'1.15.2','status':'PASS','source':'MaleCNS v1.0 official body annotations',
        'annotation_sha256':ANN_SHA,'fbc103_sha256':FBC_SHA,'fbc103_modified':False,
        'neurons_fbr10':N,'vnc_motor_rows':len(rows),'counts':dict(counts),'sides':dict(sides),
        'functional_tags':dict(Counter(x['functionalTag'] for x in rows)),
        'reclassified_from_old_fbc103':sum(x['discrepancy']=='RECLASSIFIED' for x in rows),
        'old_role_counts':dict(Counter(x['currentFBC103Role'] for x in rows)),
        'rule':'anatomicalClass is sourced from official class; TTMn receives functionalTag=JUMP without changing anatomicalClass=WING.'
    }
    rp=Path(args.report); rp.parent.mkdir(parents=True,exist_ok=True); rp.write_text(json.dumps(report,indent=2,sort_keys=True),encoding='utf-8')
    print(json.dumps(report,indent=2))

if __name__=='__main__': main()
