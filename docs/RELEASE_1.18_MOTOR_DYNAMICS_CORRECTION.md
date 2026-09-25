# FlyBrain 1.18.3 — motor recruitment, route preservation and reference LIF correction

This release addresses the observed condition in which food input produced ORN activity and sparse motor spikes but negligible locomotor output.

## Changes

- FBD105 now uses raw retained MaleCNS contact counts multiplied by the published Shiu et al. `W_syn = 0.275 mV` instead of per-target normalization.
- Synaptic signs now include acetylcholine/dopamine/octopamine/serotonin as excitatory and GABA/glutamate/histamine as inhibitory, matching the published whole-brain model convention.
- The Android LIF engine uses 0.5 ms internal integration, 20 ms outer frames, 2 ms quantized delay for the published 1.8 ms delay, 5 ms synaptic decay, 20 ms membrane time constant and 2.2 ms refractory period.
- Incoming synaptic events are delivered during refractory; `unless refractory` freezes differential-equation integration, not `on_pre` event delivery.
- FBR-10 route selection and runtime VNC semantics use the same official motor class/subclass vocabulary.
- Body translation remains driven only by measured VNC leg/wing motor output. `approachAction` is not a kinematic command.

## Scientific boundary

The environmental interface (food/olfaction/light/taste) remains a defined engineering transducer into neural input events; it is not allowed to write motor state or bypass the connectome. The neural graph, edge weights, neurotransmitter sign convention and LIF dynamics are kept separate so that the sensor boundary can be audited independently from the connectome simulation.

## Validation

CI must rebuild FBC103/FBD105 from the pinned MaleCNS v1.0 Feather sources before APK assembly. The container used for this handoff does not contain Gradle or the large official Feather sources, so the final APK must be produced by the repository workflow.

## Food-pathway reduction correction

The reducer now protects two separate measured structural route families needed for food-directed locomotion:

1. `ORN -> central candidate -> retained DN`, using the complete retained DN population rather than a hand-labelled subset of "forward" DNs.
2. `retained DN -> VNC premotor candidate -> LEG motor neuron`, using the complete retained DN population and the authoritative leg-MN class.

These protections only select real neurons already present in MaleCNS v1.0; they do not add edges or currents. Zero-score cells do not consume these dedicated route quotas.

## Neurotransmitter resolution correction

FBD105 resolves `consensus_nt` first. When the published consensus is `unclear`, missing, or not one of the explicitly mapped transmitter classes, the builder uses the official `predicted_nt` value as a documented fallback. If that fallback is also unresolved, the edge remains outside the fast signed dynamics layer. No sign is invented.


## 2026-09-25 video follow-up

The first APK tested after the temporal sample-hold fix was V1.17.1/FBR-10-OLF1, not the current V1.18.3 source baseline. The recorded run showed measurable sensory/DN/MN activity but essentially no leg recruitment or physical displacement. V1.18.3 therefore addresses both the reference LIF state semantics and the FBR-10 motor-route preservation problem.


## V1.18.3 motor-actuator hardening

The physical actuator now uses a 40 ms first-order activation state driven only by the measured VNC motor-neuron spike-rate output. This prevents the body layer from treating a sparse single 20 ms spike window as an instantaneous force discontinuity. It does not read `approachAction`, food concentration, sensory bias, or any other stimulus variable.

A new release test parses generated FBD105 data and requires actual signed dynamic connectivity from retained olfactory neurons toward the descending population and from descending neurons toward at least one LEG motor neuron, including an excitatory signed input to a LEG motor neuron.

Most importantly, the recorded V1.17.1 run is now understood as a test of the older FBD104 dynamics artifact; it is not a runtime validation of the current V1.18.3 source.
