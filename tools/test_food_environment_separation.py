from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
MAIN=(ROOT/"app/src/main/java/com/example/flybrain/MainActivity.kt").read_text()
run_start=MAIN.index('private fun driveBody'); run_end=MAIN.index('private fun runNeuralSimulation',run_start); run=MAIN[run_start:run_end]
assert 'foodX =' not in run and 'foodY =' not in run
assert 'rng.nextFloat()' not in run
assert 'tasteContactLatched' in MAIN
assert 'foodHits++' not in MAIN
food_pos_start=MAIN.index('private fun setFoodPosition'); food_pos_end=MAIN.index('private fun moveStimulus',food_pos_start); food_pos=MAIN[food_pos_start:food_pos_end]
assert 'foodX = x.coerceIn(.06f, .94f)' in food_pos and 'foodY = y.coerceIn(.10f, .82f)' in food_pos
print('FOOD ENVIRONMENT / NEURAL FEEDING STATE SEPARATION AUDIT: PASS')
