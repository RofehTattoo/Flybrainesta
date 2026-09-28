#!/usr/bin/env python3
"""Static regression audit for the V1.19.8 wall escape reflex."""
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
MAIN=(ROOT/"app/src/main/java/com/example/flybrain/MainActivity.kt").read_text()
ACT=(ROOT/"app/src/main/java/com/example/flybrain/LeggedSensorimotorActuator.kt").read_text()

assert "fun applyWallConstraint(" in ACT and "contactActive: Boolean" in ACT
assert "WALL_ESCAPE_MAX_YAW_RATE = 3.10f" in ACT
assert "WALL_ESCAPE_RESPONSE_TAU = .085f" in ACT
assert "wallEscapeBias" in ACT
assert "wallEscapeDirection" in ACT
assert "forwardIntoWall" in ACT
assert "wallRightProjection" in ACT
assert "phaseKick" in ACT

# Collision removes penetration but does not reflect the body or directly write heading.
assert "vx -= outward * nx" in ACT
assert "vy -= outward * ny" in ACT
assert "heading =" not in ACT
assert "exploratoryTurn" not in MAIN
assert "locomotionRng" not in MAIN

# Body heading remains integrated from actuator yaw in MainActivity.
assert "heading += yawRate * dt" in MAIN
assert "legActuator.applyWallConstraint(" in MAIN and "wallContactNow" in MAIN

# Version sync.
META=(ROOT/"app/src/main/java/com/example/flybrain/GeneratedConnectomeMeta.kt").read_text()
GRADLE=(ROOT/"app/build.gradle.kts").read_text()
assert 'APP_VERSION = "1.19.8"' in META and 'APP_VERSION_CODE = 149' in META
assert 'versionName = "1.19.8"' in GRADLE and 'versionCode = 149' in GRADLE

print("WALL ESCAPE REFLEX AUDIT: PASS")
