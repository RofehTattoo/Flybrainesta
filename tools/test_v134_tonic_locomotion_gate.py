from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/com/example/flybrain/MainActivity.kt").read_text(encoding="utf-8")
ACT = (ROOT / "app/src/main/java/com/example/flybrain/LeggedSensorimotorActuator.kt").read_text(encoding="utf-8")
META = (ROOT / "app/src/main/java/com/example/flybrain/GeneratedConnectomeMeta.kt").read_text(encoding="utf-8")
TEST = (ROOT / "app/src/test/java/com/example/flybrain/LeggedSensorimotorActuatorTest.kt").read_text(encoding="utf-8")

# The regression was caused by feeding tonic motor firing into the phase clock.
drive = MAIN[MAIN.index("// V1.19.30: the previous decoder"):
             MAIN.index("// Walk-OFF is a measured neural actuator gate.")]
assert "val burst =" in drive
assert "val fastBaseline = legFastBaselineRateHz[g]" in drive
assert "fastExcess" in drive
assert "LEG_FAST_BASELINE_TAU_SECONDS" in MAIN
assert "val target = burst.coerceIn(0f, 1f)" in drive
assert "tonic * .42f" not in drive

# Mechanical phase must remain stateful, but it cannot invent locomotion when
# the upstream phasic drive is below threshold.
assert "private const val MOTOR_THRESHOLD = .035f" in ACT
assert "val phaseHz = if (a > MOTOR_THRESHOLD)" in ACT
assert "Phase is a locomotor state variable, not a clock." in ACT
assert "foodOn" not in ACT and "dangerOn" not in ACT and "approachAction" not in ACT

# Regression test must exist in the Android test suite.
assert "sustainedBackgroundMotorInput_isNotAClockThatForcesWalking" in TEST
assert 'const val APP_VERSION = "1.19.35"' in META
assert 'const val APP_VERSION_CODE = 176' in META

print("V1.19.35 TONIC/PHASIC LOCOMOTION GATE AUDIT: PASS")
