# FlyBrain FIX5 — causal side-resolved telemetry (2026-09-26)

## Why this change exists

The FIX4 video series showed that the food sensor/ORN input changes with source geometry, while the body remains in sustained locomotion and any food-oriented reorientation is transient. The existing on-screen telemetry showed the environmental ORN input and aggregate DN/MN activity, but did not expose the complete left/right chain in comparable units.

## Changes

- Added read-only, diagnostic-window firing rates for retained ORNs on the annotated left and right sides. These count actual LIF spike events after neural integration; they are not the externally injected odor rates.
- Added side-resolved mean firing rates for descending neurons and leg motor neurons, using the existing `nodeSide` and official motor-role annotations.
- Replaced less relevant on-screen rows with the causal-chain rates so future screen recordings can reveal whether lateralization is lost at ORNs, DNs, or leg MNs.
- Normalized the internal heading angle to `[-π, π]` during integration and the displayed angle to `[-180°, 180°)`. This changes representation only, not orientation or steering.
- Renamed the action readout `PAUSA / FRENADO` to `FRENADO NEURAL` to distinguish a neural action label from a measured physical pause.

## Deliberately unchanged

- Frozen MaleCNS v1.0/FBR-10 neuron selection, edges, weights, transmitter signs, and LIF parameters.
- Olfactory field, antenna geometry, and FIX4 tarsal-contact gustation.
- Body steering/translation equations. No direct food-to-heading or food-to-speed term was added; no synthetic pauses or feeding animation were introduced.

## Interpretation and validation boundary

This is an observability and numerical-representation correction, not a claim that realistic food tracking has been solved. The videos do not identify which individual retained pathway or actuator parameter is responsible, and the new telemetry is intended to localize that failure in the next controlled run. The Python static audit can verify source invariants, but an Android/GitHub Actions build and a new APK recording are still required.
