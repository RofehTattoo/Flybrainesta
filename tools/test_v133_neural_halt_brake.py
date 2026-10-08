from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ACT = ROOT / "app/src/main/java/com/example/flybrain/LeggedSensorimotorActuator.kt"
MAIN = ROOT / "app/src/main/java/com/example/flybrain/MainActivity.kt"
META = ROOT / "app/src/main/java/com/example/flybrain/GeneratedConnectomeMeta.kt"

a = ACT.read_text(encoding="utf-8")
m = MAIN.read_text(encoding="utf-8")
meta = META.read_text(encoding="utf-8")

assert 'fun step(legActivation: FloatArray, walkOffActivation: Float, brakeActivation: Float, dtRaw: Float)' in a
assert 'val brake = brakeActivation.coerceIn(0f, 1f)' in a
assert 'val gaitGate = walkGate * brakeGate' in a
assert 'BRK_BRAKE_ACCEL' in a
assert 'val brakeRateHz = if (haltBrakeTotal <= 0) 0f else {' in m
assert 'brakeActivationState = relaxMotorActivation(brakeActivationState, brakeTarget, dt)' in m
assert 'legActuator.step(legGroupActivation, walkOffActivationState, brakeActivationState, dt)' in m
assert 'if (food' not in a.lower()
assert 'if (wall' not in a.lower()
assert 'FLYBRAIN_VERSION = "1.19.35"' in meta
assert 'APP_VERSION_CODE = 176' in meta
assert 'BINARY_SHA256 = "0044ab166af3439f2b86d4e6c5897481a1c3f28a58b6afb2c4f761489b276bbf"' in meta
print("V1.19.35 NEURAL HALT/BRK AUDIT: PASS")
