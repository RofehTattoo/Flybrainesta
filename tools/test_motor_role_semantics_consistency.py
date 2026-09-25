from pathlib import Path
import ast

ROOT=Path(__file__).resolve().parents[1]
B=(ROOT/"tools/build_connectome.py").read_text()
V=(ROOT/"tools/build_vnc_motor_semantics.py").read_text()
assert 'REDUCTION_ID = "FBR-10-OLF2-MOTORROUTE"' in B
assert '"fl": 1, "ml": 1, "hl": 1' in B
assert '"wm": 2, "nm": 4, "hm": 3, "ad": 5, "xm": 6' in B
assert 'SUBCLASS_TO_CLASS = {' in V
assert '"fl": "LEG"' in V and '"ad": "ABDOMEN"' in V
print("MOTOR ROLE SEMANTICS CONSISTENCY: PASS")
