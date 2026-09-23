#!/usr/bin/env python3
"""Build the signed/normalized dynamics layer for the current FBR-10 release graph.

The structural FBC103 artifact is not modified by this tool. It derives a separate
runtime dynamics artifact from:
  - app/src/main/res/raw/malecns_reduced.bin (current FBR-10 topology/metadata)
  - official MaleCNS v1.0 body-neurotransmitters Feather table

Neurotransmitter convention is intentionally the same convention historically
used by FlyBrain's LIF builder:
  acetylcholine -> +1
  GABA          -> -1
  glutamate     -> -1
  other/unknown/modulatory -> 0 (edge omitted from fast current)

The edge magnitude is normalized per postsynaptic target over recognized
fast-synaptic contacts and scaled by 0.42, preserving the project's previous
signed-LIF scaling convention without altering the frozen connectome.
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
FBD_MAGIC = b"FBD104\x00\x00"
NORM_SCALE = 0.42
NT_SIGN = {
    "acetylcholine": 1.0,
    "ach": 1.0,
    "gaba": -1.0,
    "gamma-aminobutyric acid": -1.0,
    "glutamate": -1.0,
    "glutamatergic": -1.0,
}
NODE_BYTES = 26
EDGE_BYTES = 12
BASELINE_STRUCTURAL_SHA256 = "bfadc30fd113c25f9711cce6ef8f6b80e9c139fe6d229965a4adabb94d8b4e60"
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
        raise RuntimeError("FBR-10 structural artifact is not FBC103")
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


def load_nt(path: Path):
    table = feather.read_table(path, columns=["body", "consensus_nt"])
    bodies = table.column("body").to_pylist()
    nts = table.column("consensus_nt").to_pylist()
    grouped: dict[int, Counter[str]] = defaultdict(Counter)
    nonempty_rows = 0
    for body, nt in zip(bodies, nts):
        if body is None or nt is None:
            continue
        name = str(nt).strip().lower()
        if name:
            grouped[int(body)][name] += 1
            nonempty_rows += 1

    mapping: dict[int, str] = {}
    conflicts = 0
    for body, counts in grouped.items():
        # consensus_nt is repeated across prediction rows. A unique mode is
        # accepted; ties remain explicitly unresolved rather than guessed.
        best = counts.most_common()
        if len(best) > 1 and best[0][1] == best[1][1]:
            conflicts += 1
            continue
        mapping[body] = best[0][0]
    return mapping, conflicts, nonempty_rows


def main(root: Path, nt_path: Path, output: Path, allow_unpinned: bool, expected_structural_sha: str | None):
    structural = root / "app" / "src" / "main" / "res" / "raw" / "malecns_reduced.bin"
    structural_hash = sha256_file(structural)
    nt_hash = sha256_file(nt_path)
    if not allow_unpinned and expected_structural_sha is not None and structural_hash != expected_structural_sha:
        raise RuntimeError(f"FBC103 SHA256 mismatch: {structural_hash} != {expected_structural_sha}")
    if not allow_unpinned and nt_hash != EXPECTED_NT_SHA256:
        raise RuntimeError(f"neurotransmitter SHA256 mismatch: {nt_hash} != {EXPECTED_NT_SHA256}")
    n, structural_edges, body_ids, edges = load_structural(structural)
    nt, nt_conflicts, nt_nonempty_rows = load_nt(nt_path)

    signs = bytearray(n)
    recognized_nodes = 0
    for i, body in enumerate(body_ids):
        sign = NT_SIGN.get(nt.get(body, ""), 0.0)
        # Physical files store bytes as unsigned 0..255. FBD104 defines
        # the signed-node convention as 0 = unknown, 1 = excitatory,
        # 0xFF = inhibitory (-1 when decoded as a Kotlin Byte).
        sign_byte = 1 if sign > 0 else (0xFF if sign < 0 else 0)
        signs[i] = sign_byte
        if sign_byte != 0:
            recognized_nodes += 1
    if not all(x in (0, 1, 0xFF) for x in signs):
        raise AssertionError("FBD104 sign encoding must be 0, 1, or 0xFF")

    resolved = []
    unresolved = 0
    e_contact = 0
    i_contact = 0
    target_totals = [0.0] * n
    for src, dst, raw_weight in edges:
        sign_byte = signs[src]
        # Decode the on-disk unsigned byte representation back to the
        # mathematical sign before applying it to edge weights.
        # FBD104: 0 = unknown/modulatory, 1 = excitatory, 0xFF = inhibitory.
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

    normalized = []
    for src, dst, signed_raw in resolved:
        denom = max(1.0, target_totals[dst])
        normalized.append((src, dst, signed_raw / denom * NORM_SCALE))
    normalized.sort(key=lambda x: (x[1], x[0]))

    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("wb") as f:
        f.write(FBD_MAGIC)
        f.write(struct.pack("<II", n, len(normalized)))
        f.write(signs)
        for src, dst, weight in normalized:
            f.write(struct.pack("<iif", src, dst, float(weight)))

    report = {
        "format": "FBD104",
        "structural_format": "FBC103",
        "neurons": n,
        "structural_edges": structural_edges,
        "signed_edges": len(normalized),
        "unresolved_or_modulatory_edges": unresolved,
        "recognized_neurons": recognized_nodes,
        "nt_body_ids_with_consensus": len(nt),
        "nt_nonempty_rows": nt_nonempty_rows,
        "nt_conflicting_body_ids": nt_conflicts,
        "normalization": "per-postsynaptic target sum(abs(raw recognized fast contacts))",
        "normalization_scale": NORM_SCALE,
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
    ap.add_argument("--expected-structural-sha", default=None, help="pin a specific FBC103 SHA when required by a release")
    ap.add_argument(
        "--output",
        type=Path,
        default=Path("app/src/main/res/raw/malecns_fbr10_dynamics.bin"),
    )
    args = ap.parse_args()
    if not args.output.is_absolute():
        args.output = args.root / args.output
    main(args.root, args.nt, args.output, args.allow_unpinned, args.expected_structural_sha)
