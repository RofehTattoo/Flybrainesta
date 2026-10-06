#!/usr/bin/env python3
"""V1.19.25 obstacle-contact sensory audit.

A wall may influence locomotion only by entering the retained mechanosensory
input path. The mechanical collision layer must not synthesize a turn.
"""
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/com/example/flybrain/MainActivity.kt").read_text(encoding="utf-8")
ACT = (ROOT / "app/src/main/java/com/example/flybrain/LeggedSensorimotorActuator.kt").read_text(encoding="utf-8")
MAP = (ROOT / "tools/build_sensory_input_map.py").read_text(encoding="utf-8")
META = (ROOT / "app/src/main/java/com/example/flybrain/GeneratedConnectomeMeta.kt").read_text(encoding="utf-8")
GRADLE = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")

assert '"mechSite"' in MAP
assert 'mechanosensorySite' in MAIN
assert 'val expectedHeader = "index\\tbodyId\\tmodality\\tsideCode\\tsideSource\\ttype\\tclass\\tsuperclass\\tsubclass\\treceptorType\\tflywireType\\tgustSite\\tmechSite"' in MAIN
assert 'footWallLeft' in MAIN and 'footWallRight' in MAIN
assert 'antennaWallLeft' in MAIN and 'antennaWallRight' in MAIN
assert 'mechanosensorySite[i].toInt()' in MAIN
assert 'externalRateHz[i] = (local * 130f + mechGlobal * 12f).coerceIn(0f, 150f)' in MAIN

wall = ACT[ACT.index("fun applyWallConstraint("):ACT.index("private fun bilateralMechanicalMean", ACT.index("fun applyWallConstraint("))]
assert "yawRate =" not in wall
assert "phase[" not in wall
assert "wallEscapeBias" not in wall

body = MAIN[MAIN.index("private fun applyMechanicalBodyState"):MAIN.index("private fun updateMeasuredPauseState")]
assert "legActuator.applyWallConstraint(" in body
assert "heading = Math.PI.toFloat() - heading" not in body
assert "heading = -heading" not in body

assert 'const val APP_VERSION = "1.19.25"' in META
assert 'const val APP_VERSION_CODE = 166' in META
assert 'versionName = "1.19.25"' in GRADLE
assert 'versionCode = 166' in GRADLE
print("V1.19.25 OBSTACLE CONTACT → MECHANOSENSORY → CONNECTOME AUDIT: PASS")
