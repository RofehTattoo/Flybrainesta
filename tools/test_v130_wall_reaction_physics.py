from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
MAIN=(ROOT/"app/src/main/java/com/example/flybrain/MainActivity.kt").read_text(encoding="utf-8")
ACT=(ROOT/"app/src/main/java/com/example/flybrain/LeggedSensorimotorActuator.kt").read_text(encoding="utf-8")

span=MAIN[MAIN.index("private fun applyMechanicalBodyState"):
          MAIN.index("private fun updateMeasuredPauseState")]
assert "val wallReactionTorque = 0f" not in span
assert "var wallReactionTorque = 0f" in span
assert "val load = legActuator.load[g].coerceIn(0f, 1f)" in span
assert "val penL" in span and "val penR" in span and "val penT" in span and "val penB" in span
assert "val rX" in span and "val rY" in span
assert "rX * ry - rY * rx" in span
assert "recordWallReactionTorque(normalizedWallTorque)" in span
assert "wallReactionTorqueProxy" in ACT
assert "fun recordWallReactionTorque(normalizedTorque: Float)" in ACT
# No behavioral target is allowed into the physical contact calculation.
for forbidden in ["foodOn", "foodX", "foodY", "dangerOn", "dangerX", "dangerY", "approachAction", "orientAction", "escapeAction"]:
    assert forbidden not in span
print("V1.19.30 WALL REACTION PHYSICS AUDIT: PASS")
