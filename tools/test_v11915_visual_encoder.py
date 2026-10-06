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
# V1.19.27 isolates the physical/mechanical body integration in its own function.
# That function is the protected causal boundary: it may consume only measured
# VNC leg motor groups and walk-OFF neural output.
def kotlin_function_span(source: str, signature: str) -> str:
    start = source.index(signature)
    brace = source.index('{', start)
    depth = 0
    for i in range(brace, len(source)):
        if source[i] == '{': depth += 1
        elif source[i] == '}':
            depth -= 1
            if depth == 0: return source[start:i + 1]
    raise AssertionError(f'unclosed function: {signature}')

causal_body = kotlin_function_span(MAIN, 'private fun applyMechanicalBodyState(dt: Float)')
drive_body = kotlin_function_span(MAIN, 'private fun driveBody(dt: Float)')
for token in ('approachAction', 'escapeAction', 'orientAction', 'lightDrive', 'dangerDrive', 'lightOn', 'dangerOn', 'foodOn', 'foodX', 'foodY'):
    assert token not in causal_body, token
assert 'legActuator.step(legGroupActivation, walkOffActivationState, dt)' in causal_body
assert 'applyMechanicalBodyState(dt)' in drive_body
# V1.19.27 explicitly reconstructs the visual slice of FBR-10 while keeping the
# global 16,669-node target and the induced-edge rule. The primary receptor quota
# must be derived from the pinned MaleCNS source, never from the old selected graph.
assert 'REDUCTION_ID = "FBR-10-OLF2-MOTORROUTE"' in BUILD
assert 'TARGET_PRIMARY_VISUAL = int(round(primary_visual_source_count * 0.10))' in BUILD
assert 'TARGET_VISUAL_TOTAL = int(round(visual_source_count * 0.10))' in BUILD
assert 'visual_primary_source = annotated[is_primary_visual].copy()' in BUILD
assert 'annotated["channel"] != 0' in BUILD
assert 'route_visual_turn' in BUILD and 'route_visual_escape' in BUILD
assert 'visual_three_turn' in BUILD and 'visual_three_escape' in BUILD
assert 'const val REDUCTION_ID = "FBR-10-OLF2-MOTORROUTE"' in META
assert 'const val APP_VERSION = "1.19.27"' in META
assert 'const val APP_VERSION_CODE = 167' in META
assert 'versionName = "1.19.27"' in GRADLE
assert 'versionCode = 167' in GRADLE
print('V1.19.27 BILATERAL VISUAL + LOOMING ENCODER AUDIT: PASS')
