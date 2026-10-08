#!/usr/bin/env python3
"""V1.19.36 audit: close the retained descending TURN output to physical body yaw.

This test is intentionally source- and binary-aware. It verifies that:
  * FBR-10 remains frozen and contains a bilateral role-2 TURN population;
  * role-2 spikes are counted per 20 ms neural frame and resolved by side;
  * the mechanical body receives those measured neural signals;
  * no stimulus, action score, random exploration or wall-escape shortcut is used;
  * unit tests cover turn-in-place and bilateral cancellation.
"""
from pathlib import Path
import hashlib
import struct

ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/com/example/flybrain/MainActivity.kt").read_text(encoding="utf-8")
ACT = (ROOT / "app/src/main/java/com/example/flybrain/LeggedSensorimotorActuator.kt").read_text(encoding="utf-8")
META = (ROOT / "app/src/main/java/com/example/flybrain/GeneratedConnectomeMeta.kt").read_text(encoding="utf-8")
GRADLE = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
MANIFEST = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
TEST = (ROOT / "app/src/test/java/com/example/flybrain/LeggedSensorimotorActuatorTest.kt").read_text(encoding="utf-8")
BIN = ROOT / "app/src/main/res/raw/malecns_reduced.bin"

assert 'const val APP_VERSION = "1.19.37"' in META
assert 'const val APP_VERSION_CODE = 178' in META
assert 'versionName = "1.19.37"' in GRADLE
assert 'versionCode = 178' in GRADLE
assert 'android:label="FlyBrain V1.19.37"' in MANIFEST
assert 'const val BINARY_SHA256 = "0044ab166af3439f2b86d4e6c5897481a1c3f28a58b6afb2c4f761489b276bbf"' in META

# Frozen FBC103 census: 20 role-2 DNs, exactly 10 L and 10 R.
data = BIN.read_bytes()
assert hashlib.sha256(data).hexdigest() == "0044ab166af3439f2b86d4e6c5897481a1c3f28a58b6afb2c4f761489b276bbf"
assert data[:8] == b"FBC103\x00\x00"
n, e = struct.unpack_from("<II", data, 8)
assert n == 16669
turn_left = turn_right = turn_unknown = 0
off = 16
for _ in range(n):
    off += 8
    off += 1
    side = struct.unpack_from("<b", data, off)[0]; off += 1
    off += 1
    off += 1
    desc = struct.unpack_from("<b", data, off)[0]; off += 1
    off += 1
    off += 12
    if desc == 2:
        if side == -1: turn_left += 1
        elif side == 1: turn_right += 1
        else: turn_unknown += 1
assert (turn_left, turn_right, turn_unknown) == (10, 10, 0)

# Bilateral frame counters and body wiring.
for token in [
    'turnDnLeftSpikeEventsFrame',
    'turnDnRightSpikeEventsFrame',
    'if (descendingRole[i].toInt() == 2)',
    'turnDnLeftTotal++',
    'turnDnRightTotal++',
    'turnDnLeftActivationState = relaxMotorActivation',
    'turnDnRightActivationState = relaxMotorActivation',
    'turnDnLeftActivationState,\n                turnDnRightActivationState',
]:
    assert token in MAIN, token

# The body boundary must consume neural turn output but no environment/action variables.
start = MAIN.index('private fun applyMechanicalBodyState')
brace = MAIN.index('{', start)
depth = 0
end = None
for i in range(brace, len(MAIN)):
    if MAIN[i] == '{': depth += 1
    elif MAIN[i] == '}':
        depth -= 1
        if depth == 0:
            end = i + 1
            break
assert end is not None
body = MAIN[start:end]
assert 'turnDnLeftActivationState' in body and 'turnDnRightActivationState' in body
for forbidden in ('foodOn', 'foodX', 'foodY', 'lightOn', 'dangerOn', 'approachAction', 'orientAction', 'exploreAction', 'escapeAction'):
    assert forbidden not in body, forbidden

# Actuator consumes bilateral DN state and has bounded, cancellable steering.
for token in [
    'turnDnLeftActivation: Float',
    'turnDnRightActivation: Float',
    'val dnBalance =',
    'val dnDrive =',
    'val dnSteering = dnBalance * dnDrive * TURN_DN_MAX_CONTRIBUTION',
    'val steeringSignal = (legSteering + dnSteering).coerceIn(-1f, 1f)',
]:
    assert token in ACT, token
forbidden = ('foodOn', 'foodX', 'foodY', 'dangerOn', 'lightOn', 'approachAction', 'exploratoryTurn', 'locomotionRng', 'wallEscapeBias')
for token in forbidden:
    assert token not in ACT, token

# Regression coverage must exist.
for token in [
    'retainedTurnDnOutput_canReorientBodyWithoutLegPropulsion',
    'symmetricTurnDnOutput_hasNoIntrinsicYawBias',
    'bilateralTurnDnSignals_competeBySideInsteadOfUsingAOneShotHeadingEdit',
]:
    assert token in TEST, token

print("V1.19.37 RETAINED TURN-DN -> PHYSICAL YAW AUDIT: PASS")
