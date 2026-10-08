from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ACT = (ROOT / "app/src/main/java/com/example/flybrain/LeggedSensorimotorActuator.kt").read_text(encoding="utf-8")
MAIN = (ROOT / "app/src/main/java/com/example/flybrain/MainActivity.kt").read_text(encoding="utf-8")
GRADLE = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
META = (ROOT / "app/src/main/java/com/example/flybrain/GeneratedConnectomeMeta.kt").read_text(encoding="utf-8")

assert 'versionName = "1.19.36"' in GRADLE
assert 'versionCode = 177' in GRADLE
assert 'APP_VERSION_CODE = 177' in META
assert 'INITIAL_PHASES' in ACT
assert 'var cycle = (phase[g] / TWO_PI) % 1f' in ACT
assert 'TRIPOD_OFFSETS' not in ACT
assert 'tripodPhaseCoherence' not in ACT
assert 'yawTorqueProxy' in ACT
assert 'yawTorque += footLateral[g] * force' in ACT
assert 'val legSteering =' in ACT
assert 'val steeringSignal = (legSteering + dnSteering).coerceIn(-1f, 1f)' in ACT
assert 'val dnSteering = dnBalance * dnDrive * TURN_DN_MAX_CONTRIBUTION' in ACT
# The mechanical layer remains blind to environmental targets.
body = ACT[ACT.index('fun step('):ACT.index('fun applyWallConstraint(')]
assert 'flyX' not in body and 'foodOn' not in body and 'dangerOn' not in body and 'approachAction' not in body
# MainActivity still passes only measured leg-MN groups + walk-off.
assert 'legActuator.step(' in MAIN
assert 'turnDnLeftActivationState' in MAIN and 'turnDnRightActivationState' in MAIN
print('V1.19.36 ADAPTIVE SIX-LEG MECHANICS AUDIT: PASS')

# Regression: construction must initialize the anatomical startup phases.
assert 'init {' in ACT
init_start = ACT.index('init {')
init_end = ACT.index('\n    }', init_start) + len('\n    }')
init_block = ACT[init_start:init_end]
assert 'reset()' in init_block

# The constructor contract must initialize phase[] before runtime step() can execute.
init_pos = ACT.index('init {')
reset_fun_pos = ACT.index('fun reset(')
step_pos = ACT.index('fun step(')
assert init_pos < reset_fun_pos < step_pos
