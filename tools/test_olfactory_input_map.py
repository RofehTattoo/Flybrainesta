from pathlib import Path
import hashlib
ROOT=Path(__file__).resolve().parents[1]
MAIN=(ROOT/'app/src/main/java/com/example/flybrain/MainActivity.kt').read_text()
assert 'injectSensoryPopulation(OLF_START, OLF_END' not in MAIN
sense=MAIN[MAIN.index('private fun sense'):MAIN.index('private fun stepBrain')]
assert 'injectSensoryPopulation(OLF_START, OLF_END' not in sense
assert 'injectOlfactoryPopulation' in sense
assert 'FOOD_OLF_PATTERN_CAP' not in MAIN
assert 'foodDirectionalBias' not in MAIN[MAIN.index('private fun updateActionSelection'):MAIN.index('private fun updateActionSelection')+6000]
assert 'loadOlfactoryInputMap()' in MAIN
assert 'assets.open("olfactory_input_map.tsv")' in MAIN
FBC=ROOT/'app/src/main/res/raw/malecns_reduced.bin'
assert hashlib.sha256(FBC.read_bytes()).hexdigest()=='bfadc30fd113c25f9711cce6ef8f6b80e9c139fe6d229965a4adabb94d8b4e60'
print('OLF ANATOMICAL INPUT AUDIT: PASS')
