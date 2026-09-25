from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / 'app/src/main/java/com/example/flybrain/MainActivity.kt').read_text()

# Environment position must not be mutated by the neural simulation. The only
# runtime writer for food position is the explicit user-placement function;
# RESET calls that function to restore the deterministic default.
run_start = MAIN.index('private fun driveBody')
next_fn = MAIN.index('\n        private fun runNeuralSimulation', run_start)
assert next_fn > run_start
run = MAIN[run_start:next_fn]
assert 'foodX =' not in run
assert 'foodY =' not in run
assert 'rng.nextFloat()' not in run
assert 'foodContactLatched' in run
assert 'if (foodContact && !foodContactLatched)' in run

# Feeding state is allowed to change; environment coordinates are not.
assert 'foodHits++' in run
assert 'satiety = min(1f, satiety + .24f)' in run
assert 'reward += 1f' in run
assert 'foodX = .08f + rng.nextFloat()' not in run
assert 'foodY = .14f + rng.nextFloat()' not in run

# Explicit environment manipulation remains available to the user.
food_pos_start = MAIN.index('private fun setFoodPosition')
food_pos_end = MAIN.index('private fun moveStimulus', food_pos_start)
food_pos = MAIN[food_pos_start:food_pos_end]
assert 'foodX = x.coerceIn(.06f, .94f)' in food_pos
assert 'foodY = y.coerceIn(.10f, .82f)' in food_pos

move_start = MAIN.index('private fun moveStimulus')
move_end = MAIN.index('\n        }', move_start) + len('\n        }')
move = MAIN[move_start:move_end]
assert '0 -> setFoodPosition(x, y)' in move
assert '1 -> { lightX = x; lightY = y; invalidate() }' in move
assert 'else -> { dangerX = x; dangerY = y; invalidate() }' in move

# Touch remains an explicit environment-control path: tap places, drag moves.
touch_start = MAIN.index('override fun onTouchEvent')
touch_end = MAIN.index('private fun setFoodPosition', touch_start)
touch = MAIN[touch_start:touch_end]
assert 'MotionEvent.ACTION_DOWN' in touch
assert 'MotionEvent.ACTION_MOVE' in touch
assert 'draggingStimulus = true' in touch
assert 'moveStimulus(e.x / width.toFloat(), e.y / sceneBottom())' in touch
assert 'draggingStimulus = false' in touch

print('FOOD ENVIRONMENT / CEREBRO SEPARATION AUDIT: PASS')
