from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/com/example/flybrain/MainActivity.kt").read_text(encoding="utf-8")
body_start = MAIN.index("private fun driveBody")
body_end = MAIN.index("private fun runNeuralSimulation", body_start)
body = MAIN[body_start:body_end]

assert 'feedingPauseActivation' not in body
assert 'effectiveWalkOffActivation' not in body
assert 'legActuator.step(legGroupActivation, walkOffActivationState, dt)' in body
assert 'legActuator.yawRate' in body
assert 'supportBalance' in (ROOT / "app/src/main/java/com/example/flybrain/LeggedSensorimotorActuator.kt").read_text(encoding="utf-8")
assert "neckActivity *" not in body
assert "locomotionRng" not in MAIN
assert "exploratoryTurn" not in MAIN
assert "turnNoiseTarget" not in body
assert "heading = Math.PI.toFloat() - heading" not in body
assert "heading = -heading" not in body

META = (ROOT / "app/src/main/java/com/example/flybrain/GeneratedConnectomeMeta.kt").read_text(encoding="utf-8")
assert 'const val REDUCTION_ID = "FBR-10-OLF2-MOTORROUTE"' in META
assert 'const val APP_VERSION = "1.19.18"' in META
assert "const val APP_VERSION_CODE = 159" in META
print("V1.19.14 YAW GATING / NO RANDOM STEERING AUDIT: PASS")
