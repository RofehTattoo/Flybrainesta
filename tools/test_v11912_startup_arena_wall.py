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
assert 'private val BODY_MIN_X = .045f' in MAIN
assert 'private val BODY_MAX_X = .955f' in MAIN
assert 'private val BODY_MIN_Y = .055f' in MAIN
assert 'private val BODY_MAX_Y = .945f' in MAIN
assert 'private fun brainPanelHeight(): Float = min(height * .32f, 390.dp().toFloat())' in MAIN
assert 'fun applyWallConstraint(' in ACT
assert 'wallPressure' in ACT
assert 'wallEscapeBias' not in ACT
assert 'phase[' not in ACT[ACT.index('fun applyWallConstraint('):ACT.index('private fun bilateralMechanicalMean', ACT.index('fun applyWallConstraint('))]
print("V1.19.22 STARTUP / ARENA / WALL CONTACT AUDIT: PASS")
