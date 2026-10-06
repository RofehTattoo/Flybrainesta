from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/com/example/flybrain/MainActivity.kt").read_text(encoding="utf-8")
ACT = (ROOT / "app/src/main/java/com/example/flybrain/LeggedSensorimotorActuator.kt").read_text(encoding="utf-8")
assert "MIN_TRANSLATION_FOR_NEURAL_YAW" in ACT
assert "PAUSE_YAW_CUTOFF" in ACT
assert "YAW_STOP_RESPONSE_TAU" in ACT
assert "turnBalance * MAX_YAW_RATE" in ACT
wall = ACT[ACT.index("fun applyWallConstraint("):ACT.index("private fun bilateralMechanicalMean", ACT.index("fun applyWallConstraint("))]
assert "wallPressure" in wall
assert "wallEscapeBias" not in wall
assert "yawRate =" not in wall
assert "phase[" not in wall
assert "vx -= outward * nx" in wall and "vy -= outward * ny" in wall
assert "window.setDecorFitsSystemWindows(true)" in MAIN
assert "safe.bottom + 8.dp()" in MAIN
print("V1.19.24 WALL-YAW / NAVIGATION PURITY REGRESSION: PASS")
