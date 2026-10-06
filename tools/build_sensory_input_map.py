#!/usr/bin/env python3
"""Build the Phase-1 anatomical sensory input map from official MaleCNS v1.0 data.

Only retained primary receptor populations receive external sensory current at runtime.
This tool never changes FBC103 or creates neurons/edges.

Modalities:
  VIS  = ol_sensory / visual / photoreceptor (R1-6, R7, R8)
  GUST = gustatory cells in cb_sensory or vnc_sensory (primary taste receptors)
  MECH = mechanosensory_proprioceptive cells (source class is authoritative)
"""
from __future__ import annotations
import argparse, csv, hashlib, json, re
from pathlib import Path
import pyarrow.feather as feather
from fbc103_reader import read_fbc103, sha256

ANN_SHA = "2177e246113e4cfbf1e7772ec37c6da1955ff22e8063d0b1f833101f99a9a3b2"


def clean(v) -> str:
    if v is None:
        return ""
    if hasattr(v, "as_py"):
        v = v.as_py()
    return str(v).strip()


def side(v):
    x = clean(v).upper()
    if x in {"L", "LEFT"}: return -1
    if x in {"R", "RIGHT"}: return 1
    return 0


def side_source(row):
    ss = side(row.get("somaSide"))
    rs = side(row.get("rootSide"))
    if ss in (-1, 1):
        return ss, "somaSide"
    if rs in (-1, 1):
        return rs, "rootSide"

    # Some MaleCNS sensory annotations do not populate somaSide/rootSide but
    # encode the published lateral identity in instance/name/type strings.
    # This is still source provenance, not a geometric guess.
    for field in ("instance", "name", "type", "flywireType"):
        text = clean(row.get(field)).upper()
        if not text:
            continue
        tokens = re.split(r"[^A-Z0-9]+", text)
        if any(tok in {"L", "LEFT"} for tok in tokens):
            return -1, field
        if any(tok in {"R", "RIGHT"} for tok in tokens):
            return 1, field
    return 0, "unknown"


def visual_receptor(row) -> bool:
    sc = clean(row.get("superclass")).lower()
    cl = clean(row.get("class")).lower()
    typ = clean(row.get("type"))
    fw = clean(row.get("flywireType")).upper()
    return (
        sc == "ol_sensory"
        and cl == "visual"
        and (
            fw in {"R1-6", "R7", "R8"}
            or typ == "R1-R6"
            or typ.startswith("R7")
            or typ.startswith("R8")
        )
    )


def gustatory_receptor(row) -> bool:
    sc = clean(row.get("superclass")).lower()
    cl = clean(row.get("class")).lower()
    return cl == "gustatory" and sc in {"cb_sensory", "vnc_sensory"}


def gust_site(row) -> str:
    sub = clean(row.get("subclass")).lower()
    typ = clean(row.get("type")).lower()
    if sub == "leg bristle" or "leg bristle" in typ:
        return "TARSAL"
    if "labellar" in sub or "labellum" in sub or "labial" in sub or "taste peg" in sub:
        return "LABELLAR"
    if "pharyngeal" in sub or "pharynx" in sub:
        return "PHARYNGEAL"
    return "OTHER"


def mechanosensory_receptor(row) -> bool:
    return clean(row.get("class")).lower() == "mechanosensory_proprioceptive" and bool(clean(row.get("subclass")))


