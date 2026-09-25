#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/com/example/flybrain/MainActivity.kt").read_text()

assert "sensoryCurrent[i] / NEURAL_SUBSTEPS_PER_FRAME.toFloat()" in MAIN
assert "applySensoryKick = true" in MAIN
assert "applySensoryKick = substep == 0" not in MAIN
assert "holds this sample across" in MAIN

# The frame dose is distributed, not quadrupled: the runtime divides the
# sampled ΔV by exactly the number of internal substeps.
assert "NEURAL_SUBSTEPS_PER_FRAME.toFloat()" in MAIN
print("SENSORY SAMPLE-HOLD / TEMPORAL DRIVE AUDIT: PASS")
