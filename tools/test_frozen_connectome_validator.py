#!/usr/bin/env python3
"""Dependency-free smoke test for the frozen-connectome validator imports."""
import ast
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
src = (ROOT / 'tools/validate_frozen_connectome.py').read_text(encoding='utf-8')
tree = ast.parse(src)
imports = [n for n in ast.walk(tree) if isinstance(n, (ast.Import, ast.ImportFrom))]
for node in imports:
    names = [a.name.split('.')[0] for a in node.names]
    assert 'numpy' not in names, 'frozen validator must not require NumPy'
assert 'from fbc103_reader import' in src
assert 'read_fbc103(BIN)' in src
assert 'validate_edges(BIN, EXPECTED_E, EXPECTED_N)' in src
print('FROZEN VALIDATOR DEPENDENCY CHECK: PASS')
