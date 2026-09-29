from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/com/example/flybrain/MainActivity.kt").read_text(encoding="utf-8")
ACT = (ROOT / "app/src/main/java/com/example/flybrain/LeggedSensorimotorActuator.kt").read_text(encoding="utf-8")
META = (ROOT / "app/src/main/java/com/example/flybrain/GeneratedConnectomeMeta.kt").read_text(encoding="utf-8")
assert "WALL_POSITION_RECOVERY = .0035f" in MAIN
assert "nextFlyX += wallNx * invLen * WALL_POSITION_RECOVERY" in MAIN
assert "nextFlyY += wallNy * invLen * WALL_POSITION_RECOVERY" in MAIN
assert "private var wallStallTimer = 0f" in ACT
assert "WALL_STALL_RETRIGGER_SECONDS = .28f" in ACT
assert "WALL_REPULSE_COOLDOWN_SECONDS = .42f" in ACT
assert "if (planarSpeed < .045f && wallRepulseCooldown <= 0f)" in ACT
assert "val retrySide = when {" in ACT
assert "if (wallEscapePulseRemaining <= 0f)" in ACT
assert "data class StartupEntry" in MAIN
assert 'APP_VERSION = "1.19.13"' in META
assert 'APP_VERSION_CODE = 154' in META
print("V1.19.13 WALL CORNER RECOVERY / STARTUP COMPILE REGRESSION: PASS")
