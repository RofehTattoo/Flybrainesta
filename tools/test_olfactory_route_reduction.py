from pathlib import Path
import ast

ROOT = Path(__file__).resolve().parents[1]
B = (ROOT / "tools" / "build_connectome.py").read_text()

assert 'REDUCTION_ID = "FBR-10-OLF1"' in B
assert 'TARGET_ORNS = 264' in B
assert 'EXPECTED_ORN_TYPES = 54' in B
assert 'UNTYPED_ORN_BODY_IDS = frozenset({242812, 242908, 488209, 956041})' in B
assert 'def is_olfactory_orn' in B
assert 'route_olfactory_forward' in B
assert 'route_olfactory_motor' in B
assert 'protected_orns' in B
assert "extra_pool = annotated[\\n            ~annotated.bodyId.isin(selected_ids_now)\\n            & ~annotated[\"is_olfactory_orn\"]" in B
assert 'entryNerve' in B
assert 'annotations[annotations["status"]' not in B
assert 'official release contains 166,700' in B
assert 'len(annotated)' in B
assert 'side_from_nerve' not in B
assert 'sc == "ol_sensory"' not in B

module = ast.parse(B)

keep = []
for node in module.body:
    if isinstance(node, ast.FunctionDef) and node.name in {
        "clean",
        "is_olfactory_orn",
        "classify_channel",
    }:
        keep.append(node)
    elif isinstance(node, ast.Assign):
        if any(
            isinstance(target, ast.Name)
            and target.id == "UNTYPED_ORN_BODY_IDS"
            for target in node.targets
        ):
            keep.append(node)

class FakePD:
    @staticmethod
    def isna(v):
        return v is None

ns = {"pd": FakePD}
exec(
    compile(ast.Module(body=keep, type_ignores=[]), "<helpers>", "exec"),
    ns,
)

assert ns["is_olfactory_orn"]({
    "superclass": "cb_sensory",
    "class": "olfactory",
    "type": "ORN_TEST",
    "entryNerve": "AN",
})

assert ns["is_olfactory_orn"]({
    "superclass": "cb_sensory",
    "class": "olfactory",
    "type": "ORN_TEST",
    "entryNerve": "MxLbN",
})

# The four official untyped ORNs must also be recognized without inventing
# a synthetic type label.
for body_id in (242812, 242908, 488209, 956041):
    assert ns["is_olfactory_orn"]({
        "bodyId": body_id,
        "superclass": "cb_sensory",
        "class": "olfactory",
        "type": None,
        "entryNerve": "AN",
    })

assert ns["classify_channel"]({
    "superclass": "ol_sensory",
    "class": "visual",
    "type": "R7d",
}) == 0

assert ns["classify_channel"]({
    "superclass": "cb_sensory",
    "class": "olfactory",
    "type": "ORN_TEST",
    "entryNerve": "AN",
}) == 1

print("OLF ROUTE REDUCTION STATIC AUDIT: PASS")
