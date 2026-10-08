from pathlib import Path
import hashlib, struct

ROOT = Path(__file__).resolve().parents[1]
EXPECTED = "0044ab166af3439f2b86d4e6c5897481a1c3f28a58b6afb2c4f761489b276bbf"
BIN = ROOT / "app/src/main/res/raw/malecns_reduced.bin"
MANIFEST = ROOT / "tools/fbr10_frozen_body_ids.txt"
META = ROOT / "app/src/main/java/com/example/flybrain/GeneratedConnectomeMeta.kt"

raw = BIN.read_bytes()
assert hashlib.sha256(raw).hexdigest() == EXPECTED
assert raw[:8] == b"FBC103\\x00\\x00"
n, e = struct.unpack("<II", raw[8:16])
assert n == 16669
ids = [struct.unpack("<q", raw[16+i*26:24+i*26])[0] for i in range(n)]
manifest = [int(x.strip()) for x in MANIFEST.read_text().splitlines()
            if x.strip() and not x.startswith("#")]
assert ids == manifest
assert len(set(ids)) == 16669
meta = META.read_text()
assert f'const val BINARY_SHA256 = "{EXPECTED}"' in meta
print("FBR-10 FROZEN REPRODUCIBILITY CONTRACT: PASS")
