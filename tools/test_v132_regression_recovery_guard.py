from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ACT = (ROOT / "app/src/main/java/com/example/flybrain/LeggedSensorimotorActuator.kt").read_text(encoding="utf-8")
MAIN = (ROOT / "app/src/main/java/com/example/flybrain/MainActivity.kt").read_text(encoding="utf-8")
GRADLE = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
META = (ROOT / "app/src/main/java/com/example/flybrain/GeneratedConnectomeMeta.kt").read_text(encoding="utf-8")

assert 'versionName = "1.19.32"' in GRADLE
assert 'versionCode = 173' in GRADLE
assert 'APP_VERSION = "1.19.32"' in META
assert 'APP_VERSION_CODE = 173' in META

# Regression guard: stance/swing semantics must remain physically ordered.
assert 'val stanceFraction = if (s) 1f - smooth else 0f' in ACT
assert 'val swingFraction = if (s) 0f else smooth' in ACT

# Regression guard: propulsion remains an active neural-motor downstream readout.
assert 'val propulsive = if (s && a > MOTOR_THRESHOLD)' in ACT
assert '(-strideRate * contact[g]).coerceAtLeast(0f)' in ACT
assert 'val force = (propulsive * .055f * (0.55f + .45f * a)).coerceIn(0f, .22f)' in ACT
assert 'forwardAcceleration = FORWARD_ACCEL * propulsive' in ACT

# V1.19.31's passive slip-friction replacement must not return.
for forbidden in [
    'footVelocityForward', 'footVelocityLateral', 'footStrokeVelocity',
    'val forceForward = (-forceScale *', 'val forceLateral = (-forceScale *',
    'fun applyWallReactionWorld(', 'YAW_TORQUE_TO_ACCEL * torqueWorld'
]:
    assert forbidden not in ACT, f"V1.19.31 regression token present: {forbidden}"

print("V1.19.32 REGRESSION RECOVERY GUARD: PASS")
