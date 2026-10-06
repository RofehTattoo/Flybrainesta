from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
ACT = (ROOT/"app/src/main/java/com/example/flybrain/LeggedSensorimotorActuator.kt").read_text(encoding="utf-8")
MAIN = (ROOT/"app/src/main/java/com/example/flybrain/MainActivity.kt").read_text(encoding="utf-8")
GRADLE = (ROOT/"app/build.gradle.kts").read_text(encoding="utf-8")
META = (ROOT/"app/src/main/java/com/example/flybrain/GeneratedConnectomeMeta.kt").read_text(encoding="utf-8")
MANIFEST = (ROOT/"app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")

assert 'versionName = "1.19.30"' in GRADLE
assert 'versionCode = 171' in GRADLE
assert 'APP_VERSION = "1.19.30"' in META
assert 'APP_VERSION_CODE = 171' in META
assert 'android:label="FlyBrain V1.19.30"' in MANIFEST

# Six measured neural leg streams remain the sole locomotor actuator input.
body = ACT[ACT.index("fun step("):ACT.index("fun applyWallConstraint(")]
assert "legActivation: FloatArray" in body
assert "foodOn" not in body and "dangerOn" not in body and "approachAction" not in body

# Tonic+phasic motor decoding preserves measured motor output without a stimulus shortcut.
drive = MAIN[MAIN.index("// V1.19.30: the previous decoder"):
             MAIN.index("// Walk-OFF is a measured neural actuator gate.")]
assert "val tonic =" in drive and "val burst =" in drive
assert "legGroupActivation[g] = relaxMotorActivation" in drive

# Individual foot mechanics and physical contact reaction must remain explicit.
assert "wallReactionTorque" in MAIN
assert "rX * ry - rY * rx" in MAIN
assert "val bodyYawInertia = 0.20f" in MAIN
assert "recordWallReactionTorque" in ACT
assert "yawRate = (yawRate + normalizedWallTorque" in MAIN

# No direct behavioral controller.
for forbidden in [
    "wallEscapeBias", "if (wallContactNow) turn", "if (wallContactNow) yaw",
    "approachAction *", "foodDirectionalBias *", "dangerDrive * MAX_YAW_RATE"
]:
    assert forbidden not in MAIN

print("V1.19.30 LOCOMOTION RECONSTRUCTION PHASE 2 AUDIT: PASS")
