#!/usr/bin/env python3
"""Download and cryptographically verify the exact MaleCNS v1.0 source bundle.

This script is intentionally independent from the reducer. It pins the three
official Feather objects by URL, byte size, SHA-256, schema and basic row
counts. It never silently accepts a different object at the same URL.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import sys
import tempfile
import urllib.error
import urllib.request
from pathlib import Path

import pyarrow.feather as feather

BASE = "https://storage.googleapis.com/flyem-male-cns/v1.0/connectome-data/flat-connectome/"
FILES = {
    "annotations": {
        "name": "body-annotations-male-cns-v1.0-minconf-0.5.feather",
        "size": 14_483_314,
        "sha256": "2177e246113e4cfbf1e7772ec37c6da1955ff22e8063d0b1f833101f99a9a3b2",
        "rows": 211_577,
        "required_columns": {"bodyId", "superclass", "type", "somaSide", "status"},
    },
    "neurotransmitters": {
        "name": "body-neurotransmitters-male-cns-v1.0.feather",
        "size": 43_282_834,
        "sha256": "95c9289220663abeb3409f3ad9e5a7f8a53f8093f5139d15502cd08da8879621",
        "rows": 1_835_518,
        "required_columns": {"body", "consensus_nt", "predicted_nt", "ground_truth"},
    },
    "weights": {
        "name": "connectome-weights-male-cns-v1.0-minconf-0.5.feather",
        "size": 1_051_241_946,
        "sha256": "e35da783d1c686b2b58b3b87cd6a403ae43bfcfba8bff28e08ef752c1a56afc1",
        "rows": 151_856_684,
        "required_columns": {"body_pre", "body_post", "weight"},
    },
}


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(8 * 1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def http_download(url: str, destination: Path) -> None:
    """Download to destination.part, with a conservative restart on failure."""
    part = destination.with_suffix(destination.suffix + ".part")
    if part.exists():
        part.unlink()
    req = urllib.request.Request(url, headers={"User-Agent": "FlyBrain-FBR10/1.0"})
    try:
        with urllib.request.urlopen(req, timeout=120) as r, part.open("wb") as out:
            total = int(r.headers.get("Content-Length", "0") or 0)
            done = 0
            while True:
                chunk = r.read(8 * 1024 * 1024)
                if not chunk:
                    break
                out.write(chunk)
                done += len(chunk)
                if total:
                    print(f"  {done:,}/{total:,} bytes ({100.0*done/total:.1f}%)", end="\r", flush=True)
        print()
    except Exception:
        try:
            part.unlink()
        except FileNotFoundError:
            pass
        raise
    part.replace(destination)


def feather_info(path: Path) -> dict:
    table = feather.read_table(path, memory_map=True)
    return {
        "rows": int(table.num_rows),
        "columns": [f.name for f in table.schema],
    }


def verify_file(path: Path, spec: dict) -> dict:
    result = {"path": str(path), "expected": spec.copy()}
    if not path.exists():
        raise FileNotFoundError(path)
    size = path.stat().st_size
    result["actual_size"] = size
    if size != spec["size"]:
        raise RuntimeError(f"SIZE MISMATCH: {path.name}: {size} != {spec['size']}")
    digest = sha256_file(path)
    result["actual_sha256"] = digest
    if digest != spec["sha256"]:
        raise RuntimeError(f"SHA256 MISMATCH: {path.name}: {digest} != {spec['sha256']}")
    info = feather_info(path)
    result["actual_rows"] = info["rows"]
    result["actual_columns"] = info["columns"]
    if info["rows"] != spec["rows"]:
        raise RuntimeError(f"ROW COUNT MISMATCH: {path.name}: {info['rows']} != {spec['rows']}")
    missing = sorted(spec["required_columns"] - set(info["columns"]))
    if missing:
        raise RuntimeError(f"SCHEMA MISMATCH: {path.name}; missing {missing}")
    return result


def verify_bundle(raw: Path) -> dict:
    results = {}
    for key, spec in FILES.items():
        results[key] = verify_file(raw / spec["name"], spec)

    # Reproduce the FlyBrain source-node census used by the reducer: unique
    # bodyId values with a non-empty published superclass.
    ann = feather.read_table(raw / FILES["annotations"]["name"], columns=["bodyId", "superclass"])
    body = ann.column("bodyId").to_numpy(zero_copy_only=False)
    sc = ann.column("superclass").to_pylist()
    selected = {int(b) for b, s in zip(body, sc) if s is not None and str(s).strip()}
    if len(selected) != 166_700:
        raise RuntimeError(f"AUDITED NODE CENSUS MISMATCH: {len(selected)} != 166700")
    results["audited_neuron_census"] = 166_700

    report = {
        "status": "PASS",
        "source": "MaleCNS v1.0 official bulk-download objects",
        "base_url": BASE,
        "files": results,
        "policy": "FlyBrain source census = unique bodyId with non-empty superclass; not status==Traced.",
    }
    return report


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    ap.add_argument("--download", action="store_true", help="Download any missing/wrong source file from the official bucket.")
    ap.add_argument("--verify-only", action="store_true", help="Only verify local files; never download.")
    args = ap.parse_args()
    if args.download and args.verify_only:
        ap.error("choose --download or --verify-only, not both")

    raw = args.root / "build" / "malecns_raw"
    raw.mkdir(parents=True, exist_ok=True)

    if args.download:
        for key, spec in FILES.items():
            dest = raw / spec["name"]
            ok = False
            if dest.exists():
                try:
                    verify_file(dest, spec)
                    ok = True
                    print(f"OK: {dest.name} already matches the pinned release.")
                except Exception as exc:
                    print(f"Existing {dest.name} is not the pinned object: {exc}")
            if not ok:
                print(f"Downloading {spec['name']} ...")
                http_download(BASE + spec["name"], dest)
                verify_file(dest, spec)
                print(f"Verified {dest.name}.")

    report = verify_bundle(raw)
    out = args.root / "build" / "MALECNS_SOURCE_VERIFICATION.json"
    out.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))
    print(f"\nPASS: verified source bundle. Report: {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
