# FBR-10 V3 — pinned MaleCNS source + census correction

This version adds a reproducible source gate before reduction.

## Official source pin

The three MaleCNS v1.0 Feather objects are pinned by URL, byte size and SHA-256.
The verifier also checks the expected Feather schema and row count.

The source-node census used by FlyBrain is **166,700 unique bodyId values with a non-empty published `superclass`**. It is deliberately **not** `status == Traced`: the official annotation table contains 165,122 Traced rows but the audited neuron population used by this project is 166,700 annotated bodies with a non-empty superclass.

## Run

If the three files are already present in `build/malecns_raw`:

```powershell
py -3 tools\prepare_malecns.py --verify-only
```

To let the project download missing/wrong files from the official MaleCNS bucket:

```powershell
py -3 tools\prepare_malecns.py --download
```

Then build and validate:

```powershell
py -3 tools\build_connectome_fbr10.py
py -3 tools\validate_fbr10.py
```

Or run `RUN_FBR10.bat`.

The reducer refuses to proceed with a source file whose size, SHA-256, row count or required schema does not match the pinned release.
