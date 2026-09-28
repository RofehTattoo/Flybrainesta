from pathlib import Path
import hashlib
ROOT=Path(__file__).resolve().parents[1]
MAIN=(ROOT/"app/src/main/java/com/example/flybrain/MainActivity.kt").read_text()
META=(ROOT/"app/src/main/java/com/example/flybrain/GeneratedConnectomeMeta.kt").read_text()
BUILDER=(ROOT/"tools/build_olfactory_input_map.py").read_text()
SENS=(ROOT/"tools/build_sensory_input_map.py").read_text()
FEED=(ROOT/"tools/build_feeding_motor_semantics.py").read_text()
FBC=ROOT/"app/src/main/res/raw/malecns_reduced.bin"
assert 'olfactoryNeuronIndices' in MAIN and 'for (i in olfactoryNeuronIndices)' in MAIN
assert 'loadFeedingMotorSemantics()' in MAIN
assert 'feedingFunctionalTag' in MAIN and 'feedingFunctionRateHz' in MAIN
assert 'gustatoryTarsalReceptorIndices' in MAIN and 'gustatorySide' in MAIN
assert 'updateFeedingNeuralReadout(dt)' in MAIN
run_start=MAIN.index('private fun driveBody'); run_end=MAIN.index('private fun runNeuralSimulation',run_start); run=MAIN[run_start:run_end]
assert 'foodDirectionalBias' not in run
assert 'foodX =' not in run and 'foodY =' not in run
assert 'foodHits++' not in run
assert 'satiety = min(1f, satiety + .24f)' not in run
assert 'ingestionEvents++' not in run
assert 'val newIngestionEpisode = ingestionNeural && !ingestionEpisodeLatched' in MAIN
assert 'return if (newIngestionEpisode) 1f else 0f' in MAIN
assert 'proboscisExtension = relaxMotorActivation(proboscisExtension, rostrumTarget, dt)' in MAIN
assert 'satiety = min(1f, satiety + .24f)' in MAIN
assert 'FEEDING_FUNCTION_PROBOSCIS_ROSTRUM' in META
assert 'FEEDING_FUNCTION_INGESTION_PHARYNGEAL' in META
assert 'cb_motor' in FEED and 'mn9' in FEED and 'mn4a' in FEED and 'mn11d' in FEED
assert 'leg bristle' in SENS
assert 'REDUCTION_ID = "FBR-10-OLF2-MOTORROUTE"' in META
assert 'APP_VERSION = "1.19.4"' in META and 'APP_VERSION_CODE = 145' in META
print('NEURAL FOOD CONTACT→TASTE→PROBOSCIS→INGESTION AUDIT: PASS')
print('CURRENT BOOTSTRAP FBC SHA-256:',hashlib.sha256(FBC.read_bytes()).hexdigest())
