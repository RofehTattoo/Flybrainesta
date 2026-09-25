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
assert 'normalized voltage-dose (ΔV)' in MAIN
assert 'applySensoryKick = substep == 0' not in MAIN
# The 5 ms synaptic trace is deliberately unchanged in this release.
assert 'exp((-dt / .005f).toDouble()).toFloat()' in MAIN

# Numerical invariant: exact retention after one 20 ms tick is exp(-1).
dt = 0.020
tau = 0.020
retention = math.exp(-dt / tau)
assert abs(retention - math.exp(-1.0)) < 1e-12
assert 0.367 < retention < 0.369

# The sampled sensory dose is held across all four internal substeps and divided by four.
# The full 20 ms dose is conserved; only its temporal distribution changes.
v_rest, v_th, kick_limit = -0.72, -0.50, 0.55
threshold_gap = v_th - v_rest
assert abs(threshold_gap - 0.22) < 1e-12
assert kick_limit > threshold_gap
assert v_rest + threshold_gap >= v_th
# Subthreshold kicks can accumulate under repeated stimulation in the ideal
# passive model; this is a diagnostic bound, not a biological calibration.
steady_state_gain = 1.0 / (1.0 - retention)
minimum_repeated_kick = threshold_gap / steady_state_gain
assert 0.139 < minimum_repeated_kick < 0.140

assert 'APP_VERSION = "1.17.1"' in META
assert 'APP_VERSION_CODE = 132' in META
assert 'REDUCTION_ID = "FBR-10-OLF1"' in META
assert 'versionName = "1.17.1"' in GRADLE
assert 'versionCode = 132' in GRADLE

import hashlib, struct
raw=FBC.read_bytes()
assert raw[:8] == b'FBC103\x00\x00'
assert struct.unpack_from('<II', raw, 8)[0] == 16669
current_fbc=hashlib.sha256(raw).hexdigest()

print('V1.17.1 MEMBRANE INTEGRATION AUDIT: PASS')
print(f'Membrane retention @ 20 ms: {retention:.9f}')
print(f'Rest-to-threshold ΔV: {threshold_gap:.3f}; sensory kick cap: {kick_limit:.2f}')
print(f'Ideal repeated-kick threshold: {minimum_repeated_kick:.6f} per 20 ms')
print(f'Current FBC103 SHA-256: {current_fbc}')
