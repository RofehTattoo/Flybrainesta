#!/usr/bin/env python3
"""Regression guard: three-edge visual scores must exist before they are merged."""
from pathlib import Path

source = Path(__file__).with_name("build_connectome.py").read_text(encoding="utf-8")

accumulate_turn = source.index("np.add.at(visual_three_turn, ai[m3_vturn], path_score)")
accumulate_escape = source.index("np.add.at(visual_three_escape, ai[m3_vescape], path_score)")
merge_turn = source.index("visual_turn = np.maximum(visual_turn, visual_three_turn)")
merge_escape = source.index("threat_escape = np.maximum(threat_escape, visual_three_escape)")
rebuild_turn = source.index("route_turn = visual_turn + turn_motor_path", merge_turn)
rebuild_escape = source.index("route_escape = threat_escape + escape_motor_path", merge_escape)
validate_routes = source.index('("visual_primary_turn", visual_turn)')

assert accumulate_turn < merge_turn, "visual three-edge turn score merged before accumulation"
assert accumulate_escape < merge_escape, "visual three-edge escape score merged before accumulation"
assert merge_turn < rebuild_turn < validate_routes, "turn route must be recomputed before route validation"
assert merge_escape < rebuild_escape < validate_routes, "escape route must be recomputed before route validation"
print("VISUAL THREE-EDGE SCORE ORDER: PASS")
