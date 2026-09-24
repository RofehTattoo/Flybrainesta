# FlyBrain — Senior update of uploaded source — 2026-09-23

This source tree started from the uploaded `Flybrainesta-main (2).zip` and was updated against the Phase 1 and Phase 2B audits already completed in this project.

## Integrated corrections

### PHASE 1 — sensory input purification
- Removed the historical synthetic 12-channel sensory pattern.
- Removed index-cyclic sensory projection (`(i * 17) % pattern.size`).
- Removed per-index sinusoidal sensory modulation.
- Removed the old broad population injection path for visual, gustatory and mechanosensory input.
- Visual input is restricted to the anatomically mapped retained photoreceptor population.
- Gustatory input is restricted to the anatomically mapped primary gustatory population.
- Mechanosensory input is restricted to the anatomically mapped mechanosensory/proprioceptive population.
- Olfactory food input uses the explicit MaleCNS-derived retained ORN map.
- Danger no longer broadcasts a remote-threat signal directly into mechanosensory receptors.
- Diagnostics remain read-only with respect to neural state and body state.

### PHASE 2B — temporal integration
- Public neural time step remains 20 ms.
- Internal neural integration is 4 × 5 ms substeps.
- Synaptic trace is evolved at 5 ms resolution.
- Membrane leak is integrated analytically; the 20 ms membrane retention remains exactly `exp(-1)` over a complete public frame.
- The external sensory kick is applied once per 20 ms frame, not once per substep.
- DN/MN spike diagnostics aggregate spikes from all four substeps.
- VNC motor output consumes the complete 20 ms frame's motor activity.
- Outer/homeostatic telemetry remains frame-level.
- The visual presentation memory was corrected after review: it is updated once per 20 ms frame, while `frameFired[]` latches any spike that occurred in the four internal substeps. This preserves the previous display semantics instead of accidentally shrinking the display time constant.

### Additional audited engineering corrections
- Runtime now computes SHA-256 of `malecns_reduced.bin` and compares it with `GeneratedConnectomeMeta.BINARY_SHA256` before parsing the FBC103 resource.
- The FBR-10 reducer now explicitly protects the four official MaleCNS ORNs whose published `type` is NULL: `242812`, `242908`, `488209`, `956041`. No synthetic type is assigned.
- The FBR-10 validation report now records and checks the retained untyped ORN bodyIds.
- CI source-structure checks cover the runtime FBC103 SHA guard and the Phase 2B frame spike latch.

## Important build/provenance note

The uploaded source snapshot does not contain the official MaleCNS Feather inputs and does not contain the generated CI assets (`sensory_input_map.tsv`, `olfactory_input_map.tsv`, `vnc_motor_semantics.tsv`). It contains the existing FBC103 binary from the uploaded version, whose SHA-256 is:

`bfadc30fd113c25f9711cce6ef8f6b80e9c139fe6d229965a4adabb94d8b4e60`

The four-ORN reducer correction necessarily changes the generated FBR-10 selection. Therefore the included historical FBC103 binary is **not claimed to be the newly regenerated post-fix graph**. The authoritative path is CI: it must rebuild FBR-10 from the official MaleCNS v1.0 files, then regenerate the sensory maps, VNC semantics and FBD104, and then compile the APK.

No fallback neurotransmitter sign was invented for unresolved FBD104 neurons. The previously audited unknown-NT edge omission remains explicitly unresolved and is not altered here.

ORN lateralization balance remains an open reducer optimization/audit item and has not been artificially rebalanced.

`behaviorLabel()`, `dangerLoom`, and the body kinematics remain engineering/readout abstractions and have not been falsely relabeled as proven emergent biological behavior.

## Validation performed in this source snapshot

Static/source audits were executed on this snapshot and the applicable audits passed. Android compilation itself is not claimed as locally verified because the uploaded snapshot has no Gradle wrapper and relies on CI Gradle setup plus CI-generated MaleCNS assets.
