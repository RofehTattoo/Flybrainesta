#!/usr/bin/env python3
"""V1.20 phase-free neuromuscular embodiment structural audit."""
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
MAIN=(ROOT/'app/src/main/java/com/example/flybrain/MainActivity.kt').read_text()
ACT=(ROOT/'app/src/main/java/com/example/flybrain/LeggedSensorimotorActuator.kt').read_text()
META=(ROOT/'app/src/main/java/com/example/flybrain/GeneratedConnectomeMeta.kt').read_text()
GRADLE=(ROOT/'app/build.gradle.kts').read_text()
for token in ['"fl" -> if (side < 0) 1 else 4','"ml" -> if (side < 0) 2 else 5','"hl" -> if (side < 0) 3 else 6']:
    assert token in MAIN, token
assert 'fun step(motorRateHz: FloatArray, walkOffActivation: Float, dtRaw: Float)' in ACT
assert 'legActuator.step(legGroupRateHz, walkOffActivationState, dt)' in MAIN
assert 'legGroupRateHz[g] = if (total <= 0) 0f' in MAIN
assert 'legBaselineRateHz' not in MAIN and 'legPreviousRateHz' not in MAIN
assert 'TRIPOD_OFFSETS' not in ACT and 'STANCE_DUTY' not in ACT
assert 'phase[g] +=' not in ACT and 'phaseHz' not in ACT
assert 'contact[g]' in ACT and 'footForward[g]' in ACT and 'jointState[g]' in ACT
assert 'proprioceptionLeft' in ACT and 'proprioceptionRight' in ACT
assert 'applyWallConstraint' in ACT and 'WALL_CONTACT_FLOOR' in ACT
assert 'heading += yawRate * dt' in MAIN
assert 'foodOn' not in ACT and 'approachAction' not in ACT and 'dangerOn' not in ACT
assert 'const val APP_VERSION = "1.20.0"' in META and 'APP_VERSION_CODE = 143' in META
assert 'versionName = "1.20.0"' in GRADLE and 'versionCode = 143' in GRADLE
print('V1.20 PHASE-FREE NEUROMUSCULAR EMBODIMENT STATIC AUDIT: PASS')
