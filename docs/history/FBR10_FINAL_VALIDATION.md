# FBR-10 final validated reduction

Source census: **166,700 neurons**, **25,582,938 directed neuron-level connections**, **124,177,617 contacts**.

Selection: exactly **16,669 neurons**. All **1,314 descending neurons** and all **708 VNC motor neurons** are retained; the selection also retains the broader motor/efferent anchor roles used by the reduction. Sensory selection is 10% of the 17,937 explicitly defined sensory superclasses, with a small per-superclass floor and a sensor→DN coverage term. Type diversity remains a soft objective.

Final induced subgraph: **2,064,951 published edges** and **16,783,932 contacts**. No synthetic edges are present: every emitted edge comes from the filtered MaleCNS neuron-level graph and both endpoints are selected.

Type coverage: **9,255 / 11,751 = 78.759%**. Sensor nodes retained: **1,794 / 17,937 = 10.002%**. All 1,314 DNs and 925 motor/efferent-anchor neurons selected.

Sensor→DN→motor validation: 437 of 563 DNs with sensor input remain connected to at least one retained sensory node; all 730 DNs with motor output remain connected to retained motor outputs. The two-hop DN pair proxy retains 34.338%.

Neurotransmitter distribution comparison (consensus_nt, mapped to the 166,700-neuron census) gives Jensen–Shannon divergence **0.047454**.

The FBC103 resource SHA-256 is `bfadc30fd113c25f9711cce6ef8f6b80e9c139fe6d229965a4adabb94d8b4e60`.

This artifact is the validated connectome/reduction stage. The Android behavioral/controller code has **not** been rewritten here; doing that is the next simulation-validation stage, so behavior is not being claimed to emerge merely because the reduced graph is now correct.
