# Food response audit — olfactory saturation correction

## Scope
Only the food/olfactory sensor interface and its validation were changed. The frozen FBC103/FBD104 artifacts, MaleCNS source data, neuron selection, synaptic weights, and body motor readout were not modified.

## Confirmed issue
`MainActivity.kt` previously multiplied each antenna concentration by `FOOD_OLF_GAIN` and independently clamped each ORN current to `SENSORY_KICK_LIMIT`. With the documented initial concentration example (L=0.8881, R=0.8849), gain=6.0, both values exceeded 0.55 and were clamped to 0.55. The left/right concentration difference was therefore erased at the receptor-current interface.

## Fix
A new production helper `OlfactoryInputEncoder` compresses the common-mode component into the bounded current range while preserving the signed left/right difference whenever representable. Outputs are bounded to [0, 0.55]. Unknown-side ORNs receive the bounded bilateral mean. This changes only environmental sensor encoding; it does not write a motor command, alter graph topology, or change neuronal dynamics.

The UI's `OLF ORN L/C/R` diagnostics now report the actual encoded currents injected, not the pre-clamp raw concentration×gain values.

## Validation added
- JVM unit tests exercise the production Kotlin encoder for the previous saturated starting case, left/right reversal, equal inputs, and zero input.
- `tools/test_food_response_chain.py` verifies the production call wiring from olfactory injection through neural stepping, measured motor output, and physical movement, and checks that no direct `foodDirectionalBias` path was added to `driveBody`.
- GitHub Actions runs the chain-wiring check and `:app:testDebugUnitTest` before assembling the APK.

## Integration-test limitation
The automated chain test checks production wiring and stage observability; it does **not** launch Android or execute the full 16,669-neuron simulation against the connectome. Runtime localization remains available through the app's measured readouts: actual injected ORN current (OLF L/C/R), OLF firing, descending/DN and motor/MN firing, left/right motor drive, and physical speed/position. A true end-to-end dynamic integration run requires executing the APK (or a dedicated Android instrumentation harness) and capturing those values over time.

## Expected diagnostic interpretation
1. L/R injected currents identical despite asymmetric concentrations: encoder regression.
2. L/R current differs but OLF firing stays at baseline: sensory kick / threshold / ORN mapping stage.
3. OLF firing rises but central/DN activity does not: propagation through retained graph/dynamics.
4. DN/central activity rises but MN firing/drive does not: descending-to-VNC path or motor semantics/readout.
5. MN activity/drive rises but speed/position does not: body mechanics integration.

## Not changed
- FBC103 and FBD104 binary artifacts and provenance
- MaleCNS v1.0 source hashes
- ORN identity, lateral mapping, and retained populations
- Synaptic weights, thresholds, membrane dynamics, and motor control rules
