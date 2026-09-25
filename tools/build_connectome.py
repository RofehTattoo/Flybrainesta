#!/usr/bin/env python3
"""Build a deterministic 16,669-neuron MaleCNS v1.0 reduction for FlyBrain V1.18.3 FBR-10-OLF2-MOTORROUTE.

The reduction is derived from the published MaleCNS v1.0 annotation and weighted
connectivity tables. It keeps exactly 16,669 neurons from the audited 166,700-neuron census
by stratifying on the published superclass and preserving measured route support,
connectivity, and the audited ORN/VNC populations. Only published edges between retained neurons
are embedded; no graph edge is invented here.
"""
from __future__ import annotations

import json
import math
import re
import hashlib
import struct
import urllib.request
from pathlib import Path

import numpy as np
import pandas as pd
import pyarrow as pa
import pyarrow.ipc as ipc

BASE = "https://storage.googleapis.com/flyem-male-cns/v1.0/connectome-data/flat-connectome/"
TARGET = 16669
FLYBRAIN_RELEASE = "1.18.3"
APP_VERSION_CODE = 136
REDUCTION_ID = "FBR-10-OLF2-MOTORROUTE"
TARGET_ORNS = 264  # 10% of the 2,639 MaleCNS v1.0 ORNs, rounded to nearest integer.
EXPECTED_ORN_TYPES = 54

# Four MaleCNS v1.0 cells are anatomically annotated as olfactory/cb_sensory
# ORNs entering through the antennal nerve, but their published `type` field is
# NULL. They are part of the official 2,639-cell ORN census and must therefore
# be recognized without inventing a type label.
UNTYPED_ORN_BODY_IDS = frozenset({242812, 242908, 488209, 956041})

FORMAT_MAGIC = b"FBC103\x00\x00"
FORMAT_VERSION = 103
NODE_SIZE = 26
EDGE_SIZE = 12
FILES = {
    "annotations": "body-annotations-male-cns-v1.0-minconf-0.5.feather",
    "weights": "connectome-weights-male-cns-v1.0-minconf-0.5.feather",
}

EXPECTED_SOURCE_SHA256 = {
    "annotations": "2177e246113e4cfbf1e7772ec37c6da1955ff22e8063d0b1f833101f99a9a3b2",
    "weights": "e35da783d1c686b2b58b3b87cd6a403ae43bfcfba8bff28e08ef752c1a56afc1",
}


SUPERCLASS_CODE = {}

def download(path: Path, name: str) -> None:
    if path.exists() and path.stat().st_size > 0:
        return
    url = BASE + name
    tmp = path.with_suffix(path.suffix + ".partial")
    print(f"Downloading {name} ...", flush=True)
    urllib.request.urlretrieve(url, tmp)
    tmp.replace(path)


def clean(x):
    if pd.isna(x):
        return ""
    return str(x)


def normalized_orn_type_entry_nerve_pairs(df: pd.DataFrame) -> pd.DataFrame:
    """Return unique published ORN (type, entryNerve) combinations.

    MaleCNS v1.0 contains 54 distinct typed ORN combinations but only 53
    unique `type` strings because ORN_VA7l occurs under both AN and MxLbN.
    The reduction must preserve the source distinction without inventing a
    new type label.
    """
    pairs = df.loc[df["type"].notna(), ["type", "entryNerve"]].copy()
    pairs["type"] = pairs["type"].astype(str).str.strip()
    pairs["entryNerve"] = pairs["entryNerve"].astype(str).str.strip().str.upper()
    # The four official untyped ORNs are retained with their source `type`
    # represented as an empty string during selection. They are real ORNs, but
    # an empty type is not a published type+entryNerve combination and must not
    # inflate the 54-combination source/retained census.
    pairs = pairs[pairs["type"].ne("")]
    return pairs.drop_duplicates().sort_values(["type", "entryNerve"]).reset_index(drop=True)


def is_olfactory_orn(row) -> bool:
    """True only for real MaleCNS olfactory receptor neurons (ORNs).

    The normal MaleCNS ORN annotation has an ORN_* type. Four published
    MaleCNS v1.0 ORNs are an explicit annotation exception: they are
    cb_sensory + olfactory + AN cells whose `type` is NULL. Their bodyIds
    come directly from the official annotation and are not assigned a
    synthetic type here.
    """
    sc = clean(row.get("superclass", "")).strip().lower()
    cl = clean(row.get("class", "")).strip().lower()
    typ = clean(row.get("type", "")).strip().upper()
    nerve = clean(row.get("entryNerve", "")).strip().upper()
    try:
        body_id = int(row.get("bodyId", -1))
    except (TypeError, ValueError):
        body_id = -1

    is_published_untype_orn = body_id in UNTYPED_ORN_BODY_IDS

    return (
        sc == "cb_sensory"
        and cl == "olfactory"
        and nerve in {"AN", "MXLBN"}
        and (typ.startswith("ORN_") or is_published_untype_orn)
    )


def classify_channel(row) -> int:
    """0 visual, 1 olfactory, 2 gustatory, 3 mechanosensory/proprioceptive, 4 other."""
    sc = clean(row.get("superclass", ""))
    cl = clean(row.get("class", "")).lower()
    sub = clean(row.get("subclass", "")).lower()
    typ = clean(row.get("type", "")).lower()
    inst = clean(row.get("instance", "")).lower()
    name = clean(row.get("name", "")).lower()
    text = " ".join((cl, sub, typ, inst, name))
    # Only anatomically identified ORNs are assigned to the olfactory channel.
    # This deliberately prevents the historical ol_sensory visual cells (R7/R8)
    # from being mislabeled as olfactory.
    if is_olfactory_orn(row):
        return 1
    if sc in {"visual_projection", "visual_centrifugal"} or "visual" in text or "optic lobe" in text:
        return 0
    if "gust" in text or "taste" in text:
        return 2
    if sc in {"vnc_sensory", "sensory_ascending", "sensory_descending"}:
        return 3
    if sc.startswith("cb_sensory"):
        return 3
    if any(k in text for k in (
        "mechanosensory", "proprio", "bristle", "hair plate",
        "campaniform", "chordotonal", "johnston",
    )):
        return 3
    return 4


def classify_motor_role(row) -> int:
    """Assign the authoritative MaleCNS VNC motor class used by runtime.

    The previous reducer used free-text keyword guesses while runtime used the
    official class/subclass map. That made route preservation internally
    inconsistent: a cell could be selected as one motor family and executed as
    another. We now use the same measured annotation vocabulary in both layers.

    0=non-motor, 1=leg, 2=wing, 3=haltere, 4=neck, 5=abdomen, 6=jump, 7=other.
    TTMn remains anatomically WING; its jump functional tag belongs to the
    separate VNC semantics layer.
    """
    sc = clean(row.get("superclass", ""))
    if sc != "vnc_motor":
        return 0
    raw_cls = clean(row.get("class", "")).strip().lower()
    subclass = clean(row.get("subclass", "")).strip().lower()
    subclass_to_role = {
        "fl": 1, "ml": 1, "hl": 1,
        "wm": 2, "nm": 4, "hm": 3, "ad": 5, "xm": 6,
    }
    raw_to_role = {
        "leg": 1, "wing": 2, "haltere": 3, "neck": 4,
        "abdominal": 5, "abdomen": 5, "other": 6,
    }
    if subclass in subclass_to_role:
        role = subclass_to_role[subclass]
        if raw_cls and raw_cls in raw_to_role and raw_to_role[raw_cls] != role:
            raise ValueError(
                f"official VNC class/subclass conflict for bodyId={row.get('bodyId')}: "
                f"class={raw_cls!r} subclass={subclass!r}"
            )
        return role
    if raw_cls in raw_to_role:
        return raw_to_role[raw_cls]
    # Fail closed: unknown official motor semantics must never be promoted into
    # a locomotor route class by keyword coincidence.
    return 7


