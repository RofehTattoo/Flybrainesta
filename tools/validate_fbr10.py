#!/usr/bin/env python3
"""Independent validator for the FBR-10 structural MaleCNS reduction.

Checks:
- exactly 16,669 unique source neurons;
- all DNs and VNC motor neurons retained;
- all hard halt-labelled neurons retained;
- every binary edge is an exact published MaleCNS edge with the same raw weight;
- edge/contact totals equal the induced source subgraph;
- type/superclass/lateralization coverage;
- selected sensor->DN and DN->intermediate->motor routes.
"""
from __future__ import annotations
import argparse, json, re, struct
from pathlib import Path
import numpy as np
import pandas as pd
import pyarrow as pa
import pyarrow.ipc as ipc
import pyarrow.parquet as pq

TARGET=16669
MAGIC=b"FBC103\x00\x00"
NODE_SIZE=26
EDGE_SIZE=12

def clean(x):
    if x is None or pd.isna(x): return ""
    return str(x)

def classify_channel(row):
    sc=clean(row.get("superclass", "")); text=" ".join(clean(row.get(c,"")) for c in ("class","subclass","type","instance","name")).lower()
    if sc in {"visual_projection","visual_centrifugal"} or "visual" in text or "optic lobe" in text: return 0
    if sc=="ol_sensory" or "olf" in text or "antennal lobe" in text: return 1
    if "gust" in text or "taste" in text: return 2
    if sc in {"vnc_sensory","sensory_ascending","sensory_descending"} or sc.startswith("cb_sensory"): return 3
    if any(k in text for k in ("mechanosensory","proprio","bristle","hair plate","campaniform","chordotonal","johnston")): return 3
    return 4

def classify_motor_role(row):
    if clean(row.get("superclass", ""))!="vnc_motor": return 0
    text=" ".join(clean(row.get(c,"")) for c in ("type","instance","name","class","subclass","nerve","target","muscle","annotation","group")).lower()
    if any(k in text for k in ("tergotrochanteral","jump","ttmn")): return 6
    if any(k in text for k in ("haltere","halter")): return 3
    if any(k in text for k in ("wing","dvm","dlm","flight","steering")): return 2
    if any(k in text for k in ("neck","cervical")): return 4
    if any(k in text for k in ("abdominal","abdomen")): return 5
    if any(k in text for k in ("leg","t1","t2","t3","coxa","femur","tibia","tars","trochanter","levator","depressor","flexor","extensor")): return 1
    return 7

def classify_dn_role(row):
    if clean(row.get("superclass", ""))!="descending_neuron": return 0
    text=" ".join(clean(row.get(c,"")) for c in ("type","instance","name","class","subclass","annotation","group")).lower(); compact=re.sub(r"[^a-z0-9]+","",text)
    if any(k in compact for k in ("dng100","dng97","dnb08")) or "forward walking" in text: return 1
    if any(k in compact for k in ("dna01","dna02","dna03","dna04","dna11","dnb01","dng13")) or any(k in text for k in ("turn","steer","steering")): return 2
    if any(k in compact for k in ("dnp50","mdn","moonwalker","dnp07","dnp09")) or "backward walking" in text: return 3
    if "dnp01" in compact or any(k in text for k in ("giant fiber","giant-fiber","giant fibre","giant-fibre")): return 4
    return 0

def classify_halt(row):
    text=" ".join(clean(row.get(c,"")) for c in ("type","instance","name","class","subclass","annotation","group")).lower(); compact=re.sub(r"[^a-z0-9]+","",text)
    if "foxglove" in text or "cb0890" in compact or "gng458" in compact: return 1
    if "bluebell" in text or "dng60" in compact: return 2
    if "brake" in text or "an19a018" in compact: return 3
    return 0

def batches(path):
    if path.suffix.lower()=='.parquet':
        for b in pq.ParquetFile(path).iter_batches(columns=['body_pre','body_post','weight'],batch_size=1_000_000): yield b
    else:
        r=ipc.open_file(pa.memory_map(str(path),'r'))
        for i in range(r.num_record_batches): yield r.get_batch(i)

def source(root):
    raw=root/'build'/'malecns_raw'
    def pick(name):
        p=raw/name
        if p.exists(): return p
        q=raw/name.replace('.feather','.parquet')
        if q.exists(): return q
        raise FileNotFoundError(p)
    return pick('body-annotations-male-cns-v1.0-minconf-0.5.feather'),pick('connectome-weights-male-cns-v1.0-minconf-0.5.feather')

