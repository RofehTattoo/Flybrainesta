from pathlib import Path
import math

ROOT=Path(__file__).resolve().parents[1]
MAIN=(ROOT/"app/src/main/java/com/example/flybrain/MainActivity.kt").read_text(encoding="utf-8")
DYN=(ROOT/"tools/build_fbr10_dynamics.py").read_text(encoding="utf-8")

assert "TAU_MEMBRANE_SECONDS = 0.020f" in MAIN
assert "TAU_SYNAPSE_SECONDS = 0.005f" in MAIN
assert "NEURAL_SUBSTEP_DT_SECONDS = 0.0005f" in MAIN
assert "NEURAL_SUBSTEPS_PER_FRAME = 40" in MAIN
assert "SYNAPTIC_DELAY_SECONDS = 0.0018f" in MAIN
assert "private val SYNAPTIC_DELAY_STEPS = 4" in MAIN
assert "private val synConductance = FloatArray(N)" in MAIN
assert "private fun deliverDelayedSynapses()" in MAIN
assert "v[i] = V_REST + x0 * a + g0 * coupling * (a - b)" in MAIN
assert "synConductance[i] = g0 * b" in MAIN
# Reference semantics: a spike resets both v and the neuron's synaptic state g;
# during refractory the membrane/synaptic state is held until the gate opens again.
step_start = MAIN.index("private fun stepBrainSubstep")
step_end = MAIN.index("private fun resetMotorSubstepAccumulators", step_start)
step_logic = MAIN[step_start:step_end]
refractory_block_start = step_logic.index("if (refractory[i] > 0f)")
refractory_block_end = step_logic.index("continue", refractory_block_start) + len("continue")
refractory_block = step_logic[refractory_block_start:refractory_block_end]
assert "synConductance[i] = g0 * b" not in refractory_block
assert "refractory[i] = (refractory[i] - dt).coerceAtLeast(0f)" in refractory_block
spike_block_start = step_logic.index("if (v[i] > V_THRESHOLD)")
spike_block_end = step_logic.index("pendingSpikeCursor =", spike_block_start)
spike_block = step_logic[spike_block_start:spike_block_end]
assert "synConductance[i] = 0f" in spike_block
assert "synTrace" not in MAIN and "sensoryCurrent" not in MAIN and "adapt[i]" not in MAIN
assert "W_SYN_FULL_MV = 0.275" in DYN
assert "W_SYN_REDUCED_MV = W_SYN_FULL_MV" in DYN
for nt in ("dopamine", "octopamine", "serotonin", "histamine"):
    assert f'"{nt}"' in DYN

# Exact analytical LIF step for the published alpha synapse equation.
dt=.0005; tau_m=.020; tau_s=.005
a=math.exp(-dt/tau_m); b=math.exp(-dt/tau_s); c=tau_s/(tau_m-tau_s)
vrest=-52.; v=-52.; g=.275*3
v2=vrest+(v-vrest)*a+g*c*(a-b)
assert v2>v and v2 < vrest+g
assert abs(40*dt-.020)<1e-12
assert abs(4*dt-.0018)<=dt
print("REFERENCE LIF / SYNAPTIC DYNAMICS AUDIT: PASS")
print(f"dt={dt*1000:.3f} ms; delay={4*dt*1000:.3f} ms; Wsyn=0.275 mV")

# Neurotransmitter resolution: consensus first, official predicted_nt only as fallback.
import ast as _ast
_mod = _ast.parse(DYN)
_keep = []
for _node in _mod.body:
    if isinstance(_node, (_ast.FunctionDef, _ast.ClassDef)) and _node.name in {"resolve_nt_label", "_is_known_nt"}:
        _keep.append(_node)
    elif isinstance(_node, _ast.Assign) and any(isinstance(t, _ast.Name) and t.id == "NT_SIGN" for t in _node.targets):
        _keep.append(_node)
_ns = {}
exec(compile(_ast.Module(body=_keep, type_ignores=[]), "<nt_helpers>", "exec"), _ns)
assert _ns["resolve_nt_label"]("acetylcholine", "gaba") == ("acetylcholine", "consensus")
assert _ns["resolve_nt_label"]("unclear", "gaba") == ("gaba", "predicted_fallback")
assert _ns["resolve_nt_label"]("", "glutamate") == ("glutamate", "predicted_fallback")
assert _ns["resolve_nt_label"]("unclear", "unclear")[0] is None
print("NT CONSENSUS/PREDICTED FALLBACK AUDIT: PASS")
