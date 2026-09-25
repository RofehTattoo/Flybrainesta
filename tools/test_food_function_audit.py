from pathlib import Path
import hashlib, re

ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / 'app/src/main/java/com/example/flybrain/MainActivity.kt').read_text()
META = (ROOT / 'app/src/main/java/com/example/flybrain/GeneratedConnectomeMeta.kt').read_text()
BUILDER = (ROOT / 'tools/build_olfactory_input_map.py').read_text()
FBC = ROOT / 'app/src/main/res/raw/malecns_reduced.bin'

assert 'olfactoryNeuronIndices' in MAIN
assert 'for (i in olfactoryNeuronIndices)' in MAIN
assert MAIN.count('injectMappedSensoryPopulation(mechanosensoryReceptorIndices, wallSignal * .055f)') == 1
assert 'olfactoryPopulationRate()' in MAIN
assert 'mechanosensoryPopulationRate()' in MAIN
assert 'olfProjectionMode' not in MAIN
assert 'INDEX-CYCLIC' not in MAIN
assert 'FOOD_OLF_PATTERN_CAP' not in MAIN
assert 'encodeOdor' not in MAIN

# The olfactory population must not be the historical contiguous visual block.
assert 'for (i in OLF_START until OLF_END)' not in MAIN
assert 'parsed.size != 45' not in MAIN
assert 'expected=45' not in MAIN
assert 'expected L=12 R=27 U=6' not in MAIN
assert 'populationRate(OLF_START, OLF_END)' not in MAIN

# No direct food-bias motor/control path.
body_start = MAIN.index('private fun driveBody')
body_end = MAIN.index('private fun runNeuralSimulation', body_start)
body = MAIN[body_start:body_end]
assert 'foodDirectionalBias' not in body
assert 'SENSORY_KICK_LIMIT' in MAIN
assert 'OlfactoryInputEncoder.encode(' in MAIN
assert 'coerceIn(-SENSORY_KICK_LIMIT, SENSORY_KICK_LIMIT)' not in MAIN

# The builder is source-backed and must cross both annotation and NT provenance.
for token in ['cb_sensory', 'olfactory', 'ORN_', 'acetylcholine', 'entryNerve', 'EXPECTED_ORNS = 264', 'EXPECTED_ORN_TYPE_ENTRY_NERVE_PAIRS = 54', 'neurotransmitters']:
    assert token in BUILDER, token

assert 'APP_VERSION = "1.17.1"' in META
assert 'APP_VERSION_CODE = 132' in META
assert 'REDUCTION_ID = "FBR-10-OLF1"' in META
assert 'route_olfactory_forward' not in MAIN
assert 'side_from_nerve' not in BUILDER
print('OLF ANATOMICAL INPUT STATIC AUDIT: PASS')
print('CURRENT BOOTSTRAP FBC SHA-256:', hashlib.sha256(FBC.read_bytes()).hexdigest())
