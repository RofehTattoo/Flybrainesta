from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/com/example/flybrain/MainActivity.kt").read_text(encoding="utf-8")
body_start = MAIN.index("private fun driveBody")
body_end = MAIN.index("private fun runNeuralSimulation", body_start)
body = MAIN[body_start:body_end]

assert "val rawTurn = (rightLeg - leftLeg) * 1.55f" in body
assert "neckActivity *" not in body
assert "locomotionRng" not in MAIN
assert "exploratoryTurn" not in MAIN
assert "turnNoiseTarget" not in body
assert "val turn = rawTurn - baselineTurnBias * .72f" in body

META = (ROOT / "app/src/main/java/com/example/flybrain/GeneratedConnectomeMeta.kt").read_text(encoding="utf-8")
assert 'const val REDUCTION_ID = "FBR-10-OLF2-MOTORROUTE"' in META
assert 'const val APP_VERSION = "1.18.5"' in META
assert "const val APP_VERSION_CODE = 138" in META
print("V1.18.5 CAUSAL YAW / NO RANDOM STEERING AUDIT: PASS")
