#!/usr/bin/env python3
"""Audit the feeding/locomotion interface in V1.18.7."""
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
s=(ROOT/'app/src/main/java/com/example/flybrain/MainActivity.kt').read_text(encoding='utf-8')

drive=s[s.index('private fun driveBody'):s.index('private fun driveBody')+26000]
assert 'walkOffRateHz' in drive
assert 'walkOffActivationState' in drive
assert 'val walkOffGate = (1f - .94f * walkOffActivationState)' in drive
mechanics = drive[drive.index('val walkOffRateHz'):drive.index('// Feeding is a measured neural readout.')]
assert 'foodOn' not in mechanics
assert 'foodDirectionalBias' not in mechanics
assert 'foodDrive' not in mechanics
assert 'approachAction' not in mechanics
assert 'escapeAction' not in mechanics
assert 'flyX = (flyX +' in mechanics and 'flyY = (flyY +' in mechanics
# Feeding event recognition remains neural-output based.
feed=s[s.index('private fun updateFeedingNeuralReadout'):s.index('private fun populationRate')]
assert 'feedingFunctionSpikeEvents' in feed
assert 'ingestionEventsFrame >= FEEDING_INGESTION_NEURON_SPIKE_MIN' in feed
assert 'haltWalkOffSpikeEventsFrame > 0' in feed
print('V1.18.7 FEEDING/WALK-OFF CAUSAL GATE AUDIT: PASS')
