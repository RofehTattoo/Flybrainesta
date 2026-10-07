from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ACT = (ROOT / "app/src/main/java/com/example/flybrain/LeggedSensorimotorActuator.kt").read_text(encoding="utf-8")
MAIN = (ROOT / "app/src/main/java/com/example/flybrain/MainActivity.kt").read_text(encoding="utf-8")

# Six independent neural leg streams feed the mechanical layer.
body = ACT[ACT.index("fun step("):ACT.index("fun applyWallConstraint(")]
assert "legActivation: FloatArray" in body
assert "footVelocityForward" in body and "footVelocityLateral" in body
assert "footStrokeVelocity" in body
assert "forceForward" in body and "forceLateral" in body
assert "yawTorque += footForward[g] * forceLateral - footLateral[g] * forceForward" in body

# No scalar steering fallback remains in the actuator.
for forbidden in ["turnBalance", "steeringSignal", "MAX_YAW_RATE", "translationYawGate"]:
    assert forbidden not in ACT

# Wall contact produces a world-frame reaction force and moment from actual foot penetration.
wall = MAIN[MAIN.index("private fun applyMechanicalBodyState"):MAIN.index("private fun updateMeasuredPauseState")]
for required in [
    "wallForceWorldX", "wallForceWorldY", "wallReactionTorque",
    "val penL", "val penR", "val penT", "val penB",
    "val rX", "val rY", "rX * fy - rY * fx",
    "applyWallReactionWorld(", "applyWallConstraint("
]:
    assert required in wall, required

# The wall resolver may alter velocity only; it may not prescribe heading, gait phase,
# motor activation or position recovery.
resolver = ACT[ACT.index("fun applyWallConstraint("):ACT.index("private fun bilateralMechanicalMean")]
for forbidden in ["heading +=", "phase[", "legActivation", "flyX =", "flyY =", "WALL_POSITION_RECOVERY"]:
    assert forbidden not in resolver

# The mechanical body call is blind to environmental goals.
for forbidden in ["foodOn", "foodX", "foodY", "dangerOn", "dangerX", "dangerY", "approachAction", "orientAction", "escapeAction"]:
    assert forbidden not in wall, forbidden

print("V1.19.31 GROUND-REACTION / WALL-CONTACT PURITY AUDIT: PASS")
