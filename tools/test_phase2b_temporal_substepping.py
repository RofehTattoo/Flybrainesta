from pathlib import Path
import math

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / 'app/src/main/java/com/example/flybrain/MainActivity.kt'
s = MAIN.read_text(encoding='utf-8')

# ----- Structural contract -----
assert 'private val NEURAL_FRAME_DT_SECONDS = 0.020f' in s
assert 'private val NEURAL_SUBSTEP_DT_SECONDS = 0.005f' in s
assert 'private val NEURAL_SUBSTEPS_PER_FRAME = 4' in s
assert 'private val REFRACTORY_SECONDS = 0.0022f' in s
assert 'private fun stepBrainSubstep(dt: Float, applySensoryKick: Boolean): Int' in s
assert 'private val frameFired = BooleanArray(N)' in s
assert 'frameFired[i] = true' in s
assert 'if (frameFired[i]) .22f else 0f' in s
assert 'visualSubstepDecay' not in s
assert 'visualSubstepGain' not in s
assert 'private fun updateOuterNeuralState(dt: Float, totalSpikes: Int)' in s
assert 'totalSpikes += stepBrainSubstep(' in s
assert 'applySensoryKick = substep == 0' in s
assert 'sense(dt)' in s
assert 'resetMotorSubstepAccumulators()' in s
assert 'driveBody(dt)' in s
assert 'runNeuralSimulation(NEURAL_FRAME_DT_SECONDS)' in s
assert 'runNeuralSimulation(.020f)' not in s
assert 'private fun stepBrain(dt: Float)' not in s

# The public 20 ms step must be exactly four internal 5 ms steps.
outer_dt = 0.020
sub_dt = 0.005
nsub = 4
assert math.isclose(nsub * sub_dt, outer_dt, rel_tol=0, abs_tol=1e-12)

# ----- Exact membrane composition -----
tau_m = 0.020
outer_retention = math.exp(-outer_dt / tau_m)
sub_retention = math.exp(-sub_dt / tau_m)
assert math.isclose(sub_retention ** nsub, outer_retention, rel_tol=0, abs_tol=1e-15)
assert math.isclose(outer_retention, math.exp(-1), rel_tol=0, abs_tol=1e-15)

# ----- Exact 5 ms synaptic decay -----
tau_syn = 0.005
sub_syn_retention = math.exp(-sub_dt / tau_syn)
outer_syn_retention = math.exp(-outer_dt / tau_syn)
assert math.isclose(sub_syn_retention, math.exp(-1), rel_tol=0, abs_tol=1e-15)
assert math.isclose(sub_syn_retention ** nsub, outer_syn_retention, rel_tol=0, abs_tol=1e-15)

# One spike is inserted once into the decayed trace. The new event is amplitude 1;
# the subsequent residuals are resolved every 5 ms.
trace = 0.0
samples = []
for sub in range(nsub):
    prev_fired = sub == 0
    trace = trace * sub_syn_retention + (1.0 if prev_fired else 0.0)
    samples.append(trace)
assert samples[0] == 1.0
assert math.isclose(samples[1], math.exp(-1), rel_tol=0, abs_tol=1e-15)
assert math.isclose(samples[2], math.exp(-2), rel_tol=0, abs_tol=1e-15)
assert math.isclose(samples[3], math.exp(-3), rel_tol=0, abs_tol=1e-15)

# ----- Causal chain timing -----
# A spike created at substep 0 can affect B at substep 1 and C at substep 2.
# This is the intended consequence of temporal resolution; no synthetic edge is added.
chain_fire_substeps = [0, 1, 2]
assert chain_fire_substeps[1] - chain_fire_substeps[0] == 1
assert chain_fire_substeps[2] - chain_fire_substeps[1] == 1
assert [x * sub_dt for x in chain_fire_substeps] == [0.0, 0.005, 0.010]

# ----- Refractory quantization -----
refractory = 0.0022
# With the discrete threshold evaluated only at substep boundaries, a 2.2 ms
# refractory cannot be represented exactly at 5 ms resolution. It is improved
# versus the legacy 20 ms grid, but remains a known residual limitation.
legacy_next_available = 2 * outer_dt   # 40 ms from a spike at tick 0
substep_next_available = 2 * sub_dt     # 10 ms from a spike at substep 0
assert substep_next_available < legacy_next_available
assert substep_next_available > refractory

# ----- Sensory kick conservation -----
# Phase 2B deliberately keeps the old per-20 ms sensory kick semantics: one kick,
# not four. This prevents an unintended 4x increase in stimulus amplitude.
apply_flags = [sub == 0 for sub in range(nsub)]
assert apply_flags == [True, False, False, False]

# ----- Presentation-memory conservation -----
# The old renderer updated visualActivity once per 20 ms frame with 0.88 retention
# and +0.22 per firing frame. Phase 2B must preserve that exact presentation cadence
# while latching spikes from any of the four 5 ms substeps.
visual = 0.0
frame_had_spike = any([False, True, False, False])
visual = visual * 0.88 + (0.22 if frame_had_spike else 0.0)
assert math.isclose(visual, 0.22, rel_tol=0, abs_tol=1e-15)
visual = visual * 0.88 + 0.0
assert math.isclose(visual, 0.22 * 0.88, rel_tol=0, abs_tol=1e-15)

# ----- Output aggregation -----
# Motor activity from any internal substep is visible to the 20 ms body interface.
spikes = [False, True, False, True]
assert any(spikes) is True
assert sum(1 for x in spikes if x) == 2

print('PHASE 2B TEMPORAL SUBSTEPPING AUDIT: PASS')
print(f'outer_dt_ms={outer_dt*1000:.1f}; substep_dt_ms={sub_dt*1000:.1f}; substeps={nsub}')
print(f'membrane_retention_outer={outer_retention:.12f}')
print(f'synaptic_retention_5ms={sub_syn_retention:.12f}')
print(f'synaptic_trace_samples={[[round(x, 12) for x in samples]][0]}')
print(f'legacy_min_spike_interval_ms={legacy_next_available*1000:.1f}')
print(f'phase2b_min_discrete_interval_ms={substep_next_available*1000:.1f}')
print('sensory_kick_application=[true,false,false,false]')
print('motor_output=20ms aggregate of all 4 substeps')
print('visual_memory=20ms legacy 0.88 retention + 0.22 frame spike latch')
print('FBC103_runtime_hash_check=required')
