# FBR-10 — Functional Balanced Reduction

## Purpose

FBR-10 is the definitive MaleCNS reduction strategy for the next FlyBrain phase.
It reduces the 166,700-neuron MaleCNS v1.0 neuron census to **exactly 16,669 neurons**.

The project uses **philosophy A**:

> Maximize neuron-type diversity within the fixed neuron budget, but never force a 10%-of-types rule when that conflicts with functional architecture or connectivity.

The reducer is structural. It does not program behaviour and does not create synthetic connections.

## Priority hierarchy

1. Hard neuron budget: `|S| = 16,669`.
2. Preserve the complete descending-neuron and VNC-motor populations as command/output anchors.
3. Preserve measured sensorimotor bridge structure.
4. Preserve real graph connectivity and high-value boundaries between selected/unselected populations.
5. Preserve lateralization.
6. Maximize type diversity as a soft objective.
7. Use degree/connectivity as a secondary structural criterion.

## Objective proxy

For a candidate neuron `i`, the selector uses a normalized marginal score:

`J_i = 0.30 E_i + 0.28 R_i + 0.17 A_i + 0.10 T_i + 0.08 L_i + 0.07 D_i`

where:

- `E`: connectivity boundary gain toward already selected neurons.
- `R`: measured sensor→DN and DN→motor bridge score.
- `A`: DN/motor architecture score.
- `T`: concave type novelty bonus.
- `L`: lateralization availability.
- `D`: degree/connectivity score.

These weights are explicit engineering hyperparameters. They are not presented as biological constants.

## Type policy

There are 11,751 published neuron types in the audited 166,700-neuron census.
FBR-10 does **not** require approximately 1,175 types.

The first representative of a type receives a diversity bonus, but additional neurons from a highly populated and functionally important type remain eligible. Conversely, a rare type can be omitted if retaining it has lower structural/functional value and the 16,669-node budget is exhausted.

## Edge policy

The final structural graph is the strict induced subgraph:

`E_FBR = { (u,v,w) in E_MaleCNS : u in S and v in S }`

No synthetic edge, bridge, current, or behavioural shortcut is added.

## Important implementation detail

`build_connectome_fbr10.py` is intentionally separate from the historical `build_connectome.py`. The old reducer is preserved for reproducibility; FBR-10 is the new experimental/final selector.

The script accepts either the official Feather files or Parquet conversions placed in:

`build/malecns_raw/`

Required files:

- `body-annotations-male-cns-v1.0-minconf-0.5.feather` or `.parquet`
- `body-neurotransmitters-male-cns-v1.0.feather` or `.parquet`
- `connectome-weights-male-cns-v1.0-minconf-0.5.feather` or `.parquet`

Run:

```text
python tools/build_connectome_fbr10.py
```

The script writes:

- `app/src/main/res/raw/malecns_fbr10.bin`
- `build/FBR10_REPORT.json`

The generated binary is a **structural FBR-10 artifact**. It is not yet a claim that the current Android runtime is ready to consume it. Runtime integration comes only after structural validation.
