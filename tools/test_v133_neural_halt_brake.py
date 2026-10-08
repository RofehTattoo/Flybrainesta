#!/usr/bin/env python3
"""V1.19.33 neural halt/BRK contract audit.

The release keeps FG/BB walk-OFF and BRK VNC braking as distinct measured
neural outputs. BRK may affect the mechanical actuator only through its
measured retained-population activity; no environment/action variable is
allowed to synthesize braking.
"""
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/com/example/flybrain/MainActivity.kt").read_text(encoding="utf-8")
ACT = (ROOT / "app/src/main/java/com/example/flybrain/LeggedSensorimotorActuator.kt").read_text(encoding="utf-8")
META = (ROOT / "app/src/main/java/com/example/flybrain/GeneratedConnectomeMeta.kt").read_text(encoding="utf-8")
GRADLE = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
BUILDER = (ROOT / "tools/build_connectome.py").read_text(encoding="utf-8")
TEST = (ROOT / "app/src/test/java/com/example/flybrain/LeggedSensorimotorActuatorTest.kt").read_text(encoding="utf-8")

assert 'const val APP_VERSION = "1.19.33"' in META
assert 'const val APP_VERSION_CODE = 174' in META
assert 'versionName = "1.19.33"' in GRADLE
assert 'versionCode = 174' in GRADLE
assert 'FLYBRAIN_RELEASE = "1.19.33"' in BUILDER
assert 'APP_VERSION_CODE = 174' in BUILDER

# Published halt-role semantics remain three-way and BRK is not collapsed into walk-OFF.
assert '1=FG walk-OFF, 2=BB walk-OFF, 3=BRK VNC brake' in MAIN
assert '1, 2 -> haltWalkOffSpikeEventsFrame++' in MAIN
assert '3 -> haltBrakeSpikeEventsFrame++' in MAIN
assert 'val brakeRateHz = if (haltBrakeTotal <= 0) 0f else' in MAIN
assert 'val brakeTarget = (brakeRateHz / BRAKE_RATE_REFERENCE_HZ).coerceIn(0f, 1f)' in MAIN
assert 'brakeActivationState = relaxMotorActivation(brakeActivationState, brakeTarget, dt)' in MAIN
assert 'legActuator.step(legGroupActivation, walkOffActivationState, brakeActivationState, dt)' in MAIN

# The actuator accepts the two halt mechanisms separately and contains no direct
# food/wall/action selector.
assert 'brakeActivation: Float' in ACT
assert 'BRK_MAX_PHASE_SUPPRESSION' in ACT
assert 'BRK_RESISTANCE' in ACT
assert 'BRK_RESISTANCE * brake * forwardVelocity' in ACT
for forbidden in ['foodOn', 'foodX', 'foodY', 'approachAction', 'orientAction', 'wallEscapeBias']:
    assert forbidden not in ACT

# Unit coverage must explicitly exercise BRK independently of walk-OFF.
assert 'brkActivation_addsNeuralBrakeWithoutSyntheticReverseDrive' in TEST

print("V1.19.33 NEURAL HALT / BRK AUDIT: PASS")
