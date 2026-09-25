from pathlib import Path
import ast

ROOT = Path(__file__).resolve().parents[1]
DYN = (ROOT / "tools/build_fbr10_dynamics.py").read_text(encoding="utf-8")

module = ast.parse(DYN)
keep = []
for node in module.body:
    if isinstance(node, ast.FunctionDef) and node.name in {"_is_known_nt", "resolve_nt_label"}:
        keep.append(node)
    elif isinstance(node, ast.Assign) and any(
        isinstance(t, ast.Name) and t.id == "NT_SIGN" for t in node.targets
    ):
        keep.append(node)
ns = {}
exec(compile(ast.Module(body=keep, type_ignores=[]), "<nt_helpers>", "exec"), ns)

resolve = ns["resolve_nt_label"]
assert resolve("acetylcholine", "gaba") == ("acetylcholine", "consensus")
assert resolve("unclear", "gaba") == ("gaba", "predicted_fallback")
assert resolve("", "glutamate") == ("glutamate", "predicted_fallback")
assert resolve("made_up", "serotonin") == ("serotonin", "predicted_fallback")
assert resolve("unclear", "made_up")[0] is None
assert resolve(None, None)[0] is None

# The runtime sign map must explicitly represent the known MaleCNS transmitter
# classes used by the reference whole-brain model. Unknowns remain unresolved.
for token in (
    '"acetylcholine": 1.0',
    '"dopamine": 1.0',
    '"octopamine": 1.0',
    '"serotonin": 1.0',
    '"histamine": -1.0',
    '"gaba": -1.0',
    '"glutamate": -1.0',
):
    assert token in DYN, token

assert 'columns=["body", "consensus_nt", "predicted_nt"]' in DYN
print("NT RESOLUTION / PREDICTED FALLBACK AUDIT: PASS")
