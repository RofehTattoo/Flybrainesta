from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
MAIN=(ROOT/"app/src/main/java/com/example/flybrain/MainActivity.kt").read_text()
assert 'injectOlfactoryPopulation(foodOn, foodX, foodY' in MAIN
assert 'externalRateHz[i] = (concentration * maxRateHz)' in MAIN
assert 'poissonEvent(i, dt)' in MAIN
assert 'deliverDelayedSynapses()' in MAIN
assert 'outgoing[source]' in MAIN
assert 'driveBody(dt)' in MAIN
assert 'flyX += cos(heading) * flySpeed * dt * 60f' in MAIN
body_start=MAIN.index('private fun driveBody'); body_end=MAIN.index('private fun runNeuralSimulation',body_start)
assert 'approachAction' not in MAIN[body_start:body_end]
assert 'foodDirectionalBias' not in MAIN[body_start:body_end]
assert 'flyX += cos(heading) * flySpeed * dt * 60f' in MAIN[body_start:body_end]
print('FOOD→OLF→NETWORK→VNC→BODY CAUSAL WIRING AUDIT: PASS')
