@echo off
setlocal
cd /d "%~dp0"

echo ============================================================
echo FlyBrain FBR-10-OLF1 - source verification, reduction, validation
echo ============================================================

echo [1/3] Verifying pinned MaleCNS v1.0 source files...
py -3 tools\prepare_malecns.py --verify-only
if errorlevel 1 (
  echo.
  echo ERROR: MaleCNS source verification failed. No reduction was run.
  exit /b 1
)

echo.
echo [2/3] Building the canonical FBR-10-OLF1 reduction...
py -3 toolsuild_connectome.py
if errorlevel 1 (
  echo.
  echo ERROR: FBR-10-OLF1 build failed. Validation was not run.
  exit /b 1
)

echo.
echo [3/3] Independently validating the generated FBR-10-OLF1 connectome...
py -3 toolsalidate_fbr10_olf1.py --fbc103 app\src\mainesaw\malecns_reduced.bin --report app\src\mainesaw\malecns_reduced_report.json
if errorlevel 1 (
  echo.
  echo ERROR: FBR-10-OLF1 validation FAILED. Do not build the APK.
  exit /b 1
)

echo.
echo ============================================================
echo FBR-10-OLF1 PASSED source verification + build + validation.
echo ============================================================
endlocal
