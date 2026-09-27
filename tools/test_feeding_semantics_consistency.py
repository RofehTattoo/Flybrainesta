from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
META = (ROOT / "app/src/main/java/com/example/flybrain/GeneratedConnectomeMeta.kt").read_text()
BUILDER = (ROOT / "tools/build_connectome.py").read_text()
FEED = (ROOT / "tools/build_feeding_motor_semantics.py").read_text()
MAIN = (ROOT / "app/src/main/java/com/example/flybrain/MainActivity.kt").read_text()

required_meta_tokens = [
    'FEEDSEM103',
    'FEEDING_FUNCTION_PROBOSCIS_ROSTRUM = 1',
    'FEEDING_FUNCTION_PROBOSCIS_HAUSTELLUM = 2',
    'FEEDING_FUNCTION_PROBOSCIS_LABELLUM = 3',
    'FEEDING_FUNCTION_PROBOSCIS_SPREAD = 4',
    'FEEDING_FUNCTION_INGESTION_PHARYNGEAL = 5',
    'FEEDING_FUNCTION_INGESTION_CROP_ENTRY = 6',
]
for token in required_meta_tokens:
    assert token in META, token
    assert token in BUILDER, f"GeneratedConnectomeMeta regeneration would drop: {token}"

for token in ['"mn9"', '"mn4a"', '"mn6"', '"mn8"', '"mn11d"', '"mn11v"', '"cem"',
              '"cb_motor"', '"pm"']:
    assert token in FEED, token

assert 'if (tag in 1..FEEDING_FUNCTION_INGESTION_CROP_ENTRY)' in MAIN
assert 'functionCode !in 1..FEEDING_FUNCTION_INGESTION_CROP_ENTRY' in MAIN
assert 'val newIngestionEpisode = ingestionNeural && !ingestionEpisodeLatched' in MAIN
assert 'return if (newIngestionEpisode) 1f else 0f' in MAIN
assert MAIN.count('memoryTrace =') == 3, 'ingestion readout must not double-count memoryTrace; reward is applied by learn()'
print('FEEDSEM103 / GENERATED-META / INGESTION LATCH CONSISTENCY AUDIT: PASS')
