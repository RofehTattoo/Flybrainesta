# Sensory sample-hold correction — FlyBrain 1.18.0

## Observed failure

The Android runtime showed strong olfactory activity with food enabled, but the descending-neuron readout remained at 0% and physical velocity remained 0.0000. The previous integration path injected each 20 ms environmental sensory sample only during the first 5 ms internal substep.

That made the sensory interface behave like a narrow impulse rather than a sample-and-hold environmental signal. The downstream circuit therefore had only one 5 ms opportunity per 20 ms frame to integrate the odor input.

## Correction

The environmental sensory sample is now held for all four 5 ms substeps of the 20 ms public neural frame. The calibrated voltage dose is divided by four before each substep, so the total injected ΔV per public frame is preserved rather than quadrupled.

This correction changes only temporal delivery of the already-computed sensory input. It does not modify FBC103, FBD105, the retained edges, ORN selection, motor semantics, or `approachAction`.

## Expected causal effect

With food enabled, ORN firing should become temporally sustained enough to recruit retained olfactory central neurons, forward/approach-related descending neurons and, downstream, VNC motor neurons through their real retained connections. The body still moves only from measured VNC motor activity in `driveBody()`.

## Validation

`tools/test_sensory_sample_hold.py` asserts the sample-hold path and rejects the former first-substep-only implementation. Existing connectome, sensory-purity, temporal, olfactory, motor-semantics and food-chain tests remain required in CI.
