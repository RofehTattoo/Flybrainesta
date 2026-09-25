from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/com/example/flybrain/MainActivity.kt").read_text(encoding="utf-8")
start = MAIN.index("private fun stepBrainSubstep(")
end = MAIN.index("private fun resetMotorSubstepAccumulators()", start)
body = MAIN[start:end]

snapshot = body.index("for (i in 0 until N) {\n                synTrace[i] =")
membrane = body.index("// All neurons now read the same completed synaptic-state snapshot.")
refractory = body.index("if (refractory[i] > 0f)")
assert snapshot < membrane < refractory
assert body.count("synTrace[i] =") == 1
assert "prevFired[i]" in body[snapshot:membrane]
assert "syn += w[k] * synTrace[source]" in body[membrane:]

print("SYNCHRONOUS NEURAL UPDATE AUDIT: PASS")
print("Synaptic traces are updated in a complete pass before membrane integration.")
print("No target reads a source trace partially updated during the same substep.")
