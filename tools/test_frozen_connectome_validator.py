#!/usr/bin/env python3
"""Dependency-free smoke test for the current FBR-10-OLF2-MOTORROUTE validator."""
import ast
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
src = (ROOT / 'tools/validate_frozen_connectome.py').read_text(encoding='utf-8')
tree = ast.parse(src)
imports = [n for n in ast.walk(tree) if isinstance(n, (ast.Import, ast.ImportFrom))]
for node in imports:
    names = [a.name.split('.')[0] for a in node.names]
    assert 'numpy' not in names, 'validator must not require NumPy'
assert 'from fbc103_reader import' in src
assert 'FBR-10-OLF2-MOTORROUTE' in src
assert 'BINARY_SHA256' in src
print('CURRENT FBR-10-OLF2-MOTORROUTE VALIDATOR DEPENDENCY CHECK: PASS')
