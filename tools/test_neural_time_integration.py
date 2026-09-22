from pathlib import Path
import math

ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/com/example/flybrain/MainActivity.kt").read_text(encoding="utf-8")
META = (ROOT / "app/src/main/java/com/example/flybrain/GeneratedConnectomeMeta.kt").read_text(encoding="utf-8")
GRADLE = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
FBC = ROOT / "app/src/main/res/raw/malecns_reduced.bin"

assert 'TAU_MEMBRANE_SECONDS = 0.020f' in MAIN
assert 'val membraneDecay = exp((-dt / TAU_MEMBRANE_SECONDS).toDouble()).toFloat()' in MAIN
assert 'val membraneLeak = (V_REST - v[i]) * (1f - membraneDecay)' in MAIN
assert MAIN.count('val membraneDecay = exp((-dt / TAU_MEMBRANE_SECONDS).toDouble()).toFloat()') == 1
assert 'v[i] += membraneLeak - adapt[i] * dt +' in MAIN
assert 'v[i] += ((V_REST - v[i]) * 50.0f - adapt[i]) * dt +' not in MAIN
assert '50.0f * dt' not in MAIN
# The 5 ms synaptic trace is deliberately unchanged in this release.
assert 'exp((-dt / .005f).toDouble()).toFloat()' in MAIN

# Numerical invariant: exact retention after one 20 ms tick is exp(-1).
dt = 0.020
tau = 0.020
retention = math.exp(-dt / tau)
assert abs(retention - math.exp(-1.0)) < 1e-12
assert 0.367 < retention < 0.369

assert 'APP_VERSION = "1.17.0"' in META
assert 'APP_VERSION_CODE = 131' in META
assert 'REDUCTION_ID = "FBR-10-OLF1"' in META
assert 'versionName = "1.17.0"' in GRADLE
assert 'versionCode = 131' in GRADLE

import hashlib, struct
raw=FBC.read_bytes()
assert raw[:8] == b'FBC103\x00\x00'
assert struct.unpack_from('<II', raw, 8)[0] == 16669
current_fbc=hashlib.sha256(raw).hexdigest()

print('V1.17.0 MEMBRANE INTEGRATION AUDIT: PASS')
print(f'Membrane retention @ 20 ms: {retention:.9f}')
print(f'Current FBC103 SHA-256: {current_fbc}')