def classify_descending_role(row) -> int:
    """Assign a descriptive motor-family label to a descending neuron.

    The label is metadata only: it never creates, removes, or rewrites an edge.
    V1.04 uses explicit published cell-type names for the few descending
    populations whose behavioural role is established, and only then falls
    back to annotation keywords. This avoids the previous-version failure mode where
    almost all 1,314 DNs were left as role 0 simply because the annotation
    did not literally contain words such as "walk" or "escape".

    0=generic/unknown, 1=forward/walking, 2=turn/steering,
    3=backward walking, 4=fast escape.
    """
    sc = clean(row.get("superclass", ""))
    if sc != "descending_neuron":
        return 0

    fields = []
    for c in ("type", "instance", "name", "class", "subclass", "primary_neuropil", "target", "annotation", "group"):
        if c in row.index:
            fields.append(clean(row.get(c, "")))
    text = " ".join(fields).lower()
    compact = re.sub(r"[^a-z0-9]+", "", text)

    # Established walking/forward DNs.
    if any(k in compact for k in ("dng100", "dng97", "dnb08")) or any(
        k in text for k in ("forward walking", "walking command")
    ):
        return 1

    # Established steering/turning DNs and major central-complex descending
    # targets described for goal-directed steering.
    if any(k in compact for k in (
        "dna01", "dna02", "dna03", "dna04", "dna11",
        "dnb01", "dng13", "dng13a",
    )) or any(k in text for k in (
        "turn", "steer", "turning", "steering", "pfl3",
    )):
        return 2

    # Moonwalker/backward walking pathways.
    if any(k in compact for k in (
        "dnp50", "mdn", "moonwalker", "dnp07", "dnp09",
    )) or any(k in text for k in (
        "backward walking", "backward locomotion", "halting",
    )):
        return 3

    # DNp01 is the Giant Fiber descending neuron and drives the fast escape
    # pathway downstream of optic-lobe looming detectors.
    if "dnp01" in compact or any(k in text for k in (
        "giant fiber", "giant-fiber", "giant fibre", "giant-fibre",
    )):
        return 4

    return 0


def classify_halt_role(row) -> int:
    """Classify experimentally identified halting populations without inventing edges.
    0=none/unknown, 1=FG walk-OFF, 2=BB walk-OFF, 3=BRK VNC brake.
    Names/types are only metadata used to protect these real cells during reduction.
    """
    fields = []
    for c in ("type", "instance", "name", "class", "subclass", "primary_neuropil",
              "nerve", "target", "muscle", "annotation", "group"):
        if c in row.index:
            fields.append(clean(row.get(c, "")))
    text = " ".join(fields).lower()
    compact = re.sub(r"[^a-z0-9]+", "", text)
    # The 2024 halting study identifies FG, BB and BRK as causal halt populations.
    # MaleCNS v1.0 uses its current cell-type names/aliases rather than
    # necessarily carrying the Sapkal names in every annotation field.
    # Ground-truth mappings:
    #   FG / Foxglove  -> CB0890 (FlyWire) -> GNG458 in MaleCNS v1.0
    #   BB / Bluebell  -> DNg60
    #   BRK / Brake    -> AN19A018
    # These aliases are sourced from the MaleCNS Cell Type Explorer / FlyBase
    # mappings and are used only to label/protect the already-existing cells.
    if (
        "foxglove" in text
        or "cb0890" in compact
        or "gng458" in compact
        or re.search(r"(^|[^a-z0-9])fg([^a-z0-9]|$)", text)
        or "fgneuron" in compact
    ):
        return 1
    if (
        "bluebell" in text
        or "dng60" in compact
        or re.search(r"(^|[^a-z0-9])bb([^a-z0-9]|$)", text)
        or "bbneuron" in compact
    ):
        return 2
    if (
        re.search(r"(^|[^a-z0-9])brk([^a-z0-9]|$)", text)
        or "brake" in text
        or "an19a018" in compact
    ):
        return 3
    return 0

