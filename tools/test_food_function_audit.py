from pathlib import Path
import hashlib, re

ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / 'app/src/main/java/com/example/flybrain/MainActivity.kt').read_text()
META = (ROOT / 'app/src/main/java/com/example/flybrain/GeneratedConnectomeMeta.kt').read_text()
BUILDER = (ROOT / 'tools/build_olfactory_input_map.py').read_text()
FBC = ROOT / 'app/src/main/res/raw/malecns_reduced.bin'
EXPECTED = 'bfadc30fd113c25f9711cce6ef8f6b80e9c139fe6d229965a4adabb94d8b4e60'

assert 'olfactoryNeuronIndices' in MAIN
assert 'for (i in olfactoryNeuronIndices)' in MAIN
assert 'injectSensoryPopulation(MECH_START, MECH_END, dangerPattern, SENSORY_MECH_GAIN, excludeOlfactory = true)' in MAIN
assert 'if (!isOlfactoryNeuron(i)) sensoryCurrent[i] += wallSignal' in MAIN
assert 'olfactoryPopulationRate()' in MAIN
assert 'mechanosensoryPopulationRate()' in MAIN
assert 'olfProjectionMode' not in MAIN
assert 'INDEX-CYCLIC' not in MAIN
assert 'FOOD_OLF_PATTERN_CAP' not in MAIN
assert 'encodeOdor' not in MAIN

# The olfactory population must not be the historical contiguous visual block.
assert 'for (i in OLF_START until OLF_END)' not in MAIN
assert 'populationRate(OLF_START, OLF_END)' not in MAIN

# No direct food-bias motor/control path.
body_start = MAIN.index('private fun driveBody')
body_end = MAIN.index('private fun runNeuralSimulation', body_start)
body = MAIN[body_start:body_end]
assert 'foodDirectionalBias' not in body

# The builder is source-backed and must cross both annotation and NT provenance.
for token in ['cb_sensory', 'olfactory', 'ORN_', 'acetylcholine', 'entryNerve', 'EXPECTED_ORNS = 45', 'EXPECTED_SIDE = {"L": 12, "R": 27, "U": 6}', 'neurotransmitters']:
    assert token in BUILDER, token

assert 'APP_VERSION = "1.16.2"' in META
assert 'APP_VERSION_CODE = 130' in META
assert hashlib.sha256(FBC.read_bytes()).hexdigest() == EXPECTED

print('OLF ANATOMICAL INPUT STATIC AUDIT: PASS')
print('FBC103 SHA-256:', EXPECTED)
