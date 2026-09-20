#!/usr/bin/env python3
"""Validate the frozen FlyBrain FBR-10 FBC103 artifact without rebuilding it."""
from pathlib import Path
import hashlib, json, struct
ROOT=Path(__file__).resolve().parents[1]
BIN=ROOT/'app/src/main/res/raw/malecns_reduced.bin'
META=ROOT/'app/src/main/java/com/example/flybrain/GeneratedConnectomeMeta.kt'
REPORT=ROOT/'app/src/main/res/raw/malecns_reduced_report.json'
EXPECTED_SHA='bfadc30fd113c25f9711cce6ef8f6b80e9c139fe6d229965a4adabb94d8b4e60'
EXPECTED_N=16669; EXPECTED_E=2064951; NODE=26; EDGE=12
b=BIN.read_bytes()
assert hashlib.sha256(b).hexdigest()==EXPECTED_SHA, 'FBC103 SHA-256 mismatch'
assert b[:8]==b'FBC103\x00\x00', 'bad magic'
n,e=struct.unpack_from('<II',b,8)
assert (n,e)==(EXPECTED_N,EXPECTED_E), (n,e)
assert len(b)==16+n*NODE+e*EDGE, f'bad size: {len(b)}'
# Structural endpoint validation
import numpy as np
raw=b[16+n*NODE:]
a=np.frombuffer(raw,dtype='<i4').reshape(-1,3)
assert a[:,0].min()>=0 and a[:,1].min()>=0
assert a[:,0].max()<n and a[:,1].max()<n
assert len(np.unique(a[:,:2],axis=0))==e, 'duplicate directed edges'
# Node body IDs are int64 at offset 16, one per 26-byte record.
bodies=np.frombuffer(b[16:16+n*NODE],dtype=np.uint8).reshape(n,NODE)[:,:8].copy().view('<i8').ravel()
assert len(np.unique(bodies))==n, 'duplicate body IDs'
text=META.read_text()
for token in ('FLYBRAIN_VERSION = "1.14"','FLYBRAIN_VERSION_CODE = 114','NEURONS = 16669','EDGES = 2064951','CONTACTS_RETAINED = 16783932L','FROZEN'):
    assert token in text, f'missing meta token: {token}'
r=json.loads(REPORT.read_text())
assert r['status']=='FROZEN' and r['neurons_retained']==n and r['edges_retained']==e and r['sha256']==EXPECTED_SHA
print('FROZEN CONNECTOME: PASS')
print(f'FBR-10 / FBC103 / neurons={n} / edges={e} / sha256={EXPECTED_SHA}')
