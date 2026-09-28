#!/usr/bin/env python3
"""V1.19.7 regression audit for neural feeding dwell and physical braking."""
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
MAIN=(ROOT/"app/src/main/java/com/example/flybrain/MainActivity.kt").read_text()
ACT=(ROOT/"app/src/main/java/com/example/flybrain/LeggedSensorimotorActuator.kt").read_text()

for token in (
    "feedingPauseActivation", "feedingPauseHoldSeconds", "feedingPausePeak",
    "FEEDING_TASTE_HOLD_SECONDS = 0.65f",
    "FEEDING_PROBOSCIS_HOLD_SECONDS = 1.80f",
    "FEEDING_INGESTION_HOLD_SECONDS = 2.80f",
    "FEEDING_PHARYNGEAL_CONTEXT_WINDOW_SECONDS = 0.35f",
    "pharyngealContextAgeSeconds", "effectiveWalkOffActivation",
):
    assert token in MAIN, token

ing=MAIN[MAIN.index("val ingestionNeural ="):MAIN.index("// Neural feeding events renew")]
assert "pharyngealContextActive" in ing
assert "tasteContextActive" in ing
assert "proboscisContextActive" in ing
assert "ingestionEventsFrame >= FEEDING_INGESTION_NEURON_SPIKE_MIN" in ing
assert "gustatorySpikeEventsFrame >= FEEDING_TASTE_NEURON_SPIKE_MIN" not in ing

assert "legActuator.step(legGroupActivation, effectiveWalkOffActivation, dt)" in MAIN
assert "WALK_OFF_BRAKE_ACCEL = 7.0f" in ACT
assert "WALK_OFF_BRAKE_ACCEL * walkOff" in ACT
assert "foodOn" not in ACT and "foodX" not in ACT and "foodY" not in ACT
print("V1.19.7 FEEDING DWELL / BRAKE AUDIT: PASS")
