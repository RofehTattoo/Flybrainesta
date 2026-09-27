#!/usr/bin/env python3
"""Static audit for the V1.18.7 six-leg motor actuator.

This intentionally checks source architecture, not runtime behavior. Runtime
behavior must still be verified from an APK video/telemetry run.
"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/example/flybrain/MainActivity.kt"
BUILD = ROOT / "tools/build_vnc_motor_semantics.py"
META = ROOT / "app/src/main/java/com/example/flybrain/GeneratedConnectomeMeta.kt"
GRADLE = ROOT / "app/build.gradle.kts"

s = MAIN.read_text(encoding="utf-8")
b = BUILD.read_text(encoding="utf-8")
m = META.read_text(encoding="utf-8")
g = GRADLE.read_text(encoding="utf-8")

assert '"fl" -> if (side < 0) 1 else 4' in s
assert '"ml" -> if (side < 0) 2 else 5' in s
assert '"hl" -> if (side < 0) 3 else 6' in s
assert "private val motorLegGroup = ByteArray(N)" in s
assert "private val legGroupActivation = FloatArray(LEG_COUNT)" in s
assert "private var gaitPhase = 0f" in s
assert "if (gaitDrive > LEG_WALK_THRESHOLD)" in s
assert "legGroupSpikeEvents[group - 1] += motorSpikes" in s
assert "val supportDrive = (stanceSum / 3f).coerceIn(0f, 1f)" in s
assert "val rawTurn = (rightLeg - leftLeg) * 1.55f" in s
assert "worldVx = 0f" in s and "worldVy = 0f" in s
assert "heading = Math.PI.toFloat() - heading" not in s
assert "heading = -heading" not in s
assert "wingActivity * .010f" not in s
assert "flightMotor * .010f" not in s
assert "flyY += sin(flightPhase" not in s
assert "val walkOffTarget" in s
assert "walkOffActivationState" in s
assert "haltWalkOffSpikeEventsFrame > 0" in s
# The six-leg grouping is decoded from the official semantics asset rather than
# hard-coded by neuron index.
assert 'val fn = when (c[8])' in s
assert 'c[3].trim().lowercase()' in s
# Runtime keeps exact six groups non-empty.
assert 'if (legGroupTotals.sum() != legLeftTotal + legRightTotal)' in s
# Version consistency.
assert '1.18.7' in m and '140' in m
assert 'versionCode = 140' in g and 'versionName = "1.18.7"' in g
# The source-of-truth semantic builder must expose the three leg subclasses.
for token in ['"fl"', '"ml"', '"hl"']:
    assert token in b
print('V1.18.7 LEGGED MOTOR ACTUATOR STATIC AUDIT: PASS')
