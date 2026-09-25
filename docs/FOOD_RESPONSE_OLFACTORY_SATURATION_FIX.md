# Food response audit — olfactory field and bilateral calibration

## Scope
Only the environmental olfactory sensor interface and its unit tests changed. FBR-10-OLF2-MOTORROUTE/FBC103/FBD105 artifacts, MaleCNS source data, neuron selection, synaptic weights, neural thresholds, and motor readout were not modified.

## Changes
- Moved the antenna sampling geometry and Gaussian field into `OlfactorySensorModel`, a pure sensor-interface model with no motor/body access.
- Reduced the Gaussian sigma from 1.10 to 0.30 normalized scene units. The former field was so broad that concentration stayed high over most of the arena; the new local field has a measurable distance falloff.
- Increased virtual antenna half-spacing from 0.018 to 0.035 scene units while preserving the existing body-relative coordinate convention. This increases spatial sampling baseline; it does not directly turn the fly.
- Kept receptor kick gain and the bounded bilateral encoder separate from environmental odor presence.
- Changed `foodDrive` to the mean raw antenna concentration (0..1), rather than concentration multiplied by neural gain and clamped. Thus presence/strength does not saturate simply because receptor gain is high.
- Unknown-side ORNs receive the encoder's common-mode value, not a separately hard-clamped mean.
- The normalized signed contrast remains diagnostic only; no `foodDirectionalBias` path was added to body mechanics.

## Validation
Kotlin unit tests cover local field falloff, symmetric ahead stimulus, reversal of bilateral contrast when the source moves across the midline, bounded encoder outputs, saturation behavior, and zero input. The existing chain-wiring test continues to ensure the sensory interface does not write motor state.

## Interpretation and limitations
This is a calibrated phenomenological odor field, not a CFD plume or a measured Drosophila antennal transfer function. The scene coordinates are normalized, so sigma and antenna spacing are model-space values. The correction restores spatial information at the sensor interface; it does not guarantee that the reduced connectome has a functional downstream steering pathway. Runtime diagnostics must still establish ORN firing -> central/descending activity -> motor-neuron drive -> physical movement.
