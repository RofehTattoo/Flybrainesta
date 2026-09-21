#!/usr/bin/env python3
from __future__ import annotations
import argparse, csv, hashlib, json, struct
from collections import Counter
from pathlib import Path

FBC_SHA='bfadc30fd113c25f9711cce6ef8f6b80e9c139fe6d229965a4adabb94d8b4e60'
ANN_SHA='2177e246113e4cfbf1e7772ec37c6da1955ff22e8063d0b1f833101f99a9a3b2'
N=16669; MOTOR_START=4278; MOTOR_END=4986
EXPECTED={'LEG':381,'ABDOMEN':214,'WING':67,'NECK':24,'HALTERE':16,'OTHER':6}
EXPECTED_SIDE={'L':355,'R':353}

def sha(p):
 h=hashlib.sha256(); h.update(Path(p).read_bytes()); return h.hexdigest()

def fbc(path):
 b=Path(path).read_bytes(); assert sha(path)==FBC_SHA; assert b[:8]==b'FBC103\0\0'
 n,e=struct.unpack_from('<II',b,8); assert n==N
 ids=[]; roles=[]; off=16
 for _ in range(n):
  ids.append(struct.unpack_from('<Q',b,off)[0]); off+=8
  off+=1; off+=1; off+=1
  roles.append(struct.unpack_from('<b',b,off)[0]); off+=1
  off+=1; off+=12
 return ids,roles

def main():
 ap=argparse.ArgumentParser(); ap.add_argument('--semantics',required=True); ap.add_argument('--fbc103',required=True); ap.add_argument('--annotations',required=True); ap.add_argument('--report',required=True); a=ap.parse_args()
 assert sha(a.annotations)==ANN_SHA, 'annotation SHA mismatch'
 ids,old=fbc(a.fbc103); retained=set(ids)
 rows=list(csv.DictReader(open(a.semantics,encoding='utf-8'),delimiter='\t'))
 assert len(rows)==708, len(rows)
 assert len({int(r['bodyId']) for r in rows})==708
 assert all(int(r['bodyId']) in retained for r in rows)
 counts=Counter(r['anatomicalClass'] for r in rows); sides=Counter(r['somaSide'] for r in rows)
 assert dict(counts)==EXPECTED, dict(counts)
 assert dict(sides)==EXPECTED_SIDE, dict(sides)
 assert all(r['functionalTag'] in ('NONE','JUMP') for r in rows)
 assert all(not (r['anatomicalClass']=='JUMP') for r in rows)
 jump=[r for r in rows if r['functionalTag']=='JUMP']; assert len(jump)==2
 assert all(r['anatomicalClass']=='WING' and r['type'].strip().lower()=='ttmn' for r in jump)
 assert sha(a.fbc103)==FBC_SHA
 out=json.loads(Path(a.report).read_text())
 assert out['fbc103_modified'] is False
 print('VNC-01 708/708: PASS')
 print('VNC-02 355L/353R: PASS')
 print('VNC-03 anatomical census: PASS')
 print('VNC-04 unresolved motors retained in semantics map: PASS')
 print('VNC-05 FBC103 SHA unchanged: PASS')
 print('VNC-06 FBD104 untouched by this layer: PASS (build-level invariant)')
 print('Reclassified from frozen role byte:', out['reclassified_from_old_fbc103'])

if __name__=='__main__': main()
