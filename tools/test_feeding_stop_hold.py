#!/usr/bin/env python3
"""V1.19.25 regression audit for neural feeding semantics and physical walk-off."""
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
MAIN=(ROOT/"app/src/main/java/com/example/flybrain/MainActivity.kt").read_text()
ACT=(ROOT/"app/src/main/java/com/example/flybrain/LeggedSensorimotorActuator.kt").read_text()

for token in (
    "FEEDING_CONTEXT_WINDOW_SECONDS = 0.75f",
    "FEEDING_PHARYNGEAL_CONTEXT_WINDOW_SECONDS = 0.80f",
    "tasteContextAgeSeconds",
    "proboscisContextAgeSeconds",
    "pharyngealContextAgeSeconds",
    "ingestionEventsFrame >= FEEDING_INGESTION_NEURON_SPIKE_MIN",
    "walkOffActivationState = relaxMotorActivation",
    "legActuator.step(legGroupActivation, walkOffActivationState, dt)",
):
    assert token in MAIN, token

# Dead software pause state must not return in the runtime.
for token in ("feedingPauseActivation", "feedingPauseHoldSeconds", "feedingPausePeak",
              "FEEDING_TASTE_HOLD_SECONDS", "FEEDING_PROBOSCIS_HOLD_SECONDS",
              "FEEDING_INGESTION_HOLD_SECONDS"):
    assert token not in MAIN, token

feed=MAIN[MAIN.index("private fun updateFeedingNeuralReadout"):MAIN.index("private fun populationRate")]
assert "flySpeed =" not in feed and "heading =" not in feed and "forceExternalSpike" not in feed
assert "satiety = min(1f, satiety + .24f)" in feed

assert "WALK_OFF_BRAKE_ACCEL = 7.0f" in ACT
assert "val walkGateBase = (1f - .97f * walkOffActivation.coerceIn(0f, 1f))" in ACT
assert "val walkGate = walkGateBase * walkGateBase" in ACT
assert "WALK_OFF_BRAKE_ACCEL * walkOff" in ACT
assert "foodOn" not in ACT and "foodX" not in ACT and "foodY" not in ACT
print("V1.19.25 FEEDING NEURAL WALK-OFF / NO SOFTWARE PAUSE BYPASS: PASS")
