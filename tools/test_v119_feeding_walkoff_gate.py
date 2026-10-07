#!/usr/bin/env python3
"""Audit the V1.19 boundary between neural feeding outputs and body mechanics."""
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
MAIN=(ROOT/'app/src/main/java/com/example/flybrain/MainActivity.kt').read_text(encoding='utf-8')
ACT=(ROOT/'app/src/main/java/com/example/flybrain/LeggedSensorimotorActuator.kt').read_text(encoding='utf-8')
drive=MAIN[MAIN.index('private fun driveBody'):MAIN.index('private fun runNeuralSimulation')]
assert 'walkOffActivationState' in drive and 'feedingPauseActivation' not in drive
assert 'effectiveWalkOffActivation' not in drive and 'legActuator.step(legGroupActivation, walkOffActivationState, dt)' in drive
assert 'foodOn' not in ACT and 'foodDirectionalBias' not in ACT and 'approachAction' not in ACT
# The drive function delegates all body integration to the dedicated mechanical layer.
assert 'applyMechanicalBodyState(dt)' in drive
mechanical=MAIN[MAIN.index('private fun applyMechanicalBodyState'):MAIN.index('private fun updateMeasuredPauseState')]
assert 'val nextFlyX = flyX + worldVx * dt' in mechanical
assert 'val nextFlyY = flyY + worldVy * dt' in mechanical
assert 'flyX = nextFlyX.coerceIn(BODY_MIN_X, BODY_MAX_X)' in mechanical
assert 'flyY = nextFlyY.coerceIn(BODY_MIN_Y, BODY_MAX_Y)' in mechanical
assert 'applyWallReactionWorld(' in mechanical
assert 'updateFeedingNeuralReadout(dt)' in drive
feed=MAIN[MAIN.index('private fun updateFeedingNeuralReadout'):MAIN.index('private fun populationRate')]
assert 'feedingFunctionSpikeEvents' in feed
assert 'ingestionEventsFrame >= FEEDING_INGESTION_NEURON_SPIKE_MIN' in feed
assert 'haltWalkOffSpikeEventsFrame > 0' in feed
print('V1.19.7 FEEDING / CLOSED-LOOP BOUNDARY AUDIT: PASS')
