from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/com/example/flybrain/MainActivity.kt").read_text(encoding="utf-8")
ACT = (ROOT / "app/src/main/java/com/example/flybrain/LeggedSensorimotorActuator.kt").read_text(encoding="utf-8")
assert 'class StartupView(private val sim: FlyView)' in MAIN
assert '"Código de colores del cerebro"' in MAIN
assert '"Verde · Olfato"' in MAIN and '"Amarillo · Gusto"' in MAIN
assert '"Rojo · Motor"' in MAIN and '"Gris · Central"' in MAIN
assert 'sim.startBrainLoading()' in MAIN
assert 'Thread {' in MAIN and 'name = "flybrain-connectome-loader"' in MAIN
assert 'brainLoadFinished' in MAIN and 'brainLoadFinishedAt' in MAIN
assert 'private data class StartupEntry' in MAIN
assert '@Volatile var brainLoadFinished = false' in MAIN
assert '@Volatile var brainLoadOk = false' in MAIN
assert '@Volatile var brainLoadFinishedAt = 0L' in MAIN
assert 'private val BODY_MIN_X = .045f' in MAIN
assert 'private val BODY_MAX_X = .955f' in MAIN
assert 'private val BODY_MIN_Y = .055f' in MAIN
assert 'private val BODY_MAX_Y = .945f' in MAIN
assert 'private fun brainPanelHeight(): Float = min(height * .32f, 390.dp().toFloat())' in MAIN
assert 'WALL_ESCAPE_PULSE_SECONDS = .65f' in ACT
assert 'WALL_SEPARATION_SPEED = .030f' in ACT
assert 'vx += nx * WALL_SEPARATION_SPEED' in ACT
assert 'vy += ny * WALL_SEPARATION_SPEED' in ACT
assert 'wallEscapePulseRemaining = 0f' in ACT
print("V1.19.12 STARTUP / ARENA UTILIZATION / WALL CORNER UNSTICK AUDIT: PASS")
