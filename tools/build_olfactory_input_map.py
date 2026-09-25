#!/usr/bin/env python3
"""Build the runtime olfactory input map from the official MaleCNS v1.0 data.

The frozen FBC103 index ranges are not used to decide what is olfactory.  The
olfactory population is derived from the actual retained neurons whose official
MaleCNS annotations say superclass=cb_sensory, class=olfactory and either type=ORN_* or one of the four official untyped ORN bodyIds.
The neurotransmitter table is cross-checked as an additional provenance guard.
No neuron or edge is created or modified by this tool.
"""
from __future__ import annotations
import argparse, csv, hashlib, json
from pathlib import Path
import pyarrow.feather as feather
from fbc103_reader import read_fbc103, sha256

ANN_SHA = "2177e246113e4cfbf1e7772ec37c6da1955ff22e8063d0b1f833101f99a9a3b2"
NT_SHA = "95c9289220663abeb3409f3ad9e5a7f8a53f8093f5139d15502cd08da8879621"
EXPECTED_ORNS = 264
EXPECTED_ORN_TYPE_ENTRY_NERVE_PAIRS = 54
UNTYPED_ORN_BODY_IDS = frozenset({242812, 242908, 488209, 956041})


def clean(v):
    if v is None:
        return ""
    if hasattr(v, "as_py"):
        v = v.as_py()
    return str(v).strip()


def side(v):
    x = clean(v).upper()
    if x in {"L", "LEFT"}: return -1
    if x in {"R", "RIGHT"}: return 1
    if x in {"B", "BILATERAL", "LR", "L/R", "R/L"}: return 0
    return None