def main():
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

    columns = [
        "bodyId", "superclass", "type", "class", "subclass", "receptorType",
        "flywireType", "somaSide", "rootSide", "entryNerve", "instance", "name"
    ]
    table = feather.read_table(annp, columns=columns)
    by_ann = {}
    for row in table.to_pylist():
        bid = int(row["bodyId"])
        if bid in by_ann:
            raise SystemExit(f"duplicate annotation bodyId={bid}")
        by_ann[bid] = row

    # Derive the actual FBC103 population blocks from node channel codes rather
    # than duplicating numeric ranges in this tool. This makes the map fail closed
    # if the reducer changes population boundaries or channel semantics.
    CHANNEL_BY_MODALITY = {"VIS": 0, "GUST": 2, "MECH": 3}
    ranges = {}
    for modality, channel_code in CHANNEL_BY_MODALITY.items():
        indices = [int(n["index"]) for n in nodes if int(n["channel"]) == channel_code]
        if not indices:
            raise SystemExit(f"FBC103 channel {channel_code} for {modality} is empty")
        expected = list(range(min(indices), max(indices) + 1))
        if indices != expected:
            raise SystemExit(
                f"FBC103 channel {channel_code} for {modality} is not contiguous: "
                f"first={min(indices)} last={max(indices)} count={len(indices)}"
            )
        ranges[modality] = (min(indices), max(indices) + 1, channel_code)

    out = []
    counts = {"VIS": 0, "GUST": 0, "MECH": 0}
    excluded_gust_ascending = 0
    visual_candidates = gust_candidates = mech_candidates = 0
    modality_overlaps = 0

    # The visual runtime injects current only into real primary photoreceptors.
    # The reducer is therefore required to retain the full 10% source quota; a
    # silently tiny VIS map would make any downstream visual route untestable.
    source_primary_visual_count = 0
    for row in table.to_pylist():
        if visual_receptor(row):
            source_primary_visual_count += 1
    expected_primary_visual = int(round(source_primary_visual_count * 0.10))

    for bid, node in by_id.items():
        r = by_ann.get(bid)
        if r is None:
            continue
        cl = clean(r.get("class")).lower()
        sc = clean(r.get("superclass")).lower()

        if cl == "visual":
            visual_candidates += 1
        if cl == "gustatory":
            gust_candidates += 1
            if sc == "sensory_ascending":
                excluded_gust_ascending += 1
        if cl == "mechanosensory_proprioceptive":
            mech_candidates += 1

        matches = []
        if visual_receptor(r):
            matches.append("VIS")
        if gustatory_receptor(r):
            matches.append("GUST")
        if mechanosensory_receptor(r):
            matches.append("MECH")

        if len(matches) > 1:
            modality_overlaps += 1
            raise SystemExit(
                f"sensory receptor matches multiple modalities: bodyId={bid} modalities={matches}"
            )
        if not matches:
            continue

        modality = matches[0]
        index = int(node["index"])
        start, end, expected_channel = ranges[modality]
        if not (start <= index < end) or int(node["channel"]) != expected_channel:
            # The FBC block/channel contract is strict. Any mismatch means that
            # reducer/runtime population semantics have drifted and must fail.
            raise SystemExit(
                f"{modality} retained receptor outside FBC block/channel: "
                f"bodyId={bid} index={index} channel={node['channel']} "
                f"expected={start}:{end}/{expected_channel}"
            )

        side_code, side_src = side_source(r)
        out.append({
            "index": index,
            "bodyId": bid,
            "modality": modality,
            "sideCode": side_code,
            "sideSource": side_src,
            "type": clean(r.get("type")),
            "class": clean(r.get("class")),
            "superclass": clean(r.get("superclass")),
            "subclass": clean(r.get("subclass")),
            "receptorType": clean(r.get("receptorType")),
            "flywireType": clean(r.get("flywireType")),
            "gustSite": gust_site(r) if modality == "GUST" else "OTHER",
            "mechSite": mechanosensory_site(r) if modality == "MECH" else "OTHER",
        })
        counts[modality] += 1

    out.sort(key=lambda x: (x["modality"], x["index"]))
    tarsal_gust = [r for r in out if r["modality"] == "GUST" and r["gustSite"] == "TARSAL"]
    labellar_gust = [r for r in out if r["modality"] == "GUST" and r["gustSite"] == "LABELLAR"]
    pharyngeal_gust = [r for r in out if r["modality"] == "GUST" and r["gustSite"] == "PHARYNGEAL"]
    if not tarsal_gust:
        raise SystemExit("no retained primary gustatory tarsal receptors for contact")
    if not labellar_gust:
        raise SystemExit("no retained labellar gustatory receptors")
    if not pharyngeal_gust:
        raise SystemExit("no retained pharyngeal gustatory receptors")
    if len({r["index"] for r in out}) != len(out):
        raise SystemExit("duplicate retained sensory receptor index")
    if any(counts[k] == 0 for k in counts):
        raise SystemExit(f"retained receptor population missing: {counts}")
    if counts["VIS"] != expected_primary_visual:
        raise SystemExit(
            "primary visual receptor quota mismatch: "
            f"source={source_primary_visual_count} expected_retained={expected_primary_visual} "
            f"actual_retained={counts['VIS']}"
        )

    p = Path(args.output)
    p.parent.mkdir(parents=True, exist_ok=True)
    fields = list(out[0])
    with p.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=fields, delimiter="\t", lineterminator="\n")
        w.writeheader(); w.writerows(out)

    report = {
        "version": "phase1-sensory-receptor-map",
        "status": "PASS",
        "annotation_sha256": ANN_SHA,
        "fbc103_sha256": sha256(fbc),
        "fbc103_neurons": len(nodes),
        "retained_receptor_counts": counts,
        "primary_visual_source_count": source_primary_visual_count,
        "primary_visual_expected_retained_10pct": expected_primary_visual,
        "retained_tarsal_gustatory_count": len(tarsal_gust),
        "retained_labellar_gustatory_count": len(labellar_gust),
        "retained_pharyngeal_gustatory_count": len(pharyngeal_gust),
        "retained_tarsal_gustatory_by_side": {
            "L": sum(1 for r in tarsal_gust if r["sideCode"] == -1),
            "R": sum(1 for r in tarsal_gust if r["sideCode"] == 1),
            "U": sum(1 for r in tarsal_gust if r["sideCode"] == 0),
        },
        "gustatory_subclass_counts": {
            sub: sum(1 for r in out if r["modality"] == "GUST" and r["subclass"] == sub)
            for sub in sorted({r["subclass"] for r in out if r["modality"] == "GUST"})
        },
        "total_mapped": len(out),
        "candidate_counts": {
            "visual_class": visual_candidates,
            "gustatory_class": gust_candidates,
            "mechanosensory_proprioceptive_class": mech_candidates,
            "gustatory_sensory_ascending_excluded": excluded_gust_ascending,
            "modality_overlaps": modality_overlaps,
        },
        "fbc103_channel_blocks": {
            name: {"start": start, "end": end, "channel": channel}
            for name, (start, end, channel) in ranges.items()
        },
        "gustatory_site_counts": {
            "TARSAL": len(tarsal_gust),
            "LABELLAR": len(labellar_gust),
            "PHARYNGEAL": len(pharyngeal_gust),
            "OTHER": sum(1 for r in out if r["modality"] == "GUST" and r["gustSite"] == "OTHER"),
        },
        "policy": {
            "VIS": "ol_sensory + visual + photoreceptor R1-6/R7/R8 only",
            "GUST": "gustatory + primary sensory superclasses cb_sensory/vnc_sensory; sensory_ascending relays excluded; runtime site classes are derived from official subclass/type annotations",
            "MECH": "mechanosensory_proprioceptive with a non-empty receptor-organ subclass",
            "runtime": "external current is injected only into these explicit retained receptor indices; no index-cyclic pattern or population-wide injection",
            "no_graph_mutation": True,
        },
    }
    Path(args.report).write_text(json.dumps(report, indent=2, sort_keys=True), encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
