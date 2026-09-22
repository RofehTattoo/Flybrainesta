from pathlib import Path
import math

ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/com/example/flybrain/MainActivity.kt").read_text(encoding="utf-8")
META = (ROOT / "app/src/main/java/com/example/flybrain/GeneratedConnectomeMeta.kt").read_text(encoding="utf-8")
GRADLE = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
FBC = ROOT / "app/src/main/res/raw/malecns_reduced.bin"
EXPECTED_FBC = "bfadc30fd113c25f9711cce6ef8f6b80e9c139fe6d229965a4adabb94d8b4e60"

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

assert 'APP_VERSION = "1.16.2"' in META
assert 'APP_VERSION_CODE = 130' in META
assert 'versionName = "1.16.2"' in GRADLE
assert 'versionCode = 130' in GRADLE

import hashlib
assert hashlib.sha256(FBC.read_bytes()).hexdigest() == EXPECTED_FBC

print('V1.16.2 MEMBRANE INTEGRATION AUDIT: PASS')
print(f'Membrane retention @ 20 ms: {retention:.9f}')
print(f'FBC103 SHA-256: {EXPECTED_FBC}')
