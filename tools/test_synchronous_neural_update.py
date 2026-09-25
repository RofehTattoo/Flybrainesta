from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
s=(ROOT/"app/src/main/java/com/example/flybrain/MainActivity.kt").read_text()
start=s.index('private fun stepBrainSubstep('); end=s.index('private fun resetMotorSubstepAccumulators()',start)
body=s[start:end]
assert 'deliverDelayedSynapses()' in body
assert 'for (i in 0 until SENSOR_END)' in body
assert 'scheduleSpike(i)' in body
assert 'v[i] = V_REST + x0 * a + g0 * coupling * (a - b)' in body
assert 'prevFired' not in body
assert 'synTrace' not in body
print('SYNCHRONOUS / EVENT-DRIVEN NEURAL UPDATE AUDIT: PASS')
