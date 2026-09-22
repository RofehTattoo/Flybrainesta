#!/usr/bin/env python3
"""Build the V1.17.0 VNC motor semantics layer from official MaleCNS annotations.

This script never rewrites FBC103. It reads the current release FBR-10-OLF1 binary
only to recover the retained bodyIds and previous role bytes for audit comparison.
The official annotation Feather is the sole source of anatomical class/side/
type/subclass/neuromere/exit-nerve data.
"""
from __future__ import annotations
import argparse, csv, hashlib, json, struct
from collections import Counter
from pathlib import Path
import pyarrow.feather as feather
from fbc103_reader import read_fbc103

ANN_SHA = "2177e246113e4cfbf1e7772ec37c6da1955ff22e8063d0b1f833101f99a9a3b2"
N = 16669
EXPECTED = {"LEG":381, "ABDOMEN":214, "WING":67, "NECK":24, "HALTERE":16, "OTHER":6}
EXPECTED_SIDE = {"L":355, "R":353}
ROLE_NAMES = {1:"LEG",2:"WING",3:"HALTERE",4:"NECK",5:"ABDOMEN",6:"OTHER",7:"OLD_OTHER",0:"NON_MOTOR"}


def clean(v) -> str:
    """Normalize Arrow scalar / Python values without requiring pandas."""
    if v is None:
        return ""
    if hasattr(v, "as_py"):
        v = v.as_py()
    return str(v).strip()

# MEASURED MaleCNS motor subclass vocabulary.  The official annotation file
# occasionally leaves `class` empty (observed for bodyId 164190 / MNad21).
# `subclass` is the curated motor body-part code and is therefore the
# authoritative completion field; it is not a synthetic behavioral label.
# Canonical runtime vocabulary. Keep MaleCNS raw annotation values separate from
# the FlyBrain anatomical vocabulary so naming differences (e.g. abdominal vs
# abdomen) cannot create false census failures.
SUBCLASS_TO_CLASS = {
    "fl": "LEG", "ml": "LEG", "hl": "LEG",
    "wm": "WING", "nm": "NECK", "hm": "HALTERE",
    "ad": "ABDOMEN", "xm": "OTHER",
}
RAW_CLASS_TO_CANONICAL = {
    "leg": "LEG", "wing": "WING", "haltere": "HALTERE",
    "neck": "NECK", "abdominal": "ABDOMEN", "abdomen": "ABDOMEN",
    "other": "OTHER",
}
CLASS_TO_ROLE = {"LEG":1, "WING":2, "HALTERE":3, "NECK":4, "ABDOMEN":5, "OTHER":6}

def resolve_motor_class(raw_cls: str, subclass: str, body_id: int) -> tuple[str, str]:
    if subclass not in SUBCLASS_TO_CLASS:
        raise SystemExit(f"unsupported official motor subclass {subclass!r} for {body_id}")
    derived = SUBCLASS_TO_CLASS[subclass]
    if raw_cls:
        canonical = RAW_CLASS_TO_CANONICAL.get(raw_cls)
        if canonical is None:
            raise SystemExit(f"unsupported official motor class {raw_cls!r} for {body_id}")
        if canonical != derived:
            raise SystemExit(
                f"official class/subclass conflict for {body_id}: class={raw_cls!r}, "
                f"subclass={subclass!r} -> {derived!r}"
            )
        return canonical, "class"
    return derived, "subclass_completion"


def sha256(p: Path) -> str:
    return __import__("fbc103_reader").sha256(p)


