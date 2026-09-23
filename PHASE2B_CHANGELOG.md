# PHASE 2B CHANGELOG — 2026-09-23

## Scope
Internal neural temporal sub-stepping only.

## Changed
- Public neural frame remains 20 ms.
- Internal neural integration uses 4 × 5 ms substeps.
- Synaptic trace is resolved at 5 ms.
- Membrane leak remains analytically exact per substep.
- Sensory kick is applied once per public frame to preserve stimulus magnitude.
- Motor spikes and motor synaptic drive are aggregated over all internal substeps before `driveBody()`.
- DN/MN diagnostic spike windows now count all internal substeps.
- Visual presentation memory remains exactly frame-level (20 ms), with a spike latch across all 5 ms substeps, preserving the legacy 0.88/0.22 display update.
- Added `test_phase2b_temporal_substepping.py` and CI validation step.
- Removed stale CI assertion rejecting presentation-only `excludeOlfactory = true`.

## PHASE 2B frozen inputs
The temporal correction itself does not modify these model values or the current embedded FBC103/FBD104 resources:
- FBC103 topology/weights and FBD104 dynamics.
- Sensory gains and neural gains.
- Thresholds and membrane resting/reset values.
- `driveBody()` geometry/kinematics model.

## Post-PHASE-2B senior hardening
After the temporal correction, the reducer received one separate audited integrity fix: the four official MaleCNS ORNs with NULL `type` are now explicitly retained by bodyId. That selector change requires CI to regenerate FBR-10/FBD104/maps before producing a new release binary.

## Validation
All existing static Python audits plus the new PHASE 2B golden audit pass.
Android compilation remains pending CI/device execution because the supplied source snapshot has no Gradle wrapper.
