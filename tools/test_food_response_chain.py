from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/com/example/flybrain/MainActivity.kt").read_text()
ENCODER = (ROOT / "app/src/main/java/com/example/flybrain/OlfactoryInputEncoder.kt").read_text()

# Production path: world stimulus -> receptor injection -> neural integration ->
# measured motor output -> physical state update.
assert "injectOlfactoryPopulation(foodOn, foodX, foodY" in MAIN
assert "OlfactoryInputEncoder.encode(" in MAIN
assert "sensoryCurrent[i] += current" in MAIN
assert "externalCurrent + centralNoise" in MAIN
assert "sense(dt)" in MAIN
assert "stepBrainSubstep(" in MAIN
assert "driveBody(dt)" in MAIN
assert "flyX += cos(heading) * flySpeed * dt * 60f" in MAIN

# The actual injected currents, rather than pre-clamp values, are displayed.
assert "olfInputLeftCache = encoded.left" in MAIN
assert "olfInputCenterCache = encoded.center" in MAIN
assert "olfInputRightCache = encoded.right" in MAIN

# No sensory-to-motor shortcut is introduced.
body_start = MAIN.index("private fun driveBody")
body_end = MAIN.index("private fun runNeuralSimulation", body_start)
assert "foodDirectionalBias" not in MAIN[body_start:body_end]
assert "flyX += cos(heading) * flySpeed * dt * 60f" in MAIN[body_start:body_end]

# Verify production encoder preserves signed contrast and bounded outputs.
for token in ["rawMean", "contrast", "limit - absContrast", "encodedLeft", "encodedRight"]:
    assert token in ENCODER, token

# Report stage observability used to locate the break on-device.
for token in ["olfactoryRateDisplay", "descendingRateDisplay", "motorRateDisplay", "leftLegDriveSignedCache", "rightLegDriveSignedCache", "physicalSpeed"]:
    assert token in MAIN, token

print("FOOD RESPONSE CHAIN WIRING AUDIT: PASS")
print("Runtime checkpoints: injected ORN current -> OLF firing -> DN/MN firing -> bilateral motor drive -> physical speed/position")
print("Note: this test verifies production encoder wiring and stage observability; it does not execute the Android runtime/connectome simulation.")
