#!/usr/bin/env python3
"""V1.19.6 regression audit for neural feeding dwell."""
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
MAIN=(ROOT/"app/src/main/java/com/example/flybrain/MainActivity.kt").read_text()
ACT=(ROOT/"app/src/main/java/com/example/flybrain/LeggedSensorimotorActuator.kt").read_text()

for token in (
    "feedingPauseActivation", "feedingPauseHoldSeconds", "feedingPausePeak",
    "FEEDING_TASTE_HOLD_SECONDS", "FEEDING_PROBOSCIS_HOLD_SECONDS",
    "FEEDING_INGESTION_HOLD_SECONDS", "effectiveWalkOffActivation",
):
    assert token in MAIN

assert "legActuator.step(legGroupActivation, effectiveWalkOffActivation, dt)" in MAIN
assert "foodOn" not in ACT and "foodX" not in ACT and "foodY" not in ACT

feed=MAIN[MAIN.index("val ingestionNeural"):MAIN.index("tasteContactNeuralNow = tasteNeural")]
assert "tasteNeural" in feed and "proboscisNeural" in feed and "ingestionNeural" in feed

print("FEEDING STOP/TASTE HOLD AUDIT: PASS")
