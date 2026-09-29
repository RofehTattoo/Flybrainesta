from pathlib import Path
import re
ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/com/example/flybrain/MainActivity.kt").read_text(encoding="utf-8")
META = (ROOT / "app/src/main/java/com/example/flybrain/GeneratedConnectomeMeta.kt").read_text(encoding="utf-8")
GRADLE = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
BUILD = (ROOT / "tools/build_connectome.py").read_text(encoding="utf-8")

assert 'private val visualSide = ByteArray(N)' in MAIN
assert 'visualSide[idx] = side.toByte()' in MAIN
assert 'private fun setMappedVisualRate(' in MAIN
assert 'val lightGains = bilateralGains(lightX, lightY)' in MAIN
assert 'val dangerGains = bilateralGains(dangerX, dangerY)' in MAIN
assert 'lightGains[0]' in MAIN and 'lightGains[1]' in MAIN
assert 'dangerGains[0]' in MAIN and 'dangerGains[1]' in MAIN
assert 'dangerLoomMemory' in MAIN
assert 'dangerOnsetMemory' in MAIN
assert 'previousDangerOn' in MAIN
assert 'setMappedSensoryRate(\n                visualReceptorIndices,' not in MAIN
# The body method also contains read-only action telemetry after the mechanical
# integration. The protected causal region ends at the measured leg actuator call.
drive_start = MAIN.index('private fun driveBody(dt: Float) {')
actuator_end = MAIN.index('legActuator.step(legGroupActivation, effectiveWalkOffActivation, dt)', drive_start)
causal_body = MAIN[drive_start:actuator_end]
for token in ('approachAction', 'escapeAction', 'orientAction', 'lightDrive', 'dangerDrive', 'lightOn', 'dangerOn'):
    assert token not in causal_body, token
assert causal_body.count('legActuator.step') == 0
# Reduction is intentionally frozen for this release; visual correction is runtime-only.
assert 'REDUCTION_ID = "FBR-10-OLF2-MOTORROUTE"' in BUILD
assert 'const val REDUCTION_ID = "FBR-10-OLF2-MOTORROUTE"' in META
assert 'const val APP_VERSION = "1.19.15"' in META
assert 'const val APP_VERSION_CODE = 156' in META
assert 'versionName = "1.19.15"' in GRADLE
assert 'versionCode = 156' in GRADLE
print('V1.19.15 BILATERAL VISUAL + LOOMING ENCODER AUDIT: PASS')
