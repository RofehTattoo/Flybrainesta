from pathlib import Path

root=Path(__file__).resolve().parents[1]
act=(root/"app/src/main/java/com/example/flybrain/LeggedSensorimotorActuator.kt").read_text()
assert "translationYawGate" not in act
assert "val legSteering =" in act
assert "val steeringSignal = (legSteering + dnSteering).coerceIn(-1f, 1f)" in act
assert "val dnSteering = dnBalance * dnDrive * TURN_DN_MAX_CONTRIBUTION" in act
assert "applyWallConstraint" in act
start=act.index("fun applyWallConstraint")
wall=act[start:]
assert "yawRate =" not in wall
assert "phase[" not in wall
assert "wallEscape" not in wall
print("V1.19.28 NEURAL YAW / ADAPTIVE LEG MECHANICS: PASS")
