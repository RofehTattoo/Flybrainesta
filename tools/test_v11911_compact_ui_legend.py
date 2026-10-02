from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/com/example/flybrain/MainActivity.kt").read_text(encoding="utf-8")
META = (ROOT / "app/src/main/java/com/example/flybrain/GeneratedConnectomeMeta.kt").read_text(encoding="utf-8")
assert 'LinearLayout.LayoutParams(-1, 44.dp())' in MAIN
assert 'LinearLayout.LayoutParams(-1, 40.dp())' in MAIN
assert 'private fun brainPanelHeight(): Float = min(height * .32f, 390.dp().toFloat())' in MAIN
assert '"CONNECTOME + DYNAMICS OK"' in MAIN
assert 'Triple("OLFATO", Color.rgb(45, 190, 105), 0)' in MAIN
assert 'Triple("VISIÓN", Color.rgb(55, 145, 235), 1)' in MAIN
assert 'Triple("GUSTO", Color.rgb(238, 190, 42), 2)' in MAIN
assert 'Triple("MECANO", Color.rgb(238, 125, 48), 3)' in MAIN
assert 'Triple("DESC.", Color.rgb(218, 75, 175), 4)' in MAIN
assert 'Triple("ASC.", Color.rgb(55, 190, 210), 5)' in MAIN
assert 'Triple("MOTOR", Color.rgb(235, 70, 75), 6)' in MAIN
assert 'Triple("CENTRAL", Color.rgb(150, 160, 170), 7)' in MAIN
assert 'APP_VERSION = "1.19.22"' in META
assert 'APP_VERSION_CODE = 163' in META
print("V1.19.11 COMPACT UI / NEURAL COLOR LEGEND REGRESSION: PASS")
