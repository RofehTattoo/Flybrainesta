#!/usr/bin/env python3
"""Audit retained FBR-10 topology for GUST -> feeding motor and GUST -> HALT paths.

The test does not mutate the graph. It uses the official annotation source to
classify retained gustatory/feeding populations, then searches the existing FBC103
edges. It deliberately accepts paths up to four hops because the published feeding
circuit contains multiple intermediate layers.
"""
from __future__ import annotations
import argparse, struct
from pathlib import Path
import numpy as np
import pyarrow.feather as feather
from fbc103_reader import read_fbc103, sha256

N = 16669
NODE_SIZE = 26
HEADER_SIZE = 16
FEEDING_TYPES = {"mn9","mn4a","mn6","mn8","mn11d","mn11v","cem"}

def clean(v):
    if v is None: return ""
    if hasattr(v, "as_py"): v=v.as_py()
    return str(v).strip()

def main():
    ap=argparse.ArgumentParser(); ap.add_argument("--annotations",required=True); ap.add_argument("--fbc103",required=True); args=ap.parse_args()
    annp=Path(args.annotations); fbc=Path(args.fbc103)
    nodes=read_fbc103(fbc, expected_sha=None)
    data=fbc.read_bytes(); n,e=struct.unpack_from('<II',data,8)
    assert n==N
    ann=feather.read_table(annp, columns=["bodyId","type","superclass","class","subclass"]).to_pylist()
    by_ann={int(r["bodyId"]):r for r in ann}
    gust=[]; tarsal=[]; feeding=[]
    for i,node in enumerate(nodes):
        r=by_ann.get(int(node["bodyId"]))
        if not r: continue
        sc=clean(r["superclass"]).lower(); cl=clean(r["class"]).lower(); sub=clean(r["subclass"]).lower(); typ=clean(r["type"]).lower()
        if cl=="gustatory" and sc in {"cb_sensory","vnc_sensory"}:
            gust.append(i)
            if sub=="leg bristle": tarsal.append(i)
        if sc=="cb_motor" and sub=="pm" and typ in FEEDING_TYPES:
            feeding.append(i)
    assert gust, "no retained GUST"
    assert tarsal, "no retained tarsal gustatory receptors"
    assert feeding, "no retained feeding motor neurons"
    feed_set=set(feeding)
    # Verify edge table and build compact source CSR.
    off=HEADER_SIZE+N*NODE_SIZE
    arr=np.frombuffer(data,dtype=np.dtype([('src','<i4'),('dst','<i4'),('w','<f4')]),count=e,offset=off)
    assert np.all(arr['src']>=0) and np.all(arr['src']<N)
    assert np.all(arr['dst']>=0) and np.all(arr['dst']<N)
    assert np.all(arr['w']>0)
    counts=np.bincount(arr['src'],minlength=N)
    order=np.argsort(arr['src'],kind='mergesort')
    dst=arr['dst'][order]
    starts=np.empty(N+1,dtype=np.int64); starts[0]=0; starts[1:]=np.cumsum(counts)
    def neigh(u): return dst[starts[u]:starts[u+1]]
    # Multi-source BFS from the exact tarsal gustatory receptors used by the
    # runtime contact encoder. The all-GUST population is retained as a secondary
    # topology audit, but the release gate is tarsal-GUST -> feeding.
    def bfs(seed_indices):
        front=np.array(sorted(set(seed_indices)),dtype=np.int32); seen=np.zeros(N,dtype=bool); seen[front]=True
        feed_hits=set(); halt_hits_by_depth={}
        for depth in range(1,5):
            chunks=[]
            for u in front:
                x=neigh(int(u))
                if len(x): chunks.append(x)
            if not chunks: break
            cand=np.unique(np.concatenate(chunks))
            cand=cand[~seen[cand]]
            seen[cand]=True
            if len(cand):
                feed_hits.update(feed_set.intersection(set(map(int,cand))))
            halts={int(i) for i,node in enumerate(nodes) if int(node['haltRole'])>0}
            h=set(map(int,cand)).intersection(halts)
            if h: halt_hits_by_depth[depth]=sorted(h)
            front=cand
            if not len(front): break
        return feed_hits, halt_hits_by_depth

    tarsal_feed_hits, tarsal_halt_hits = bfs(tarsal)
    gust_feed_hits, gust_halt_hits = bfs(gust)
    assert tarsal_feed_hits==feed_set, f"not every retained feeding target is reachable from tarsal GUST <=4 hops: missing={sorted(feed_set-tarsal_feed_hits)}"
    assert tarsal_halt_hits, "no retained tarsal GUST->HALT path <=4 hops"
    assert gust_feed_hits==feed_set, f"not every retained feeding target is reachable from all GUST <=4 hops: missing={sorted(feed_set-gust_feed_hits)}"
    report={
        'fbc103_sha256':sha256(fbc),'neurons':N,'edges':e,
        'retained_gustatory':len(gust),'retained_tarsal_gustatory':len(tarsal),'retained_feeding_motor':len(feeding),
        'feeding_targets_reached_from_tarsal_le4':len(tarsal_feed_hits),
        'feeding_targets_reached_from_all_gust_le4':len(gust_feed_hits),
        'tarsal_halt_hits_by_depth':{str(k):len(v) for k,v in tarsal_halt_hits.items()},
        'all_gust_halt_hits_by_depth':{str(k):len(v) for k,v in gust_halt_hits.items()},
        'status':'PASS'
    }
    print(report)

if __name__=='__main__': main()
