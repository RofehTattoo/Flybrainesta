from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/com/example/flybrain/MainActivity.kt").read_text(encoding="utf-8")
ENCODER = (ROOT / "app/src/main/java/com/example/flybrain/OlfactoryInputEncoder.kt").read_text(encoding="utf-8")
assert "OlfactoryInputEncoder.encode(" in MAIN
assert "externalRateHz[i] = when (olfactorySide[i].toInt())" in MAIN
assert "max(\n                walkOffActivationState, feedingPauseActivation" not in MAIN
assert "legActuator.step(legGroupActivation, walkOffActivationState, dt)" in MAIN
assert "effectiveWalkOffActivation" not in MAIN
assert "legActuator.step(legGroupActivation, feedingPauseActivation" not in MAIN
assert "fun encode(left: Float, right: Float, gain: Float, limit: Float)" in ENCODER
print("V1.19.20 OLFACTORY RUNTIME + NEURAL WALK-OFF CONTRACT: PASS")
