#!/usr/bin/env python3
"""Build a source-derived 2D anatomical placement map for the retained FBR-10 neurons.

This file is PRESENTATION ONLY. It never changes FBC103 or neural edges.
Primary sensory neurons are anchored to their receptor organ; central neurons are
placed into conservative neuropil families using official MaleCNS annotations and
well-established cell-type vocabularies. Unknown central neurons stay in CENTRAL_BRAIN
rather than being assigned a false precise location.
"""
from __future__ import annotations
import argparse, csv, json, re
from pathlib import Path
from fbc103_reader import read_fbc103, sha256

ANN_SHA = "2177e246113e4cfbf1e7772ec37c6da1955ff22e8063d0b1f833101f99a9a3b2"
N = 16669

VISUAL_PRIMARY_TYPES = {"R1-6", "R7", "R8"}

# Conservative cell-type family hints. A neuron that does not match one of these
# remains CENTRAL_BRAIN instead of receiving an invented exact neuropil location.
VISUAL_HINTS = ("R1-6", "R7", "R8", "DM", "MI", "TM", "TMY", "LC", "LPLC", "LPTC", "HS", "VS", "MELO", "MEVP")
MB_HINTS = ("KC", "MBON", "APL")
CX_HINTS = ("PFL", "PFN", "PEN", "EPG", "PEG", "FB", "EB", "PB", "NOD", "\u0394")
AL_HINTS = ("ALIN", "LLN", "PN")
SEZ_HINTS = ("GNG", "SEZ", "VES")


def clean(v) -> str:
    if v is None:
        return ""
    if hasattr(v, "as_py"):
        v = v.as_py()
    return str(v).strip()


def side_code(row) -> int:
    for key in ("somaSide", "rootSide"):
        v = clean(row.get(key)).upper()
        if v in {"L", "LEFT"}: return -1
        if v in {"R", "RIGHT"}: return 1
        if v in {"B", "BILATERAL", "LR", "L/R", "R/L"}: return 0
    return 0


def contains_hint(t: str, hints: tuple[str, ...]) -> bool:
    u = t.upper()
    return any(h in u for h in hints)


def gust_site(row) -> str:
    sub = clean(row.get("subclass")).lower()
    typ = clean(row.get("type")).lower()
    if sub == "leg bristle" or "leg bristle" in typ:
        return "TARSAL"
    if any(x in sub for x in ("labellar", "labellum", "labial", "taste peg")):
        return "LABELLAR"
    if any(x in sub for x in ("pharyngeal", "pharynx")):
        return "PHARYNGEAL"
    return "OTHER"


def mech_site(row) -> str:
    sub = clean(row.get("subclass")).lower()
    typ = clean(row.get("type")).lower()
    text = f"{sub} {typ}"
    if any(x in text for x in ("johnston", "antenn", "jo_", "jo")):
        return "ANTENNAL_AMMC"
    if any(x in text for x in ("leg", "hair plate", "campaniform", "chordotonal", "tarsal")):
        return "VNC"
    return "SEZ"


