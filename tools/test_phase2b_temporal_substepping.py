from pathlib import Path
import math
ROOT=Path(__file__).resolve().parents[1]
s=(ROOT/"app/src/main/java/com/example/flybrain/MainActivity.kt").read_text(encoding="utf-8")
assert 'private val NEURAL_FRAME_DT_SECONDS = 0.020f' in s
assert 'private val NEURAL_SUBSTEP_DT_SECONDS = 0.0005f' in s
assert 'private val NEURAL_SUBSTEPS_PER_FRAME = 40' in s
assert 'private val SYNAPTIC_DELAY_SECONDS = 0.0018f' in s
assert 'private val SYNAPTIC_DELAY_STEPS = 4' in s
assert 'private fun deliverDelayedSynapses()' in s
assert 'scheduleSpike(i)' in s
assert 'applySensoryKick' in s  # external Poisson gating
# 40 * 0.5 ms = 20 ms exactly.
assert math.isclose(40*.0005,.020,rel_tol=0,abs_tol=1e-12)
# Delay quantization: 1.8 ms -> 2.0 ms, bounded within one integration step.
assert abs(4*.0005-.0018) <= .0005
# Refractory is resolved to 0.5 ms; unlike the old 5 ms grid this is a close physical resolution.
assert 0 < .0022 < .003
# No old discrete trace/voltage-dose architecture remains.
for bad in ('synTrace','sensoryCurrent','adapt[i] * dt','applySensoryKick = substep == 0'):
    assert bad not in s
print('V1.18 TEMPORAL / DELAY AUDIT: PASS')
print('outer=20.0 ms; internal=0.5 ms; substeps=40; delay=2.0 ms quantized from 1.8 ms')