def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(8 * 1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def verify_pinned_source(path: Path, key: str) -> None:
    expected = EXPECTED_SOURCE_SHA256[key]
    actual = sha256_file(path)
    if actual != expected:
        raise RuntimeError(
            f"MaleCNS v1.0 {key} SHA-256 mismatch: {actual} != {expected}"
        )


def main(root: Path) -> None:
    raw = root / "build" / "malecns_raw"
    raw.mkdir(parents=True, exist_ok=True)
    for key, name in FILES.items():
        download(raw / name, name)
        verify_pinned_source(raw / name, key)

    annotations = pd.read_feather(raw / FILES["annotations"])
    # MaleCNS v1.0 reduction universe: every annotated neuronal entry with an
    # assigned superclass. Do NOT restrict the source graph to status=="Traced".
    # The pinned MaleCNS v1.0 release contains 166,700 such annotated neurons;
    # the ORN census of 2,639 is defined on this same universe. Status is provenance
    # metadata and is not a node-selection filter.
    annotated = annotations[annotations["superclass"].notna()].copy()
    annotated["bodyId"] = annotated["bodyId"].astype(np.int64)
    annotated = annotated.drop_duplicates("bodyId").sort_values("bodyId").reset_index(drop=True)
    if len(annotated) != 166700:
        raise RuntimeError(
            f"MaleCNS v1.0 annotated neuron census changed: expected 166700, found {len(annotated)}"
        )

    ids = annotated["bodyId"].to_numpy(np.int64)
    degree = np.zeros(len(ids), dtype=np.float64)

    # One streaming pass over the 1.1 GB weighted graph to measure connectivity.
    weights_path = raw / FILES["weights"]
    reader = ipc.open_file(pa.memory_map(str(weights_path), "r"))
    for bi in range(reader.num_record_batches):
        b = reader.get_batch(bi)
        pre = b.column(b.schema.get_field_index("body_pre")).to_numpy(zero_copy_only=False).astype(np.int64)
        post = b.column(b.schema.get_field_index("body_post")).to_numpy(zero_copy_only=False).astype(np.int64)
        w = b.column(b.schema.get_field_index("weight")).to_numpy(zero_copy_only=False).astype(np.float64)
        pi = np.searchsorted(ids, pre)
        po = np.searchsorted(ids, post)
        pv = (pi < len(ids)) & (ids[np.minimum(pi, len(ids)-1)] == pre)
        qv = (po < len(ids)) & (ids[np.minimum(po, len(ids)-1)] == post)
        if pv.any(): np.add.at(degree, pi[pv], w[pv])
        if qv.any(): np.add.at(degree, po[qv], w[qv])
        if bi % 50 == 0:
            print(f"degree pass batch {bi}/{reader.num_record_batches}", flush=True)

    annotated["degree"] = degree
    counts = annotated.groupby("superclass", sort=True).size().to_dict()
    total = len(annotated)

    # V1.04 functional-route analysis. The reduction now preferentially preserves
    # measured two-hop pathways in the published graph instead of using a single
    # aggregate bridge score. For each candidate neuron we estimate whether the
    # full connectome contains paths of the form sensor -> candidate -> DN and
    # DN -> candidate -> VNC motor neuron, separately for forward, turning and
    # escape-related routes. No edge is invented by this analysis.
    annotated["is_olfactory_orn"] = annotated.apply(is_olfactory_orn, axis=1)
    annotated["channel"] = annotated.apply(classify_channel, axis=1)
    annotated["motor_role"] = annotated.apply(classify_motor_role, axis=1)
    annotated["descending_role"] = annotated.apply(classify_descending_role, axis=1)
    annotated["halt_role"] = annotated.apply(classify_halt_role, axis=1)

    is_sensory = annotated["channel"].to_numpy(np.int8) < 4
    is_desc = annotated["superclass"].astype(str).eq("descending_neuron").to_numpy()
    is_motor = annotated["superclass"].astype(str).eq("vnc_motor").to_numpy()
    channel = annotated["channel"].to_numpy(np.int8)
    desc_role = annotated["descending_role"].to_numpy(np.int8)
    motor_role = annotated["motor_role"].to_numpy(np.int8)
    halt_role = annotated["halt_role"].to_numpy(np.int8)
    is_olfactory = annotated["is_olfactory_orn"].to_numpy(bool)

    orn_source = annotated[is_olfactory].copy()
    orn_type_counts_source = orn_source.groupby("type", sort=True).size().to_dict()
    orn_type_entry_nerve_pairs_source = normalized_orn_type_entry_nerve_pairs(orn_source)
    if len(orn_source) != 2639:
        raise RuntimeError(f"MaleCNS v1.0 ORN census changed: expected 2639, found {len(orn_source)}")
    if len(orn_type_entry_nerve_pairs_source) != EXPECTED_ORN_TYPES:
        raise RuntimeError(
            "MaleCNS v1.0 ORN type+entryNerve census changed: "
            f"expected {EXPECTED_ORN_TYPES}, found {len(orn_type_entry_nerve_pairs_source)}"
        )

    role_counts_source = np.bincount(desc_role[is_desc], minlength=5)
    missing_roles = [
        name for name, role in (("forward", 1), ("turn", 2), ("escape", 4))
        if int(role_counts_source[role]) == 0
    ]
    if missing_roles:
        raise RuntimeError(
            "Published MaleCNS annotations did not expose required DN role(s): "
            + ", ".join(missing_roles)
        )

    # Incoming sensory weight per candidate, separated by modality.
    sensor_in = np.zeros((4, len(ids)), dtype=np.float64)
    # Candidate -> DN role weight.
    cell_to_desc = np.zeros((5, len(ids)), dtype=np.float64)
    # DN role -> candidate weight.
    desc_to_cell = np.zeros((5, len(ids)), dtype=np.float64)
    # Candidate <-> ANY descending neuron weight. Role labels are descriptive and
    # some official DNs remain role 0; routing must not discard those real cells.
    cell_to_any_desc = np.zeros(len(ids), dtype=np.float64)
    any_desc_to_cell = np.zeros(len(ids), dtype=np.float64)
    # Candidate -> motor-role weight.
    cell_to_motor = np.zeros((8, len(ids)), dtype=np.float64)

    direct_sensor_desc = np.zeros((4, 5), dtype=np.float64)
    direct_desc_motor = np.zeros((5, 8), dtype=np.float64)

    for bi in range(reader.num_record_batches):
        b = reader.get_batch(bi)
        pre = b.column(b.schema.get_field_index("body_pre")).to_numpy(zero_copy_only=False).astype(np.int64)
        post = b.column(b.schema.get_field_index("body_post")).to_numpy(zero_copy_only=False).astype(np.int64)
        w = b.column(b.schema.get_field_index("weight")).to_numpy(zero_copy_only=False).astype(np.float64)
        pi = np.searchsorted(ids, pre)
        po = np.searchsorted(ids, post)
        pv = (pi < len(ids)) & (ids[np.minimum(pi, len(ids)-1)] == pre)
        qv = (po < len(ids)) & (ids[np.minimum(po, len(ids)-1)] == post)
        both = pv & qv & (w > 0)
        if not both.any():
            continue
        ai = pi[both]
        ci = po[both]
        wd = w[both]

        # Sensor -> candidate.
        m = is_sensory[ai]
        if m.any():
            for ch in range(4):
                mm = m & (channel[ai] == ch)
                if mm.any():
                    np.add.at(sensor_in[ch], ci[mm], wd[mm])

        # Candidate -> descending neuron. Keep both the role-specific view used
        # by diagnostics and an all-DN view used for topology preservation.
        m = is_desc[ci]
        if m.any():
            np.add.at(cell_to_any_desc, ai[m], wd[m])
            for role in range(1, 5):
                mm = m & (desc_role[ci] == role)
                if mm.any():
                    np.add.at(cell_to_desc[role], ai[mm], wd[mm])

        # Descending neuron -> candidate. Generic DNs (role 0) are included in
        # the topology-preservation view because the biological role classifier
        # must never determine whether a real DN is allowed to carry the circuit.
        m = is_desc[ai]
        if m.any():
            np.add.at(any_desc_to_cell, ci[m], wd[m])
            for role in range(1, 5):
                mm = m & (desc_role[ai] == role)
                if mm.any():
                    np.add.at(desc_to_cell[role], ci[mm], wd[mm])

        # Candidate -> motor neuron.
        m = is_motor[ci]
        if m.any():
            for mr in range(1, 8):
                mm = m & (motor_role[ci] == mr)
                if mm.any():
                    np.add.at(cell_to_motor[mr], ai[mm], wd[mm])

        # Direct route counts are retained for the build report.
        m = is_sensory[ai] & is_desc[ci]
        if m.any():
            np.add.at(
                direct_sensor_desc,
                (channel[ai[m]], desc_role[ci[m]]),
                wd[m],
            )
        m = is_desc[ai] & is_motor[ci]
        if m.any():
            np.add.at(
                direct_desc_motor,
                (desc_role[ai[m]], motor_role[ci[m]]),
                wd[m],
            )

        if bi % 50 == 0:
            print(f"functional route pass batch {bi}/{reader.num_record_batches}", flush=True)

    # Two-hop route scores. Geometric means prevent a cell with only one strong
    # side of a route from dominating. The scores are used for neuron selection
    # and are also embedded as descriptive runtime metadata; they never alter an edge.
    # Route-family scores preserve both sides of the circuit:
    #   sensor -> DN   and   DN -> premotor -> motor.
    # A candidate does not need to be the same cell on both sides. This is
    # important for real layered circuits such as LC4/LPLC2 -> DNp01 -> VNC.
    # The scores are still purely descriptive and never create an edge.
    sensory_forward = np.sqrt(
        np.maximum(0.0, sensor_in.sum(axis=0) * cell_to_desc[1])
    )
    visual_turn = np.sqrt(np.maximum(0.0, sensor_in[0] * cell_to_desc[2]))
    threat_escape = np.sqrt(
        np.maximum(0.0, (sensor_in[0] + sensor_in[3]) * cell_to_desc[4])
    )

    forward_motor_path = np.sqrt(
        np.maximum(0.0, desc_to_cell[1] * cell_to_motor[1])
    )
    # Do not make the reduction depend on a hand-labelled DN role for the
    # existence of the VNC walking pathway. Every published DN is retained;
    # therefore premotor bridge cells are scored against the complete DN pool.
    # IMPORTANT: use the complete retained DN population for structural route
    # preservation. `descending_role=0` is only an annotation/readout label; it
    # must not erase a real MaleCNS DN from the sensorimotor graph.
    all_cell_to_desc = cell_to_any_desc
    all_desc_to_cell = any_desc_to_cell
    olfactory_to_desc_path = np.sqrt(
        np.maximum(0.0, sensor_in[1] * all_cell_to_desc)
    )
    descending_to_leg_path = np.sqrt(
        np.maximum(0.0, all_desc_to_cell * cell_to_motor[1])
    )
    # Turning/steering can recruit coordinated motor outputs.
    turn_motor_outputs = cell_to_motor[1:].sum(axis=0)
    turn_motor_path = np.sqrt(
        np.maximum(0.0, desc_to_cell[2] * turn_motor_outputs)
    )
    escape_motor_path = np.sqrt(np.maximum(
        0.0,
        desc_to_cell[4] * (cell_to_motor[1] + cell_to_motor[2] + cell_to_motor[6])
    ))

    route_forward = sensory_forward + forward_motor_path
    route_turn = visual_turn + turn_motor_path
    route_escape = threat_escape + escape_motor_path
    route_sensorimotor = np.sqrt(np.maximum(
        0.0,
        sensor_in.sum(axis=0) * cell_to_motor[1:].sum(axis=0)
    ))
    # Explicit olfactory sensorimotor preservation. This is still a measured
    # topology score over published edges; it never creates or rewrites an edge.
    # The first term protects real ORN -> candidate -> forward-DN intermediates;
    # the second protects real ORN -> candidate -> motor sensorimotor bridges.
    route_olfactory_forward = np.sqrt(
        np.maximum(0.0, sensor_in[1] * cell_to_desc[1])
    )
    route_olfactory_motor = np.sqrt(
        np.maximum(0.0, sensor_in[1] * cell_to_motor[1:].sum(axis=0))
    )

    # Halt-route protection and multi-hop bridge discovery. These scores are
    # measured from published edges and are used only to choose which real
    # neurons survive the 10% reduction. They never create or rewrite edges.
    halt_to_walk = np.zeros(len(ids), dtype=np.float64)
    halt_to_motor = np.zeros(len(ids), dtype=np.float64)
    halt_target = np.zeros(len(ids), dtype=np.float64)

    # The previous route analysis only protected `sensor -> candidate -> DN`
    # and `DN -> candidate -> LEG-MN`. A layered circuit can contain two real
    # intermediate neurons between the endpoint populations. We therefore also
    # score every measured three-edge path `ORN -> A -> B -> DN` and
    # `DN -> A -> B -> LEG-MN`. Both A and B are legitimate reduction candidates.
    # The score uses only published contact counts and is normalized later.
    olfactory_three_edge = np.zeros(len(ids), dtype=np.float64)
    desc_leg_three_edge = np.zeros(len(ids), dtype=np.float64)
    # Re-use the retained classification arrays: halt neurons -> walking DNs,
    # and halt neurons -> motor/premotor outputs are the two biologically relevant
    # preservation routes.
    for bi in range(reader.num_record_batches):
        b = reader.get_batch(bi)
        pre = b.column(b.schema.get_field_index("body_pre")).to_numpy(zero_copy_only=False).astype(np.int64)
        post = b.column(b.schema.get_field_index("body_post")).to_numpy(zero_copy_only=False).astype(np.int64)
        w = b.column(b.schema.get_field_index("weight")).to_numpy(zero_copy_only=False).astype(np.float64)
        pi = np.searchsorted(ids, pre)
        po = np.searchsorted(ids, post)
        pv = (pi < len(ids)) & (ids[np.minimum(pi, len(ids)-1)] == pre)
        qv = (po < len(ids)) & (ids[np.minimum(po, len(ids)-1)] == post)
        both = pv & qv & (w > 0)
        if not both.any():
            continue
        ai = pi[both]; ci = po[both]; wd = w[both]
        # Source halt neurons to forward/turn/escape walking DNs.
        m = (halt_role[ai] > 0) & is_desc[ci] & np.isin(desc_role[ci], [1, 2, 4])
        if m.any():
            np.add.at(halt_target, ci[m], wd[m])
        if m.any():
            np.add.at(halt_to_walk, ai[m], wd[m])
        # Measured three-edge olfactory relay:
        # ORN -> A -> B -> DN. `sensor_in[1][A]` proves a real ORN->A edge,
        # `all_cell_to_desc[B]` proves a real B->DN edge, and the current
        # A->B edge is the middle published connection. No bridge is invented.
        olf_source = sensor_in[1][ai]
        olf_target = all_cell_to_desc[ci]
        m3_olf = (olf_source > 0) & (olf_target > 0)
        if m3_olf.any():
            path_score = np.sqrt(np.maximum(0.0, olf_source[m3_olf] * wd[m3_olf] * olf_target[m3_olf]))
            np.add.at(olfactory_three_edge, ai[m3_olf], path_score)
            np.add.at(olfactory_three_edge, ci[m3_olf], path_score)

        # Measured three-edge DN->leg relay:
        # DN -> A -> B -> LEG-MN. `all_desc_to_cell[A]` proves DN->A,
        # `cell_to_motor[LEG][B]` proves B->leg-MN, and A->B is the measured
        # intermediate connection.
        desc_source = all_desc_to_cell[ai]
        leg_target = cell_to_motor[1][ci]
        m3_leg = (desc_source > 0) & (leg_target > 0)
        if m3_leg.any():
            path_score = np.sqrt(np.maximum(0.0, desc_source[m3_leg] * wd[m3_leg] * leg_target[m3_leg]))
            np.add.at(desc_leg_three_edge, ai[m3_leg], path_score)
            np.add.at(desc_leg_three_edge, ci[m3_leg], path_score)

        # Halt neurons to motor neurons or real premotor intermediates.
        m2 = (halt_role[ai] > 0) & (is_motor[ci] | (
            (~is_sensory[ci]) & (~is_desc[ci]) & (~is_motor[ci])
        ))
        if m2.any():
            np.add.at(halt_to_motor, ai[m2], wd[m2])
            np.add.at(halt_target, ci[m2], wd[m2])

    route_halt = np.maximum(halt_target, 0.5 * halt_to_walk)

    # Keep a node when either a measured two-hop route or a measured three-edge
    # relay supports it. The graph itself remains the strict induced MaleCNS
    # subgraph; these arrays only influence which nodes make the 16,669 cut.
    olfactory_to_desc_path = np.maximum(olfactory_to_desc_path, olfactory_three_edge)
    descending_to_leg_path = np.maximum(descending_to_leg_path, desc_leg_three_edge)

    # V1.04 is fail-closed at the source-analysis level. A build is not allowed
    # to publish a reduced graph whose three intended behavioural route families
    # are silently absent. These are measured topology scores, not synthetic
    # currents or generated edges.
    missing_routes = [
        name for name, score in (
            ("forward", route_forward),
            ("turn", route_turn),
            ("escape", route_escape),
            ("olfactory_forward", route_olfactory_forward),
            ("olfactory_to_desc", olfactory_to_desc_path),
            ("descending_to_leg", descending_to_leg_path),
        )
        if not np.any(score > 0)
    ]
    if missing_routes:
        raise RuntimeError(
            "MaleCNS v1.0 route analysis produced no positive measured "
            + ", ".join(missing_routes)
            + " route candidate(s); refusing to publish V1.04."
        )

    def normalize_score(x):
        m = float(np.nanmax(x)) if len(x) else 0.0
        return (x / m) if m > 0 else np.zeros_like(x)

    route_forward_n = normalize_score(route_forward)
    route_turn_n = normalize_score(route_turn)
    route_escape_n = normalize_score(route_escape)
    route_sensorimotor_n = normalize_score(route_sensorimotor)
    route_olfactory_forward_n = normalize_score(route_olfactory_forward)
    route_olfactory_motor_n = normalize_score(route_olfactory_motor)
    olfactory_to_desc_n = normalize_score(olfactory_to_desc_path)
    descending_to_leg_n = normalize_score(descending_to_leg_path)
    route_halt_n = normalize_score(route_halt)
    degree_n = normalize_score(annotated["degree"].to_numpy(np.float64))

    annotated["route_forward"] = route_forward_n
    annotated["route_turn"] = route_turn_n
    annotated["route_escape"] = route_escape_n
    annotated["route_sensorimotor"] = route_sensorimotor_n
    annotated["route_olfactory_forward"] = route_olfactory_forward_n
    annotated["route_olfactory_motor"] = route_olfactory_motor_n
    annotated["route_olfactory_to_desc"] = olfactory_to_desc_n
    annotated["route_desc_to_leg"] = descending_to_leg_n
    annotated["route_halt"] = route_halt_n

    # Source ORN census is validated above independently of selection scores.
    # Selection ranking happens after route metadata exists, so use a refreshed
    # ORN view for the ranking stage.
    annotated["route_score"] = (
        0.30 * route_forward_n
        + 0.22 * route_turn_n
        + 0.34 * route_escape_n
        + 0.14 * route_sensorimotor_n
        + 0.24 * route_olfactory_forward_n
        + 0.12 * route_olfactory_motor_n
    )

    # Selection strategy for V1.04:
    # 1) retain every curated descending neuron and every curated VNC motor neuron;
    # 2) retain at least one representative per published neuron type;
    # 3) reserve a substantial quota for measured two-hop functional routes;
    # 4) fill remaining slots proportionally by superclass, ranked by route score
    #    and then degree. This explicitly protects intermediate premotor cells.
    forced = annotated[annotated["superclass"].astype(str).isin({"descending_neuron", "vnc_motor"}) | (halt_role > 0)].copy()

    type_col = "type" if "type" in annotated.columns else None
    if type_col is None:
        annotated["type"] = annotated["bodyId"].astype(str)
        type_col = "type"
    annotated[type_col] = annotated[type_col].fillna("").astype(str)
    # Rebuild the ORN selection view after type normalization. The source contains
    # four official ORNs whose published type is NULL; normalizing here lets us
    # explicitly exclude them from the typed group-by rather than silently dropping
    # them from protection. No synthetic type label is assigned.
    orn_selection_source = annotated[annotated["is_olfactory_orn"]].copy()

    type_rep = (
        annotated.sort_values(["degree", "bodyId"], ascending=[False, True])
        .drop_duplicates(["superclass", type_col], keep="first")
    )

    # Protect 10% of the published ORN population (264/2639) and ensure that
    # all 54 published ORN types remain represented. Selection is deterministic
    # and still ranked only by measured connectivity/route support.
    orn_protected_parts = []
    remaining_orn = TARGET_ORNS

    # Explicitly retain the four official MaleCNS ORNs whose published type is
    # NULL. A pandas group-by on `type` drops NULL groups by default, which would
    # otherwise exclude these real ORNs from FBR-10 even though `is_olfactory_orn`
    # correctly recognizes them. The source annotation is preserved verbatim.
    official_untyped = orn_selection_source[
        orn_selection_source["bodyId"].isin(UNTYPED_ORN_BODY_IDS)
    ].sort_values("bodyId")
    if len(official_untyped) != len(UNTYPED_ORN_BODY_IDS):
        found_untyped = sorted(official_untyped["bodyId"].astype(int).tolist())
        raise AssertionError(
            "MaleCNS official untyped ORNs not all present in selection source: "
            f"expected={sorted(UNTYPED_ORN_BODY_IDS)} found={found_untyped}"
        )
    orn_protected_parts.append(official_untyped)
    remaining_orn -= len(official_untyped)

    selected_orn_ids = set(official_untyped["bodyId"].astype(int).tolist())
    typed_orn_selection_source = orn_selection_source[
        ~orn_selection_source.bodyId.isin(selected_orn_ids)
        & orn_selection_source["type"].astype(str).str.strip().ne("")
    ].copy()
    for orn_type, group in typed_orn_selection_source.groupby("type", sort=True):
        if remaining_orn <= 0:
            break
        take = min(4, len(group), remaining_orn)
        part = group.sort_values(
            ["route_olfactory_forward", "route_olfactory_motor", "degree", "bodyId"],
            ascending=[False, False, False, True],
        ).head(take)
        orn_protected_parts.append(part)
        remaining_orn -= len(part)
    if remaining_orn > 0:
        selected_orn_ids = set(
            pd.concat(orn_protected_parts, ignore_index=True).bodyId.astype(int).tolist()
            if orn_protected_parts else []
        )
        extra_orn = orn_selection_source[~orn_selection_source.bodyId.isin(selected_orn_ids)].sort_values(
            ["route_olfactory_forward", "route_olfactory_motor", "degree", "bodyId"],
            ascending=[False, False, False, True],
        )
        orn_protected_parts.append(extra_orn.head(remaining_orn))
        remaining_orn -= min(remaining_orn, len(extra_orn))
    if remaining_orn != 0:
        raise AssertionError(("unable to reserve requested ORNs", TARGET_ORNS, remaining_orn))
    protected_orns = pd.concat(orn_protected_parts, ignore_index=True).drop_duplicates("bodyId")

    seed = pd.concat([forced, type_rep, protected_orns], ignore_index=True).drop_duplicates("bodyId")

    if len(seed) > TARGET:
        forced_ids = set(forced.bodyId.astype(int).tolist())
        protected_orn_ids = set(protected_orns.bodyId.astype(int).tolist())
        keep_forced = forced.drop_duplicates("bodyId")
        keep_orn = protected_orns.drop_duplicates("bodyId")
        protected_core = pd.concat([keep_forced, keep_orn], ignore_index=True).drop_duplicates("bodyId")
        if len(protected_core) > TARGET:
            raise AssertionError(("forced+ORN protection exceeds target", len(protected_core), TARGET))
        optional_types = type_rep[
            ~type_rep.bodyId.isin(set(protected_core.bodyId.astype(int).tolist()))
        ].sort_values(
            ["route_score", "degree", "bodyId"], ascending=[False, False, True]
        )
        seed = pd.concat(
            [protected_core, optional_types.head(max(0, TARGET - len(protected_core)))],
            ignore_index=True,
        ).drop_duplicates("bodyId")

    remaining_slots = TARGET - len(seed)
    if remaining_slots < 0:
        raise AssertionError(("seed exceeds target", len(seed), TARGET))

    seed_ids = set(seed.bodyId.astype(int).tolist())
    pool = annotated[
        ~annotated.bodyId.isin(seed_ids)
        & ~annotated["is_olfactory_orn"]
    ].copy()

    # Route preservation is intended to protect intermediate circuit cells,
    # not to spend the route quota on sensory/DN/MN populations already handled
    # by the forced/type-diversity stages. Ascending neurons remain eligible as
    # measured intermediate/feedback cells.
    # Route-preservation pool includes sensory bridge cells as well as central
    # intermediates. This is important for canonical pathways such as
    # visual/looming -> LC4/LPLC2 -> DNp01 -> VNC. The motor-path validator
    # below still requires its two-hop bridge cell itself to be non-sensory.
    intermediate_pool = pool[
        ~pool["superclass"].astype(str).isin({"descending_neuron", "vnc_motor"})
    ].copy()

    # Reserve route cells by functional family. Quotas are capped by the
    # available slots, so the exact 16,669 target is always respected.
    route_quota = {
        "route_halt": 700,
        "route_escape": 900,
        "route_forward": 650,
        "route_turn": 600,
        "route_sensorimotor": 450,
        "route_olfactory_to_desc": 800,
        "route_desc_to_leg": 800,
        "route_olfactory_forward": 500,
        "route_olfactory_motor": 250,
    }
    route_parts = []
    route_ids = set()
    # Protect measured halt-circuit intermediates before generic route quotas.
    positive_halt = intermediate_pool[intermediate_pool["route_halt"] > 0].sort_values(
        ["route_halt", "route_score", "degree", "bodyId"],
        ascending=[False, False, False, True],
    )
    if len(positive_halt) and remaining_slots > 0:
        take_halt = min(route_quota["route_halt"], remaining_slots, len(positive_halt))
        halt_seed = positive_halt.head(take_halt)
        route_parts.append(halt_seed)
        route_ids.update(halt_seed.bodyId.astype(int).tolist())
        remaining_slots -= len(halt_seed)


    # Explicitly protect cells with a positive measured turn route before
    # allocating the other route-family quotas. No edge is created here.
    positive_turn = intermediate_pool[
        intermediate_pool["route_turn"] > 0
    ].sort_values(
        ["route_turn", "route_score", "degree", "bodyId"],
        ascending=[False, False, False, True],
    )

    if len(positive_turn) and remaining_slots > 0:
        take_turn = min(
            route_quota["route_turn"],
            remaining_slots,
            len(positive_turn),
        )
        turn_seed = positive_turn.head(take_turn)
        route_parts.append(turn_seed)
        route_ids.update(turn_seed.bodyId.astype(int).tolist())
        remaining_slots -= len(turn_seed)

    # Explicitly reserve central bridge cells for the two distinct causal halves
    # of food-to-locomotion routing. Both scores are computed from real published
    # edges: ORN -> intermediate -> any retained DN, and any retained DN ->
    # intermediate -> leg motor. The bridge candidate itself is non-sensory so that
    # the protected cells represent central/VNC premotor circuitry rather than an
    # accidental sensory relay. Zero-score cells are never counted against a route
    # quota. This avoids requiring a manually named "forward" DN for the existence
    # of a locomotor chain.
    food_bridge_pool = intermediate_pool[~intermediate_pool["is_sensory"]].copy()
    for score_col in ("route_olfactory_to_desc", "route_desc_to_leg",
                      "route_olfactory_forward", "route_olfactory_motor"):
        if remaining_slots <= 0:
            break
        quota = route_quota[score_col]
        candidates = food_bridge_pool[
            ~food_bridge_pool.bodyId.isin(route_ids)
            & (food_bridge_pool[score_col] > 0)
        ].sort_values(
            [score_col, "route_olfactory_forward", "route_olfactory_motor", "degree", "bodyId"],
            ascending=[False, False, False, False, True],
        )
        part = candidates.head(min(quota, remaining_slots))
        if len(part):
            route_parts.append(part)
            route_ids.update(part.bodyId.astype(int).tolist())
            remaining_slots -= len(part)

    for score_col, quota in route_quota.items():
        if score_col in ("route_turn", "route_halt", "route_olfactory_to_desc",
                         "route_desc_to_leg", "route_olfactory_forward", "route_olfactory_motor"):
            continue
        if remaining_slots <= 0:
            break
        take = min(quota, remaining_slots)
        candidates = intermediate_pool[~intermediate_pool.bodyId.isin(route_ids)].sort_values(
            [score_col, "route_score", "degree", "bodyId"],
            ascending=[False, False, False, True],
        )
        part = candidates.head(take)
        if len(part):
            route_parts.append(part)
            route_ids.update(part.bodyId.astype(int).tolist())
            remaining_slots -= len(part)

    if route_parts:
        route_seed = pd.concat(route_parts, ignore_index=True)
        seed = pd.concat([seed, route_seed], ignore_index=True).drop_duplicates("bodyId")

    remaining_slots = TARGET - len(seed)
    if remaining_slots < 0:
        raise AssertionError(("route seed exceeds target", len(seed), TARGET))

    seed_ids = set(seed.bodyId.astype(int).tolist())
    pool = annotated[
        ~annotated.bodyId.isin(seed_ids)
        & ~annotated["is_olfactory_orn"]
    ].copy()

    pool_counts = pool.groupby("superclass", sort=True).size().to_dict()
    raw_extra = {sc: n * remaining_slots / max(1, len(pool)) for sc, n in pool_counts.items()}
    extra_quota = {sc: int(math.floor(q)) for sc, q in raw_extra.items()}

    while sum(extra_quota.values()) < remaining_slots:
        candidates = [sc for sc in pool_counts if extra_quota[sc] < pool_counts[sc]]
        if not candidates:
            break
        sc = max(candidates, key=lambda x: (raw_extra[x] - extra_quota[x], x))
        extra_quota[sc] += 1

    while sum(extra_quota.values()) > remaining_slots:
        sc = max(extra_quota, key=lambda x: (extra_quota[x], x))
        extra_quota[sc] -= 1

    extras = []
    for sc, group in pool.groupby("superclass", sort=True):
        n = min(extra_quota.get(sc, 0), len(group))
        if n:
            extras.append(
                group.sort_values(
                    ["route_score", "degree", "bodyId"],
                    ascending=[False, False, True],
                ).head(n)
            )

    selected = pd.concat([seed] + extras, ignore_index=True).drop_duplicates("bodyId")

    if len(selected) < TARGET:
        selected_ids_now = set(selected.bodyId.astype(int).tolist())
        extra_pool = annotated[
            ~annotated.bodyId.isin(selected_ids_now)
            & ~annotated["is_olfactory_orn"]
        ]
        selected = pd.concat(
            [
                selected,
                extra_pool.sort_values(
                    ["route_score", "degree", "bodyId"],
                    ascending=[False, False, True],
                ).head(TARGET - len(selected)),
            ],
            ignore_index=True,
        )

    if len(selected) > TARGET:
        forced_ids = set(forced.bodyId.astype(int).tolist())
        protected_orn_ids = set(protected_orns.bodyId.astype(int).tolist())
        protected_ids = forced_ids | protected_orn_ids
        keep_protected = selected[selected.bodyId.isin(protected_ids)]
        if len(keep_protected) > TARGET:
            raise AssertionError(("protected cells exceed target", len(keep_protected), TARGET))
        optional = selected[~selected.bodyId.isin(protected_ids)].sort_values(
            ["route_score", "degree", "bodyId"], ascending=[False, False, True]
        )
        selected = pd.concat(
            [keep_protected, optional.head(max(0, TARGET - len(keep_protected)))],
            ignore_index=True,
        )

    selected = selected.drop_duplicates("bodyId").reset_index(drop=True)
    if len(selected) != TARGET:
        raise AssertionError((len(selected), TARGET))

    for route_name in ("route_forward", "route_turn", "route_escape",
                       "route_olfactory_forward", "route_olfactory_to_desc",
                       "route_desc_to_leg"):
        if not np.any(selected[route_name].to_numpy(np.float64) > 0):
            raise AssertionError(
                f"selected set contains no non-zero measured {route_name} cell"
            )

    selected_orns = selected[selected["is_olfactory_orn"]].copy()
    if len(selected_orns) != TARGET_ORNS:
        raise AssertionError(f"selected ORNs={len(selected_orns)} expected={TARGET_ORNS}")
    retained_untyped_orn_ids = set(
        selected_orns.loc[
            selected_orns["bodyId"].isin(UNTYPED_ORN_BODY_IDS), "bodyId"
        ].astype(int).tolist()
    )
    if retained_untyped_orn_ids != set(UNTYPED_ORN_BODY_IDS):
        raise AssertionError(
            "official untyped ORNs were not all retained: "
            f"expected={sorted(UNTYPED_ORN_BODY_IDS)} "
            f"found={sorted(retained_untyped_orn_ids)}"
        )
    # The four official untyped ORNs have no published `type` value.
    # Keep the source `type` field untouched and validate the published
    # type+entryNerve combinations instead: MaleCNS v1.0 has 54 such
    # combinations but only 53 unique type strings because ORN_VA7l occurs
    # under both AN and MxLbN.
    retained_orn_types = (
        selected_orns["type"]
        .dropna()
        .astype(str)
        .str.strip()
        .loc[lambda s: s.ne("")]
        .nunique()
    )
    retained_orn_type_entry_nerve_pairs = normalized_orn_type_entry_nerve_pairs(selected_orns)
    if len(retained_orn_type_entry_nerve_pairs) != EXPECTED_ORN_TYPES:
        raise AssertionError(
            "selected ORN type+entryNerve combinations="
            f"{len(retained_orn_type_entry_nerve_pairs)} expected={EXPECTED_ORN_TYPES}"
        )

    # Stable anatomical ordering: sensory channels first, then descending,
    # ascending, motor and the remaining central/intrinsic populations. This
    # gives the Android runtime contiguous anatomical readout blocks.
    selected["channel"] = selected.apply(classify_channel, axis=1)
    selected["motor_role"] = selected.apply(classify_motor_role, axis=1)
    selected["descending_role"] = selected.apply(classify_descending_role, axis=1)
    selected["halt_role"] = selected.apply(classify_halt_role, axis=1)
    def block(row):
        sc = str(row["superclass"])
        ch = int(row["channel"])
        if ch < 4:
            return ch
        if sc == "descending_neuron":
            return 4
        if sc == "ascending_neuron":
            return 5
        if sc == "vnc_motor":
            return 6
        return 7
    selected["block"] = selected.apply(block, axis=1)
    selected = selected.sort_values(["block", "superclass", "bodyId"]).reset_index(drop=True)
    selected_ids = selected["bodyId"].to_numpy(np.int64)
    index = {int(b): i for i, b in enumerate(selected_ids)}

    # Materialize all per-selected-neuron metadata arrays before the edge pass.
    # These arrays must be indexed in the same stable order as `selected` and
    # are consumed below when classifying retained sensor->DN and DN->motor
    # edges and when writing route metadata to the binary file.
    channel_selected = selected["channel"].to_numpy(np.int8)
    is_desc_selected = selected["superclass"].astype(str).eq("descending_neuron").to_numpy(bool)
    is_motor_selected = selected["superclass"].astype(str).eq("vnc_motor").to_numpy(bool)
    descending_role_selected = selected["descending_role"].to_numpy(np.int8)
    motor_role_selected = selected["motor_role"].to_numpy(np.int8)
    route_forward_selected = selected["route_forward"].to_numpy(np.float64)
    route_turn_selected = selected["route_turn"].to_numpy(np.float64)
    route_escape_selected = selected["route_escape"].to_numpy(np.float64)
    route_olfactory_forward_selected = selected["route_olfactory_forward"].to_numpy(np.float64)
    route_olfactory_motor_selected = selected["route_olfactory_motor"].to_numpy(np.float64)
    halt_role_selected = selected["halt_role"].to_numpy(np.int8)

    # Second streaming pass: retain only published edges between selected neurons.
    edges = []
    contacts = 0
    candidate_edges = 0
    retained_sensor_desc = np.zeros((4, 5), dtype=np.int64)
    retained_desc_motor = np.zeros((5, 8), dtype=np.int64)
    retained_sensor_desc_edges = 0
    retained_desc_motor_edges = 0

    # Retained two-hop motor routes. Direct DN->MN contacts are not required:
    # in the biological VNC, descending influence commonly reaches motor neurons
    # through premotor/intermediate neurons. Count only paths formed by published
    # edges between neurons that actually survived the 16,669-node reduction.
    retained_desc_to_intermediate_edges = 0
    retained_intermediate_to_motor_edges = 0
    retained_desc_to_intermediate_to_motor_paths = 0
    retained_two_hop_by_role = np.zeros((5, 8), dtype=np.int64)
    retained_desc_to_leg_direct_any_role_edges = 0
    retained_desc_to_leg_direct_any_role_contacts = 0
    desc_to_intermediate = {}
    intermediate_to_motor = {}
    for bi in range(reader.num_record_batches):
        b = reader.get_batch(bi)
        pre = b.column(b.schema.get_field_index("body_pre")).to_numpy(zero_copy_only=False).astype(np.int64)
        post = b.column(b.schema.get_field_index("body_post")).to_numpy(zero_copy_only=False).astype(np.int64)
        w = b.column(b.schema.get_field_index("weight")).to_numpy(zero_copy_only=False).astype(np.int64)
        mask = np.isin(pre, selected_ids) & np.isin(post, selected_ids) & (w > 0)
        if mask.any():
            for a, c, d in zip(pre[mask], post[mask], w[mask]):
                candidate_edges += 1
                src_i = index[int(a)]
                dst_i = index[int(c)]
                # FBC103 is purely structural. Preserve every published
                # retained edge with its raw positive MaleCNS contact count.
                # Neurotransmitter sign and dynamics normalization belong only
                # to the separate FBD105 layer.
                edges.append((src_i, dst_i, float(d)))
                contacts += int(d)
                src_ch = int(channel_selected[src_i])
                dst_desc = int(descending_role_selected[dst_i])
                src_desc = int(descending_role_selected[src_i])
                dst_mr = int(motor_role_selected[dst_i])
                if src_ch < 4 and dst_desc > 0:
                    retained_sensor_desc[src_ch, dst_desc] += int(d)
                    retained_sensor_desc_edges += 1
                if src_desc > 0 and dst_mr > 0:
                    retained_desc_motor[src_desc, dst_mr] += int(d)
                    retained_desc_motor_edges += 1
                # Direct DN -> LEG is retained for every DN, regardless of its
                # descriptive role label. This is the ground-truth direct VNC
                # motor path available in the selected induced subgraph.
                if is_desc_selected[src_i] and dst_mr == 1:
                    retained_desc_to_leg_direct_any_role_edges += 1
                    retained_desc_to_leg_direct_any_role_contacts += int(d)

                # Intermediate/premotor cells are non-sensory, non-DN, non-MN
                # retained neurons. This avoids counting a sensory feedback cell
                # as the sole "premotor" bridge. Crucially, source DN status uses
                # the authoritative retained superclass, not the optional role label.
                src_is_intermediate = (
                    not is_desc_selected[src_i]
                    and not is_motor_selected[src_i]
                    and int(channel_selected[src_i]) >= 4
                )
                dst_is_intermediate = (
                    not is_desc_selected[dst_i]
                    and not is_motor_selected[dst_i]
                    and int(channel_selected[dst_i]) >= 4
                )
                if is_desc_selected[src_i] and dst_is_intermediate:
                    desc_to_intermediate.setdefault(src_i, set()).add(dst_i)
                    retained_desc_to_intermediate_edges += 1
                if src_is_intermediate and dst_mr > 0:
                    intermediate_to_motor.setdefault(src_i, set()).add(dst_i)
                    retained_intermediate_to_motor_edges += 1
        if bi % 50 == 0:
            print(f"edge pass batch {bi}/{reader.num_record_batches}", flush=True)

    if retained_sensor_desc[0, 4] <= 0:
        raise RuntimeError(
            "No retained visual -> escape-DN contacts survived the reduction; "
            "refusing to publish V1.04."
        )

    # Count actual retained DN -> intermediate -> motor paths. Each path is a
    # real two-edge path in the published graph; no synthetic bridge is added.
    for dn_i, mids in desc_to_intermediate.items():
        dn_role = int(descending_role_selected[dn_i])
        for mid_i in mids:
            motors = intermediate_to_motor.get(mid_i)
            if not motors:
                continue
            retained_desc_to_intermediate_to_motor_paths += len(motors)
            if dn_role <= 0:
                continue
            for motor_i in motors:
                mr = int(motor_role_selected[motor_i])
                if mr > 0:
                    retained_two_hop_by_role[dn_role, mr] += 1

    if retained_desc_to_leg_direct_any_role_edges <= 0 and retained_desc_to_intermediate_to_motor_paths <= 0:
        raise RuntimeError(
            "FBR-10-OLF2 retained graph contains no measured DN->LEG direct or "
            "DN->intermediate->LEG-MN path; refusing to publish a locomotor-silent graph."
        )

    # FBC103 stores the exact raw positive structural contact count.
    # Do not apply neurotransmitter signs, normalization, or dynamical gains here.
    if len(edges) != candidate_edges:
        raise AssertionError(
            f"retained structural edge count changed during edge pass: "
            f"{len(edges)} != {candidate_edges}"
        )
    edges.sort(key=lambda e: (e[1], e[0]))

    ranges = {}
    for code, name in [(0,"visual"),(1,"olfactory"),(2,"gustatory"),(3,"mechanosensory"),(4,"other")]:
        idxs = np.where(channel_selected == code)[0]
        ranges[name] = [int(idxs.min()), int(idxs.max()+1)] if len(idxs) else [0,0]

    sc_list = sorted(selected["superclass"].astype(str).unique().tolist())
    SUPERCLASS_CODE.clear()
    SUPERCLASS_CODE.update({s:i for i,s in enumerate(sc_list)})

    # Compact binary FBC103:
    # 8-byte magic + node/edge counts + 26-byte node records + 12-byte edges.
    # Node metadata contains the measured anatomical roles used by the Android
    # runtime. The halt role is descriptive metadata derived from the source
    # connectome annotations; it does not create synthetic edges.
    out = root / "app" / "src" / "main" / "res" / "raw" / "malecns_reduced.bin"
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("wb") as f:
        f.write(FORMAT_MAGIC)
        f.write(struct.pack("<II", TARGET, len(edges)))
        for i, row in selected.iterrows():
            body = int(row.bodyId)
            sc = SUPERCLASS_CODE[str(row.superclass)]
            side_text = clean(row.get("somaSide", ""))
            side = 1 if side_text == "R" else (-1 if side_text == "L" else 0)
            ch = int(channel_selected[i])
            f.write(struct.pack(
                "<qbbbbbbfff",
                body,
                sc,
                side,
                ch,
                int(row.motor_role),
                int(row.descending_role),
                int(row.halt_role),
                float(route_forward_selected[i]),
                float(route_turn_selected[i]),
                float(route_escape_selected[i]),
            ))
        for src, dst, weight in edges:
            f.write(struct.pack("<iif", int(src), int(dst), float(weight)))

    fbc_sha = hashlib.sha256(out.read_bytes()).hexdigest()

    def block_range(block_id):
        idxs = np.where(selected["block"].to_numpy(np.int8) == block_id)[0]
        return (int(idxs.min()), int(idxs.max()+1)) if len(idxs) else (0,0)

    # Runtime population ranges follow the stable anatomical block order.
    # channel=4 means "non-sensory" and spans DESC/ASC/MOTOR/OTHER; it is
    # therefore not the runtime OTHER population.
    desc = block_range(4)
    asc = block_range(5)
    vmotor = block_range(6)
    other = block_range(7)
    population_ranges = {
        "visual": block_range(0), "olfactory": block_range(1),
        "gustatory": block_range(2), "mechanosensory": block_range(3),
        "descending": desc, "ascending": asc, "motor": vmotor, "other": other,
    }

    meta = root / "app" / "src" / "main" / "java" / "com" / "example" / "flybrain" / "GeneratedConnectomeMeta.kt"
    motor_role_counts = {int(k): int(v) for k,v in selected.groupby("motor_role").size().to_dict().items()}

    meta.write_text('package com.example.flybrain\n\nobject GeneratedConnectomeMeta {\n    const val VERSION = "MaleCNS v1.0 · FBR-10-OLF2-MOTORROUTE · FBD105 · VNCSEM102"\n    const val FLYBRAIN_VERSION = "1.18.3"\n    const val FLYBRAIN_VERSION_CODE = 136\n    const val APP_VERSION = "1.18.3"\n    const val APP_VERSION_CODE = 136\n    const val REDUCTION_ID = "FBR-10-OLF2-MOTORROUTE"\n    const val RETAINED_OLFACTORY_ORNS = %d\n    // 54 distinct published (type, entryNerve) combinations; 53 unique\n    // non-null type strings because ORN_VA7l occurs under AN and MxLbN.\n    const val RETAINED_OLFACTORY_ORN_TYPES = %d\n    const val RETAINED_OLFACTORY_ORN_TYPE_ENTRY_NERVE_PAIRS = %d\n    const val BINARY_SHA256 = "%s"\n    const val FORMAT_MAGIC = "FBC103"\n    const val FORMAT_VERSION = 103\n    const val NEURONS = %d\n    const val EDGES = %d\n    const val CONTACTS_RETAINED = %dL\n    const val VIS_START = %d\n    const val VIS_END = %d\n    const val OLF_START = %d\n    const val OLF_END = %d\n    const val GUST_START = %d\n    const val GUST_END = %d\n    const val MECH_START = %d\n    const val MECH_END = %d\n    const val DESC_START = %d\n    const val DESC_END = %d\n    const val ASC_START = %d\n    const val ASC_END = %d\n    const val VMOTOR_START = %d\n    const val VMOTOR_END = %d\n    const val OTHER_START = %d\n    const val OTHER_END = %d\n    const val MOTOR_LEG = 1\n    const val MOTOR_WING = 2\n    const val MOTOR_HALTERE = 3\n    const val MOTOR_NECK = 4\n    const val MOTOR_ABDOMEN = 5\n    const val MOTOR_OTHER = 6\n    const val MOTOR_FUNCTION_NONE = 0\n    const val MOTOR_FUNCTION_JUMP = 1\n}\n' % (TARGET_ORNS, retained_orn_types, len(retained_orn_type_entry_nerve_pairs), fbc_sha, TARGET, len(edges), contacts,
       population_ranges["visual"][0], population_ranges["visual"][1], population_ranges["olfactory"][0], population_ranges["olfactory"][1],
       ranges["gustatory"][0], ranges["gustatory"][1], ranges["mechanosensory"][0], ranges["mechanosensory"][1],
       desc[0], desc[1], asc[0], asc[1], vmotor[0], vmotor[1], other[0], other[1]))

    selected_route_counts = {
        "forward_nonzero": int((route_forward_selected > 0).sum()),
        "turn_nonzero": int((route_turn_selected > 0).sum()),
        "escape_nonzero": int((route_escape_selected > 0).sum()),
        "halt_nonzero": int((halt_role_selected > 0).sum()),
        "escape_high": int((route_escape_selected >= 0.25).sum()),
    }

    source_route_counts = {
        "forward_nonzero": int((route_forward > 0).sum()),
        "turn_nonzero": int((route_turn > 0).sum()),
        "escape_nonzero": int((route_escape > 0).sum()),
        "halt_nonzero": int((route_halt > 0).sum()),
    }

    report = {
        "dataset": "MaleCNS v1.0",
        "flybrain_version": FLYBRAIN_RELEASE,
        "reduction": REDUCTION_ID,
        "binary_format": "FBC103",
        "sha256": fbc_sha,
        "node_record_bytes": NODE_SIZE,
        "edge_record_bytes": EDGE_SIZE,
        "selection": "exactly 16,669 annotated neurons with a MaleCNS superclass; all descending and VNC motor neurons are retained; exactly 264 real MaleCNS v1.0 ORNs are protected (10% of the 2,639 ORN census, rounded), including explicit retention of the four official ORNs with NULL type (bodyIds 242812, 242908, 488209, 956041) without assigning synthetic labels; all 54 published ORN type+entryNerve combinations are represented (53 unique type labels; ORN_VA7l is present under AN and MxLbN), and measured ORN-driven forward/motor route cells receive explicit preservation quotas; remaining quota is stratified by superclass and ranked by measured route support and degree",
        "source": BASE,
        "neurons_source": int(total),
        "neurons_retained": TARGET,
        "edges_retained": len(edges),
        "olfactory_orns_source": int(len(orn_source)),
        "olfactory_orns_retained": int(len(selected_orns)),
        "untyped_orn_body_ids_source": sorted(int(x) for x in UNTYPED_ORN_BODY_IDS),
        "untyped_orn_body_ids_retained": sorted(int(x) for x in retained_untyped_orn_ids),
        # `*_types_*` preserve the literal unique `type`-label count (53);
        # the 54-value census is the distinct published (type, entryNerve)
        # combination count and is exposed separately to avoid conflating the two.
        "olfactory_orn_types_source": int(len(orn_type_counts_source)),
        "olfactory_orn_types_retained": int(retained_orn_types),
        "olfactory_orn_type_entry_nerve_pairs_source": int(len(orn_type_entry_nerve_pairs_source)),
        "olfactory_orn_type_entry_nerve_pairs_retained": int(len(retained_orn_type_entry_nerve_pairs)),
        "olfactory_orn_target": TARGET_ORNS,
        "olfactory_route_forward_source_nonzero": int((route_olfactory_forward > 0).sum()),
        "olfactory_route_forward_selected_nonzero": int((route_olfactory_forward_selected > 0).sum()),
        "olfactory_route_motor_source_nonzero": int((route_olfactory_motor > 0).sum()),
        "olfactory_route_motor_selected_nonzero": int((route_olfactory_motor_selected > 0).sum()),
        "olfactory_to_desc_source_nonzero": int((olfactory_to_desc_path > 0).sum()),
        "olfactory_to_desc_three_edge_source_nonzero": int((olfactory_three_edge > 0).sum()),
        "olfactory_to_desc_selected_nonzero": int((selected["route_olfactory_to_desc"] > 0).sum()),
        "descending_to_leg_source_nonzero": int((descending_to_leg_path > 0).sum()),
        "descending_to_leg_three_edge_source_nonzero": int((desc_leg_three_edge > 0).sum()),
        "descending_to_leg_selected_nonzero": int((selected["route_desc_to_leg"] > 0).sum()),
        "candidate_edges_between_retained_neurons": candidate_edges,
        "contacts_retained": contacts,
        "edge_weight_definition": "raw positive MaleCNS contact counts; neurotransmitter sign is applied only in FBD105",
        "superclass_counts_source": {str(k): int(v) for k,v in counts.items()},
        "superclass_extra_quota": {str(k): int(v) for k,v in extra_quota.items()},
        "channel_ranges": ranges,
        "population_ranges": {k: [int(v[0]), int(v[1])] for k, v in population_ranges.items()},
        "motor_role_counts": motor_role_counts,
        "descending_role_counts": {int(k): int(v) for k,v in selected.groupby("descending_role").size().to_dict().items()},
        "halt_role_counts": {int(k): int(v) for k,v in selected.groupby("halt_role").size().to_dict().items()},
        "descending_role_source_counts": {
            str(i): int(role_counts_source[i]) for i in range(5)
        },
        "descending_type_role_counts": {
            f"{k[0]}|role{k[1]}": int(v)
            for k, v in selected[selected["superclass"].astype(str).eq("descending_neuron")]
            .groupby(["type", "descending_role"]).size().to_dict().items()
        },
        "motor_role_definition": "authoritative MaleCNS class/subclass vocabulary shared with runtime VNC semantics; runtime movement is driven only by measured vnc_motor activity",
        "halt_role_definition": "FG and BB are walk-OFF halt populations; BRK is the VNC brake population; roles are annotation metadata only and do not create edges",
        "descending_role_definition": "published behavioural cell-type names plus conservative annotation keywords; descriptive metadata only and never a synthetic current source",
        "route_score_definition": "route-family topology scores combine measured published edges; explicit food-pathway preservation uses the complete retained DN population and protects measured ORN->candidate->DN, ORN->A->B->DN, DN->candidate->LEG and DN->A->B->LEG bridges; scores are selection metadata only and never create edges",
        "route_score_source_counts": source_route_counts,
        "route_score_selected_counts": selected_route_counts,
        "halt_selected_nonzero": int((halt_role_selected > 0).sum()),
        "route_quota_requested": route_quota,
        "olfactory_orn_type_counts_source": {str(k): int(v) for k,v in orn_type_counts_source.items()},
        "olfactory_orn_type_counts_retained": {str(k): int(v) for k,v in selected_orns.groupby("type").size().to_dict().items()},
        "olfactory_orn_type_entry_nerve_pairs_source": [
            {"type": str(row["type"]), "entryNerve": str(row["entryNerve"])}
            for _, row in orn_type_entry_nerve_pairs_source.iterrows()
        ],
        "olfactory_orn_type_entry_nerve_pairs_retained": [
            {"type": str(row["type"]), "entryNerve": str(row["entryNerve"])}
            for _, row in retained_orn_type_entry_nerve_pairs.iterrows()
        ],
        "retained_sensor_to_desc_edges": int(retained_sensor_desc_edges),
        "retained_desc_to_motor_edges": int(retained_desc_motor_edges),
        "retained_sensor_to_desc_contacts": int(retained_sensor_desc.sum()),
        "retained_desc_to_motor_contacts": int(retained_desc_motor.sum()),
        "retained_desc_to_intermediate_edges": int(retained_desc_to_intermediate_edges),
        "retained_intermediate_to_motor_edges": int(retained_intermediate_to_motor_edges),
        "retained_desc_to_intermediate_to_motor_paths": int(retained_desc_to_intermediate_to_motor_paths),
        "retained_desc_to_leg_direct_any_role_edges": int(retained_desc_to_leg_direct_any_role_edges),
        "retained_desc_to_leg_direct_any_role_contacts": int(retained_desc_to_leg_direct_any_role_contacts),
        "retained_desc_to_intermediate_to_motor_by_role_paths": retained_two_hop_by_role.tolist(),
        "retained_sensor_to_desc_by_channel_role_contacts": retained_sensor_desc.tolist(),
        "retained_desc_to_motor_by_role_contacts": retained_desc_motor.tolist(),
        "direct_sensor_desc_full_graph_weight": direct_sensor_desc.tolist(),
        "direct_desc_motor_full_graph_weight": direct_desc_motor.tolist(),
        "license": "CC-BY-4.0",
        "download": "https://male-cns.janelia.org/download/",
        "paper": "Berg et al., Cell 2026, DOI 10.1016/j.cell.2026.08.015",
    }
    (root / "app" / "src" / "main" / "res" / "raw" / "malecns_reduced_report.json").write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2), flush=True)

if __name__ == "__main__":
    main(Path(__file__).resolve().parents[1])
