from pathlib import Path
import hashlib

ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / 'app/src/main/java/com/example/flybrain/MainActivity.kt').read_text()
BUILDER = (ROOT / 'tools/build_olfactory_input_map.py').read_text()
FBC = ROOT / 'app/src/main/res/raw/malecns_reduced.bin'
EXPECTED = 'bfadc30fd113c25f9711cce6ef8f6b80e9c139fe6d229965a4adabb94d8b4e60'

assert 'olfactoryNeuronIndices' in MAIN
assert 'injectSensoryPopulation(MECH_START, MECH_END, dangerPattern, SENSORY_MECH_GAIN, excludeOlfactory = true)' in MAIN
assert 'olfactoryPopulationRate()' in MAIN
assert 'mechanosensoryPopulationRate()' in MAIN
assert 'for (i in OLF_START until OLF_END)' not in MAIN
assert 'INDEX-CYCLIC' not in MAIN
assert 'FOOD_OLF_PATTERN_CAP' not in MAIN
assert 'encodeOdor' not in MAIN
assert 'foodDirectionalBias' not in MAIN[MAIN.index('private fun driveBody'):MAIN.index('private fun runNeuralSimulation')]

assert 'EXPECTED_ORNS = 45' in BUILDER
assert 'EXPECTED_SIDE = {"L": 12, "R": 27, "U": 6}' in BUILDER
assert 'sc == "cb_sensory"' in BUILDER
assert 'cl == "olfactory"' in BUILDER
assert 'typ.startswith("ORN_")' in BUILDER
assert 'nt.lower() != "acetylcholine"' in BUILDER and 'entryNerve' in BUILDER
assert 'side_from_nerve' not in BUILDER
assert 'if ss in (-1, 1):' in BUILDER
assert 'elif rs in (-1, 1):' in BUILDER
assert 'code, source = 0, "unknown"' in BUILDER

assert hashlib.sha256(FBC.read_bytes()).hexdigest() == EXPECTED
print('OLF ANATOMICAL INPUT AUDIT: PASS')
print('FBC103 SHA-256:', EXPECTED)
