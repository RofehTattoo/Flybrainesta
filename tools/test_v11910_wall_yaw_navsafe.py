from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
ACT = (ROOT / "app/src/main/java/com/example/flybrain/LeggedSensorimotorActuator.kt").read_text(encoding="utf-8")
MAIN = (ROOT / "app/src/main/java/com/example/flybrain/MainActivity.kt").read_text(encoding="utf-8")
assert "MIN_TRANSLATION_FOR_NEURAL_YAW" in ACT
assert "PAUSE_YAW_CUTOFF" in ACT
assert "YAW_STOP_RESPONSE_TAU" in ACT
assert "val contactOnset = !wallContactLatched" in ACT
assert "wallEscapeBias = wallEscapeDirection * contactGain" in ACT
assert "if (contactOnset)" in ACT
assert "wallEscapeBias = (wallEscapeBias *" in ACT
assert "window.setDecorFitsSystemWindows(true)" in MAIN
assert "view.setPadding(safe.left, safe.top, safe.right, safe.bottom + 8.dp())" in MAIN
assert "view.setPadding(left, top, right, bottom + 8.dp())" in MAIN
assert "FLYBRAIN V1.19.11 · SENSORIMOTOR CLOSED LOOP" in MAIN
print("V1.19.11 WALL-YAW PULSE / NAVIGATION INSETS AUDIT: PASS")
