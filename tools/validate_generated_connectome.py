#!/usr/bin/env python3
"""Independent structural validation for the current FlyBrain FBR-10-OLF2-MOTORROUTE release."""
from __future__ import annotations
import hashlib, json, math, re, struct
from pathlib import Path

TARGET = 16669
MAGIC = b"FBC103\x00\x00"
NODE_SIZE = 26
EDGE_SIZE = 12
HEADER_SIZE = 16
EXPECTED_SHA = None

def sha256(p: Path) -> str:
    h=hashlib.sha256()
    with p.open("rb") as f:
        for c in iter(lambda:f.read(8*1024*1024),b""): h.update(c)
    return h.hexdigest()

def parse_meta_int(meta: str, name: str) -> int:
    m=re.search(rf"const val {re.escape(name)} = (-?\d+)", meta)
    assert m, f"missing metadata constant {name}"
    return int(m.group(1))

def main(root: Path|None=None) -> None:
    root = root or Path(__file__).resolve().parents[1]
    raw=root/"app/src/main/res/raw"
    bin_path=raw/"malecns_reduced.bin"
    report_path=raw/"malecns_reduced_report.json"
    meta_path=root/"app/src/main/java/com/example/flybrain/GeneratedConnectomeMeta.kt"
    data=bin_path.read_bytes(); report=json.loads(report_path.read_text(encoding="utf-8")); meta=meta_path.read_text(encoding="utf-8")
    assert report["dataset"]=="MaleCNS v1.0"
    assert report["flybrain_version"]=="1.18.5"
    assert report["reduction"]=="FBR-10-OLF2-MOTORROUTE"
    assert report["binary_format"]=="FBC103"
    assert parse_meta_int(meta,"NEURONS")==TARGET
    assert parse_meta_int(meta,"EDGES")==report["edges_retained"]
    assert parse_meta_int(meta,"FORMAT_VERSION")==103
    assert re.search(r'const val REDUCTION_ID = "FBR-10-OLF2-MOTORROUTE"', meta)
    assert data[:8]==MAGIC
    n,e=struct.unpack_from("<II",data,8)
    assert n==TARGET and e==report["edges_retained"]
    assert len(data)==HEADER_SIZE+n*NODE_SIZE+e*EDGE_SIZE
    actual=sha256(bin_path)
    assert actual==report["sha256"]
    meta_sha=re.search(r'const val BINARY_SHA256 = "([0-9a-f]{64})"', meta).group(1)
    assert actual==meta_sha
    seen=set(); contacts=0
    for i in range(e):
        off=HEADER_SIZE+n*NODE_SIZE+i*EDGE_SIZE
        src,dst,w=struct.unpack_from("<iif",data,off)
        assert 0<=src<n and 0<=dst<n and w>0
        assert (src,dst) not in seen
        seen.add((src,dst)); contacts += w
    assert contacts==report["contacts_retained"]
    ranges=report["population_ranges"]
    prev=0
    for key in ("visual","olfactory","gustatory","mechanosensory","descending","ascending","motor","other"):
        start,end=map(int,ranges[key]); assert start==prev and 0<=start<=end<=TARGET; prev=end
    assert prev==TARGET
    print(f"GENERATED FBR-10-OLF2-MOTORROUTE: PASS ({n} neurons, {e} edges, {contacts} contacts, {actual})")

if __name__=="__main__":
    main()
