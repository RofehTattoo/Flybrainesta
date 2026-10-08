#!/usr/bin/env python3
"""V1.19.36 phasic high-pass locomotion audit."""
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT/"app/src/main/java/com/example/flybrain/MainActivity.kt").read_text(encoding="utf-8")
ACT = (ROOT/"app/src/main/java/com/example/flybrain/LeggedSensorimotorActuator.kt").read_text(encoding="utf-8")
GRADLE = (ROOT/"app/build.gradle.kts").read_text(encoding="utf-8")
META = (ROOT/"app/src/main/java/com/example/flybrain/GeneratedConnectomeMeta.kt").read_text(encoding="utf-8")
MANIFEST = (ROOT/"app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
assert 'legFastBaselineRateHz' in MAIN
assert 'LEG_FAST_BASELINE_TAU_SECONDS = 0.22f' in MAIN
assert 'fastExcess = (rate - fastBaseline - 0.55f).coerceAtLeast(0f)' in MAIN
assert 'legFastBaselineRateHz[g] += (rate - legFastBaselineRateHz[g]) * fastAlpha' in MAIN
assert 'target = burst.coerceIn(0f, 1f)' in MAIN
assert 'foodOn' not in ACT and 'dangerOn' not in ACT and 'approachAction' not in ACT
assert 'contact[g] = stanceFraction.coerceIn(0f, 1f)' in ACT
assert 'supportMean = (totalContact / LEG_COUNT)' in ACT
assert 'versionName = "1.19.36"' in GRADLE and 'versionCode = 177' in GRADLE
assert 'APP_VERSION = "1.19.36"' in META and 'APP_VERSION_CODE = 177' in META
assert 'android:label="FlyBrain V1.19.36"' in MANIFEST
print("V1.19.36 PHASIC HIGH-PASS LOCOMOTION AUDIT: PASS")
