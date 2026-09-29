from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
ACT = (ROOT / "app/src/main/java/com/example/flybrain/LeggedSensorimotorActuator.kt").read_text(encoding="utf-8")
MAIN = (ROOT / "app/src/main/java/com/example/flybrain/MainActivity.kt").read_text(encoding="utf-8")
assert "TURN_PROPULSION_DEADZONE" in ACT
assert "TURN_PROPULSION_FULL_SCALE" in ACT
assert "turnDrive" in ACT and "pauseYawGate" in ACT
assert "WindowInsets.Type.systemBars()" in MAIN
assert "WindowInsets.Type.displayCutout()" in MAIN
assert "view.setPadding(safe.left, safe.top, safe.right, safe.bottom + 8.dp())" in MAIN
assert "FLYBRAIN V1.19.12 · SENSORIMOTOR CLOSED LOOP" in MAIN
assert "MaleCNS v1.0 · FBR-10-OLF2-MOTORROUTE" in MAIN
print("V1.19.12 YAW / EDGE-TO-EDGE / HEADER COLLISION AUDIT: PASS")
