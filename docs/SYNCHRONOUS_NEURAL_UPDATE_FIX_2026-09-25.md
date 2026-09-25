# Synchronous neural-state update fix — 2026-09-25

## Critical defect

`stepBrainSubstep()` previously updated `synTrace[i]` and then immediately evaluated neuron `i` in the same loop. Since each target sums `synTrace[source]`, a target could observe a source trace already advanced for the current substep when that source had a lower array index, but observe the previous trace when the source had a higher index. This made neural propagation depend on array ordering rather than only on the network state.

## Correction

The substep is now explicitly two-phase:

1. Snapshot `prevFired`, then update every synaptic trace from that prior-substep spike state.
2. Integrate every neuron's membrane using the completed synaptic-trace snapshot.

This removes the unintended within-substep index-order bias. It does not add motor current, alter edge weights/topology, connect `approachAction` to body coordinates, or synthesize locomotion. VNC motor neurons still need to be recruited by the retained neural network.

## Validation

`tools/test_synchronous_neural_update.py` asserts that the full synaptic-trace pass occurs before membrane integration and that the target loop reads the completed traces. Existing connectome, sensory-purity, temporal-integration, motor-semantics, olfactory and food-chain static tests are also run in CI.

The static tests do not substitute for an Android runtime experiment. Confirm the effect in the APK by comparing baseline and food-on activity through ORNs, central neurons, descending neurons, VNC motor neurons, and physical speed.
