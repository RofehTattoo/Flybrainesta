from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
BC = (ROOT / "tools/build_connectome.py").read_text(encoding="utf-8")
MAIN = (ROOT / "app/src/main/java/com/example/flybrain/MainActivity.kt").read_text(encoding="utf-8")
META = (ROOT / "app/src/main/java/com/example/flybrain/GeneratedConnectomeMeta.kt").read_text(encoding="utf-8")
assert "def is_primary_visual_receptor" in BC
assert 'sc == "ol_sensory"' in BC
assert 'cl == "visual"' in BC
assert 'fw in {"R1-6", "R7", "R8"}' in BC
assert "primary_visual_in = np.zeros(len(ids)" in BC
assert "m = is_primary_visual[ai]" in BC
assert "visual_turn = np.sqrt(np.maximum(0.0, primary_visual_in * cell_to_desc[2]))" in BC
assert "threat_escape = np.sqrt(" in BC and "primary_visual_in * cell_to_desc[4]" in BC
assert '"visual_primary_turn"' in BC and '"visual_primary_escape"' in BC
assert '"visual_primary_to_turn_source_nonzero"' in BC
assert '"visual_primary_to_escape_source_nonzero"' in BC
assert 'val lightIntensity = stimulusIntensity(lightOn, lightX, lightY, .42f)' in MAIN
assert 'val dangerBaseIntensity = stimulusIntensity(dangerOn, dangerX, dangerY, .38f)' in MAIN
assert 'val visualThreatComponent = (dangerBaseIntensity *' in MAIN
assert 'setMappedSensoryRate(' in MAIN
assert 'visualReceptorIndices' in MAIN
assert 'visualRateDisplay = .72f * visualRateDisplay + .28f * visualRate' in MAIN
assert 'const val APP_VERSION = "1.19.34"' in META
assert 'const val APP_VERSION_CODE = 175' in META
print("V1.19.28 VISUAL PHOTORECEPTOR ROUTE / LIGHT-LOOM ENCODER AUDIT: PASS")
