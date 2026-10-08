from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ACT = (ROOT / "app/src/main/java/com/example/flybrain/LeggedSensorimotorActuator.kt").read_text(encoding="utf-8")
MAIN = (ROOT / "app/src/main/java/com/example/flybrain/MainActivity.kt").read_text(encoding="utf-8")
GRADLE = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
META = (ROOT / "app/src/main/java/com/example/flybrain/GeneratedConnectomeMeta.kt").read_text(encoding="utf-8")

assert 'versionName = "1.19.34"' in GRADLE
assert 'versionCode = 175' in GRADLE
assert 'APP_VERSION_CODE = 175' in META
assert 'INITIAL_PHASES' in ACT
assert 'var cycle = (phase[g] / TWO_PI) % 1f' in ACT
assert 'TRIPOD_OFFSETS' not in ACT
assert 'tripodPhaseCoherence' not in ACT
assert 'yawTorqueProxy' in ACT
assert 'yawTorque += footLateral[g] * force' in ACT
assert 'val steeringSignal = if (abs(yawTorqueFiltered) > .015f) yawTorqueFiltered else turnBalance' in ACT
# The mechanical layer remains blind to environmental targets.
body = ACT[ACT.index('fun step('):ACT.index('fun applyWallConstraint(')]
assert 'flyX' not in body and 'foodOn' not in body and 'dangerOn' not in body and 'approachAction' not in body
# MainActivity still passes only measured leg-MN groups + walk-off.
assert 'legActuator.step(legGroupActivation, walkOffActivationState, brakeActivationState, dt)' in MAIN
print('V1.19.28 ADAPTIVE SIX-LEG MECHANICS AUDIT: PASS')
