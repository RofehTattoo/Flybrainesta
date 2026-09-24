# FlyBrain V1.17.0 / FBR-10-OLF1 — Senior Audit

## Scope
Audited repository: `Flybrainesta.zip` supplied in this conversation.
Focus: FBR-10-OLF1 reducer, FBC103/FBD104 binary contract, ORN census, VNC semantic reader, CI ordering, and static/runtime validation.

## Root cause of the reported CI failure

The failure:

`RuntimeError: invalid structural edge`

in `tools/build_fbr10_dynamics.py` is caused by a producer/consumer contract violation in the current `tools/build_connectome.py`.

The current structural builder was:
1. looking up neurotransmitter sign while constructing FBC103;
2. omitting structural edges whose transmitter sign was unknown/modulatory;
3. writing signed edge weights (`raw_weight * sign`);
4. normalizing those weights by postsynaptic total and multiplying by `0.42`.

FBC103 is supposed to be the **structural** layer. Its edges must be the strict induced MaleCNS subgraph with the **raw positive contact counts**. FBD104 is the separate layer that applies neurotransmitter sign and the `0.42` normalization.

Therefore the current builder could emit negative FBC103 weights, which FBD104 correctly rejects. Even if an edge were positive, pre-normalizing it in FBC103 would make FBD104 normalize it a second time.

## Additional structural defect found

The current builder also deleted unknown/modulatory-transmitter edges from FBC103. This contradicts the project's own V1.15.0 integrity contract: unknown/modulatory edges are retained structurally and omitted only from the fast-current FBD104 layer.

## FBC103 reader defect

`tools/fbc103_reader.py` documented and parsed the 26-byte node record incorrectly.

Actual emitted/runtime layout:
- uint64 bodyId
- uint8 superclassCode
- int8 somaSideCode
- uint8 channelCode
- int8 motorRoleCode
- int8 descendingRoleCode
- int8 haltRoleCode
- float32 routeForward
- float32 routeTurn
- float32 routeEscape

The old reader instead treated the superclass byte as `channel`, inserted a nonexistent `reserved` byte, and exposed the motor-role byte as generic `role`.

This made the VNC semantic audit read the wrong byte when reporting `currentFBC103Role`.

The binary layout itself was not changed; the reader and its VNC consumers were corrected to match the already-emitted layout.

## ORN census

The ORN correction is internally consistent:
- source ORNs: 2,639
- retained ORNs: 264
- unique non-null `type` labels: 53
- distinct `(type, entryNerve)` combinations: 54
- `ORN_VA7l` is the duplicated type label across AN and MxLbN
- four official untyped ORNs are handled by bodyId without inventing a type.

The four official untyped bodyIds are:
`242812, 242908, 488209, 956041`.

## Validation hardening

The corrected audit:
- validates FBC103 edge endpoints;
- rejects non-finite, non-positive, or non-integer structural weights;
- rejects duplicate structural edges;
- verifies the report's structural edge count and raw contact total;
- moves FBR-10 structural validation before FBD104 generation in CI;
- improves the FBD104 structural-edge error to report edge index/source/target/weight;
- fixes the FBD104 report's `nt_nonempty_rows` counter so it counts actual non-empty rows rather than unique transmitter labels per body;
- corrects the bootstrap metadata from 54 unique ORN labels to 53 labels + 54 type/entryNerve pairs;
- corrects the V1.17 audit wording.

## Documentation correction

The current `build_connectome.py` contained the typo `166,691` for the audited neuron universe. The repository's audited universe is 166,700 neurons. The builder description also overstated the selector as simply choosing highest-connectivity cells per superclass; the active algorithm uses measured connectivity plus route-support preservation quotas and other selection logic.

## Tests executed

Passed in the corrected staging tree:
- Python `compileall`
- `tools/test_fbc103_reader.py`
- `tools/test_olfactory_route_reduction.py`
- `tools/test_olfactory_input_map.py`
- `tools/test_food_function_audit.py`
- `tools/test_frozen_connectome_validator.py`
- `tools/test_vnc_motor_semantics_logic.py`
- `tools/test_neural_time_integration.py`
- YAML parse of `.github/workflows/build-apk.yml`

The current frozen FBC103 bootstrap was also checked directly:
- 16,669 nodes
- 2,064,951 edges
- all structural weights finite, positive, and integer-valued
- no duplicate directed edges
- weight range 1..2591

The full MaleCNS rebuild and Android/Gradle build were not executed locally because the supplied repository does not contain the 1.1 GB official source Feather files and this runtime has no network access for installing the missing `pyarrow` dependency.

## Corrected files

The supplied corrected snapshot contains only the files changed by this audit.
