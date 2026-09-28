#!/usr/bin/env python3
"""Audit the V1.19 boundary between neural feeding outputs and body mechanics."""
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
MAIN=(ROOT/'app/src/main/java/com/example/flybrain/MainActivity.kt').read_text(encoding='utf-8')
ACT=(ROOT/'app/src/main/java/com/example/flybrain/LeggedSensorimotorActuator.kt').read_text(encoding='utf-8')
drive=MAIN[MAIN.index('private fun driveBody'):MAIN.index('private fun runNeuralSimulation')]
assert 'walkOffActivationState' in drive and 'feedingPauseActivation' in drive
assert 'effectiveWalkOffActivation' in drive and 'legActuator.step(legGroupActivation, effectiveWalkOffActivation, dt)' in drive
assert 'foodOn' not in ACT and 'foodDirectionalBias' not in ACT and 'approachAction' not in ACT
assert 'flyX = (flyX + worldVx * dt)' in drive and 'flyY = (flyY + worldVy * dt)' in drive
assert 'updateFeedingNeuralReadout(dt)' in drive
feed=MAIN[MAIN.index('private fun updateFeedingNeuralReadout'):MAIN.index('private fun populationRate')]
assert 'feedingFunctionSpikeEvents' in feed
assert 'ingestionEventsFrame >= FEEDING_INGESTION_NEURON_SPIKE_MIN' in feed
assert 'haltWalkOffSpikeEventsFrame > 0' in feed
print('V1.19.7 FEEDING / CLOSED-LOOP BOUNDARY AUDIT: PASS')
