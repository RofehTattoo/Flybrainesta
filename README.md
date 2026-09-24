# FlyBrain — V1.17.0 / FBR-10-OLF1

FlyBrain is an Android simulation built from the published MaleCNS v1.0 connectome of the male *Drosophila* central nervous system.

## Current structural release candidate

- MaleCNS v1.0
- 166,700 source neurons
- 16,669 retained neurons
- 2,483,165 structural FBC103 edges
- 20,992,679 retained contacts
- 264 retained real ORNs
- 53 ORN type labels / 54 published `(type, entryNerve)` combinations
- FBC103 SHA-256:
  `0044ab166af3439f2b86d4e6c5897481a1c3f28a58b6afb2c4f761489b276bbf`

The historical FBR-10 v1.14 artifact (2,064,951 edges, SHA `bfadc30f...`) is preserved under `docs/history/fbr10-v1.14/` and is not the current structural release.

## Scientific constraints

The project deliberately does **not**:

- add synthetic neurons;
- add synthetic connectome edges;
- create direct FOOD→motor/turn/speed commands;
- replace missing graph structure with random walk behaviour;
- use diagnostic labels as motor commands;
- silently substitute an artificial graph when a packaged artifact fails validation.

The structural graph is the strict induced subgraph of published MaleCNS edges.

## Sensory interface

External stimuli are mapped only to receptor populations derived from official MaleCNS annotations.

- VIS → retained photoreceptors.
- OLF → retained real ORNs.
- GUST → retained primary gustatory receptors.
- MECH → retained mechanosensory/proprioceptive receptors.

Sensory input is an environmental interface, not a graph mutation. The current olfactory interface models two virtual antenna sampling points and applies the measured left/right concentration to anatomically sided retained ORNs. MaleCNS v1.0 does not provide an odorant-specific food/receptor affinity table in this release, so the simulator does not invent one.

All single-receptor environmental kicks are capped at the same order as the post-gain synaptic-current limit (`0.55`) to prevent the environmental interface from numerically overwhelming the retained network.

## Neural integration

- Public neural frame: 20 ms.
- Internal integration: four 5 ms substeps.
- Synaptic trace: exact exponential decay with `tau_syn = 5 ms`.
- Membrane leak: analytical exponential integration with `tau_mem = 20 ms`.
- Refractory period: 2.2 ms, represented on the 5 ms internal grid.
- Plasticity: disabled by default.

## Motor/body boundary

Body movement is driven from measured retained VNC motor activity. The body mechanics are an engineering readout layer; they are not claimed to be a full biomechanical model of *Drosophila*.

Behaviour labels and action scores are diagnostics/readouts. They do not write direct sensory commands into body position.

## Build provenance

Pinned MaleCNS v1.0 objects:

- annotations SHA-256:
  `2177e246113e4cfbf1e7772ec37c6da1955ff22e8063d0b1f833101f99a9a3b2`
- neurotransmitters SHA-256:
  `95c9289220663abeb3409f3ad9e5a7f8a53f8093f5139d15502cd08da8879621`
- connectome weights SHA-256:
  `e35da783d1c686b2b58b3b87cd6a403ae43bfcfba8bff28e08ef752c1a56afc1`

The canonical structural builder is:

`tools/build_connectome.py`

Historical reducers are not release entry points.

## Validation

Run:

```text
py tools/test_test_fbc103_reader.py
py tools/test_test_food_function_audit.py
py tools/test_test_frozen_connectome_validator.py
py tools/test_test_neural_time_integration.py
py tools/test_test_olfactory_input_map.py
py tools/test_test_olfactory_route_reduction.py
py tools/test_test_phase2b_temporal_substepping.py
py tools/test_test_sensory_input_purity.py
py tools/test_test_vnc_motor_semantics_logic.py
```

Then:

```text
py tools/validate_validate_fbr10_olf1.py --fbc103 app/src/main/res/raw/malecns_reduced.bin --report app/src/main/res/raw/malecns_reduced_report.json
py tools/validate_validate_generated_connectome.py
```

FBD104 validation requires the generated `malecns_fbr10_dynamics.bin` and its build report.

## Historical documentation

Previous V1.12–V1.16 audits and changelogs are preserved under `docs/history/`. They describe historical states and are not current runtime specifications.
