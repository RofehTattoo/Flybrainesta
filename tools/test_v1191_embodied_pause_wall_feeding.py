#!/usr/bin/env python3
"""V1.20 regression audit for pause measurement, wall contact and feeding separation."""
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
MAIN=(ROOT/'app/src/main/java/com/example/flybrain/MainActivity.kt').read_text()
ACT=(ROOT/'app/src/main/java/com/example/flybrain/LeggedSensorimotorActuator.kt').read_text()
SENS=(ROOT/'tools/build_sensory_input_map.py').read_text()
META=(ROOT/'app/src/main/java/com/example/flybrain/GeneratedConnectomeMeta.kt').read_text()
GRADLE=(ROOT/'app/build.gradle.kts').read_text()
assert 'foodAmount = (foodAmount - FOOD_INGESTION_STEP).coerceAtLeast(0f)' in MAIN
assert 'val ingestionNeural = foodOn &&' in MAIN and 'foodPharyngealContactFrame >= .04f' in MAIN
assert 'fun updateMeasuredPauseState(dt: Float, wallContact: Boolean)' in MAIN
assert 'updateMeasuredPauseState(dt, wallContactNow)' in MAIN
assert 'PAUSA ESPONTÁNEA' in MAIN
assert 'BODY_MIN_X' in MAIN and 'BODY_MAX_X' in MAIN and 'BODY_MIN_Y' in MAIN and 'BODY_MAX_Y' in MAIN
assert 'fun applyWallConstraint(heading: Float, normalX: Float, normalY: Float, dtRaw: Float)' in ACT
assert 'inward < 0f' in ACT and 'heading = -heading' not in MAIN
assert 'TRIPOD_OFFSETS' not in ACT and 'STANCE_DUTY' not in ACT
assert 'gustatoryLabellarReceptorIndices' in MAIN and 'gustatoryPharyngealReceptorIndices' in MAIN
assert '"LABELLAR"' in SENS and '"PHARYNGEAL"' in SENS and '"TARSAL"' in SENS
assert 'gustSite' in SENS
speed_pos=MAIN.index('physicalSpeed = hypot(dxPhysical, dyPhysical) / dt.coerceAtLeast(.001f)')
pause_pos=MAIN.index('updateMeasuredPauseState(dt, wallContactNow)')
assert speed_pos < pause_pos
assert 'max(wallLeft, wallTop * .30f)' not in MAIN
assert 'max(wallRight, wallBottom * .30f)' not in MAIN
assert 'if (foodOn && foodAmount > 0f)' in MAIN
assert 'WALL_TANGENTIAL_FRICTION' in ACT
assert '1.20.0' in META and '143' in META and '1.20.0' in GRADLE and '143' in GRADLE
print('V1.20 PAUSE / WALL / FEEDING REGRESSION AUDIT: PASS')