def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--annotations', required=True)
    ap.add_argument('--fbc103', required=True)
    ap.add_argument('--output', required=True)
    ap.add_argument('--report', required=True)
    args=ap.parse_args()
    ann_path=Path(args.annotations); fbc=Path(args.fbc103)
    if sha256(ann_path)!=ANN_SHA: raise SystemExit("official annotation SHA mismatch")
    fbc_nodes = read_fbc103(fbc, expected_sha=None)
    actual_fbc_sha = sha256(fbc)
    body = [r["bodyId"] for r in fbc_nodes]
    old_role = [r["role"] for r in fbc_nodes]
    old_side = [r["side"] for r in fbc_nodes]
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
    class_completion = []
    for r in motors:
        bid=int(r['bodyId'])
        raw_cls=clean(r.get('class')).lower()
        subclass=clean(r.get('subclass')).lower()
        cls, class_source = resolve_motor_class(raw_cls, subclass, bid)
        if class_source == "subclass_completion":
            class_completion.append(bid)
        side=clean(r.get('somaSide')).upper()
        if side not in ('L','R'): raise SystemExit(f"invalid motor side {side!r} for {bid}")
        typ=clean(r.get('type')); func='JUMP' if typ.lower() == 'ttmn' else 'NONE'
        oldr=old[bid][0]
        expected_role=CLASS_TO_ROLE[cls]
        rows.append({
            'bodyId':bid,'type':typ,'class':cls,'subclass':subclass,
            'somaSide':side,'somaNeuromere':clean(r.get('somaNeuromere')),
            'exitNerve':clean(r.get('exitNerve')),'anatomicalClass':cls,
            'functionalTag':func,'classSource':class_source,
            'currentFBC103Role':ROLE_NAMES.get(oldr,'UNKNOWN'),
            'currentFBC103RoleCode':oldr,'semanticRoleCode':expected_role,
            'discrepancy': 'MATCH' if oldr==expected_role else 'RECLASSIFIED'
        })
    rows.sort(key=lambda x:x['bodyId'])
    counts=Counter(x['anatomicalClass'] for x in rows); sides=Counter(x['somaSide'] for x in rows)
    raw_class_counts=Counter(clean(r.get('class')) for r in motors)
    subclass_counts=Counter(clean(r.get('subclass')).lower() for r in motors)
    if dict(counts)!=EXPECTED: raise SystemExit(f"class census {dict(counts)} != {EXPECTED}")
    if dict(sides)!=EXPECTED_SIDE: raise SystemExit(f"side census {dict(sides)} != {EXPECTED_SIDE}")
    if any(not x['class'] for x in rows): raise SystemExit('effective motor class unexpectedly blank')
    out=Path(args.output); out.parent.mkdir(parents=True,exist_ok=True)
    with out.open('w',newline='',encoding='utf-8') as f:
        w=csv.DictWriter(f,fieldnames=list(rows[0]),delimiter='\t',lineterminator='\n'); w.writeheader(); w.writerows(rows)
    report={
        'version':'1.17.0','status':'PASS','source':'MaleCNS v1.0 official body annotations',
        'annotation_sha256':ANN_SHA,'fbc103_sha256':actual_fbc_sha,'fbc103_modified':False,
        'neurons_fbr10':N,'vnc_motor_rows':len(rows),'counts':dict(counts),'sides':dict(sides),
        'functional_tags':dict(Counter(x['functionalTag'] for x in rows)),
        'raw_official_class_census':dict(raw_class_counts),
        'official_motor_subclass_census':dict(subclass_counts),
        'class_completion_count':len(class_completion),
        'class_completion_bodyIds':class_completion,
        'reclassified_from_old_fbc103':sum(x['discrepancy']=='RECLASSIFIED' for x in rows),
        'old_role_counts':dict(Counter(x['currentFBC103Role'] for x in rows)),
        'rule':'anatomicalClass is sourced from official class when present; when class is blank, it is completed from the measured official subclass vocabulary. TTMn receives functionalTag=JUMP without changing anatomicalClass=WING.'
    }
    rp=Path(args.report); rp.parent.mkdir(parents=True,exist_ok=True); rp.write_text(json.dumps(report,indent=2,sort_keys=True),encoding='utf-8')
    print(json.dumps(report,indent=2))

if __name__=='__main__': main()
