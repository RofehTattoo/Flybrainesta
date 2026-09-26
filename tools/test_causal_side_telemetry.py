from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/com/example/flybrain/MainActivity.kt").read_text(encoding="utf-8")

# Side-resolved ORN counts are accumulated from actual LIF spikes, not injected rates.
assert "for (i in olfactoryNeuronIndices)" in MAIN
assert "olfWindowSpikesLeft++" in MAIN and "olfWindowSpikesRight++" in MAIN
assert "olfLeftHz = olfWindowSpikesLeft * invWindow / olfLeftN.toFloat()" in MAIN
# Descending and leg motor outputs are computed from measured spike windows and official side metadata.
assert "dnLeftHz = if (dnLeftCount == 0) 0f else dnLeftSpikes * invWindow / dnLeftCount.toFloat()" in MAIN
assert "legLeftHz = if (legLeftTotal == 0) 0f else legLeftSpikes * invWindow / legLeftTotal.toFloat()" in MAIN
assert "legRightHz = if (legRightTotal == 0) 0f else legRightSpikes * invWindow / legRightTotal.toFloat()" in MAIN
# Telemetry must not become a hidden behavioral shortcut.
body_start = MAIN.index("private fun driveBody")
body_end = MAIN.index("private fun runNeuralSimulation", body_start)
body = MAIN[body_start:body_end]
assert "foodDirectionalBias" not in body
assert "approachAction" not in body
assert "olfLeftHz" not in body and "dnLeftHz" not in body and "legLeftHz" not in body
# Heading is bounded for numerical stability and displayed in a conventional signed range.
assert "if (heading > Math.PI.toFloat()) heading -= twoPi" in MAIN
assert "if (heading < -Math.PI.toFloat()) heading += twoPi" in MAIN
assert "((raw + 180f) % 360f + 360f) % 360f - 180f" in MAIN
assert '"FRENADO NEURAL"' in MAIN
print("CAUSAL SIDE-RESOLVED TELEMETRY / NO-BYPASS AUDIT: PASS")
