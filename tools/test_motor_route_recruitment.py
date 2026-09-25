from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
B=(ROOT/"tools/build_connectome.py").read_text(encoding="utf-8")
M=(ROOT/"app/src/main/java/com/example/flybrain/MainActivity.kt").read_text(encoding="utf-8")

assert 'route_olfactory_forward' in B
assert 'forward_motor_path = np.sqrt(' in B
assert 'cell_to_motor[1]' in B
assert 'cell_to_any_desc = np.zeros' in B
assert 'any_desc_to_cell = np.zeros' in B
assert 'all_cell_to_desc = cell_to_any_desc' in B
assert 'all_desc_to_cell = any_desc_to_cell' in B
assert 'olfactory_to_desc_path = np.sqrt(' in B
assert 'descending_to_leg_path = np.sqrt(' in B
assert 'olfactory_three_edge = np.zeros' in B
assert 'desc_leg_three_edge = np.zeros' in B
assert 'olfactory_to_desc_path = np.maximum(olfactory_to_desc_path, olfactory_three_edge)' in B
assert 'descending_to_leg_path = np.maximum(descending_to_leg_path, desc_leg_three_edge)' in B
assert 'route_olfactory_to_desc' in B
assert 'route_desc_to_leg' in B
assert 'intermediate_pool' in B
assert 'all descending and VNC motor neurons are retained' in B
assert 'classify_motor_role' in B
assert 'fl": 1' in B and 'wm": 2' in B and 'nm": 4' in B and 'hm": 3' in B and 'ad": 5' in B
assert 'loadVncMotorSemantics()' in M
assert 'MOTOR_LEG' in M
assert 'legRateHz' in M
assert 'approachAction' not in M[M.index('private fun driveBody'):M.index('private fun runNeuralSimulation')]
print('MOTOR RECRUITMENT STATIC AUDIT: PASS')
