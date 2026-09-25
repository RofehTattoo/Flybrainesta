# LIF membrane audit — 2026-09-25

## Scope
This correction clarifies and tests the existing membrane integration without changing the frozen FBR-10-OLF1 connectome, the sensory gain, threshold, or behavioral pathways.

## Model actually implemented
- Resting potential: `V_REST = -0.72` (normalized model units).
- Threshold: `V_THRESHOLD = -0.50`; rest-to-threshold gap is `0.22`.
- Membrane time constant: `TAU_MEMBRANE_SECONDS = 0.020`.
- Outer neural frame: 20 ms; four internal substeps of 5 ms.
- Leak: exact exponential decay, `exp(-dt / tau_m)`, not explicit Euler.
- Environmental sensory input: a bounded normalized voltage increment (ΔV), capped at `0.55`, injected once at the first substep of each 20 ms frame. It is not a physical current in amperes.
- Synaptic drive: normalized voltage contribution evaluated at each 5 ms substep.

## Numerical checks
For a 5 ms substep, passive deviation retention is `exp(-0.25) = 0.7788008`. Across four substeps, retention is `exp(-1) = 0.3678794`. The passive leak is stable and does not reset the membrane to rest.

At rest, the threshold gap is `0.22`. Therefore a single sensory kick at or above `0.22` can reach threshold immediately in the absence of other terms; the configured cap `0.55` is above that gap. For repeated subthreshold kicks every 20 ms, the ideal passive steady-state threshold is approximately `0.1392` per frame. This is a mathematical property of this normalized model, not a biological calibration or guarantee in the full network (adaptation, synapses, refractory state, and reset also matter).

## Interpretation and limits
The legacy variable name `sensoryCurrent` is retained to avoid a broad, risky refactor, but its values are dimensionless normalized ΔV kicks. They must not be interpreted as SI current. Converting the model to physical current would require explicit membrane resistance/capacitance or a documented normalization and recalibration of all sensory and synaptic inputs. No such conversion is made here.

## Validation
`tools/test_neural_time_integration.py` now checks the source-level integration contract and the rest-to-threshold / repeated-kick numerical bounds. `tools/test_phase2b_temporal_substepping.py` checks that a 20 ms frame contains exactly four 5 ms substeps and that the environmental kick is applied once.

## Biological caveat
These checks validate numerical consistency, not the biological fidelity of the complete fly. Fidelity still requires runtime measurements of ORN membrane trajectories, spike counts, propagation through the retained graph, and behavior under controlled stimulus protocols.
