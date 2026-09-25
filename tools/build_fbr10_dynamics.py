#!/usr/bin/env python3
"""Build FBD105: signed MaleCNS dynamics using raw retained contact counts.

FBC103 remains a structural artifact. FBD105 is a separate runtime dynamics layer
that maps each retained source->target connection to:
    signed_contact_count * W_SYN_REDUCED_MV
where the sign comes from the official MaleCNS neurotransmitter table.

This replaces the previous FBD104 per-target normalization, which destroyed the
absolute magnitude information carried by MaleCNS connection weights. The LIF
parameters follow Shiu et al. (Nature 2024). This release deliberately uses the
published 0.275 mV/synapse value directly; no density compensation is silently
introduced into the scientific dynamics. The fact that FBR-10 is reduced is handled
by the topology itself, not by changing individual synaptic strength.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import struct
from collections import Counter, defaultdict
from pathlib import Path

import pyarrow.feather as feather

FBC_MAGIC = b"FBC103\x00\x00"
FBD_MAGIC = b"FBD105\x00\x00"
# Shiu et al. 2024: one free parameter, 0.275 mV per synapse.
W_SYN_FULL_MV = 0.275
W_SYN_REDUCED_MV = W_SYN_FULL_MV
# Whole-neuron sign convention used by the reference Drosophila LIF model:
# cholinergic and monoaminergic outputs are excitatory; GABA/glutamate/histamine
# are inhibitory. Monoamines are a simplification of neuromodulation, but using the
# same convention is more reproducible than silently discarding their edges.
NT_SIGN = {
    "acetylcholine": 1.0,
    "ach": 1.0,
    "dopamine": 1.0,
    "dopaminergic": 1.0,
    "octopamine": 1.0,
    "octopaminergic": 1.0,
    "serotonin": 1.0,
    "serotonergic": 1.0,
    "5-ht": 1.0,
    "histamine": -1.0,
    "histaminergic": -1.0,
    "gaba": -1.0,
    "gamma-aminobutyric acid": -1.0,
    "glutamate": -1.0,
    "glutamatergic": -1.0,
}
NODE_BYTES = 26
EDGE_BYTES = 12
EXPECTED_NT_SHA256 = "95c9289220663abeb3409f3ad9e5a7f8a53f8093f5139d15502cd08da8879621"


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(8 * 1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def load_structural(path: Path):
    raw = path.read_bytes()
    b = memoryview(raw)
    if len(b) < 16 or bytes(b[:8]) != FBC_MAGIC:
        raise RuntimeError("FBR-10-OLF2-MOTORROUTE structural artifact is not FBC103")
    n, e = struct.unpack_from("<II", b, 8)
    expected = 16 + n * NODE_BYTES + e * EDGE_BYTES
    if len(raw) != expected:
        raise RuntimeError(f"FBC103 size mismatch: {len(raw)} != {expected}")
    body_ids = []
    pos = 16
    for _ in range(n):
        body = struct.unpack_from("<q", b, pos)[0]
        body_ids.append(body)
        pos += NODE_BYTES
    edges = []
    for edge_index in range(e):
        src, dst, weight = struct.unpack_from("<iif", b, pos)
        if not (
            0 <= src < n
            and 0 <= dst < n
            and math.isfinite(weight)
            and weight > 0
            and float(weight).is_integer()
        ):
            raise RuntimeError(
                "invalid FBC103 structural edge "
                f"index={edge_index} src={src} dst={dst} weight={weight!r}; "
                "expected in-range endpoints and a finite positive raw contact count"
            )
        edges.append((src, dst, float(weight)))
        pos += EDGE_BYTES
    return n, e, body_ids, edges


def _mode_unique(values: list[str]) -> str | None:
    counts = Counter(v for v in values if v)
    if not counts:
        return None
    best = counts.most_common()
    if len(best) > 1 and best[0][1] == best[1][1]:
        return None
    return best[0][0]


def _is_known_nt(name: str | None) -> bool:
    return bool(name) and name.strip().lower() in NT_SIGN


def resolve_nt_label(consensus: str | None, predicted: str | None) -> tuple[str | None, str]:
    """Resolve MaleCNS transmitter label without inventing a class.

    Preference order:
      1) consensus_nt when it is recognized by the runtime sign map;
      2) predicted_nt when consensus is unclear/unrecognized/missing;
      3) unresolved (None) otherwise.

    This preserves the official consensus label whenever usable while recovering
    real signed edges where the published consensus is explicitly `unclear`.
    """
    c = (consensus or "").strip().lower()
    p = (predicted or "").strip().lower()
    if _is_known_nt(c):
        return c, "consensus"
    if _is_known_nt(p):
        return p, "predicted_fallback"
    return None, "unresolved"


def load_nt(path: Path):
    table = feather.read_table(path, columns=["body", "consensus_nt", "predicted_nt"])
    bodies = table.column("body").to_pylist()
    consensus = table.column("consensus_nt").to_pylist()
    predicted = table.column("predicted_nt").to_pylist()

    grouped_c: dict[int, list[str]] = defaultdict(list)
    grouped_p: dict[int, list[str]] = defaultdict(list)
    nonempty_consensus = 0
    nonempty_predicted = 0
    for body, c, p in zip(bodies, consensus, predicted):
        if body is None:
            continue
        body_i = int(body)
        c_name = "" if c is None else str(c).strip().lower()
        p_name = "" if p is None else str(p).strip().lower()
        if c_name:
            grouped_c[body_i].append(c_name)
            nonempty_consensus += 1
        if p_name:
            grouped_p[body_i].append(p_name)
            nonempty_predicted += 1

    mapping: dict[int, str] = {}
    consensus_used = 0
    predicted_fallback_used = 0
    unresolved = 0
    conflicts = 0
    all_bodies = set(grouped_c) | set(grouped_p)
    for body in all_bodies:
        c = _mode_unique(grouped_c.get(body, []))
        p = _mode_unique(grouped_p.get(body, []))
        if c is None and len(grouped_c.get(body, [])) > 1:
            conflicts += 1
        label, source = resolve_nt_label(c, p)
        if label is None:
            unresolved += 1
            continue
        mapping[body] = label
        if source == "consensus":
            consensus_used += 1
        else:
            predicted_fallback_used += 1
    return (mapping, conflicts, nonempty_consensus, nonempty_predicted,
            consensus_used, predicted_fallback_used, unresolved)


def main(root: Path, nt_path: Path, output: Path, allow_unpinned: bool, expected_structural_sha: str | None):
    structural = root / "app" / "src" / "main" / "res" / "raw" / "malecns_reduced.bin"
    structural_hash = sha256_file(structural)
    nt_hash = sha256_file(nt_path)
    if not allow_unpinned and expected_structural_sha is not None and structural_hash != expected_structural_sha:
        raise RuntimeError(f"FBC103 SHA256 mismatch: {structural_hash} != {expected_structural_sha}")
    if not allow_unpinned and nt_hash != EXPECTED_NT_SHA256:
        raise RuntimeError(f"neurotransmitter SHA256 mismatch: {nt_hash} != {EXPECTED_NT_SHA256}")
    n, structural_edges, body_ids, edges = load_structural(structural)
    (nt, nt_conflicts, nt_nonempty_rows, nt_predicted_nonempty_rows,
     nt_consensus_used, nt_predicted_fallback_used, nt_unresolved_bodies) = load_nt(nt_path)

    signs = bytearray(n)
    recognized_nodes = 0
    for i, body in enumerate(body_ids):
        sign = NT_SIGN.get(nt.get(body, ""), 0.0)
        # Physical files store bytes as unsigned 0..255. FBD105 defines
        # the signed-node convention as 0 = unknown, 1 = excitatory,
        # 0xFF = inhibitory (-1 when decoded as a Kotlin Byte).
        sign_byte = 1 if sign > 0 else (0xFF if sign < 0 else 0)
        signs[i] = sign_byte
        if sign_byte != 0:
            recognized_nodes += 1
    if not all(x in (0, 1, 0xFF) for x in signs):
        raise AssertionError("FBD105 sign encoding must be 0, 1, or 0xFF")

    resolved = []
    unresolved = 0
    e_contact = 0
    i_contact = 0
    target_totals = [0.0] * n
    for src, dst, raw_weight in edges:
        sign_byte = signs[src]
        # Decode the on-disk unsigned byte representation back to the
        # mathematical sign before applying it to edge weights.
        # FBD105: 0 = unknown/modulatory, 1 = excitatory, 0xFF = inhibitory.
        sign = -1 if sign_byte == 0xFF else (1 if sign_byte == 1 else 0)
        if sign == 0:
            unresolved += 1
            continue
        target_totals[dst] += abs(raw_weight)
        if sign > 0:
            e_contact += int(round(raw_weight))
        else:
            i_contact += int(round(raw_weight))
        resolved.append((src, dst, raw_weight * float(sign)))

    weighted = []
    for src, dst, signed_raw in resolved:
        weighted.append((src, dst, signed_raw * W_SYN_REDUCED_MV))
    weighted.sort(key=lambda x: (x[1], x[0]))

    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("wb") as f:
        f.write(FBD_MAGIC)
        f.write(struct.pack("<II", n, len(weighted)))
        f.write(signs)
        for src, dst, weight in weighted:
            f.write(struct.pack("<iif", src, dst, float(weight)))

    report = {
        "format": "FBD105",
        "structural_format": "FBC103",
        "structural_release_id": "FBR-10-OLF2-MOTORROUTE",
        "neurons": n,
        "structural_edges": structural_edges,
        "signed_edges": len(weighted),
        "unresolved_or_modulatory_edges": unresolved,
        "recognized_neurons": recognized_nodes,
        "nt_body_ids_with_resolved_sign": len(nt),
        "nt_nonempty_consensus_rows": nt_nonempty_rows,
        "nt_nonempty_predicted_rows": nt_predicted_nonempty_rows,
        "nt_conflicting_body_ids": nt_conflicts,
        "nt_consensus_signs_used": nt_consensus_used,
        "nt_predicted_fallback_signs_used": nt_predicted_fallback_used,
        "nt_unresolved_bodies": nt_unresolved_bodies,
        "nt_resolution_policy": "consensus_nt when recognized; predicted_nt fallback only when consensus is unclear/unrecognized/missing; otherwise unresolved",
        "weight_definition": "signed raw retained MaleCNS contact count * published W_SYN",
        "w_syn_mv": W_SYN_FULL_MV,
        "w_syn_full_mv": W_SYN_FULL_MV,
        "w_syn_reduced_mv": W_SYN_REDUCED_MV,
        "density_compensation": 1.0,
        "dynamics_convention": "reference Shiu et al. 2024 sign/weight convention; no reduced-network weight compensation",
        "edge_signs": NT_SIGN,
        "excitatory_contacts": e_contact,
        "inhibitory_contacts": i_contact,
        "source_structural_sha256": structural_hash,
        "source_nt_sha256": nt_hash,
        "official_nt_source_pinned": not allow_unpinned,
        "structural_source_pinned": expected_structural_sha is not None and not allow_unpinned,
        "output_sha256": sha256_file(output),
    }
    report_path = root / "build" / "FBR10_DYNAMICS_REPORT.json"
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    ap.add_argument("--nt", type=Path, required=True)
    ap.add_argument("--allow-unpinned", action="store_true", help="allow non-pinned source hashes for local development only")
    ap.add_argument("--expected-structural-sha", default=None, help="SHA-256 emitted by the current connectome build; required for pinned CI")
    ap.add_argument(
        "--output",
        type=Path,
        default=Path("app/src/main/res/raw/malecns_fbr10_dynamics.bin"),
    )
    args = ap.parse_args()
    if not args.output.is_absolute():
        args.output = args.root / args.output
    main(args.root, args.nt, args.output, args.allow_unpinned, args.expected_structural_sha)
