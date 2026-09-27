from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
MAIN=(ROOT/"app/src/main/java/com/example/flybrain/MainActivity.kt").read_text()
assert 'sampleTarsalFoodContact(foodX, foodY)' in MAIN
assert 'gustatoryTarsalReceptorIndices' in MAIN
assert 'gustatorySide' in MAIN
assert 'FOOD_TARSAL_CONTACT_RADIUS' in MAIN
assert 'FOOD_GUSTATORY_SIGMA' in MAIN
assert 'setMappedSensoryRate(gustatoryReceptorIndices' not in MAIN
assert 'stimulusIntensity(foodOn, foodX, foodY, .065f)' not in MAIN
assert 'injectOlfactoryPopulation(foodOn, foodX, foodY, FOOD_OLF_MAX_HZ)' in MAIN
print('TARSAL GUSTATION CONTACT / SUBTYPE-PURE AUDIT: PASS')
