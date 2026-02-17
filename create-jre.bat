@echo off
setlocal enabledelayedexpansion

:: ============================================================================
:: PickMyTrade IB App - Custom JRE Creator (Windows)
:: Downloads Azul Zulu JDK 21 FX (bundled JavaFX) and creates a minimal JRE
:: using jlink for use with jpackage.
::
:: Usage:
::   create-jre.bat [platform]
::
:: Arguments:
::   platform  - Target platform: win (default)
::
:: Output:
::   jre-win/  - Custom JRE directory
:: ============================================================================

set "PLATFORM=%~1"
if "%PLATFORM%"=="" set "PLATFORM=win"

set "ZULU_VERSION=21.48.17"
set "JDK_VERSION=21.0.10"
set "SCRIPT_DIR=%~dp0"
set "JRE_DIR=%SCRIPT_DIR%jre-%PLATFORM%"
set "TEMP_DIR=%SCRIPT_DIR%jre-build-temp"

set "JLINK_MODULES=java.base,java.sql,java.net.http,java.logging,java.desktop,java.management,java.naming,java.xml,java.xml.crypto,java.security.sasl,jdk.unsupported,jdk.crypto.ec,javafx.base,javafx.controls,javafx.fxml,javafx.graphics,javafx.media,javafx.swing,javafx.web"

echo.
echo ============================================================
echo  Custom JRE Creator - Azul Zulu %ZULU_VERSION% (JDK %JDK_VERSION% + FX)
echo  Platform: %PLATFORM%
echo ============================================================
echo.

:: -------------------------------------------------------------------
:: Check if JRE already exists
:: -------------------------------------------------------------------

if exist "%JRE_DIR%\bin\java.exe" (
    echo [INFO] JRE already exists at: %JRE_DIR%
    echo        Delete it first if you want to rebuild.
    exit /b 0
)

:: -------------------------------------------------------------------
:: Download Azul Zulu FX JDK (includes JavaFX jmods)
:: -------------------------------------------------------------------

set "ZULU_DIR_NAME=zulu%ZULU_VERSION%-ca-fx-jdk%JDK_VERSION%-win_x64"
set "ZULU_URL=https://cdn.azul.com/zulu/bin/%ZULU_DIR_NAME%.zip"
set "ZULU_ZIP=%TEMP_DIR%\%ZULU_DIR_NAME%.zip"

echo.
echo [INFO] Downloading Azul Zulu FX JDK %JDK_VERSION%...

if not exist "%TEMP_DIR%" mkdir "%TEMP_DIR%"

if not exist "%ZULU_ZIP%" (
    echo [INFO] Downloading from: %ZULU_URL%
    powershell -Command "try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri '%ZULU_URL%' -OutFile '%ZULU_ZIP%' -UseBasicParsing } catch { Write-Error $_.Exception.Message; exit 1 }"
    if errorlevel 1 (
        echo [ERROR] Failed to download Azul Zulu FX JDK.
        echo         Download manually from: https://www.azul.com/downloads/
        echo         Select: Java 21, Windows, JDK FX, .zip
        goto :cleanup_fail
    )
    echo [OK] Downloaded Azul Zulu FX JDK
) else (
    echo [OK] Azul Zulu FX JDK already downloaded
)

:: Extract the JDK
echo [INFO] Extracting Azul Zulu FX JDK...
powershell -Command "Expand-Archive -Path '%ZULU_ZIP%' -DestinationPath '%TEMP_DIR%' -Force"
if errorlevel 1 (
    echo [ERROR] Failed to extract Azul Zulu FX JDK.
    goto :cleanup_fail
)

:: Find the extracted JDK directory and its jmods
set "ZULU_JMODS="
for /d %%D in ("%TEMP_DIR%\zulu*") do (
    if exist "%%D\jmods\javafx.base.jmod" (
        set "ZULU_JDK_DIR=%%D"
        set "ZULU_JMODS=%%D\jmods"
    )
)

if "%ZULU_JMODS%"=="" (
    echo [ERROR] Could not find JavaFX jmods in extracted Azul Zulu JDK.
    echo         Make sure you're using the Zulu FX variant.
    goto :cleanup_fail
)
echo [OK] Azul Zulu JDK at: %ZULU_JDK_DIR%
echo [OK] jmods at: %ZULU_JMODS%

:: Use jlink from the downloaded Zulu JDK (ensures version consistency)
set "JLINK_CMD=%ZULU_JDK_DIR%\bin\jlink.exe"
if not exist "%JLINK_CMD%" (
    echo [WARN] jlink not found in Azul Zulu JDK, trying system jlink...
    where jlink >nul 2>nul
    if errorlevel 1 (
        echo [ERROR] jlink not found. Cannot create JRE.
        goto :cleanup_fail
    )
    set "JLINK_CMD=jlink"
)
echo [OK] jlink: %JLINK_CMD%

:: -------------------------------------------------------------------
:: Create custom JRE with jlink
:: All jmods (JDK + JavaFX) are in a single directory in Zulu FX
:: -------------------------------------------------------------------

echo.
echo [INFO] Creating custom JRE with jlink...
echo        Modules: %JLINK_MODULES%
echo.

"%JLINK_CMD%" ^
    --module-path "%ZULU_JMODS%" ^
    --add-modules %JLINK_MODULES% ^
    --output "%JRE_DIR%" ^
    --strip-debug ^
    --no-man-pages ^
    --no-header-files ^
    --compress zip-6

if errorlevel 1 (
    echo.
    echo [ERROR] jlink failed. See output above for details.
    goto :cleanup_fail
)

:: Verify JRE
if exist "%JRE_DIR%\bin\java.exe" (
    echo.
    echo ============================================================
    echo  Custom JRE created: %JRE_DIR%
    echo  Source: Azul Zulu %ZULU_VERSION% FX (JDK %JDK_VERSION%)
    for /f "tokens=*" %%a in ('dir /s /b "%JRE_DIR%" ^| find /c /v ""') do echo  Files: %%a
    echo ============================================================
) else (
    echo [ERROR] JRE creation appeared to succeed but java.exe not found.
    goto :cleanup_fail
)

:: -------------------------------------------------------------------
:: Cleanup temp files
:: -------------------------------------------------------------------

:cleanup_success
echo.
echo [INFO] Cleaning up temp files...
if exist "%TEMP_DIR%" rmdir /s /q "%TEMP_DIR%"
echo [OK] Done.
exit /b 0

:cleanup_fail
echo.
echo [INFO] Cleaning up temp files...
if exist "%TEMP_DIR%" rmdir /s /q "%TEMP_DIR%"
exit /b 1
