from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/com/example/flybrain/MainActivity.kt").read_text(encoding="utf-8")
ACT = (ROOT / "app/src/main/java/com/example/flybrain/LeggedSensorimotorActuator.kt").read_text(encoding="utf-8")
META = (ROOT / "app/src/main/java/com/example/flybrain/GeneratedConnectomeMeta.kt").read_text(encoding="utf-8")
assert "WALL_POSITION_RECOVERY = .0035f" in MAIN
assert "nextFlyX += wallNx * invLen * WALL_POSITION_RECOVERY" in MAIN
assert "nextFlyY += wallNy * invLen * WALL_POSITION_RECOVERY" in MAIN
assert "fun applyWallConstraint(" in ACT
assert "vx -= outward * nx" in ACT and "vy -= outward * ny" in ACT
assert "wallPressure" in ACT
assert "wallEscapeBias" not in ACT
assert 'APP_VERSION = "1.19.28"' in META
assert 'APP_VERSION_CODE = 169' in META
print("V1.19.28 WALL CONTACT / BODY RECOVERY REGRESSION: PASS")
