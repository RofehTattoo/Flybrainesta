from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
ACT = (ROOT/"app/src/main/java/com/example/flybrain/LeggedSensorimotorActuator.kt").read_text(encoding="utf-8")
MAIN = (ROOT/"app/src/main/java/com/example/flybrain/MainActivity.kt").read_text(encoding="utf-8")
GRADLE = (ROOT/"app/build.gradle.kts").read_text(encoding="utf-8")
META = (ROOT/"app/src/main/java/com/example/flybrain/GeneratedConnectomeMeta.kt").read_text(encoding="utf-8")
MANIFEST = (ROOT/"app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")

assert 'versionName = "1.19.31"' in GRADLE
assert 'versionCode = 172' in GRADLE
assert 'APP_VERSION = "1.19.31"' in META
assert 'APP_VERSION_CODE = 172' in META
assert 'android:label="FlyBrain V1.19.31"' in MANIFEST

# Six measured neural leg streams remain the sole locomotor actuator input.
body = ACT[ACT.index("fun step("):ACT.index("fun applyWallConstraint(")]
assert "legActivation: FloatArray" in body
assert "foodOn" not in body and "dangerOn" not in body and "approachAction" not in body

# Tonic+phasic motor decoding preserves measured motor output without a stimulus shortcut.
drive = MAIN[MAIN.index("// V1.19.31: the previous decoder"):
             MAIN.index("// Walk-OFF is a measured neural actuator gate.")]
assert "val tonic =" in drive and "val burst =" in drive
assert "legGroupActivation[g] = relaxMotorActivation" in drive

# Individual foot mechanics and physical contact reaction must remain explicit.
assert "footVelocityForward" in ACT and "footVelocityLateral" in ACT
assert "val footStrokeVelocity" in ACT
assert "forceForward" in ACT and "forceLateral" in ACT
assert "yawTorque += footForward[g] * forceLateral - footLateral[g] * forceForward" in ACT
assert "fun applyWallReactionWorld(" in ACT
assert "rX * fy - rY * fx" in MAIN
assert "wallForceWorldX" in MAIN and "wallForceWorldY" in MAIN
assert "yawRate = (yawRate + YAW_TORQUE_TO_ACCEL * torqueWorld * dt)" in ACT
assert "val steeringSignal" not in ACT
assert "turnBalance" not in ACT
# No direct behavioral controller.
for forbidden in [
    "wallEscapeBias", "if (wallContactNow) turn", "if (wallContactNow) yaw",
    "approachAction *", "foodDirectionalBias *", "dangerDrive * MAX_YAW_RATE"
]:
    assert forbidden not in MAIN

print("V1.19.31 LOCOMOTION GROUND-REACTION AUDIT: PASS")
