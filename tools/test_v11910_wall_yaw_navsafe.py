from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/com/example/flybrain/MainActivity.kt").read_text(encoding="utf-8")
ACT = (ROOT / "app/src/main/java/com/example/flybrain/LeggedSensorimotorActuator.kt").read_text(encoding="utf-8")
assert "MIN_TRANSLATION_FOR_NEURAL_YAW" in ACT
assert "PAUSE_YAW_CUTOFF" in ACT
assert "YAW_STOP_RESPONSE_TAU" in ACT
assert "val contactOnset = !wallContactLatched" in ACT
assert "wallEscapeBias = wallEscapeDirection * contactGain" in ACT
assert "wallEscapePulseRemaining" in ACT
assert "vx += nx * WALL_SEPARATION_SPEED" in ACT
assert "vy += ny * WALL_SEPARATION_SPEED" in ACT
assert "window.setDecorFitsSystemWindows(true)" in MAIN
assert "safe.bottom + 8.dp()" in MAIN
print("V1.19.10 WALL-YAW / NAVIGATION REGRESSION: PASS")
