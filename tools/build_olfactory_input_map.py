#!/usr/bin/env python3
"""Build the runtime OLF input map from the official MaleCNS v1.0 annotations.

This does not create neurons or edges and does not modify FBC103. It only answers:
for each retained OLF neuron, what published anatomical side evidence is available
for routing an environmental odor signal into that real neuron?

Priority for side evidence:
1. somaSide
2. rootSide
3. entryNerve text containing an explicit L/R token
Conflicts are failed closed. Unknown/central neurons receive bilateral input at
runtime rather than being assigned a fabricated side.
"""
from __future__ import annotations
import argparse, csv, hashlib, json, re
from pathlib import Path
import pyarrow.feather as feather
from fbc103_reader import read_fbc103, sha256

ANN_SHA="2177e246113e4cfbf1e7772ec37c6da1955ff22e8063d0b1f833101f99a9a3b2"
FBC_SHA="bfadc30fd113c25f9711cce6ef8f6b80e9c139fe6d229965a4adabb94d8b4e60"
OLF_START, OLF_END = 618, 739

def clean(v):
    if v is None: return ""
    if hasattr(v,'as_py'): v=v.as_py()
    return str(v).strip()

def side(v):
    x=clean(v).upper()
    if x in {'L','LEFT'}: return -1
    if x in {'R','RIGHT'}: return 1
    if x in {'B','BILATERAL','LR','L/R','R/L'}: return 0
    return None

def side_from_nerve(v):
    x=clean(v).upper()
    if not x: return None
    # Require an explicit side token; do not infer from arbitrary letters.
    toks=re.split(r'[^A-Z0-9]+',x)
    vals=[]
    for t in toks:
        if t in {'L','LEFT'}: vals.append(-1)
        elif t in {'R','RIGHT'}: vals.append(1)
    if not vals: return None
    if all(v==vals[0] for v in vals): return vals[0]
    return 0

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--annotations',required=True)
    ap.add_argument('--fbc103',required=True)
    ap.add_argument('--output',required=True)
    ap.add_argument('--report',required=True)
    args=ap.parse_args()
    annp=Path(args.annotations); fbc=Path(args.fbc103)
    if sha256(annp)!=ANN_SHA: raise SystemExit('official annotation SHA mismatch')
    nodes=read_fbc103(fbc)
    rows=[r for r in nodes if OLF_START <= r['index'] < OLF_END]
    if len(rows)!=121: raise SystemExit(f'OLF block={len(rows)} expected 121')
    cols=['bodyId','superclass','type','class','subclass','instance','receptorType','rootSide','somaSide','entryNerve']
    table=feather.read_table(annp,columns=cols)
    by={}
    for r in table.to_pylist():
        bid=int(r['bodyId'])
        if bid in by: raise SystemExit(f'duplicate bodyId={bid}')
        by[bid]=r
    out=[]; counts={"somaSide":0,"rootSide":0,"entryNerve":0,"bilateral_or_unknown":0}
    for n in rows:
        bid=int(n['bodyId']); r=by.get(bid)
        if r is None: raise SystemExit(f'missing annotation bodyId={bid}')
        ss=side(r.get('somaSide')); rs=side(r.get('rootSide')); es=side_from_nerve(r.get('entryNerve'))
        explicit=[('somaSide',ss),('rootSide',rs),('entryNerve',es)]
        known=[(src,v) for src,v in explicit if v in (-1,1)]
        if len({v for _,v in known})>1:
            raise SystemExit(f'contradictory OLF side evidence bodyId={bid}: {explicit}')
        if known:
            code=known[0][1]; source=known[0][0]; counts[source]+=1
        else:
            code=0; source='bilateral_or_unknown'; counts[source]+=1
        out.append({
          'index':n['index'],'bodyId':bid,'sideCode':code,'sideSource':source,
          'type':clean(r.get('type')),'class':clean(r.get('class')),'subclass':clean(r.get('subclass')),
          'instance':clean(r.get('instance')),'receptorType':clean(r.get('receptorType')),
          'rootSide':clean(r.get('rootSide')),'somaSide':clean(r.get('somaSide')),'entryNerve':clean(r.get('entryNerve'))})
    out.sort(key=lambda x:x['index'])
    p=Path(args.output); p.parent.mkdir(parents=True,exist_ok=True)
    fields=list(out[0])
    with p.open('w',newline='',encoding='utf-8') as f:
        w=csv.DictWriter(f,fieldnames=fields,delimiter='\t',lineterminator='\n'); w.writeheader(); w.writerows(out)
    rep={'version':'1.16.0','status':'PASS','annotation_sha256':ANN_SHA,'fbc103_sha256':FBC_SHA,'fbc103_modified':False,'olf_retained':121,'side_sources':counts,'policy':'Only explicit official side evidence is used. Conflicts fail closed. Missing/central side is represented as bilateral/unknown; no side is fabricated.'}
    Path(args.report).write_text(json.dumps(rep,indent=2,sort_keys=True),encoding='utf-8')
    print(json.dumps(rep,indent=2))
if __name__=='__main__': main()
