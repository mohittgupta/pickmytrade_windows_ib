@echo off
REM ============================================================
REM PickMyTrade IB App - Windows Build Script
REM Usage: build.bat [platform] [output-dir]
REM   platform: win | mac | mac-arm | all (default: all)
REM   output-dir: directory for JAR output (default: .\output)
REM ============================================================

setlocal EnableDelayedExpansion

set "PLATFORM=%~1"
set "OUTPUT_DIR=%~2"

if "%PLATFORM%"=="" set "PLATFORM=all"
if "%OUTPUT_DIR%"=="" set "OUTPUT_DIR=output"

echo ============================================
echo  PickMyTrade IB App - Docker Build
echo ============================================

REM Check Docker is running
docker info >nul 2>&1
if errorlevel 1 (
    echo ERROR: Docker is not running. Please start Docker Desktop.
    exit /b 1
)

REM Check tws-api JAR exists
if not exist "libs\tws-api-10.30.01.jar" (
    echo WARNING: libs\tws-api-10.30.01.jar not found!
    echo The build will fail without the IB TWS API JAR.
    echo Download from: https://interactivebrokers.github.io/
    echo Place the JAR in the libs\ directory.
    echo.
    set /p "CONTINUE=Continue anyway? (y/N): "
    if /i not "!CONTINUE!"=="y" exit /b 1
)

if not exist "%OUTPUT_DIR%" mkdir "%OUTPUT_DIR%"

if /i "%PLATFORM%"=="win" goto :build_win
if /i "%PLATFORM%"=="windows" goto :build_win
if /i "%PLATFORM%"=="mac" goto :build_mac
if /i "%PLATFORM%"=="macos" goto :build_mac
if /i "%PLATFORM%"=="mac-arm" goto :build_mac_arm
if /i "%PLATFORM%"=="mac-aarch64" goto :build_mac_arm
if /i "%PLATFORM%"=="all" goto :build_all

echo Usage: build.bat [win^|mac^|mac-arm^|all] [output-dir]
echo.
echo Platforms:
echo   win       - Windows (x64)
echo   mac       - macOS (Intel x64)
echo   mac-arm   - macOS (Apple Silicon ARM64)
echo   all       - All platforms (default)
exit /b 1

:build_win
call :build_one win "Windows"
goto :done

:build_mac
call :build_one mac "macOS (Intel)"
goto :done

:build_mac_arm
call :build_one mac-aarch64 "macOS (Apple Silicon)"
goto :done

:build_all
call :build_one win "Windows"
if errorlevel 1 goto :error
call :build_one mac "macOS (Intel)"
if errorlevel 1 goto :error
call :build_one mac-aarch64 "macOS (Apple Silicon)"
if errorlevel 1 goto :error
goto :done

:build_one
echo.
echo Building for %~2...
echo -------------------------------------------
docker build --target output --build-arg TARGET_PLATFORM=%~1 --output "type=local,dest=%OUTPUT_DIR%" .
if errorlevel 1 (
    echo FAILED: Build failed for %~2
    exit /b 1
)
if exist "%OUTPUT_DIR%\pickmytrade-ib-%~1.jar" (
    echo SUCCESS: pickmytrade-ib-%~1.jar created
) else (
    echo FAILED: JAR not found for %~2
    exit /b 1
)
exit /b 0

:done
echo.
echo ============================================
echo  Build complete! Output in: %OUTPUT_DIR%\
echo ============================================
dir "%OUTPUT_DIR%\*.jar" 2>nul
exit /b 0

:error
echo.
echo Build failed!
exit /b 1
