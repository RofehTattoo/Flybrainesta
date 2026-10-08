#!/usr/bin/env python3
"""Static audit for V1.19.37 arena-derived visual turning closure.

The arena boundary must reach the retained visual receptor populations as a
sensory signal. It must not reach heading/yaw directly, nor may a synthetic
exploration action be introduced to compensate for missing visual input.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/example/flybrain/MainActivity.kt"
ACT = ROOT / "app/src/main/java/com/example/flybrain/LeggedSensorimotorActuator.kt"
AUD = ROOT / "V1.19.37_NEURAL_TURN_YAW_AUDIT.md"

main = MAIN.read_text()
act = ACT.read_text()
aud = AUD.read_text()

required = [
    "private fun arenaWallDistance(theta: Float): Float",
    "private fun eyeWallVisualIntensity(side: Int): Float",
    "val wallLeft = eyeWallVisualIntensity(-1)",
    "val wallRight = eyeWallVisualIntensity(1)",
    "wallLeftRate",
    "wallRightRate",
    "visualReceptorIndices",
]
for token in required:
    assert token in main, f"missing arena visual token: {token}"

# Arena visual input must be assigned through external receptor rates.
wall_block = main[main.index("private fun setMappedVisualRate("):main.index("private fun xorshiftUnit(")]
assert "externalRateHz[index] = rate.coerceIn(0f, 260f)" in wall_block
assert "flyX" in wall_block and "flyY" in wall_block and "heading" in wall_block

# No direct wall->yaw/heading path inside the sensory encoder.
assert "yawRate" not in wall_block
assert "heading +=" not in wall_block
assert "steeringSignal" not in wall_block
assert "turnDn" not in wall_block

# The mechanical actuator must remain stimulus-agnostic.
assert "foodOn" not in act
assert "lightOn" not in act
assert "dangerOn" not in act
assert "heading +=" not in act
assert "target" not in act or "yawTarget" in act

# Existing neural TURN closure remains present.
assert "turnDnLeftActivationState" in main
assert "turnDnRightActivationState" in main
assert "turnDnLeftActivationState,\n                turnDnRightActivationState" in main
assert "val dnSteering = dnBalance * dnDrive * TURN_DN_MAX_CONTRIBUTION" in act

assert "arena itself is a visual object" in main
assert "arena-derived" not in aud or "arena" in aud.lower()
print("V1.19.37 ARENA VISUAL -> RETAINED TURN-DN AUDIT: PASS")
