# FBR-10-OLF1 — Functional Balanced Reduction with Olfactory Preservation

## Status

Current structural release candidate for FlyBrain V1.17.0.

- Source: MaleCNS v1.0
- Source neuron census: 166,700
- Retained neurons: exactly 16,669
- Retained real ORNs: 264
- ORN type labels retained: 53
- Published ORN `(type, entryNerve)` combinations retained: 54
- Structural format: FBC103
- Current FBC103 SHA-256: `0044ab166af3439f2b86d4e6c5897481a1c3f28a58b6afb2c4f761489b276bbf`

Historical FBR-10 v1.14 artifacts are preserved under `docs/history/fbr10-v1.14/` and are not current release inputs.

## Purpose

FBR-10-OLF1 reduces the audited MaleCNS v1.0 neuron census to exactly 16,669 neurons while explicitly preserving a representative population of real olfactory receptor neurons and measured olfactory sensorimotor route support.

The reducer is structural. It does not program behaviour and does not create synthetic neurons or edges.

## Selection hierarchy

1. Hard neuron budget: `|S| = 16,669`.
2. Retain the complete curated descending-neuron and VNC-motor anchor populations.
3. Retain all four official MaleCNS ORNs whose published `type` is NULL:
   `242812`, `242908`, `488209`, `956041`.
4. Retain exactly 264 real ORNs from the official `cb_sensory + olfactory` census.
5. Preserve all 54 published typed ORN `(type, entryNerve)` combinations.
6. Preserve measured sensor→DN, DN→intermediate→motor, and explicit ORN-driven forward/motor route support.
7. Fill the remaining budget by published superclass strata using measured route support and degree.
8. The final graph is the strict induced subgraph of the published MaleCNS graph.

## Route score used by the current OLF1 builder

The current canonical builder computes normalized, measured topology scores:

- `route_forward`
- `route_turn`
- `route_escape`
- `route_sensorimotor`
- `route_olfactory_forward`
- `route_olfactory_motor`
- `route_halt`

The current composite selection score is:

`0.30 forward + 0.22 turn + 0.34 escape + 0.14 sensorimotor + 0.24 olfactory_forward + 0.12 olfactory_motor`

These coefficients are engineering selection parameters, not biological constants. They are documented here to keep the specification identical to the executable builder.

## ORN policy

An ORN is recognized from MaleCNS annotation as:

- `superclass = cb_sensory`
- `class = olfactory`
- `type` beginning with `ORN_`, or one of the four official NULL-type body IDs
- `entryNerve` in `AN` or `MxLbN`

The four NULL-type ORNs remain real ORNs. No synthetic type label is assigned.

`entryNerve` is never used as a substitute for anatomical left/right side. Side precedence is `somaSide -> rootSide -> UNKNOWN`.

## Edge policy

The final structural graph is exactly:

`E_FBR = {(u,v,w) in E_MaleCNS : u in S and v in S}`

No synthetic edge, bridge, current, motor command, or behavioural shortcut is added.

FBC103 stores the original positive MaleCNS contact count as the structural edge weight. Neurotransmitter sign and normalization belong exclusively to the separate FBD104 dynamics layer.

## Provenance

Pinned MaleCNS v1.0 SHA-256 values:

- annotations: `2177e246113e4cfbf1e7772ec37c6da1955ff22e8063d0b1f833101f99a9a3b2`
- neurotransmitters: `95c9289220663abeb3409f3ad9e5a7f8a53f8093f5139d15502cd08da8879621`
- connectome weights: `e35da783d1c686b2b58b3b87cd6a403ae43bfcfba8bff28e08ef752c1a56afc1`

The builder refuses to generate a release graph from a source file whose SHA-256 does not match the pinned v1.0 object.

## Runtime boundary

The FBC103 structural artifact is authoritative for topology. FBD104 must be regenerated from that exact FBC103 and the pinned neurotransmitter table before an APK is declared release-ready.

The runtime must fail closed if structural or dynamics artifacts are missing or structurally incompatible.