def classify(row, visual_primary: bool) -> tuple[str, int, str, int]:
    """region, side, subregion, confidence(1 high, 2 medium, 3 low)."""
    cl = clean(row.get("class")).lower()
    sc = clean(row.get("superclass")).lower()
    typ = clean(row.get("type"))
    subtype = clean(row.get("subclass"))
    side = side_code(row)

    # Primary sensory organs: source-grounded anatomical origin.
    if cl == "visual" and visual_primary:
        return "EYE", side, clean(row.get("flywireType")).upper() or typ, 1
    if cl == "visual":
        return "OPTIC_LOBE", side, typ, 1
    if cl == "olfactory":
        nerve = clean(row.get("entryNerve")).upper()
        if sc == "cb_sensory" and (nerve in {"AN", "MXLBN"} or typ.upper().startswith("ORN_")):
            if nerve == "MXLBN":
                return "MAXILLARY_PALP", side, nerve, 1
            return "ANTENNA", side, nerve or "olfactory", 1
        return "ANTENNAL_LOBE", side, typ or "olfactory", 2
    if cl == "gustatory":
        site = gust_site(row)
        if site == "TARSAL": return "TARSAL", side, site, 1
        if site == "LABELLAR": return "LABELLUM", side, site, 1
        if site == "PHARYNGEAL": return "PHARYNX", side, site, 1
        return "SEZ", side, site, 2
    if cl == "mechanosensory_proprioceptive":
        site = mech_site(row)
        if site == "ANTENNAL_AMMC": return "AMMC", side, site, 1
        if site == "VNC": return "VNC", side, site, 1
        return "SEZ", side, site, 2

    # Explicit flow classes.
    if sc == "vnc_motor":
        neu = clean(row.get("somaNeuromere"))
        sub = subtype.lower()
        group = sub if sub in {"fl", "ml", "hl"} else neu
        return "VNC", side, group or "MOTOR", 1
    if sc in {"ascending", "sensory_ascending"}:
        return "ASCENDING", side, clean(row.get("somaNeuromere")) or "VNC", 1
    if sc == "descending":
        return "DESCENDING", side, "brain_to_vnc", 1

    # Conservative central-brain family mapping.
    t = typ.upper()
    if contains_hint(t, MB_HINTS):
        return "MUSHROOM_BODY", side, typ, 2
    if contains_hint(t, CX_HINTS):
        return "CENTRAL_COMPLEX", side, typ, 2
    if contains_hint(t, AL_HINTS):
        return "ANTENNAL_LOBE", side, typ, 2
    if contains_hint(t, SEZ_HINTS):
        return "SEZ", side, typ, 2
    if contains_hint(t, VISUAL_HINTS):
        return "OPTIC_LOBE", side, typ, 2
    return "CENTRAL_BRAIN", side, typ, 3


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--annotations", required=True)
    ap.add_argument("--fbc103", required=True)
    ap.add_argument("--output", required=True)
    ap.add_argument("--report", required=True)
    args = ap.parse_args()

    ann = Path(args.annotations)
    fbc = Path(args.fbc103)
    if sha256(ann) != ANN_SHA:
        raise SystemExit("official annotation SHA mismatch")

    import pyarrow.feather as feather

    nodes = read_fbc103(fbc, expected_sha=None)
    if len(nodes) != N:
        raise SystemExit(f"FBC103 neurons={len(nodes)} expected={N}")

    cols = ["bodyId", "superclass", "class", "subclass", "type", "somaSide", "rootSide",
            "flywireType", "entryNerve", "somaNeuromere"]
    table = feather.read_table(ann, columns=cols)
    by_body = {}
    for row in table.to_pylist():
        bid = int(row["bodyId"])
        if bid in by_body:
            raise SystemExit(f"duplicate official annotation bodyId={bid}")
        by_body[bid] = row

    # Primary visual classification follows the same official rule used by the
    # sensory runtime map: ol_sensory + visual + R1-6/R7/R8.
    def is_primary_visual(r):
        return (
            clean(r.get("superclass")).lower() == "ol_sensory"
            and clean(r.get("class")).lower() == "visual"
            and (
                clean(r.get("flywireType")).upper() in VISUAL_PRIMARY_TYPES
                or clean(r.get("type")) == "R1-R6"
                or clean(r.get("type")).startswith("R7")
                or clean(r.get("type")).startswith("R8")
            )
        )

    rows = []
    counts = {}
    confidence_counts = {"HIGH": 0, "MEDIUM": 0, "LOW": 0}
    for n in nodes:
        bid = int(n["bodyId"])
        r = by_body.get(bid, {})
        region, side, subregion, conf = classify(r, is_primary_visual(r))
        rows.append({
            "index": n["index"], "bodyId": bid, "region": region,
            "sideCode": side, "subregion": subregion,
            "type": clean(r.get("type")), "superclass": clean(r.get("superclass")),
            "class": clean(r.get("class")), "subclass": clean(r.get("subclass")),
            "somaNeuromere": clean(r.get("somaNeuromere")), "confidence": conf,
        })
        counts[region] = counts.get(region, 0) + 1
        confidence_counts[{1:"HIGH",2:"MEDIUM",3:"LOW"}[conf]] += 1

    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]), delimiter="\t", lineterminator="\n")
        w.writeheader(); w.writerows(rows)

    report = {
        "version": "anatomical-visual-map-v1",
        "status": "PASS",
        "annotation_sha256": ANN_SHA,
        "fbc103_sha256": sha256(fbc),
        "neurons": N,
        "region_counts": dict(sorted(counts.items())),
        "confidence_counts": confidence_counts,
        "policy": {
            "primary_sensory": "source-grounded receptor-organ placement",
            "vnc_motor": "source-grounded VNC placement using official motor class/subclass/side",
            "central": "conservative cell-type family mapping; unknowns remain CENTRAL_BRAIN",
            "no_graph_mutation": True,
        },
    }
    Path(args.report).write_text(json.dumps(report, indent=2, sort_keys=True), encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
