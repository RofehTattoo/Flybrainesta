#!/usr/bin/env python3
"""V1.19.8 embodied pause/wall/feeding structural audit."""
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
MAIN=(ROOT/"app/src/main/java/com/example/flybrain/MainActivity.kt").read_text()
ACT=(ROOT/"app/src/main/java/com/example/flybrain/LeggedSensorimotorActuator.kt").read_text()
SENS=(ROOT/"tools/build_sensory_input_map.py").read_text()
META=(ROOT/"app/src/main/java/com/example/flybrain/GeneratedConnectomeMeta.kt").read_text()
GRADLE=(ROOT/"app/build.gradle.kts").read_text()
assert 'legBaselineRateHz' in MAIN and 'legPreviousRateHz' in MAIN and 'legBaselineReady' in MAIN
assert 'fastExcess = (rate - fastBaseline - 0.55f).coerceAtLeast(0f)' in MAIN
assert 'foodAmount = (foodAmount - FOOD_INGESTION_STEP).coerceAtLeast(0f)' in MAIN
assert 'val ingestionNeural = foodOn &&' in MAIN and 'pharyngealContextActive' in MAIN and 'foodPharyngealContactFrame >= .04f' in MAIN
assert 'fun updateMeasuredPauseState(dt: Float, wallContact: Boolean)' in MAIN
assert 'updateMeasuredPauseState(dt, wallContactNow)' in MAIN
assert 'PAUSA ESPONTÁNEA' in MAIN
assert 'BODY_MIN_X' in MAIN and 'BODY_MAX_X' in MAIN and 'BODY_MIN_Y' in MAIN and 'BODY_MAX_Y' in MAIN
assert 'fun applyWallConstraint(' in ACT and 'contactActive: Boolean' in ACT
assert 'outward < 0f' in ACT and 'heading = -heading' not in MAIN
assert 'INITIAL_PHASES = floatArrayOf(.50f, .08f, .66f, 0f, .58f, .16f)' in ACT
assert 'TRIPOD_OFFSETS' not in ACT
assert 'supportMean * 1.75f' not in ACT
assert 'gustatoryLabellarReceptorIndices' in MAIN and 'gustatoryPharyngealReceptorIndices' in MAIN
assert '"LABELLAR"' in SENS and '"PHARYNGEAL"' in SENS and '"TARSAL"' in SENS
assert 'gustSite' in SENS

# The pause detector must consume the physical speed calculated for the same
# completed frame; otherwise a true stop can be missed or shifted indefinitely.
speed_pos = MAIN.index('physicalSpeed = hypot(dxPhysical, dyPhysical) / dt.coerceAtLeast(.001f)')
pause_call_pos = MAIN.index('updateMeasuredPauseState(dt, wallContactNow)')
assert speed_pos < pause_call_pos
# Wall feedback is body-frame resolved; the old screen-top/screen-bottom side
# cross-wiring is intentionally absent.
assert 'max(wallLeft, wallTop * .30f)' not in MAIN
assert 'max(wallRight, wallBottom * .30f)' not in MAIN
assert 'if (foodOn && foodAmount > 0f)' in MAIN
assert 'WALL_TANGENTIAL_FRICTION' in ACT
assert '1.19.37' in META and '178' in META and '1.19.37' in GRADLE and '178' in GRADLE
print('V1.19.7 EMBODIED PAUSE/WALL/FEEDING AUDIT: PASS')
