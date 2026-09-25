from pathlib import Path
import hashlib

ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / 'app/src/main/java/com/example/flybrain/MainActivity.kt').read_text()
BUILDER = (ROOT / 'tools/build_olfactory_input_map.py').read_text()
FBC = ROOT / 'app/src/main/res/raw/malecns_reduced.bin'

assert 'olfactoryNeuronIndices' in MAIN
assert 'setMappedSensoryRate(mechanosensoryReceptorIndices, wallSignal * 80f)' in MAIN
assert 'olfactoryPopulationRate()' in MAIN
assert 'mechanosensoryPopulationRate()' in MAIN
assert 'for (i in OLF_START until OLF_END)' not in MAIN
assert 'INDEX-CYCLIC' not in MAIN
assert 'FOOD_OLF_PATTERN_CAP' not in MAIN
assert 'encodeOdor' not in MAIN
assert 'foodDirectionalBias' not in MAIN[MAIN.index('private fun driveBody'):MAIN.index('private fun runNeuralSimulation')]

assert 'EXPECTED_ORNS = 264' in BUILDER
assert 'EXPECTED_ORN_TYPE_ENTRY_NERVE_PAIRS = 54' in BUILDER
assert 'EXPECTED_ORN_TYPES' not in BUILDER
assert 'olfactory_orn_type_entry_nerve_pairs_retained' in BUILDER
assert 'len(retained_type_entry_nerve_pairs) != EXPECTED_ORN_TYPE_ENTRY_NERVE_PAIRS' in BUILDER
assert 'entryNerve' in MAIN and 'MXLBN' in MAIN
assert 'sc == "cb_sensory"' in BUILDER
assert 'cl == "olfactory"' in BUILDER
assert 'typ.startswith("ORN_")' in BUILDER
assert 'UNTYPED_ORN_BODY_IDS' in BUILDER
assert all(str(x) in BUILDER for x in (242812, 242908, 488209, 956041))
assert 'nt.lower() != "acetylcholine"' in BUILDER and 'entryNerve' in BUILDER
assert 'entryNerve' in BUILDER  # ORN provenance/selection only
META = (ROOT / 'app/src/main/java/com/example/flybrain/GeneratedConnectomeMeta.kt').read_text()
assert 'RETAINED_OLFACTORY_ORN_TYPE_ENTRY_NERVE_PAIRS = 54' in META
side_block = BUILDER[BUILDER.index('ss = side'):BUILDER.index('out.append({', BUILDER.index('ss = side'))]
assert 'entryNerve' not in side_block
assert 'if ss in (-1, 1):' in side_block
assert 'elif rs in (-1, 1):' in side_block
assert 'code, source = 0, "unknown"' in side_block

actual = hashlib.sha256(FBC.read_bytes()).hexdigest()
assert 'FBR-10-OLF2-MOTORROUTE' in BUILDER
assert 'side_from_nerve' not in BUILDER
print('OLF ANATOMICAL INPUT AUDIT: STATIC PASS')
print('CURRENT FBC SHA-256:', actual)
