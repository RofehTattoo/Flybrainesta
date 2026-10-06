from pathlib import Path

root=Path(__file__).resolve().parents[1]
act=(root/"app/src/main/java/com/example/flybrain/LeggedSensorimotorActuator.kt").read_text()
assert "translationYawGate" not in act
assert "val neuralYawTarget = turnBalance * MAX_YAW_RATE * turnDrive * pauseYawGate" in act
assert "applyWallConstraint" in act
start=act.index("fun applyWallConstraint")
wall=act[start:]
assert "yawRate =" not in wall
assert "phase[" not in wall
assert "wallEscape" not in wall
print("V1.19.27 NEURAL YAW WALL DEADLOCK: PASS")
