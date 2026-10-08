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
META = (ROOT / "app/src/main/java/com/example/flybrain/GeneratedConnectomeMeta.kt").read_text(encoding="utf-8")
assert 'APP_VERSION = "1.19.35"' in META
assert 'REDUCTION_ID = "FBR-10-OLF2-MOTORROUTE"' in META
print("V1.19.14 YAW / EDGE-TO-EDGE / HEADER COLLISION AUDIT: PASS")
