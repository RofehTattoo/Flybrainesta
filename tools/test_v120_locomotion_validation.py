#!/usr/bin/env python3
"""V1.19.25 stabilization audit for the ground locomotion stack.

This is a source-contract test. It does not claim biological validation by itself.
It verifies that the release keeps a strict three-layer boundary:

  environment/olfaction -> retained neural circuit -> VNC leg output -> mechanics

and that the mechanical layer exposes the expected six-leg, modified-tripod,
wall-contact and bounded-body state needed for the runtime unit tests.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/com/example/flybrain/MainActivity.kt").read_text(encoding="utf-8")
ACT = (ROOT / "app/src/main/java/com/example/flybrain/LeggedSensorimotorActuator.kt").read_text(encoding="utf-8")
META = (ROOT / "app/src/main/java/com/example/flybrain/GeneratedConnectomeMeta.kt").read_text(encoding="utf-8")
GRADLE = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
MANIFEST = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
TEST = (ROOT / "app/src/test/java/com/example/flybrain/LeggedSensorimotorActuatorTest.kt").read_text(encoding="utf-8")
WORKFLOW = (ROOT / ".github/workflows/build-apk.yml").read_text(encoding="utf-8")
BUILDER = (ROOT / "tools/build_connectome.py").read_text(encoding="utf-8")

# Release identity must be synchronized.
assert 'const val APP_VERSION = "1.19.25"' in META
assert 'const val APP_VERSION_CODE = 166' in META
assert 'SOURCE_NEUROTRANSMITTERS_SHA256 = "95c9289220663abeb3409f3ad9e5a7f8a53f8093f5139d15502cd08da8879621"' in META
assert 'versionName = "1.19.25"' in GRADLE
assert 'versionCode = 166' in GRADLE
assert 'android:label="FlyBrain V1.19.25"' in MANIFEST

# Mechanical layer: six independent legs, stance/swing/contact and modified-tripod timing.
for token in [
    'const val LEG_COUNT = 6',
    'val phase = FloatArray(LEG_COUNT)',
    'val contact = FloatArray(LEG_COUNT)',
    'val load = FloatArray(LEG_COUNT)',
    'val stance = FloatArray(LEG_COUNT)',
    'val swing = FloatArray(LEG_COUNT)',
    'private val TRIPOD_OFFSETS = floatArrayOf(.50f, .08f, .66f, 0f, .58f, .16f)',
    'private const val STANCE_DUTY = .62f',
    'private const val MAX_FORWARD_SPEED = 5.00f',
    'forwardForceProxy',
    'yawForceProxy',
    'supportCoverage',
    'bilateralMechanicalSymmetry',
    'tripodPhaseCoherence',
    'lateralAcceleration = LATERAL_ACCEL * supportBalance * turnDrive',
]:
    assert token in ACT, token

# The actuator receives only measured neural leg groups + walk-off.
assert 'fun step(legActivation: FloatArray, walkOffActivation: Float, dtRaw: Float)' in ACT
wall_start = ACT.index('fun applyWallConstraint(')
wall_end = ACT.index('private fun bilateralMechanicalMean', wall_start)
wall_body = ACT[wall_start:wall_end]
assert 'wallPressure' in wall_body
assert 'vx -= outward * nx' in wall_body and 'vy -= outward * ny' in wall_body
assert 'yawRate =' not in wall_body
assert 'phase[' not in wall_body
assert 'wallEscapeBias' not in ACT
def kotlin_function_span(source: str, signature: str) -> str:
    start = source.index(signature)
    brace = source.index('{', start)
    depth = 0
    for i in range(brace, len(source)):
        if source[i] == '{':
            depth += 1
        elif source[i] == '}':
            depth -= 1
            if depth == 0:
                return source[start:i + 1]
    raise AssertionError(f'Unclosed Kotlin function: {signature}')

body = kotlin_function_span(MAIN, 'private fun applyMechanicalBodyState')
drive_body = kotlin_function_span(MAIN, 'private fun driveBody(dt: Float)')
assert 'applyMechanicalBodyState(dt)' in drive_body
assert 'legActuator.step(legGroupActivation, walkOffActivationState, dt)' in body
for forbidden in ['foodOn', 'lightOn', 'dangerOn', 'foodX', 'foodY', 'approachAction', 'orientAction']:
    assert forbidden not in body, forbidden
assert 'effectiveWalkOffActivation' not in body

# Neural origin of leg groups: measured VNC motor spike rates, with a phasic baseline.
for token in [
    'legGroupSpikeEvents',
    'legBaselineRateHz',
    'legPreviousRateHz',
    'motorRole[i].toInt()',
    'GeneratedConnectomeMeta.MOTOR_LEG',
    'MOTOR_LEG',
    'WALKOFF_RATE_REFERENCE_HZ',
]:
    assert token in MAIN, token

# Physics/body integration: actuator velocity and yaw are integrated before displacement,
# and wall response uses measured geometry instead of heading reflection.
for token in [
    'flySpeed = legActuator.forwardVelocity',
    'bodyLateralSpeed = legActuator.lateralVelocity',
    'yawRate = legActuator.yawRate',
    'heading += yawRate * dt',
    'var worldVx = cos(heading) * flySpeed - sin(heading) * bodyLateralSpeed',
    'var nextFlyX = flyX + worldVx * dt',
    'var nextFlyY = flyY + worldVy * dt',
    'legActuator.applyWallConstraint(',
    'flyX = nextFlyX.coerceIn(BODY_MIN_X, BODY_MAX_X)',
    'flyY = nextFlyY.coerceIn(BODY_MIN_Y, BODY_MAX_Y)',
]:
    assert token in MAIN, token
assert 'heading = Math.PI.toFloat() - heading' not in MAIN
assert 'heading = -heading' not in MAIN
assert 'locomotionRng' not in MAIN
assert 'exploratoryTurn' not in MAIN

# Renderer must be driven by actuator state, not an independent animation clock.
for token in ['legActuator.stride[g]', 'legActuator.lift[g]', 'legActuator.load[g]', 'legActuator.contact[g]']:
    assert token in MAIN, token

# The static test itself must exercise the requested stability gates.
for token in [
    'noMotorOutput_doesNotAdvanceLegPhasesOrBody',
    'bilateralMotorOutput_drivesSixLegMechanicsAndFeedback',
    'rightSupportImbalance_producesPositiveYaw',
    'walkOff_reducesMechanicalDrive',
    'symmetricMotorOutput_hasNoPersistentYawBias',
    'legPhasesFreezeWhenMotorOutputStops',
]:
    assert token in TEST, token

# Canonical connectome/olfaction route must remain the release source of truth.
assert 'FBR-10-OLF2-MOTORROUTE' in META
assert 'route_olfactory_forward' in BUILDER
assert 'route_olfactory_to_desc' in BUILDER
assert 'route_desc_to_leg' in BUILDER

# CI must execute the V1.19.25 stabilization audit before the Android tests/build.
assert 'python tools/test_v120_locomotion_validation.py' in WORKFLOW

print('V1.19.25 LOCOMOTION STABILIZATION AUDIT: PASS')
print('Layers checked: NEURAL -> 6-LEG MECHANICS -> PHYSICAL BODY / WALL CONSTRAINT')