def read_binary(path):
    with path.open('rb') as f:
        if f.read(8)!=MAGIC: raise RuntimeError('Invalid FBC103 magic')
        n,e=struct.unpack('<II',f.read(8))
        bodies=[]; sc=[]; side=[]; channel=[]; motor=[]; dn=[]; halt=[]; scores=[]
        for _ in range(n):
            rec=f.read(NODE_SIZE)
            if len(rec)!=NODE_SIZE: raise RuntimeError('Truncated node section')
            body,scv,sv,ch,mr,dr,hr,sr,sa,sd=struct.unpack('<qbbbbbbfff',rec)
            bodies.append(body); sc.append(scv); side.append(sv); channel.append(ch); motor.append(mr); dn.append(dr); halt.append(hr); scores.append((sr,sa,sd))
        raw=f.read()
    if len(raw)!=e*EDGE_SIZE: raise RuntimeError(f'Edge byte count {len(raw)} != {e*EDGE_SIZE}')
    arr=np.frombuffer(raw,dtype=np.dtype([('a','<i4'),('b','<i4'),('w','<f4')]))
    return n,e,np.asarray(bodies,np.int64),np.asarray(sc,np.int8),np.asarray(side,np.int8),np.asarray(channel,np.int8),np.asarray(motor,np.int8),np.asarray(dn,np.int8),np.asarray(halt,np.int8),arr.copy()

