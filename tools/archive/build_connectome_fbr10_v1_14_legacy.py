#!/usr/bin/env python3
"""FBR-10 reducer for the MaleCNS v1.0 connectome.

Philosophy A:
    Keep exactly 16,669 neurons. Type diversity is a soft objective, not a
    requirement to keep 10% of types. Functional architecture, real
    connectivity, sensorimotor bridges and lateralization have priority.

The reducer never creates an edge. The output edge set is a strict subset of
published MaleCNS neuron-level edges whose two endpoints were selected.

This script is deliberately separate from the historical V1.13 reducer so the
old selector remains reproducible. It can consume either the official Feather
files or Parquet conversions with the same column names.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import struct
from collections import defaultdict
from pathlib import Path

import numpy as np
import pandas as pd
import pyarrow as pa
import pyarrow.feather as feather
import pyarrow.parquet as pq
import pyarrow.ipc as ipc

TARGET = 16669
MAGIC = b"FBC103\x00\x00"
BASE = "https://storage.googleapis.com/flyem-male-cns/v1.0/connectome-data/flat-connectome/"
FILES = {
    "annotations": "body-annotations-male-cns-v1.0-minconf-0.5.feather",
    "neurotransmitters": "body-neurotransmitters-male-cns-v1.0.feather",
    "weights": "connectome-weights-male-cns-v1.0-minconf-0.5.feather",
}

# Objective weights. These are intentionally explicit and configurable rather
# than hidden inside selection code. They are design hyperparameters, not claims
# about biological importance.
W_EDGE = 0.30
W_ROUTE = 0.28
W_ARCH = 0.17
W_TYPE = 0.10
W_SIDE = 0.08
W_DEGREE = 0.07

ANCHOR_SUPERCLASSES = {"descending_neuron", "vnc_motor"}
SENSORY_SUPERCLASSES = {
    "visual_projection", "visual_centrifugal", "ol_sensory", "vnc_sensory",
    "cb_sensory", "sensory_ascending", "sensory_descending",
}

NT_SIGN = {
    "acetylcholine": 1.0, "ach": 1.0,
    "gaba": -1.0, "gamma-aminobutyric acid": -1.0,
    "glutamate": -1.0, "glutamatergic": -1.0,
}


def clean(x) -> str:
    if x is None or pd.isna(x):
        return ""
    return str(x)


def read_table(path: Path, columns=None) -> pd.DataFrame:
    if path.suffix.lower() == ".parquet":
        return pd.read_parquet(path, columns=columns)
    return pd.read_feather(path, columns=columns)


def source_paths(root: Path) -> dict[str, Path]:
    raw = root / "build" / "malecns_raw"
    out = {}
    for key, name in FILES.items():
        p = raw / name
        if p.exists():
            out[key] = p
            continue
        pq_path = raw / name.replace(".feather", ".parquet")
        if pq_path.exists():
            out[key] = pq_path
            continue
        raise FileNotFoundError(
            f"Missing MaleCNS {key}: {p} or {pq_path}. "
            "Download the official v1.0 files into build/malecns_raw first."
        )
    return out


def batches(path: Path, columns):
    if path.suffix.lower() == ".parquet":
        pf = pq.ParquetFile(path)
        for b in pf.iter_batches(columns=columns, batch_size=1_000_000):
            yield b
    else:
        reader = ipc.open_file(pa.memory_map(str(path), "r"))
        for i in range(reader.num_record_batches):
            yield reader.get_batch(i)


def classify_channel(row) -> int:
    sc = clean(row.get("superclass", ""))
    text = " ".join(clean(row.get(c, "")) for c in
                     ("class", "subclass", "type", "instance", "name")).lower()
    if sc in {"visual_projection", "visual_centrifugal"} or "visual" in text or "optic lobe" in text:
        return 0
    if sc == "ol_sensory" or "olf" in text or "antennal lobe" in text:
        return 1
    if "gust" in text or "taste" in text:
        return 2
    if sc in {"vnc_sensory", "sensory_ascending", "sensory_descending"} or sc.startswith("cb_sensory"):
        return 3
    if any(k in text for k in ("mechanosensory", "proprio", "bristle", "hair plate", "campaniform", "chordotonal", "johnston")):
        return 3
    return 4


def classify_motor_role(row) -> int:
    if clean(row.get("superclass", "")) != "vnc_motor":
        return 0
    text = " ".join(clean(row.get(c, "")) for c in
                     ("type", "instance", "name", "class", "subclass", "nerve", "target", "muscle", "annotation", "group")).lower()
    if any(k in text for k in ("tergotrochanteral", "jump", "ttmn")): return 6
    if any(k in text for k in ("haltere", "halter")): return 3
    if any(k in text for k in ("wing", "dvm", "dlm", "flight", "steering")): return 2
    if any(k in text for k in ("neck", "cervical")): return 4
    if any(k in text for k in ("abdominal", "abdomen")): return 5
    if any(k in text for k in ("leg", "t1", "t2", "t3", "coxa", "femur", "tibia", "tars", "trochanter", "levator", "depressor", "flexor", "extensor")): return 1
    return 7


def classify_dn_role(row) -> int:
    if clean(row.get("superclass", "")) != "descending_neuron":
        return 0
    text = " ".join(clean(row.get(c, "")) for c in
                     ("type", "instance", "name", "class", "subclass", "annotation", "group")).lower()
    compact = re.sub(r"[^a-z0-9]+", "", text)
    if any(k in compact for k in ("dng100", "dng97", "dnb08")) or "forward walking" in text:
        return 1
    if any(k in compact for k in ("dna01", "dna02", "dna03", "dna04", "dna11", "dnb01", "dng13")) or any(k in text for k in ("turn", "steer", "steering")):
        return 2
    if any(k in compact for k in ("dnp50", "mdn", "moonwalker", "dnp07", "dnp09")) or "backward walking" in text:
        return 3
    if "dnp01" in compact or any(k in text for k in ("giant fiber", "giant-fiber", "giant fibre", "giant-fibre")):
        return 4
    return 0


def classify_halt(row) -> int:
    text = " ".join(clean(row.get(c, "")) for c in
                     ("type", "instance", "name", "class", "subclass", "annotation", "group")).lower()
    compact = re.sub(r"[^a-z0-9]+", "", text)
    if "foxglove" in text or "cb0890" in compact or "gng458" in compact: return 1
    if "bluebell" in text or "dng60" in compact: return 2
    if "brake" in text or "an19a018" in compact: return 3
    return 0


def normalize(x: np.ndarray) -> np.ndarray:
    x = np.asarray(x, dtype=np.float64)
    finite = np.isfinite(x)
    if not finite.any(): return np.zeros_like(x)
    lo = float(np.min(x[finite])); hi = float(np.max(x[finite]))
    if hi <= lo: return np.zeros_like(x)
    return (np.clip(x, lo, hi) - lo) / (hi - lo)


def largest_remainder(counts: dict[str, int], budget: int) -> dict[str, int]:
    total = sum(counts.values())
    raw = {k: budget * v / total for k, v in counts.items()}
    q = {k: int(math.floor(v)) for k, v in raw.items()}
    left = budget - sum(q.values())
    for k in sorted(raw, key=lambda k: (raw[k] - q[k], k), reverse=True)[:left]:
        q[k] += 1
    return q


def iter_edges(path: Path):
    for b in batches(path, ["body_pre", "body_post", "weight"]):
        yield (
            b.column(0).to_numpy(zero_copy_only=False).astype(np.int64),
            b.column(1).to_numpy(zero_copy_only=False).astype(np.int64),
            b.column(2).to_numpy(zero_copy_only=False).astype(np.int64),
        )


def hash_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(8 * 1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def main(root: Path, target=TARGET, max_local_swaps=250):
    paths = source_paths(root)
    ann_cols = [c for c in ["bodyId", "superclass", "type", "class", "subclass", "instance", "somaSide", "status"] if c in read_table(paths["annotations"], columns=None).columns]
    ann = read_table(paths["annotations"], columns=ann_cols)
    # The audited MaleCNS neuron census for FlyBrain is defined by a non-empty
    # published superclass, not by status == Traced. The official annotation
    # file contains 165,122 Traced rows but 166,700 distinct neuron bodies with
    # a non-empty superclass; our reduction target and previous audit use the
    # latter population.
    ann = ann[ann["superclass"].notna() & ann["superclass"].astype(str).str.strip().ne("")].copy()
    ann["bodyId"] = ann["bodyId"].astype(np.int64)
    ann = ann.drop_duplicates("bodyId").reset_index(drop=True)
    if len(ann) != 166700:
        print(f"WARNING: annotation census is {len(ann)}, expected 166700 from the audit.")

    ann = ann.sort_values("bodyId").reset_index(drop=True)
    ann["channel"] = ann.apply(classify_channel, axis=1).astype(np.int8)
    ann["motor_role"] = ann.apply(classify_motor_role, axis=1).astype(np.int8)
    ann["dn_role"] = ann.apply(classify_dn_role, axis=1).astype(np.int8)
    ann["halt_role"] = ann.apply(classify_halt, axis=1).astype(np.int8)
    ann["side"] = ann["somaSide"].fillna("").map({"L": -1, "R": 1}).fillna(0).astype(np.int8) if "somaSide" in ann.columns else 0

    ids = ann.bodyId.to_numpy(np.int64)
    n = len(ann)
    index = {int(b): i for i, b in enumerate(ids)}
    out_w = np.zeros(n, dtype=np.float64)
    in_w = np.zeros(n, dtype=np.float64)
    out_e = np.zeros(n, dtype=np.int32)
    in_e = np.zeros(n, dtype=np.int32)

    # Structural bridge features: incoming sensory weight and outgoing weight
    # toward DNs/motors. These are calculated from the published graph only.
    sensor_in = np.zeros(n, dtype=np.float64)
    dn_out = np.zeros(n, dtype=np.float64)
    motor_out = np.zeros(n, dtype=np.float64)
    dn_in = np.zeros(n, dtype=np.float64)
    motor_in = np.zeros(n, dtype=np.float64)

    for pre, post, w in iter_edges(paths["weights"]):
        pi = np.searchsorted(ids, pre)
        po = np.searchsorted(ids, post)
        pv = (pi < n) & (ids[np.minimum(pi, n - 1)] == pre)
        qv = (po < n) & (ids[np.minimum(po, n - 1)] == post)
        both = pv & qv & (w > 0)
        if not both.any(): continue
        a = pi[both]; b = po[both]; ww = w[both].astype(np.float64)
        np.add.at(out_w, a, ww); np.add.at(in_w, b, ww)
        np.add.at(out_e, a, 1); np.add.at(in_e, b, 1)
        m = ann.channel.to_numpy()[a] < 4
        if m.any(): np.add.at(sensor_in, b[m], ww[m])
        m = ann.superclass.to_numpy()[b] == "descending_neuron"
        if m.any(): np.add.at(dn_out, a[m], ww[m])
        m = ann.superclass.to_numpy()[b] == "vnc_motor"
        if m.any(): np.add.at(motor_out, a[m], ww[m])
        m = ann.superclass.to_numpy()[a] == "descending_neuron"
        if m.any(): np.add.at(dn_in, b[m], ww[m])
        m = ann.superclass.to_numpy()[a] == "vnc_motor"
        if m.any(): np.add.at(motor_in, b[m], ww[m])

    degree_score = normalize(np.log1p(out_w + in_w))
    bridge_score = normalize(np.sqrt(np.maximum(sensor_in, 0) * np.maximum(dn_out + motor_out, 0)))
    # Two-hop bridge proxies: for a candidate v, these are the measured
    # number/weight of possible sensory->v->DN and DN->v->motor paths in the
    # published graph. They are not synthetic edges; they are set-selection
    # features derived from real endpoint pairs. The products are deliberately
    # kept separate from raw degree so a high-degree cell is not automatically
    # treated as functionally relevant.
    sensor_count = np.zeros(n, dtype=np.float64)
    dn_in_count = np.zeros(n, dtype=np.float64)
    dn_out_count = np.zeros(n, dtype=np.float64)
    motor_out_count = np.zeros(n, dtype=np.float64)
    # Reconstruct counts from the already accumulated edge-weight features by
    # using one additional streaming pass below; initialized here for clarity.
    for pre, post, w in iter_edges(paths["weights"]):
        pi = np.searchsorted(ids, pre); po = np.searchsorted(ids, post)
        pv = (pi < n) & (ids[np.minimum(pi, n - 1)] == pre)
        qv = (po < n) & (ids[np.minimum(po, n - 1)] == post)
        both = pv & qv & (w > 0)
        if not both.any(): continue
        a = pi[both]; b = po[both]
        # Each source->target neuron-level edge contributes one possible
        # two-hop partner, regardless of contact weight.
        m = ann.channel.to_numpy()[a] < 4
        if m.any(): np.add.at(sensor_count, b[m], 1.0)
        m = ann.superclass.to_numpy()[a] == "descending_neuron"
        if m.any(): np.add.at(dn_in_count, b[m], 1.0)
        m = ann.superclass.to_numpy()[b] == "descending_neuron"
        if m.any(): np.add.at(dn_out_count, a[m], 1.0)
        m = ann.superclass.to_numpy()[b] == "vnc_motor"
        if m.any(): np.add.at(motor_out_count, a[m], 1.0)

    sensory_dn_pairs = sensor_count * dn_out_count
    dn_motor_pairs = dn_in_count * motor_out_count
    route_pair_score = normalize(np.sqrt(sensory_dn_pairs) + np.sqrt(dn_motor_pairs))
    route_weight_score = normalize(
        np.sqrt(np.maximum(sensor_in, 0) * np.maximum(dn_out, 0))
        + np.sqrt(np.maximum(dn_in, 0) * np.maximum(motor_out, 0))
    )
    route_score = normalize(0.65 * route_pair_score + 0.35 * route_weight_score)
    arch_score = normalize(dn_out + motor_out + dn_in + motor_in)
    ann["score_degree"] = degree_score
    ann["score_bridge"] = bridge_score
    ann["score_route"] = route_score
    ann["score_arch"] = arch_score

    # Type diversity is a soft bonus. Rare types receive a concave bonus so
    # that the first representative is valuable, but functionally useful
    # populations can still receive additional neurons.
    type_key = ann["type"].fillna("").astype(str) if "type" in ann else ann.bodyId.astype(str)
    type_counts = type_key.value_counts()
    ann["type_bonus"] = type_key.map(lambda t: 1.0 / math.sqrt(max(1, int(type_counts.get(t, 1))))).to_numpy()
    ann["side_score"] = ann.side.ne(0).astype(float).to_numpy()

    # HARD anchors: all DNs and VNC motor neurons. This preserves the complete
    # output/command populations; their incoming/outgoing edges still remain
    # subject to endpoint selection. Halt-labelled neurons are also anchors if
    # they are not already covered by those populations.
    anchor_mask = ann.superclass.isin(ANCHOR_SUPERCLASSES).to_numpy() | ann.halt_role.to_numpy().astype(bool)
    anchors = np.flatnonzero(anchor_mask)
    if len(anchors) > target:
        raise RuntimeError(f"Hard anchors ({len(anchors)}) exceed target {target}.")

    selected = np.zeros(n, dtype=bool)
    selected[anchors] = True

    # Soft type coverage: add one representative per type where there is room,
    # but choose representatives by functional score rather than blindly by
    # bodyId/degree. This implements philosophy A directly.
    remaining = target - int(selected.sum())
    reps = []
    work = ann.copy()
    work["_idx"] = np.arange(n)
    work["functional"] = (
        W_ROUTE * work.score_route +
        W_ARCH * work.score_arch +
        W_DEGREE * work.score_degree +
        W_TYPE * work.type_bonus +
        W_SIDE * work.side_score +
        W_EDGE * normalize(work.score_degree + work.score_bridge)
    )
    for _, g in work[~selected].groupby(type_key[~selected], sort=False):
        reps.append(int(g.sort_values(["functional", "score_route", "score_degree", "bodyId"], ascending=[False, False, False, True]).iloc[0]._idx))
    reps = np.asarray(reps, dtype=np.int64)
    reps = reps[np.argsort(-work.loc[reps, "functional"].to_numpy())]
    take = min(remaining, len(reps))
    selected[reps[:take]] = True

    # Remaining selection: use a GLOBAL ranking with soft superclass and
    # lateralization regularizers. Unlike the previous version, superclass
    # proportions are not hard quotas: a functionally important population can
    # exceed its proportional share if its measured score justifies it.
    remaining = target - int(selected.sum())
    pool = ~selected

    marginal_edge = np.zeros(n, dtype=np.float64)
    selected_set = selected
    for pre, post, w in iter_edges(paths["weights"]):
        pi = np.searchsorted(ids, pre); po = np.searchsorted(ids, post)
        pv = (pi < n) & (ids[np.minimum(pi, n - 1)] == pre)
        qv = (po < n) & (ids[np.minimum(po, n - 1)] == post)
        both = pv & qv & (w > 0)
        if not both.any(): continue
        a = pi[both]; b = po[both]; ww = w[both].astype(np.float64)
        m = (~selected_set[a]) & selected_set[b]
        if m.any(): np.add.at(marginal_edge, a[m], ww[m])
        m = selected_set[a] & (~selected_set[b])
        if m.any(): np.add.at(marginal_edge, b[m], ww[m])
    marginal_edge = normalize(np.log1p(marginal_edge))

    base_score = (
        W_EDGE * marginal_edge
        + W_ROUTE * route_score
        + W_ARCH * arch_score
        + W_TYPE * ann.type_bonus.to_numpy()
        + W_DEGREE * degree_score
    )

    # Select in deterministic batches so soft balance can react to the current
    # composition without rescanning the 151M-row source graph.
    selected_counts = ann.loc[selected].groupby("superclass").size().to_dict()
    source_counts = ann.groupby("superclass").size().to_dict()
    source_side_known = int((ann.side != 0).sum())
    selected_side_l = int((ann.loc[selected, "side"] == -1).sum())
    selected_side_r = int((ann.loc[selected, "side"] == 1).sum())
    batch_size = 512
    balance_lambda = 0.04
    side_lambda = 0.025

    while remaining > 0:
        candidates = np.flatnonzero(~selected)
        take = min(batch_size, remaining)
        score = base_score[candidates].copy()
        for j, idx in enumerate(candidates):
            sc = str(ann.iloc[idx].superclass)
            desired = source_counts.get(sc, 0) / max(1, n) * target
            current = selected_counts.get(sc, 0)
            # Penalize only over-representation; under-represented populations
            # are never punished. This makes superclass share a soft objective.
            score[j] -= balance_lambda * max(0.0, (current - desired) / max(1.0, desired))
            side = int(ann.iloc[idx].side)
            if side == -1 and selected_side_l > selected_side_r + 0.05 * max(1, selected_side_l + selected_side_r):
                score[j] -= side_lambda
            elif side == 1 and selected_side_r > selected_side_l + 0.05 * max(1, selected_side_l + selected_side_r):
                score[j] -= side_lambda
        order = candidates[np.argsort(-score, kind="mergesort")[:take]]
        selected[order] = True
        for idx in order:
            sc = str(ann.iloc[idx].superclass)
            selected_counts[sc] = selected_counts.get(sc, 0) + 1
            side = int(ann.iloc[idx].side)
            if side == -1: selected_side_l += 1
            elif side == 1: selected_side_r += 1
        remaining -= len(order)

    if selected.sum() != target:
        raise AssertionError((int(selected.sum()), target))

    # Local swaps: preserve all hard anchors. Swaps are evaluated only among
    # optional neurons, so the hard architectural constraints cannot be broken.
    # This is a conservative deterministic improvement pass over the static
    # objective proxy; the final report/validator remains authoritative.
    anchor_mask_final = anchor_mask.copy()
    selected_score = (
        W_ROUTE * route_score + W_ARCH * arch_score + W_DEGREE * degree_score
        + W_TYPE * ann.type_bonus.to_numpy() + W_SIDE * ann.side_score.to_numpy()
        + W_EDGE * marginal_edge
    )
    optional_selected = selected & ~anchor_mask_final
    optional_unsel = ~selected
    for _ in range(max_local_swaps):
        sel = np.flatnonzero(optional_selected)
        unsel = np.flatnonzero(optional_unsel)
        if not len(sel) or not len(unsel):
            break
        a = sel[np.argmin(selected_score[sel])]
        b = unsel[np.argmax(selected_score[unsel])]
        if selected_score[b] <= selected_score[a] + 1e-12:
            break
        selected[a] = False
        selected[b] = True
        optional_selected[a] = False
        optional_selected[b] = True
        optional_unsel[a] = True
        optional_unsel[b] = False

    chosen = ann[selected].copy().reset_index(drop=True)
    if len(chosen) != target:
        raise AssertionError(len(chosen))
    if not np.all(selected[anchor_mask]):
        raise AssertionError("Hard anchor lost during selection")
    if int(chosen.superclass.eq("descending_neuron").sum()) != int(ann.superclass.eq("descending_neuron").sum()):
        raise AssertionError("Not all descending neurons survived FBR-10")
    if int(chosen.superclass.eq("vnc_motor").sum()) != int(ann.superclass.eq("vnc_motor").sum()):
        raise AssertionError("Not all VNC motor neurons survived FBR-10")

    # Stable anatomical blocks for Android runtime.
    def block(row):
        sc = row.superclass
        if row.channel < 4: return int(row.channel)
        if sc == "descending_neuron": return 4
        if sc == "ascending_neuron": return 5
        if sc == "vnc_motor": return 6
        return 7
    chosen["block"] = chosen.apply(block, axis=1)
    chosen = chosen.sort_values(["block", "superclass", "bodyId"]).reset_index(drop=True)
    selected_ids = chosen.bodyId.to_numpy(np.int64)
    sel_index = {int(b): i for i, b in enumerate(selected_ids)}

    # Emit the strict induced subgraph. Unknown neurotransmitter sign does not
    # delete the biological edge from the structural graph; FBC103 stores the
    # structural edge with its raw positive contact weight. Runtime sign is
    # handled separately by metadata/NT lookup rather than corrupting topology.
    # First edge pass: count retained published edges and raw contacts. Do not
    # materialize millions of Python tuples in memory.
    edge_count = 0
    contacts = 0
    for pre, post, w in iter_edges(paths["weights"]):
        mask = np.isin(pre, selected_ids) & np.isin(post, selected_ids) & (w > 0)
        if not mask.any():
            continue
        edge_count += int(mask.sum())
        contacts += int(w[mask].sum())

    # Binary format: preserve FBC103 layout. Structural edge weights remain the
    # raw positive MaleCNS contact counts. No neurotransmitter sign, normalization
    # or synthetic edge is applied during FBR-10 construction.
    out = root / "app" / "src" / "main" / "res" / "raw" / "malecns_fbr10.bin"
    out.parent.mkdir(parents=True, exist_ok=True)
    scs = sorted(chosen.superclass.astype(str).unique())
    sc_code = {s: i for i, s in enumerate(scs)}
    with out.open("wb") as f:
        f.write(MAGIC)
        f.write(struct.pack("<II", target, edge_count))
        for _, r in chosen.iterrows():
            f.write(struct.pack(
                "<qbbbbbbfff", int(r.bodyId), int(sc_code[str(r.superclass)]),
                int(r.side), int(r.channel), int(r.motor_role), int(r.dn_role),
                int(r.halt_role), float(r.score_route), float(r.score_arch),
                float(r.score_degree)
            ))

        written_edges = 0
        written_contacts = 0
        for pre, post, w in iter_edges(paths["weights"]):
            mask = np.isin(pre, selected_ids) & np.isin(post, selected_ids) & (w > 0)
            if not mask.any():
                continue
            for a, b, ww in zip(pre[mask], post[mask], w[mask]):
                f.write(struct.pack("<iif", sel_index[int(a)], sel_index[int(b)], float(ww)))
                written_edges += 1
                written_contacts += int(ww)
        if written_edges != edge_count or written_contacts != contacts:
            raise AssertionError((written_edges, edge_count, written_contacts, contacts))

    report = {
        "algorithm": "FBR-10",
        "philosophy": "A",
        "target_neurons": target,
        "selected_neurons": int(len(chosen)),
        "source_neurons": int(len(ann)),
        "source_distinct_types": int(type_key.nunique()),
        "selected_distinct_types": int(chosen["type"].astype(str).nunique()) if "type" in chosen else None,
        "selected_type_fraction": float(chosen["type"].astype(str).nunique() / type_key.nunique()) if "type" in chosen else None,
        "route_pair_score_definition": "sqrt(number of sensory->candidate edges * number of candidate->DN edges) + sqrt(number of DN->candidate edges * number of candidate->motor edges), combined with corresponding contact-weight bridge score",
        "source_route_pair_score_nonzero": int((route_pair_score > 0).sum()),
        "selected_route_pair_score_nonzero": int((route_pair_score[selected] > 0).sum()),
        "hard_anchor_count": int(anchor_mask.sum()),
        "descending_selected": int(chosen.superclass.eq("descending_neuron").sum()),
        "vnc_motor_selected": int(chosen.superclass.eq("vnc_motor").sum()),
        "edges_selected": int(edge_count),
        "contacts_selected": int(contacts),
        "edge_definition": "strict induced subgraph of published MaleCNS neuron-level edges; raw positive contact weights preserved; no synthetic edges",
        "objective_weights": {"edge": W_EDGE, "route": W_ROUTE, "architecture": W_ARCH, "type": W_TYPE, "side": W_SIDE, "degree": W_DEGREE},
        "soft_balance": {"superclass_lambda": balance_lambda, "lateralization_lambda": side_lambda},
        "selection_notes": [
            "16,669 neurons is a hard constraint.",
            "Type diversity is a soft objective; no 10%-of-types constraint is imposed.",
            "Descending and VNC motor populations are hard anchors and are protected from local swaps.",
            "Halt-labelled source neurons are anchors when present.",
            "Remaining neurons are selected globally using functional bridge, two-hop route proxy, architecture, connectivity, type novelty, degree and soft superclass/lateralization regularizers; superclass proportions are not hard quotas.",
            "The final graph contains only published edges between retained neurons.",
        ],
        "male_cns_source": BASE,
        "output_sha256": hash_file(out),
    }
    report_path = root / "build" / "FBR10_REPORT.json"
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    ap.add_argument("--target", type=int, default=TARGET)
    ap.add_argument("--max-local-swaps", type=int, default=250)
    args = ap.parse_args()
    if args.target != TARGET:
        raise SystemExit("FBR-10 is intentionally fixed at exactly 16,669 neurons.")
    main(args.root, args.target, args.max_local_swaps)
