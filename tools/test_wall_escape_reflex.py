#!/usr/bin/env python3
"""V1.19.28 wall-contact purity audit.

Wall contact is physical collision handling plus mechanosensory feedback. It is
not allowed to inject a turn or gait-phase command directly into the actuator.
"""
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
MAIN=(ROOT/"app/src/main/java/com/example/flybrain/MainActivity.kt").read_text(encoding="utf-8")
ACT=(ROOT/"app/src/main/java/com/example/flybrain/LeggedSensorimotorActuator.kt").read_text(encoding="utf-8")
META=(ROOT/"app/src/main/java/com/example/flybrain/GeneratedConnectomeMeta.kt").read_text(encoding="utf-8")
GRADLE=(ROOT/"app/build.gradle.kts").read_text(encoding="utf-8")

assert "fun applyWallConstraint(" in ACT and "contactActive: Boolean" in ACT
assert "vx -= outward * nx" in ACT and "vy -= outward * ny" in ACT
assert "wallPressure" in ACT
assert "heading =" not in ACT
assert "phase[" not in ACT[ACT.index("fun applyWallConstraint("):ACT.index("private fun bilateralMechanicalMean", ACT.index("fun applyWallConstraint("))]
assert "wallEscapeBias" not in ACT
assert "WALL_ESCAPE_MAX_YAW_RATE" not in ACT
assert "WALL_SEPARATION_SPEED" not in ACT
assert "exploratoryTurn" not in MAIN and "locomotionRng" not in MAIN
assert "heading += yawRate * dt" in MAIN
assert "legActuator.applyWallConstraint(" in MAIN and "wallContactNow" in MAIN
assert 'APP_VERSION = "1.19.28"' in META and 'APP_VERSION_CODE = 169' in META
assert 'versionName = "1.19.28"' in GRADLE and 'versionCode = 169' in GRADLE
print("V1.19.28 WALL CONTACT / NO DIRECT TURN AUDIT: PASS")
