#!/usr/bin/env python3
"""V1.19.38 release-contract audit.

Checks all active source/test contracts that are supposed to describe the current
release. Historical changelog/audit documents are deliberately excluded.
"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
CURRENT = "1.19.38"
CODE = "179"

gradle = (ROOT/"app/build.gradle.kts").read_text(encoding="utf-8")
meta = (ROOT/"app/src/main/java/com/example/flybrain/GeneratedConnectomeMeta.kt").read_text(encoding="utf-8")
manifest = (ROOT/"app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")

assert f'versionName = "{CURRENT}"' in gradle
assert f'versionCode = {CODE}' in gradle
assert f'APP_VERSION = "{CURRENT}"' in meta
assert f'APP_VERSION_CODE = {CODE}' in meta
assert f'android:label="FlyBrain V{CURRENT}"' in manifest

# Active Python tests must not assert an older release identity.
excluded = {
    "test_release_version_contract.py",
}
for p in (ROOT/"tools").glob("test_*.py"):
    if p.name in excluded:
        continue
    text = p.read_text(encoding="utf-8", errors="ignore")
    for old in ("1.19.27", "1.19.28", "1.19.36"):
        # Reject stale current-release assertions in active tooling.
        for line in text.splitlines():
            if old in line and ("assert" in line or "FLYBRAIN_RELEASE" in line or "APP_VERSION_CODE" in line or "versionName" in line or "versionCode" in line):
                raise AssertionError(f"{p.name}: stale active version contract: {line.strip()}")

print("V1.19.38 RELEASE VERSION CONTRACT: PASS")
