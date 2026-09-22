from pathlib import Path
import hashlib, re, struct

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / 'app/src/main/java/com/example/flybrain/MainActivity.kt'
META = ROOT / 'app/src/main/java/com/example/flybrain/GeneratedConnectomeMeta.kt'
FBC = ROOT / 'app/src/main/res/raw/malecns_reduced.bin'
EXPECTED = 'bfadc30fd113c25f9711cce6ef8f6b80e9c139fe6d229965a4adabb94d8b4e60'

m = MAIN.read_text()
assert 'FOOD_OLF_GAIN = 6.00f' in m
assert 'gaussian(d, 1.10f)' in m
assert 'FOOD_OLF_PATTERN_CAP = 3.00f' in m
assert 'val scale = (FOOD_OLF_PATTERN_CAP / maxRaw).coerceAtMost(1f)' in m
assert 'OLF L/C/R' in m
assert 'olfProjectionMode = "INDEX-CYCLIC"' in m
assert hashlib.sha256(FBC.read_bytes()).hexdigest() == EXPECTED
meta = META.read_text()
assert 'APP_VERSION = "1.15.8"' in meta
assert 'APP_VERSION_CODE = 127' in meta
# Guardrail: no direct food-bias body command was introduced.
body_start=m.index('private fun driveBody')
body_end=m.index('private fun runNeuralSimulation', body_start)
body=m[body_start:body_end]
assert 'foodDirectionalBias' not in body
print('FOOD FUNCTION AUDIT: PASS')
print('FBC103 SHA-256:', EXPECTED)
print('FOOD gain: 6.00; sigma: 1.10; projection: INDEX-CYCLIC (explicitly exposed)')
