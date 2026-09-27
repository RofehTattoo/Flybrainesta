from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
MAIN=(ROOT/"app/src/main/java/com/example/flybrain/MainActivity.kt").read_text(encoding="utf-8")
assert 'injectOlfactoryPopulation(foodOn, foodX, foodY' in MAIN
assert 'externalRateHz[i] = (concentration * maxRateHz)' in MAIN
assert 'poissonEvent(i, dt)' in MAIN
assert 'deliverDelayedSynapses()' in MAIN
assert 'outgoing[source]' in MAIN
assert 'driveBody(dt)' in MAIN
body_start=MAIN.index('private fun driveBody'); body_end=MAIN.index('private fun runNeuralSimulation',body_start)
body=MAIN[body_start:body_end]
assert 'approachAction' not in body
assert 'foodDirectionalBias' not in body
# V1.18.7: body translation is generated from six retained leg motor groups,
# not from the old whole-population point-mass decoder.
assert 'val legGroupSpikeEvents = IntArray(LEG_COUNT)' in body
assert 'legGroupActivation[g] = relaxMotorActivation' in body
assert 'val supportDrive = (stanceSum / 3f).coerceIn(0f, 1f)' in body
assert 'flyX = (flyX + worldVx * dt * 60f)' in body
assert 'flyY = (flyY + worldVy * dt * 60f)' in body
assert 'wingActivity * .010f' not in body
assert 'flightMotor * .010f' not in body
assert 'heading = Math.PI.toFloat() - heading' not in body
assert 'heading = -heading' not in body
print('FOOD→OLF→NETWORK→VNC→BODY CAUSAL WIRING AUDIT: PASS')
