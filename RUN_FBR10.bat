@echo off
setlocal
cd /d "%~dp0"

echo ============================================================
echo FlyBrain FBR-10 V3 - source verification, reduction, validation
echo ============================================================

echo [1/3] Verifying MaleCNS v1.0 source files...
py -3 tools\prepare_malecns.py --verify-only
if errorlevel 1 (
  echo.
  echo ERROR: MaleCNS source verification failed. No reduction was run.
  exit /b 1
)

echo.
echo [2/3] Building FBR-10 from the verified MaleCNS source...
py -3 tools\build_connectome_fbr10.py
if errorlevel 1 (
  echo.
  echo ERROR: FBR-10 build failed. Validation was not run.
  exit /b 1
)

echo.
echo [3/3] Independently validating the generated connectome...
py -3 tools\validate_fbr10.py
if errorlevel 1 (
  echo.
  echo ERROR: FBR-10 validation FAILED. Do not build the APK.
  exit /b 1
)

echo.
echo ============================================================
echo FBR-10 PASSED source verification + build + validation.
echo ============================================================
endlocal