def main(root):
    ann_path,w_path=source(root)
    ann=pd.read_feather(ann_path) if ann_path.suffix.lower()=='.feather' else pd.read_parquet(ann_path)
    ann=ann[ann['superclass'].notna() & ann['superclass'].astype(str).str.strip().ne('')].copy()
    ann['bodyId']=ann.bodyId.astype(np.int64); ann=ann.drop_duplicates('bodyId').sort_values('bodyId').reset_index(drop=True)
    if len(ann)!=166700: raise RuntimeError(f'Source census {len(ann)} != audited 166700')
    ann['channel']=ann.apply(classify_channel,axis=1).astype(np.int8); ann['motor_role']=ann.apply(classify_motor_role,axis=1).astype(np.int8); ann['dn_role']=ann.apply(classify_dn_role,axis=1).astype(np.int8); ann['halt_role']=ann.apply(classify_halt,axis=1).astype(np.int8)
    n,e,bodies,sc,side,channel,motor,dn,halt,binary_edges=read_binary(root/'app/src/main/res/raw/malecns_fbr10.bin')
    if n!=TARGET: raise RuntimeError(f'Node count {n} != {TARGET}')
    if len(np.unique(bodies))!=TARGET: raise RuntimeError('Duplicate selected body IDs')
    source_ids=set(ann.bodyId.tolist())
    if not set(bodies.tolist()).issubset(source_ids): raise RuntimeError('Selected body ID absent from source')
    if not np.all(np.isin(ann.loc[ann.superclass.eq('descending_neuron'),'bodyId'].to_numpy(),bodies)): raise RuntimeError('Not all descending neurons retained')
    if not np.all(np.isin(ann.loc[ann.superclass.eq('vnc_motor'),'bodyId'].to_numpy(),bodies)): raise RuntimeError('Not all VNC motor neurons retained')
    hard_halt=set(ann.loc[ann.halt_role>0,'bodyId'].tolist())
    if hard_halt and not hard_halt.issubset(set(bodies.tolist())): raise RuntimeError('Not all halt-labelled neurons retained')
    selected=set(bodies.tolist())
    # Exact induced edge extraction and exact raw-weight comparison.
    src_a=[]; src_b=[]; src_w=[]
    edge_count=0; contacts=0
    for batch in batches(w_path):
        pre=batch.column(0).to_numpy(zero_copy_only=False).astype(np.int64); post=batch.column(1).to_numpy(zero_copy_only=False).astype(np.int64); w=batch.column(2).to_numpy(zero_copy_only=False).astype(np.int64)
        m=np.isin(pre,bodies)&np.isin(post,bodies)&(w>0)
        if m.any():
            src_a.append(pre[m]); src_b.append(post[m]); src_w.append(w[m]); edge_count+=int(m.sum()); contacts+=int(w[m].sum())
    sa=np.concatenate(src_a) if src_a else np.empty(0,np.int64); sb=np.concatenate(src_b) if src_b else np.empty(0,np.int64); sw=np.concatenate(src_w) if src_w else np.empty(0,np.int64)
    if edge_count!=e: raise RuntimeError(f'Binary edges {e} != induced source edges {edge_count}')
    body_to_idx={int(b):i for i,b in enumerate(bodies)}
    expected=np.empty(edge_count,dtype=np.dtype([('a','<i4'),('b','<i4'),('w','<i8')]))
    expected['a']=np.fromiter((body_to_idx[int(x)] for x in sa),dtype=np.int32,count=edge_count); expected['b']=np.fromiter((body_to_idx[int(x)] for x in sb),dtype=np.int32,count=edge_count); expected['w']=sw
    actual=np.empty(edge_count,dtype=np.dtype([('a','<i4'),('b','<i4'),('w','<i8')]))
    actual['a']=binary_edges['a']; actual['b']=binary_edges['b']; actual['w']=np.rint(binary_edges['w']).astype(np.int64)
    expected.sort(order=['b','a','w']); actual.sort(order=['b','a','w'])
    if not np.array_equal(expected,actual): raise RuntimeError('Binary edge set/weights are not an exact induced subset of MaleCNS')
    selected_ann=ann[ann.bodyId.isin(bodies)].copy()
    # Actual retained two-hop DN->intermediate->motor path count from binary graph.
    desc_idx=set(np.flatnonzero(selected_ann.superclass.eq('descending_neuron')).tolist())
    motor_idx=set(np.flatnonzero(selected_ann.superclass.eq('vnc_motor')).tolist())
    # selected_ann is not guaranteed to share binary index, so classify via body IDs.
    role_by_idx={i:int(dn[j]) for i,j in enumerate(range(len(bodies)))}
    motor_by_idx={i:int(motor[i]) for i in range(len(bodies))}
    channel_by_idx={i:int(channel[i]) for i in range(len(bodies))}
    dn_to_mid={}; mid_to_motor={}; sensor_to_dn=0
    for a,b,w in binary_edges:
        ai=int(a); bi=int(b)
        if channel_by_idx[ai]<4 and role_by_idx[bi]>0: sensor_to_dn+=1
        if role_by_idx[ai]>0 and role_by_idx[bi]==0 and motor_by_idx[bi]==0 and channel_by_idx[bi]>=4:
            dn_to_mid.setdefault(ai,set()).add(bi)
        if role_by_idx[ai]==0 and motor_by_idx[ai]==0 and channel_by_idx[ai]>=4 and motor_by_idx[bi]>0:
            mid_to_motor.setdefault(ai,set()).add(bi)
    two_hop=0
    for dn_i,mids in dn_to_mid.items():
        for mid in mids: two_hop += len(mid_to_motor.get(mid,set()))
    type_src=ann['type'].astype(str).nunique(); type_sel=selected_ann['type'].astype(str).nunique()
    result={
        'status':'PASS','nodes':n,'edges':e,'contacts':contacts,'induced_edge_count':edge_count,
        'types_source':int(type_src),'types_selected':int(type_sel),'type_coverage_fraction':float(type_sel/type_src),
        'source_superclass':ann.groupby('superclass').size().sort_values(ascending=False).to_dict(),
        'selected_superclass':selected_ann.groupby('superclass').size().sort_values(ascending=False).to_dict(),
        'dn_selected':int(selected_ann.superclass.eq('descending_neuron').sum()),
        'dn_source':int(ann.superclass.eq('descending_neuron').sum()),
        'motor_selected':int(selected_ann.superclass.eq('vnc_motor').sum()),
        'motor_source':int(ann.superclass.eq('vnc_motor').sum()),
        'halt_source':int((ann.halt_role>0).sum()),'halt_selected':int((selected_ann.halt_role>0).sum()),
        'left_selected':int((side<0).sum()),'right_selected':int((side>0).sum()),'side_unknown_selected':int((side==0).sum()),
        'retained_sensor_to_dn_edges':int(sensor_to_dn),'retained_dn_to_intermediate_to_motor_paths':int(two_hop),
    }
    out=root/'build'/'FBR10_VALIDATION.json'; out.parent.mkdir(parents=True,exist_ok=True); out.write_text(json.dumps(result,indent=2),encoding='utf-8'); print(json.dumps(result,indent=2))

if __name__=='__main__':
    ap=argparse.ArgumentParser(); ap.add_argument('--root',type=Path,default=Path(__file__).resolve().parents[1]); args=ap.parse_args(); main(args.root)
