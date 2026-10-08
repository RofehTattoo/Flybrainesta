from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
BUILD = (ROOT / "tools/build_connectome.py").read_text(encoding="utf-8")
SENS = (ROOT / "tools/build_sensory_input_map.py").read_text(encoding="utf-8")
META = (ROOT / "app/src/main/java/com/example/flybrain/GeneratedConnectomeMeta.kt").read_text(encoding="utf-8")
GRADLE = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")

assert 'primary_visual_source_count = int(is_primary_visual.sum())' in BUILD
assert 'annotated["is_primary_visual"] = is_primary_visual' in BUILD
assert 'selected["is_primary_visual"].to_numpy(bool)' in BUILD
assert '5900 <= primary_visual_source_count <= 6200' in BUILD
assert 'TARGET_PRIMARY_VISUAL = int(round(primary_visual_source_count * 0.10))' in BUILD
assert 'TARGET_VISUAL_TOTAL = int(round(visual_source_count * 0.10))' in BUILD
assert 'balanced' in BUILD and 'receptor family and official anatomical side' in BUILD
assert 'visual_primary_source["_visual_family"]' in BUILD
assert 'visual_primary_source["_visual_side"]' in BUILD
assert 'visual_relay_target = TARGET_VISUAL_TOTAL - TARGET_PRIMARY_VISUAL' in BUILD
assert 'annotated["channel"] != 0' in BUILD
assert 'visual_three_turn' in BUILD and 'visual_three_escape' in BUILD
assert 'vis_turn_target = cell_to_desc[2][ci]' in BUILD
assert 'vis_escape_target = cell_to_desc[4][ci]' in BUILD
assert 'route_visual_relay' in BUILD
assert 'route_visual_primary_source' in BUILD
assert 'retained_primary_visual_in' in BUILD
assert 'route_visual_turn_retained' in BUILD and 'route_visual_escape_retained' in BUILD
assert 'induced visual FBC103 has no retained PR->relay->DN bridge' in BUILD
assert 'expected_primary_visual = int(round(source_primary_visual_count * 0.10))' in SENS
assert 'counts["VIS"] != expected_primary_visual' in SENS
assert 'const val RETAINED_OLFACTORY_ORNS = 264' in META
assert 'const val REDUCTION_ID = "FBR-10-OLF2-MOTORROUTE"' in META
assert 'const val APP_VERSION_CODE = 175' in META
assert 'versionName = "1.19.34"' in GRADLE
assert 'versionCode = 175' in GRADLE
print('V1.19.28 VISUAL FBR-10 RECONSTRUCTION CONTRACT: PASS')

# When run after build_connectome.py in CI, verify the generated report itself.
REPORT = ROOT / "app/src/main/res/raw/malecns_reduced_report.json"
if not REPORT.exists():
    raise AssertionError("Canonical generated report is missing; release validation must not SKIP")
import json
rep = json.loads(REPORT.read_text(encoding="utf-8"))
if "visual_primary_source_count" not in rep:
    raise AssertionError("Report is a bootstrap/non-canonical report; release validation must not SKIP")
src = int(rep["visual_primary_source_count"])
expected = int(round(src * 0.10))
assert 5900 <= src <= 6200
assert int(rep["visual_primary_target"]) == expected
assert int(rep["visual_primary_retained"]) == expected
assert int(rep["visual_target_total"]) == int(round(int(rep["visual_source_count"]) * 0.10))
assert int(rep["visual_primary_route_turn_selected_nonzero"]) > 0
assert int(rep["visual_primary_route_escape_selected_nonzero"]) > 0
print("V1.19.28 GENERATED VISUAL FBR-10 REPORT: PASS")
