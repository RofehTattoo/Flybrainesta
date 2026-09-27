#!/usr/bin/env python3
"""Build FEEDSEM103 from official MaleCNS v1.0 annotations.

This asset does not alter FBC103. It identifies retained *cb_motor* feeding
motor neurons by their published type and assigns a read-only functional tag.
The runtime uses those tags to measure proboscis/ingestion output that already
exists in the retained connectome.
"""
from __future__ import annotations
import argparse, csv, hashlib, json
from pathlib import Path
import pyarrow.feather as feather
from fbc103_reader import read_fbc103, sha256

ANN_SHA = "2177e246113e4cfbf1e7772ec37c6da1955ff22e8063d0b1f833101f99a9a3b2"
FUNCTIONS = {
    "mn9": ("PROBOSCIS_ROSTRUM", 1),
    "mn4a": ("PROBOSCIS_HAUSTELLUM", 2),
    "mn6": ("PROBOSCIS_LABELLUM", 3),
    "mn8": ("PROBOSCIS_SPREAD", 4),
    "mn11d": ("INGESTION_PHARYNGEAL", 5),
    "mn11v": ("INGESTION_PHARYNGEAL", 5),
    "cem": ("INGESTION_CROP_ENTRY", 6),
}
EXPECTED_TYPES = tuple(sorted(FUNCTIONS))


def clean(v) -> str:
    if v is None:
        return ""
    if hasattr(v, "as_py"):
        v = v.as_py()
    return str(v).strip()


def side(v) -> str:
    x = clean(v).upper()
    if x in {"L", "LEFT"}: return "L"
    if x in {"R", "RIGHT"}: return "R"
    return ""


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--annotations", required=True)
    ap.add_argument("--fbc103", required=True)
    ap.add_argument("--output", required=True)
    ap.add_argument("--report", required=True)
    args = ap.parse_args()

    annp = Path(args.annotations)
    fbc = Path(args.fbc103)
    if sha256(annp) != ANN_SHA:
        raise SystemExit("official annotation SHA mismatch")
    nodes = read_fbc103(fbc, expected_sha=None)
    by_id = {int(n["bodyId"]): n for n in nodes}
    columns = ["bodyId", "type", "superclass", "subclass", "somaSide", "flywireType"]
    table = feather.read_table(annp, columns=columns)
    rows = table.to_pylist()
    by_ann = {int(r["bodyId"]): r for r in rows}
    if len(by_ann) != len(rows):
        raise SystemExit("duplicate annotation bodyId")

    out = []
    for bid, node in by_id.items():
        ann = by_ann.get(bid)
        if ann is None:
            continue
        sc = clean(ann.get("superclass")).lower()
        sub = clean(ann.get("subclass")).lower()
        typ = clean(ann.get("type")).lower()
        if sc != "cb_motor" or sub != "pm":
            continue
        if typ not in FUNCTIONS:
            continue
        tag_name, code = FUNCTIONS[typ]
        s = side(ann.get("somaSide"))
        if not s:
            raise SystemExit(f"feeding motor has no resolved somaSide bodyId={bid} type={typ}")
        out.append({
            "bodyId": bid,
            "type": clean(ann.get("type")),
            "superclass": clean(ann.get("superclass")),
            "subclass": clean(ann.get("subclass")),
            "somaSide": s,
            "flywireType": clean(ann.get("flywireType")),
            "functionalTag": tag_name,
            "functionalCode": code,
        })

    out.sort(key=lambda r: (r["functionalCode"], r["type"], r["somaSide"], r["bodyId"]))
    if not out:
        raise SystemExit("no retained feeding motor neurons")
    retained_types = {r["type"].strip().lower() for r in out}
    missing = sorted(set(EXPECTED_TYPES) - retained_types)
    if missing:
        raise SystemExit(f"retained feeding motor types missing: {missing}")
    retained_ids = {int(r["bodyId"]) for r in out}
    missing_graph = sorted(retained_ids - set(by_id))
    if missing_graph:
        raise SystemExit(f"FEEDSEM bodyIds absent from FBC103: {missing_graph}")

    p = Path(args.output); p.parent.mkdir(parents=True, exist_ok=True)
    fields = ["bodyId","type","superclass","subclass","somaSide","flywireType","functionalTag","functionalCode"]
    with p.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=fields, delimiter="\t", lineterminator="\n")
        w.writeheader(); w.writerows(out)

    by_function = {}
    for r in out:
        fn = r["functionalTag"]
        by_function[fn] = by_function.get(fn, 0) + 1
    report = {
        "format": "FEEDSEM103",
        "status": "PASS",
        "annotation_sha256": ANN_SHA,
        "fbc103_sha256": sha256(fbc),
        "retained_rows": len(out),
        "retained_types": sorted(retained_types),
        "required_types": list(EXPECTED_TYPES),
        "missing_types": missing,
        "by_function": by_function,
        "policy": {
            "source": "official MaleCNS v1.0 annotations",
            "allowed_superclass": "cb_motor",
            "allowed_subclass": "pm",
            "no_synthetic_edges": True,
            "runtime_role": "read-only measured motor output; no direct food-to-body command",
        },
    }
    Path(args.report).write_text(json.dumps(report, indent=2, sort_keys=True), encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