def is_olfactory_orn(r):
    """Authoritative FBR-10-OLF2-MOTORROUTE ORN selector.

    Four official MaleCNS v1.0 ORNs have a NULL `type` but are explicitly
    annotated as cb_sensory/olfactory with AN entry nerve. Their bodyIds are
    preserved without assigning a synthetic type.
    """
    bid = int(r["bodyId"])
    sc = clean(r.get("superclass")).lower()
    cl = clean(r.get("class")).lower()
    typ = clean(r.get("type")).upper()
    nerve = clean(r.get("entryNerve")).upper()
    return (
        sc == "cb_sensory"
        and cl == "olfactory"
        and nerve in {"AN", "MXLBN"}
        and (typ.startswith("ORN_") or bid in UNTYPED_ORN_BODY_IDS)
    )


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--annotations", required=True)
    ap.add_argument("--neurotransmitters", required=True)
    ap.add_argument("--fbc103", required=True)
    ap.add_argument("--output", required=True)
    ap.add_argument("--report", required=True)
    ap.add_argument("--connectome-report", required=True)
    args = ap.parse_args()

    annp = Path(args.annotations)
    ntp = Path(args.neurotransmitters)
    fbc = Path(args.fbc103)
    if sha256(annp) != ANN_SHA: raise SystemExit("official annotation SHA mismatch")
    if sha256(ntp) != NT_SHA: raise SystemExit("official neurotransmitter SHA mismatch")
    nodes = read_fbc103(fbc, expected_sha=None)
    actual_fbc_sha = sha256(fbc)
    if len(nodes) != 16669: raise SystemExit(f"FBC node count mismatch: {len(nodes)}")
    crep = json.loads(Path(args.connectome_report).read_text(encoding="utf-8"))
    if crep.get("reduction") != "FBR-10-OLF2-MOTORROUTE": raise SystemExit("connectome release id mismatch")
    if crep.get("olfactory_orns_retained") != EXPECTED_ORNS: raise SystemExit("connectome ORN count mismatch")
    retained_type_entry_nerve_pairs = crep.get("olfactory_orn_type_entry_nerve_pairs_retained")
    if not isinstance(retained_type_entry_nerve_pairs, list):
        raise SystemExit("connectome ORN type+entryNerve coverage field is missing or malformed")
    if len(retained_type_entry_nerve_pairs) != EXPECTED_ORN_TYPE_ENTRY_NERVE_PAIRS:
        raise SystemExit(
            "connectome ORN type+entryNerve coverage mismatch: "
            f"{len(retained_type_entry_nerve_pairs)} != "
            f"{EXPECTED_ORN_TYPE_ENTRY_NERVE_PAIRS}"
        )
    if crep.get("sha256") and crep.get("sha256") != actual_fbc_sha: raise SystemExit("connectome report SHA mismatch")

    cols = ["bodyId", "superclass", "type", "class", "subclass", "instance",
            "receptorType", "rootSide", "somaSide", "entryNerve"]
    table = feather.read_table(annp, columns=cols)
    by = {}
    for r in table.to_pylist():
        bid = int(r["bodyId"])
        if bid in by: raise SystemExit(f"duplicate bodyId={bid}")
        by[bid] = r

    nt_table = feather.read_table(ntp, columns=["body", "consensus_nt"])
    nt_values = {}
    for r in nt_table.to_pylist():
        bid = int(r["body"])
        nt_values.setdefault(bid, set()).add(clean(r.get("consensus_nt")).lower())
    nt_by = {}
    for bid, values in nt_values.items():
        values.discard("")
        if len(values) > 1:
            raise SystemExit(f"multiple consensus_nt values bodyId={bid}: {sorted(values)}")
        nt_by[bid] = next(iter(values), "")

    # The authoritative olfactory population is the intersection of FBC103 and
    # the official ORN annotation, not the historical 618..738 index block.
    out = []
    for n in nodes:
        bid = int(n["bodyId"])
        r = by.get(bid)
        if r is None: continue
        sc = clean(r.get("superclass"))
        cl = clean(r.get("class"))
        typ = clean(r.get("type"))
        if is_olfactory_orn(r):
            nt = nt_by.get(bid, "")
            if nt.lower() != "acetylcholine":
                raise SystemExit(f"retained ORN has unexpected consensus_nt bodyId={bid}: {nt!r}")
            # Authoritative lateralization: somaSide -> rootSide -> UNKNOWN.
            # entryNerve identifies ORNs (AN/MxLbN) but NEVER participates in lateralization.
            ss = side(r.get("somaSide"))
            rs = side(r.get("rootSide"))
            known = [(src, value) for src, value in (("somaSide", ss), ("rootSide", rs))
                     if value in (-1, 1)]
            if len({value for _, value in known}) > 1:
                raise SystemExit(f"contradictory ORN side evidence bodyId={bid}: {known}")
            if ss in (-1, 1):
                code, source = ss, "somaSide"
            elif rs in (-1, 1):
                code, source = rs, "rootSide"
            else:
                code, source = 0, "unknown"
            out.append({
                "index": n["index"], "bodyId": bid, "sideCode": code, "sideSource": source,
                "type": typ, "class": cl, "superclass": sc, "consensus_nt": nt,
                "subclass": clean(r.get("subclass")), "instance": clean(r.get("instance")),
                "receptorType": clean(r.get("receptorType")), "rootSide": clean(r.get("rootSide")),
                "somaSide": clean(r.get("somaSide")), "entryNerve": clean(r.get("entryNerve")),
            })

    out.sort(key=lambda x: x["index"])
    if len(out) != EXPECTED_ORNS:
        raise SystemExit(f"retained real ORNs={len(out)} expected={EXPECTED_ORNS}")

    type_counts = {
        t: sum(r["type"] == t for r in out)
        for t in sorted({r["type"] for r in out if r["type"]})
    }
    type_entry_nerve_pairs = {
        (r["type"], r["entryNerve"].strip().upper())
        for r in out
        if r["type"]
    }
    untyped_ids = {r["bodyId"] for r in out if not r["type"]}
    if not untyped_ids.issubset(UNTYPED_ORN_BODY_IDS):
        raise SystemExit(
            f"unexpected untyped ORN bodyIds: "
            f"{sorted(untyped_ids - UNTYPED_ORN_BODY_IDS)}"
        )
    if len(type_entry_nerve_pairs) != EXPECTED_ORN_TYPE_ENTRY_NERVE_PAIRS:
        raise SystemExit(
            "retained ORN type+entryNerve combinations="
            f"{len(type_entry_nerve_pairs)} expected="
            f"{EXPECTED_ORN_TYPE_ENTRY_NERVE_PAIRS}"
        )

    side_counts = {"L": sum(r["sideCode"] == -1 for r in out),
                   "R": sum(r["sideCode"] == 1 for r in out),
                   "U": sum(r["sideCode"] == 0 for r in out)}
    if any(
        (
            (r["type"] and not r["type"].startswith("ORN_"))
            or (not r["type"] and r["bodyId"] not in UNTYPED_ORN_BODY_IDS)
            or r["class"] != "olfactory"
            or r["superclass"] != "cb_sensory"
            or r["consensus_nt"].lower() != "acetylcholine"
        )
        for r in out
    ):
        raise SystemExit("ORN provenance validation failed")

    # Guard against the historical visual block being accidentally selected.
    visual_ids = {int(n["bodyId"]) for n in nodes[618:739]}
    if visual_ids.intersection({r["bodyId"] for r in out}):
        raise SystemExit("historical 618..738 visual OLF block overlaps real ORN map")

    p = Path(args.output); p.parent.mkdir(parents=True, exist_ok=True)
    fields = list(out[0])
    with p.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=fields, delimiter="\t", lineterminator="\n")
        w.writeheader(); w.writerows(out)

    rep = {
        "version": "1.18.3-OLF2-ORN-AUDIT",
        "status": "PASS",
        "annotation_sha256": ANN_SHA,
        "neurotransmitter_sha256": NT_SHA,
        "fbc103_sha256": actual_fbc_sha,
        "fbc103_modified": False,
        "fbc103_release_id": "FBR-10-OLF2-MOTORROUTE",
        "olf_retained": len(out),
        "historical_olf_block": {"start": 618, "end": 739, "used_for_olfactory_input": False},
        "side_counts": side_counts,
        "type_counts": type_counts,
        "type_label_count": len(type_counts),
        "type_entry_nerve_pair_count": len(type_entry_nerve_pairs),
        "untyped_orn_body_ids": sorted(untyped_ids),
        "all_class_olfactory": True,
        "all_superclass_cb_sensory": True,
        "all_consensus_nt_acetylcholine": True,
        "policy": "Only retained MaleCNS ORNs with official cb_sensory/olfactory/ORN_ annotations and AN/MxLbN entry nerve are mapped. Side precedence is somaSide -> rootSide -> UNKNOWN; entryNerve is never used for lateralization. No neurons or edges are created.",
    }
    Path(args.report).write_text(json.dumps(rep, indent=2, sort_keys=True), encoding="utf-8")
    print(json.dumps(rep, indent=2))


if __name__ == "__main__":
    main()
