@echo off
setlocal
cd /d "%~dp0"

echo ============================================================
echo FlyBrain FBR-10-OLF2-MOTORROUTE - source verification, reduction, validation
echo ============================================================

echo [1/3] Verifying pinned MaleCNS v1.0 source files...
py -3 tools\prepare_malecns.py --verify-only
if errorlevel 1 (
  echo.
  echo ERROR: MaleCNS source verification failed. No reduction was run.
  exit /b 1
)

echo.
echo [2/3] Building the canonical FBR-10-OLF2-MOTORROUTE reduction...
py -3 tools\build_connectome.py
if errorlevel 1 (
  echo.
  echo ERROR: FBR-10-OLF2-MOTORROUTE build failed. Validation was not run.
  exit /b 1
)

echo.
echo [3/3] Independently validating the generated FBR-10-OLF2-MOTORROUTE connectome...
py -3 tools\validate_fbr10_olf2.py --fbc103 app\src\main\res\raw\malecns_reduced.bin --report app\src\main\res\raw\malecns_reduced_report.json
if errorlevel 1 (
  echo.
  echo ERROR: FBR-10-OLF2-MOTORROUTE validation FAILED. Do not build the APK.
  exit /b 1
)

echo.
echo ============================================================
echo FBR-10-OLF2-MOTORROUTE PASSED source verification + build + validation.
echo ============================================================
endlocal
