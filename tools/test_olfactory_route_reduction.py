from pathlib import Path
import ast

ROOT = Path(__file__).resolve().parents[1]
B = (ROOT / "tools" / "build_connectome.py").read_text()

assert 'REDUCTION_ID = "FBR-10-OLF2-MOTORROUTE"' in B
assert 'TARGET_ORNS = 264' in B
assert 'EXPECTED_ORN_TYPES = 54' in B
assert 'UNTYPED_ORN_BODY_IDS = frozenset({242812, 242908, 488209, 956041})' in B
assert 'def is_olfactory_orn' in B
assert 'route_olfactory_forward' in B
assert 'route_olfactory_motor' in B
assert 'protected_orns' in B
assert 'official_untyped' in B
assert 'typed_orn_selection_source' in B
assert 'retained_untyped_orn_ids' in B

# Check the exclusion of olfactory ORNs from the generic extra pool
# without depending on exact whitespace/indentation in the builder.
assert 'extra_pool = annotated[' in B
assert '~annotated.bodyId.isin(selected_ids_now)' in B
assert '~annotated["is_olfactory_orn"]' in B

assert 'entryNerve' in B
assert 'annotations[annotations["status"]' not in B
assert 'pinned MaleCNS v1.0 release contains 166,700' in B
assert 'len(annotated)' in B
assert 'side_from_nerve' not in B
assert 'sc == "ol_sensory"' not in B
assert 'edge_weight_definition' in B
assert 'raw positive MaleCNS contact counts' in B
assert 'NT_SIGN' not in B
assert 'consensus_nt' not in B
assert 'normalized_edges' not in B
assert 'unresolved_edges_omitted' not in B

module = ast.parse(B)

# Extract the shared ORN helper, classifier, and the official four-ID
# exception constant directly from build_connectome.py.
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

# Normal typed ORNs.
assert ns["is_olfactory_orn"]({
    "bodyId": 1,
    "superclass": "cb_sensory",
    "class": "olfactory",
    "type": "ORN_TEST",
    "entryNerve": "AN",
})

assert ns["is_olfactory_orn"]({
    "bodyId": 2,
    "superclass": "cb_sensory",
    "class": "olfactory",
    "type": "ORN_TEST",
    "entryNerve": "MxLbN",
})

# The four official MaleCNS v1.0 ORNs whose published type is NULL.
for body_id in (242812, 242908, 488209, 956041):
    assert ns["is_olfactory_orn"]({
        "bodyId": body_id,
        "superclass": "cb_sensory",
        "class": "olfactory",
        "type": None,
        "entryNerve": "AN",
    })

# Selection must explicitly reserve the four official untyped ORNs before the
# typed group-by; relying on pandas' default group-by null handling would drop them.
assert 'orn_selection_source["bodyId"].isin(UNTYPED_ORN_BODY_IDS)' in B
assert 'remaining_orn -= len(official_untyped)' in B
assert 'orn_selection_source["type"].astype(str).str.strip().ne("")' in B
assert 'selected_orns.loc[' in B
assert 'selected_orns["bodyId"].isin(UNTYPED_ORN_BODY_IDS)' in B

# Empty-string type values are an internal selection representation for the
# four official untyped ORNs and must not count as published type+entryNerve
# combinations.
assert 'pairs = pairs[pairs["type"].ne("")]' in B

# Historical visual cells must remain visual, not olfactory.
assert ns["classify_channel"]({
    "bodyId": 3,
    "superclass": "ol_sensory",
    "class": "visual",
    "type": "R7d",
}) == 0

assert ns["classify_channel"]({
    "bodyId": 4,
    "superclass": "cb_sensory",
    "class": "olfactory",
    "type": "ORN_TEST",
    "entryNerve": "AN",
}) == 1

print("OLF ROUTE REDUCTION STATIC AUDIT: PASS")
