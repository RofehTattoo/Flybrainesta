from pathlib import Path

root=Path(__file__).resolve().parents[1]
act=(root/"app/src/main/java/com/example/flybrain/LeggedSensorimotorActuator.kt").read_text()
assert "translationYawGate" not in act
assert "val steeringSignal = if (abs(normalizedYawTorque) > .015f) normalizedYawTorque else turnBalance" in act
assert "applyWallConstraint" in act
start=act.index("fun applyWallConstraint")
wall=act[start:]
assert "yawRate =" not in wall
assert "phase[" not in wall
assert "wallEscape" not in wall
print("V1.19.28 NEURAL YAW / ADAPTIVE LEG MECHANICS: PASS")
