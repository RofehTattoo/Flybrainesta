from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / 'app/src/main/java/com/example/flybrain/MainActivity.kt').read_text()
BUILDER = (ROOT / 'tools/build_sensory_input_map.py').read_text()

# Phase 1: mapped receptor populations are the only external sensory injection targets.
for token in [
    'visualReceptorIndices', 'gustatoryReceptorIndices', 'mechanosensoryReceptorIndices',
    'loadSensoryInputMap()', 'injectMappedSensoryPopulation',
    'sensory_input_map.tsv',
]:
    assert token in MAIN, token

# The old population-wide / cyclic encoder must be gone.
for forbidden in [
    'injectSensoryPopulation(',
    'channel = ((i * 17) % pattern.size)',
    'sin((i * 0.043f)',
    'encodeStimulus(',
    'encodeTaste(',
    'combinedVisual[10]',
    'dangerPattern[10]',
    'visualThreatPattern[10]',
    'lightDirectionalBias',
    'dangerDirectionalBias',
    'abs(lightDirectionalBias) * .25f',
]:
    assert forbidden not in MAIN, forbidden

# The sensor interface must not inject remote danger into mechanosensory receptors.
sense_start = MAIN.index('private fun sense(dt: Float)')
sense_end = MAIN.index('private fun populationRate', sense_start)
sense = MAIN[sense_start:sense_end]
assert 'injectMappedSensoryPopulation(visualReceptorIndices' in sense
assert 'injectMappedSensoryPopulation(gustatoryReceptorIndices' in sense
assert 'injectMappedSensoryPopulation(mechanosensoryReceptorIndices, wallSignal * .055f)' in sense
assert 'dangerPattern' not in sense
assert 'SENSORY_MECH_GAIN' not in MAIN
assert 'SENSORY_OLF_GAIN' not in MAIN

# The map builder must be source-derived and conservative.
for token in [
    'ol_sensory', 'visual', 'R1-R6', 'R7', 'R8',
    'cl == "gustatory"',
    'gustatory', 'cb_sensory', 'vnc_sensory',
    'mechanosensory_proprioceptive',
    'flywireType', 'rootSide', 'somaSide',
    'No neuron or edge is created' if False else 'no_graph_mutation',
]:
    assert token in BUILDER, token

assert 'sensory_ascending' in BUILDER
assert 'index-cyclic' in BUILDER.lower()
assert 'CHANNEL_BY_MODALITY = {"VIS": 0, "GUST": 2, "MECH": 3}' in BUILDER
assert 'len(matches) > 1' in BUILDER
print('PHASE 1 SENSORY INPUT PURITY STATIC AUDIT: PASS')
