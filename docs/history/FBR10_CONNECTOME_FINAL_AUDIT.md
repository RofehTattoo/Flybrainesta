# FlyBrain FBR-10 — Connectome final audit / frozen baseline

**FlyBrain release:** V1.14
**Status:** CONNECTOME FROZEN
**Dataset:** MaleCNS v1.0
**Reduction:** FBR-10
**Binary:** FBC103

## 1. Source census
The audited MaleCNS neuron universe is **166,700 neurons**, defined by non-empty `superclass`. The source neuron-level graph contains **25,582,938 directed neuron-level connections** and **124,177,617 contacts**.

## 2. Exact reduced target
FBR-10 selects exactly **16,669 neurons**, i.e. exactly 10% of the audited 166,700-neuron universe. The selection is not a random 10% sample: it is an engineering reduction designed to preserve sensorimotor architecture, connectivity, degree structure, type diversity and functional routes under the hard 16,669-neuron budget.

## 3. Frozen graph
The Android resource contains **2,064,951 published MaleCNS edges** and **16,783,932 contacts**. The graph is an induced subgraph of the selected neurons: no synthetic edges are added. Raw positive MaleCNS contact weights are retained.

The frozen resource SHA-256 is:
`bfadc30fd113c25f9711cce6ef8f6b80e9c139fe6d229965a4adabb94d8b4e60`

## 4. Structural preservation metrics
- All **1,314 descending neurons** are retained.
- All **708 VNC motor neurons** are retained.
- The FBR-10 validation reports **925 motor/efferent-anchor neurons** under its broader motor-role aggregate.
- **1,794 / 17,937 = 10.002%** of the explicitly defined sensory superclass population is retained.
- **9,255 / 11,751 = 78.759%** of distinct `type` values in the audited source census are represented.
- Degree-distribution Wasserstein metric reported by the FBR-10 selection audit: **282.1594**.
- Neurotransmitter Jensen–Shannon divergence reported by the audit: **0.047454**.
- 437 of 563 DNs with source sensory input retain a sensory connection in the reduced graph.
- All 730 DNs with source motor output retain motor-output connectivity in the reduced graph.

These are validation metrics of the computational reduction; they are not biological constants or claims that the reduced network is biologically equivalent to the full MaleCNS.

## 5. Source hashes used for provenance
- annotations: `2177e246113e4cfbf1e7772ec37c6da1955ff22e8063d0b1f833101f99a9a3b2`
- neurotransmitters: `95c9289220663abeb3409f3ad9e5a7f8a53f8093f5139d15502cd08da8879621`
- weights: `e35da783d1c686b2b58b3b87cd6a403ae43bfcfba8bff28e08ef752c1a56afc1`

## 6. What is frozen here
This release freezes the **connectome/reduction layer**. Future versions must not silently regenerate or alter the 16,669-neuron graph. Any intentional graph change requires a new reduction ID and a new audit.

The neural dynamics, sensor transduction and body/motor simulation remain separate layers. Correctness of this frozen graph does not by itself prove that emergent behavior is correct; that is the next validation stage.
