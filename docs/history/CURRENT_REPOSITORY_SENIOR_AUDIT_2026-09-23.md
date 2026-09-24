# FlyBrain — Current Repository Senior Audit
## 2026-09-23

### Audited input
`Flybrainesta.zip` uploaded by the user.

### Executive result
The source tree now contains the audited PHASE 1 sensory-purification and PHASE 2B temporal-correction changes, plus the previously identified four-untyped-ORN selector correction and the audited 166,700-neuron census metadata correction.

No synthetic sensory-to-motor shortcut was reintroduced.

### PASS — corrections verified in source

- PHASE 1 removes the historical 12-channel/index-cyclic sensory injection.
- Visual, gustatory and mechanosensory external input is restricted to mapped anatomical receptor populations.
- Food olfactory input uses `olfactoryNeuronIndices` from the MaleCNS-derived olfactory map.
- Remote danger no longer broadcasts directly to the mechanosensory population.
- The runtime validates FBC103 SHA-256 before parsing the binary.
- PHASE 2B uses a public 20 ms neural frame with 4 × 5 ms internal substeps.
- The sensory kick is applied only on substep 0, preventing a ×4 stimulus-amplitude error.
- DN/MN diagnostic counts aggregate all four internal substeps.
- Motor activity is aggregated across the full 20 ms frame before `driveBody()`.
- Visual presentation memory is updated once per public frame using `frameFired[]`.
- Membrane leak remains analytically integrated.
- The reducer explicitly retains the four official NULL-type ORNs: `242812`, `242908`, `488209`, `956041`, without inventing type labels.
- The audited MaleCNS source census is corrected to 166,700 in the reducer documentation/metadata.
- Static tests for the audited Phase 1/2B invariants pass.

### Frozen binary integrity

- FBC103 SHA-256: `bfadc30fd113c25f9711cce6ef8f6b80e9c139fe6d229965a4adabb94d8b4e60`
- Nodes: `16669`
- Edges: `2064951`
- Binary bytes: `25212822`
- `GeneratedConnectomeMeta.BINARY_SHA256` matches the embedded binary.
- The embedded binary is the historical bootstrap FBC103 and has **not** been regenerated after the four-untyped-ORN selector fix.

That last point is intentional: the official MaleCNS v1.0 Feather inputs are not present in this uploaded repository, so regenerating the graph locally would require inventing or guessing source data. The CI workflow is the authoritative regeneration path.

### Expected CI-generated files currently absent from the source ZIP

These are intentionally generated during CI from the official MaleCNS v1.0 inputs:

- `app/src/main/assets/sensory_input_map.tsv`
- `app/src/main/assets/olfactory_input_map.tsv`
- `app/src/main/assets/vnc_motor_semantics.tsv`
- `app/src/main/res/raw/malecns_fbr10_dynamics.bin`
- corresponding generated reports

Therefore the source ZIP is **not an APK-ready generated release artifact by itself**. The workflow creates these before compilation.

### Still intentionally open — not silently "fixed"

These were previously audited but are not solved by PHASE 1/2B and must remain separate future work:

1. FBD104 unresolved-neurotransmitter edge omission.
2. FBR-10 causal sensory→DN route preservation beyond the current selector.
3. ORN lateralization optimization/audit.
4. Exact 2.2 ms refractory representation under a 5 ms substep grid.
5. Dimensional calibration of external sensory current versus synaptic current.
6. Separation of engineered behavioral readouts from proven emergent behavior.
7. `dangerLoom` as an engineering environmental/readout abstraction.
8. Full Android compile/device validation.
9. Final regeneration of FBC103/FBD104/maps from the official MaleCNS v1.0 inputs.

No fallback neurotransmitter sign or synthetic connection has been introduced to mask any of these open items.

### Validation executed

The following returned exit code 0:

- `test_fbc103_reader.py`
- `test_food_function_audit.py`
- `test_frozen_connectome_validator.py`
- `test_neural_time_integration.py`
- `test_olfactory_input_map.py`
- `test_olfactory_route_reduction.py`
- `test_phase2b_temporal_substepping.py`
- `test_sensory_input_purity.py`
- `test_vnc_motor_semantics_logic.py`

Python syntax compilation also passed for the modified audit/build scripts.

The Android build was not claimed as locally verified because the uploaded repository has no Gradle wrapper and the generated MaleCNS assets are CI inputs.

### Release conclusion

This package is suitable as the corrected **source baseline for continuing the next audit phase**. It should not be treated as the final regenerated FBR-10-OLF1 release until CI has regenerated the graph and all dependent assets from the official MaleCNS v1.0 files.
