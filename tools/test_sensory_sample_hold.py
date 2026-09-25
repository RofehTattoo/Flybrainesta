from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
MAIN=(ROOT/"app/src/main/java/com/example/flybrain/MainActivity.kt").read_text()
assert 'externalRateHz' in MAIN
assert 'poissonEvent(i, dt)' in MAIN
assert 'private fun forceExternalSpike(index: Int)' in MAIN
assert 'sensoryCurrent' not in MAIN
assert 'OlfactoryInputEncoder.encode(' not in MAIN
print('SENSORY POISSON RATE-ENCODER AUDIT: PASS')
