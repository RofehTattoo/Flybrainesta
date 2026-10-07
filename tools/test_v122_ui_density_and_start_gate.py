from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/com/example/flybrain/MainActivity.kt").read_text(encoding="utf-8")
META = (ROOT / "app/src/main/java/com/example/flybrain/GeneratedConnectomeMeta.kt").read_text(encoding="utf-8")
GRADLE = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
MANIFEST = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")

assert 'const val APP_VERSION = "1.19.31"' in META
assert 'const val APP_VERSION_CODE = 172' in META
assert 'versionName = "1.19.31"' in GRADLE
assert 'versionCode = 172' in GRADLE
assert '"versionCode": 164' in (ROOT / 'RELEASE_MANIFEST_FBR10_OLF2.json').read_text(encoding='utf-8')
assert 'android:label="FlyBrain V1.19.31"' in MANIFEST

startup = MAIN[MAIN.index('inner class StartupView'):MAIN.index('inner class FlyView')]
assert 'visibility = View.GONE' not in startup
assert 'startButton.isEnabled = ready' in startup
assert 'INICIAR SIMULACIÓN' in MAIN
assert 'fun startSimulation()' in MAIN
assert 'simulationStarted = true' in MAIN

on_draw = MAIN[MAIN.index('inner class FlyView'):MAIN.index('private fun sceneBottom')]
assert 'while (simulationStarted && neuralAccumulator >= NEURAL_FRAME_DT_SECONDS' in on_draw
assert 'if (!simulationStarted)' in on_draw

map_src = MAIN[MAIN.index('private fun drawBrainMap'):MAIN.index('private fun drawFly')]
assert 'val radius = 0.90f + 4.4f * activity' in map_src
assert 'val radius = if (isDenseCentral) 0.28f + 0.95f * activity else 0.45f + 1.35f * activity' in map_src
assert 'radius * 2.15f' in map_src
assert 'radius * 1.38f' in map_src
assert 'radius * 2.1f' not in map_src
assert 'val isDenseCentral = anatomicalRegion[id].toInt() == 12' in map_src
assert '0.11f + 0.17f * signal' in map_src
assert '0.18f + 0.23f * activity' in map_src

print('V1.19.28 UI DENSITY + MANUAL START GATE AUDIT: PASS')
