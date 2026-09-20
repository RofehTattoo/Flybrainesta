# FBR-10 V2 correction notes

This version supersedes the first FBR-10 draft.

Key corrections:
- Hard anchors (all descending neurons, all VNC motor neurons, halt-labelled neurons) cannot be removed by local swaps.
- Route scoring includes a measured two-hop bridge proxy based on real sensory->candidate->DN and DN->candidate->motor edge counts, combined with contact-weight bridge scores.
- Superclass proportions are soft regularizers, not hard quotas.
- Lateralization is treated as a soft balance regularizer rather than simply rewarding nonzero side.
- The structural binary preserves raw positive MaleCNS contact weights; neurotransmitter sign/normalization is not applied to the structural graph.
- Edges are written in a streaming second pass rather than stored as millions of Python tuples.
- The independent validator checks the exact induced edge set and raw edge weights, not just edge count.

The reducer still uses a deterministic one-pass graph-boundary marginal estimate for the expensive connectivity term. It is intentionally validated against the final induced graph; it does not claim an exact global optimum of the combinatorial objective.
