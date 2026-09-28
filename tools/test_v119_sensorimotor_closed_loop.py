#!/usr/bin/env python3
"""Static architecture audit for the V1.19 closed sensorimotor actuator."""
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
MAIN=(ROOT/"app/src/main/java/com/example/flybrain/MainActivity.kt").read_text(encoding="utf-8")
ACT=(ROOT/"app/src/main/java/com/example/flybrain/LeggedSensorimotorActuator.kt").read_text(encoding="utf-8")
META=(ROOT/"app/src/main/java/com/example/flybrain/GeneratedConnectomeMeta.kt").read_text(encoding="utf-8")
GRADLE=(ROOT/"app/build.gradle.kts").read_text(encoding="utf-8")

for token in ['"fl" -> if (side < 0) 1 else 4','"ml" -> if (side < 0) 2 else 5','"hl" -> if (side < 0) 3 else 6']:
    assert token in MAIN
for token in ['val phase = FloatArray(LEG_COUNT)','val contact = FloatArray(LEG_COUNT)','val load = FloatArray(LEG_COUNT)','private const val STANCE_DUTY = .62f','private const val MAX_FORWARD_SPEED = 5.00f']:
    assert token in ACT
assert 'fun step(legActivation: FloatArray, walkOffActivation: Float, dtRaw: Float)' in ACT
assert 'effectiveWalkOffActivation' in MAIN and 'legActuator.step(legGroupActivation, effectiveWalkOffActivation, dt)' in MAIN
assert 'legActuator.proprioceptionLeft' in MAIN and 'legActuator.proprioceptionRight' in MAIN
assert 'mechanosensorySide[idx] = side.toByte()' in MAIN
assert 'flyX = (flyX + worldVx * dt)' in MAIN and 'flyY = (flyY + worldVy * dt)' in MAIN
assert 'heading += yawRate * dt' in MAIN
assert MAIN.index('heading += yawRate * dt') < MAIN.index('var worldVx = cos(heading) * flySpeed')
assert '* 60f' not in MAIN[MAIN.index('private fun driveBody'):MAIN.index('private fun runNeuralSimulation')]
assert 'wingActivity * .010f' not in MAIN and 'flightMotor * .010f' not in MAIN
assert 'approachAction' not in ACT and 'foodOn' not in ACT and 'lightOn' not in ACT and 'dangerOn' not in ACT
assert 'heading = Math.PI.toFloat() - heading' not in MAIN and 'heading = -heading' not in MAIN
assert 'exploratoryTurn' not in MAIN and 'locomotionRng' not in MAIN
assert 'const val APP_VERSION = "1.19.6"' in META and 'const val APP_VERSION_CODE = 147' in META
assert 'versionName = "1.19.6"' in GRADLE and 'versionCode = 147' in GRADLE
print('V1.19.6 SENSORIMOTOR CLOSED LOOP STATIC AUDIT: PASS')
