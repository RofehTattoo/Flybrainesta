from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/com/example/flybrain/MainActivity.kt").read_text()

assert 'FEEDING_CONTEXT_WINDOW_SECONDS = 0.75f' in MAIN
assert 'Random' not in MAIN
assert MAIN.index('private val MAX_FEEDING_MOTOR_NEURONS = 128') < MAIN.index('feedingMotorIndices = IntArray(MAX_FEEDING_MOTOR_NEURONS)')
assert 'tasteContextAgeSeconds = Float.POSITIVE_INFINITY' in MAIN
assert 'proboscisContextAgeSeconds = Float.POSITIVE_INFINITY' in MAIN
assert 'pharyngealContextAgeSeconds = Float.POSITIVE_INFINITY' in MAIN
assert 'FEEDING_PHARYNGEAL_CONTEXT_WINDOW_SECONDS = 0.80f' in MAIN
assert 'val tasteContextActive = tasteContextAgeSeconds <= FEEDING_CONTEXT_WINDOW_SECONDS' in MAIN
assert 'val proboscisContextActive = proboscisContextAgeSeconds <= FEEDING_CONTEXT_WINDOW_SECONDS' in MAIN
assert 'val ingestionNeural = foodOn &&' in MAIN
assert 'ingestionEventsFrame >= FEEDING_INGESTION_NEURON_SPIKE_MIN' in MAIN
# The readout may not command the body or synthesize a feeding event.
assert 'flySpeed =' not in MAIN[MAIN.index('private fun updateFeedingNeuralReadout'):MAIN.index('private fun populationRate')]
assert 'heading =' not in MAIN[MAIN.index('private fun updateFeedingNeuralReadout'):MAIN.index('private fun populationRate')]
assert 'forceExternalSpike' not in MAIN[MAIN.index('private fun updateFeedingNeuralReadout'):MAIN.index('private fun populationRate')]
print('FEEDING TEMPORAL CONTEXT / NO MOTOR BYPASS AUDIT: PASS')
