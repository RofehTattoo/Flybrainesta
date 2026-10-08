from pathlib import Path
import json, hashlib, struct

ROOT=Path(__file__).resolve().parents[1]
report=ROOT/"app/src/main/assets/malecns_reduced_report.json"
assert report.exists(), "generated FBR-10 report missing"
d=json.loads(report.read_text())
# Accept either explicit path counters or node-population metadata; fail closed.
turn=int(d.get("retained_visual_pr_turn_dn_paths",0))
esc=int(d.get("retained_visual_pr_escape_dn_paths",0))
assert turn > 0, f"no retained PR->relay->TURN-DN path: {turn}"
assert esc > 0, f"no retained PR->relay->ESCAPE-DN path: {esc}"
assert int(d.get("retained_neurons",0)) >= 16669
print("V1.19.39 VISUAL PATH PRESERVATION: PASS")
