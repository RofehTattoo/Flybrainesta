#!/usr/bin/env python3
"""Focused invariants for the phase-free V1.20 actuator boundary."""
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
ACT=(ROOT/'app/src/main/java/com/example/flybrain/LeggedSensorimotorActuator.kt').read_text()
MAIN=(ROOT/'app/src/main/java/com/example/flybrain/MainActivity.kt').read_text()
META=(ROOT/'app/src/main/java/com/example/flybrain/GeneratedConnectomeMeta.kt').read_text()
assert 'TRIPOD_OFFSETS' not in ACT
assert 'STANCE_DUTY' not in ACT
assert 'phaseHz' not in ACT and 'phase[g] +=' not in ACT
assert 'motorRateHz: FloatArray' in ACT
assert 'motorRateHz[g].coerceAtLeast(0f)' in ACT
assert 'legGroupRateHz[g] = if (total <= 0) 0f' in MAIN
assert 'legActuator.step(legGroupRateHz, walkOffActivationState, dt)' in MAIN
assert 'legBaselineRateHz' not in MAIN and 'legPreviousRateHz' not in MAIN
assert 'foodOn' not in ACT and 'foodX' not in ACT and 'approachAction' not in ACT
assert 'wallPressure = max(wallPressure, max(WALL_CONTACT_FLOOR, pressure))' in ACT
assert 'const val APP_VERSION = "1.20.0"' in META
print('V1.20 NEUROMUSCULAR EMBODIMENT INVARIANTS: PASS')
