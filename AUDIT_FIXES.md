# Senior master correction audit — 2026-09-24

This package is a corrected source release candidate based on the uploaded Flybrainesta audit snapshot.

## Structural reference

The current structural authority is now unambiguously:

- `FBR-10-OLF1`
- MaleCNS v1.0
- 16,669 neurons
- 2,483,165 FBC103 structural edges
- 20,992,679 contacts
- 264 real ORNs
- 53 ORN type labels / 54 published type+entryNerve combinations
- FBC103 SHA-256 `0044ab166af3439f2b86d4e6c5897481a1c3f28a58b6afb2c4f761489b276bbf`

The old FBR-10 v1.14 reducer/artifacts are preserved under `docs/history/fbr10-v1.14/` and are not release entry points.

## Corrections

1. **Canonical reducer**
   - `tools/build_connectome.py` is the only active structural reducer.
   - Its MaleCNS annotation and weight inputs are SHA-pinned.
   - The generated report records source hashes.
   - `RUN_FBR10.bat` now calls the canonical OLF1 builder and validator.
   - The old selector is preserved as historical source rather than presented as current.

2. **FBC103 parser**
   - Removed the obsolete hardcoded FBR-10 v1.14 SHA from the generic parser.
   - Release validators now provide the expected release SHA explicitly.

3. **Current validation**
   - `validate_frozen_connectome.py` was converted from an FBR-10 v1.14 gate into a current FBR-10-OLF1 gate.
   - `validate_fbr10.py` is now a compatibility entry point to the OLF1 validator.
   - `validate_generated_connectome.py` now validates the current 1.17.0 OLF1 artifact instead of the historical 1.13 contract.

4. **FBD104 provenance**
   - The dynamics builder now defaults to the current FBR-10-OLF1 structural SHA.
   - The generated dynamics report records `structural_release_id` and `source_structural_sha256`.
   - The validator checks the dynamics report against the current structural SHA when the report is present.
   - Runtime now rejects a dynamics layer with zero edges or more dynamic edges than the structural graph.

5. **Sensory numerical integrity**
   - Environmental sensory kicks are capped at `0.55`, the same order as the post-gain synaptic current limit. This prevents a raw environmental value such as the historical FOOD gain of 6.0 from numerically overwhelming the retained network.
   - Olfactory left/right antenna sampling remains a sensor-interface model and does not alter graph topology.
   - The previous double subtraction of adaptation for sensory neurons was removed. Adaptation is now integrated once in the neural step.

6. **Danger looming**
   - Removed the time-phase `sin(simTime * 2.2)` looming signal.
   - Looming is now derived from measured relative approach rate of the danger object. A static danger object no longer generates artificial periodic looming.

7. **Version/provenance cleanup**
   - Android label and runtime panel now identify `FBR-10-OLF1`.
   - `GeneratedConnectomeMeta.kt` now carries pinned MaleCNS source hashes and FBD104 normalization metadata.
   - Current README/specification describe the actual executable OLF1 selector instead of the historical FBR-10 selector.

8. **Build/CI structure**
   - Added a reproducible Android application Gradle module definition.
   - Added a CI workflow that verifies pinned MaleCNS inputs, rebuilds FBR-10-OLF1, regenerates maps/FBD104, validates provenance, and assembles the APK.

## Deliberately not changed

The current FBC103 binary was not regenerated or mutated during this source cleanup. Its verified SHA remains `0044ab...`.

No synthetic neurons, graph edges, direct food-to-motor command, random walk, or behaviour shortcut was added.

## Remaining release prerequisite

The uploaded audit ZIP did not contain the generated FBD104 binary, runtime map assets, Gradle wrapper, or the original `app/build.gradle.kts` from the user's live checkout. This package reconstructs the missing application Gradle module and CI source, but **does not fabricate the missing FBD104/map binaries**.

Before calling an APK fully release-ready, run the canonical CI/build pipeline against the pinned MaleCNS v1.0 source bundle and verify:

`FBC103 SHA -> FBD104 source_structural_sha256 -> runtime assets -> APK`.

That final binary-level provenance check cannot honestly be claimed from the uploaded ZIP alone.
