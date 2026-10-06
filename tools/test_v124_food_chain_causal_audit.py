#!/usr/bin/env python3
"""V1.19.28 causal FOOD -> OLF -> CONNECTOME -> VNC/MN -> BODY audit.

This is deliberately an architectural regression test: it verifies that food
enters only through retained sensory populations, that neural integration occurs
before motor decoding, and that the body actuator has no access to food or action
variables. It does not invent a behavioral controller or synthetic edge.
"""
from pathlib import Path
import math, re
ROOT=Path(__file__).resolve().parents[1]
MAIN=(ROOT/"app/src/main/java/com/example/flybrain/MainActivity.kt").read_text(encoding='utf-8')
ACT=(ROOT/"app/src/main/java/com/example/flybrain/LeggedSensorimotorActuator.kt").read_text(encoding='utf-8')
ENC=(ROOT/"app/src/main/java/com/example/flybrain/OlfactoryInputEncoder.kt").read_text(encoding='utf-8')
SENS=(ROOT/"app/src/main/java/com/example/flybrain/OlfactorySensorModel.kt").read_text(encoding='utf-8')
META=(ROOT/"app/src/main/java/com/example/flybrain/GeneratedConnectomeMeta.kt").read_text(encoding='utf-8')
GRADLE=(ROOT/"app/build.gradle.kts").read_text(encoding='utf-8')
# 1) Environment -> bilateral sensory encoder only.
assert 'injectOlfactoryPopulation(foodOn, foodX, foodY, FOOD_OLF_MAX_HZ)' in MAIN
assert 'OlfactoryInputEncoder.encode(' in MAIN
assert 'externalRateHz[i] = when (olfactorySide[i].toInt())' in MAIN
assert 'foodDirectionalBias' in MAIN  # diagnostic only; checked below for no actuator use
assert 'sampleAntenna(' in SENS and 'return exp(' in SENS
# 2) Sensory rates become Poisson events and propagate through delayed synapses.
step=MAIN[MAIN.index('private fun stepBrainSubstep'):MAIN.index('private fun updateOuterNeuralState')]
sched=MAIN[MAIN.index('private fun forceExternalSpike'):MAIN.index('private fun stepBrainSubstep')]
deliver=MAIN[MAIN.index('private fun deliverDelayedSynapses'):MAIN.index('private fun forceExternalSpike')]
assert 'poissonEvent(i, dt)' in step
assert 'forceExternalSpike(i)' in step
assert 'scheduleSpike(index)' in sched
assert 'outgoing[source]' in deliver and 'synConductance[target] += weights[k]' in deliver
# 3) Correct causal order: sense -> neural integration -> motor decode/body.
run=MAIN[MAIN.index('private fun runNeuralSimulation'):MAIN.index('private fun runNeuralSimulation')+1800]
assert run.index('sense(dt)') < run.index('stepBrainSubstep(') < run.index('updateOuterNeuralState(dt, totalSpikes)') < run.index('driveBody(dt)')
# 4) Body path is pure neural/mechanical state: no food, target or approach shortcut.
drive=MAIN[MAIN.index('private fun driveBody'):MAIN.index('private fun runNeuralSimulation')]
mech=MAIN[MAIN.index('private fun applyMechanicalBodyState'):MAIN.index('private fun runNeuralSimulation')]
for forbidden in ('foodOn','foodX','foodY','foodDrive','foodDirectionalBias','approachAction','orientAction','escapeAction','exploreAction','feedingPauseActivation'):
    assert forbidden not in mech, forbidden
assert 'legActuator.step(legGroupActivation, walkOffActivationState, dt)' in mech
# 5) Mechanical actuator has no environment/action dependency.
for forbidden in ('foodOn','foodX','foodY','foodDrive','foodDirectionalBias','approachAction','dangerOn','lightOn'):
    assert forbidden not in ACT, forbidden
assert 'fun step(legActivation: FloatArray, walkOffActivation: Float, dtRaw: Float)' in ACT
# 6) Feeding contact is contact-gated; long-range attraction remains olfactory.
feed=MAIN[MAIN.index('private fun updateFeedingNeuralReadout'):MAIN.index('private fun populationRate')]
assert 'val tasteContactPresent = foodOn &&' in feed
assert 'val ingestionNeural = foodOn &&' in feed
assert 'forceExternalSpike' not in feed
# 7) Release identity.
assert 'APP_VERSION = "1.19.28"' in META and 'APP_VERSION_CODE = 169' in META
assert 'versionName = "1.19.28"' in GRADLE and 'versionCode = 169' in GRADLE
# 8) Numerical bilateral sanity independent of runtime state.
sigma=.30; forward=.018; half=.035

def sample(fx,fy,h,sx,sy,side):
    ca,sa=math.cos(h),math.sin(h)
    lat=half*side
    ax=fx+ca*forward-sa*lat; ay=fy+sa*forward+ca*lat
    d=math.hypot(sx-ax,sy-ay)
    return math.exp(-(d*d)/(2*sigma*sigma))
L=sample(.5,.5,0,.65,.42,-1); R=sample(.5,.5,0,.65,.42,1)
Lm=sample(.5,.5,0,.65,.58,-1); Rm=sample(.5,.5,0,.65,.58,1)
assert L != R and Lm != Rm and (L-R)*(Lm-Rm) < 0
print('V1.19.28 FOOD CAUSAL CHAIN AUDIT: PASS')
print(f'Bilateral contrast mirror check: {L-R:+.6f} / {Lm-Rm:+.6f}')
