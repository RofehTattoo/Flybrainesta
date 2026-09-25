"""Numerical regression checks for the normalized-scene olfactory sensor model."""
from pathlib import Path
import math
import re

ROOT = Path(__file__).resolve().parents[1]
MODEL = (ROOT / "app/src/main/java/com/example/flybrain/OlfactorySensorModel.kt").read_text()
MAIN = (ROOT / "app/src/main/java/com/example/flybrain/MainActivity.kt").read_text()

def const(name):
    match = re.search(rf"const val {name} = ([0-9.]+)f", MODEL)
    assert match, f"Missing {name}"
    return float(match.group(1))

SIGMA = const("ODOR_SIGMA")
FORWARD = const("ANTENNA_FORWARD")
HALF_SPACING = const("ANTENNA_HALF_SPACING")

def sample(fx, fy, heading, sx, sy, side):
    ca, sa = math.cos(heading), math.sin(heading)
    lateral = HALF_SPACING * side
    ax = fx + ca * FORWARD - sa * lateral
    ay = fy + sa * FORWARD + ca * lateral
    d = math.hypot(sx - ax, sy - ay)
    return math.exp(-(d*d)/(2*SIGMA*SIGMA))

def contrast(left, right):
    return max(-1.0, min(1.0, (left-right)/(left+right+0.001)))

# Local field: a near source must produce a stronger concentration than a far one.
near = (sample(.5,.5,0,.55,.5,-1) + sample(.5,.5,0,.55,.5,1))/2
far = (sample(.5,.5,0,.95,.5,-1) + sample(.5,.5,0,.95,.5,1))/2
assert near > far and far < .5, (near, far)

# Symmetry: source on the forward axis must not create false lateral evidence.
a = sample(.5,.5,0,.8,.5,-1)
b = sample(.5,.5,0,.8,.5,1)
assert abs(a-b) < 1e-9

# Mirroring a source across the midline reverses the sensory contrast.
ll, lr = sample(.5,.5,0,.65,.42,-1), sample(.5,.5,0,.65,.42,1)
rl, rr = sample(.5,.5,0,.65,.58,-1), sample(.5,.5,0,.65,.58,1)
assert contrast(ll, lr) * contrast(rl, rr) < 0

# Current app spawn must retain measurable (not necessarily motor-effective) contrast.
initial_l = sample(.24,.55,-.15,.76,.35,-1)
initial_r = sample(.24,.55,-.15,.76,.35,1)
assert abs(initial_l-initial_r) > 1e-4, (initial_l, initial_r)
assert "val center = (left + right) * .5f" in MAIN
assert "foodDrive = center" in MAIN
body = MAIN[MAIN.index("private fun driveBody"):MAIN.index("private fun runNeuralSimulation")]
assert "foodDirectionalBias" not in body
print(f"OLFACTORY SENSOR MODEL: PASS (sigma={SIGMA}, half-spacing={HALF_SPACING})")
print(f"Near/far presence={near:.4f}/{far:.4f}; initial L/R={initial_l:.4f}/{initial_r:.4f}; contrast={contrast(initial_l, initial_r):+.4f}")
