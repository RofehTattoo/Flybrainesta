#!/usr/bin/env python3
"""Static release-provenance audit for FBR-10-OLF1."""
from pathlib import Path
import hashlib, json, re

ROOT=Path(__file__).resolve().parents[1]
META=(ROOT/"app/src/main/java/com/example/flybrain/GeneratedConnectomeMeta.kt").read_text(encoding="utf-8")
REPORT=json.loads((ROOT/"app/src/main/res/raw/malecns_reduced_report.json").read_text(encoding="utf-8"))
BUILD=(ROOT/"tools/build_connectome.py").read_text(encoding="utf-8")
assert 'REDUCTION_ID = "FBR-10-OLF1"' in META
sha=re.search(r'const val BINARY_SHA256 = "([0-9a-f]{64})"',META).group(1)
assert sha==REPORT["sha256"]
assert sha=="0044ab166af3439f2b86d4e6c5897481a1c3f28a58b6afb2c4f761489b276bbf"
assert REPORT["source_hashes"]["annotations"]=="2177e246113e4cfbf1e7772ec37c6da1955ff22e8063d0b1f833101f99a9a3b2"
assert REPORT["source_hashes"]["weights"]=="e35da783d1c686b2b58b3b87cd6a403ae43bfcfba8bff28e08ef752c1a56afc1"
assert 'EXPECTED_SOURCE_SHA256' in BUILD
assert 'verify_pinned_source' in BUILD
print("FBR-10-OLF1 RELEASE PROVENANCE: PASS")
