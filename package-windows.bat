@echo off
setlocal enabledelayedexpansion

:: ============================================================================
:: PickMyTrade IB App - Windows MSI Packaging Script
:: Creates a Windows MSI installer using jpackage
::
:: Usage:
::   package-windows.bat [version] [jre-path]
::
:: Examples:
::   package-windows.bat                     Uses default version 10.30.0
::   package-windows.bat 10.30.1             Specify version
::   package-windows.bat 10.30.1 jre-win     Use pre-built JRE
:: ============================================================================

set "APP_NAME=PickMyTradeIB"
set "APP_VERSION=%~1"
if "%APP_VERSION%"=="" set "APP_VERSION=10.30.0"
set "JRE_PATH=%~2"
set "MAIN_CLASS=com.pickmytrade.ibapp.Launcher"
set "UPGRADE_UUID=123e4567-e89b-12d3-a456-426614174000"

set "SCRIPT_DIR=%~dp0"
set "JAR_DIR=%SCRIPT_DIR%output"
set "ICON_PATH=%SCRIPT_DIR%src\main\resources\logo.ico"
set "STAGING_DIR=%SCRIPT_DIR%staging-win"
set "DIST_DIR=%SCRIPT_DIR%dist"

echo.
echo ============================================================
echo  PickMyTrade IB App - Windows MSI Packager
echo  Version: %APP_VERSION%
echo ============================================================
echo.

:: -------------------------------------------------------------------
:: Check prerequisites
:: -------------------------------------------------------------------

:: Check jpackage is available (requires JDK 14+)
where jpackage >nul 2>nul
if errorlevel 1 (
    echo [ERROR] jpackage not found. JDK 14+ is required.
    echo         Download from: https://adoptium.net/
    echo         Make sure JAVA_HOME/bin is in your PATH.
    exit /b 1
)

:: Check WiX Toolset (required for MSI creation)
where candle >nul 2>nul
if errorlevel 1 (
    where wix >nul 2>nul
    if errorlevel 1 (
        echo [ERROR] WiX Toolset not found. Required for MSI creation.
        echo         Install WiX 3.x: https://wixtoolset.org/releases/
        echo         Or WiX 4+:       dotnet tool install --global wix
        echo         Make sure WiX bin directory is in your PATH.
        exit /b 1
    )
)
echo [OK] WiX Toolset found

:: Check for the fat JAR
set "FAT_JAR="
if exist "%JAR_DIR%\pickmytrade-ib-win.jar" (
    set "FAT_JAR=%JAR_DIR%\pickmytrade-ib-win.jar"
) else (
    echo [ERROR] Fat JAR not found at: %JAR_DIR%\pickmytrade-ib-win.jar
    echo         Build it first:  build.bat win
    echo         Or via Docker:   docker compose run --rm build-windows
    exit /b 1
)
echo [OK] Fat JAR: %FAT_JAR%

:: Check icon file
if not exist "%ICON_PATH%" (
    echo [WARN] Icon file not found: %ICON_PATH%
    echo        MSI will use default icon.
    set "ICON_PATH="
)

:: -------------------------------------------------------------------
:: Handle JRE (use provided path or create via jlink)
:: -------------------------------------------------------------------

if not "%JRE_PATH%"=="" (
    if not exist "%JRE_PATH%\bin\java.exe" (
        echo [ERROR] Provided JRE path does not contain bin\java.exe: %JRE_PATH%
        exit /b 1
    )
    echo [OK] Using provided JRE: %JRE_PATH%
) else (
    echo.
    echo [INFO] No JRE path provided. Creating custom JRE via jlink...
    call "%SCRIPT_DIR%create-jre.bat" win
    if errorlevel 1 (
        echo [ERROR] JRE creation failed.
        exit /b 1
    )
    set "JRE_PATH=%SCRIPT_DIR%jre-win"
)

:: Verify JRE exists
if not exist "%JRE_PATH%\bin\java.exe" (
    echo [ERROR] JRE not found at: %JRE_PATH%
    exit /b 1
)
echo [OK] JRE ready: %JRE_PATH%

:: -------------------------------------------------------------------
:: Prepare staging directory
:: -------------------------------------------------------------------

echo.
echo [INFO] Preparing staging directory...
if exist "%STAGING_DIR%" rmdir /s /q "%STAGING_DIR%"
mkdir "%STAGING_DIR%"
copy /y "%FAT_JAR%" "%STAGING_DIR%\%APP_NAME%.jar" >nul
if errorlevel 1 (
    echo [ERROR] Failed to copy JAR to staging directory.
    exit /b 1
)
echo [OK] Staging directory ready: %STAGING_DIR%

:: Create output directory
if not exist "%DIST_DIR%" mkdir "%DIST_DIR%"

:: -------------------------------------------------------------------
:: Build MSI with jpackage
:: -------------------------------------------------------------------

echo.
echo [INFO] Building MSI installer with jpackage...
echo.

set "JPACKAGE_CMD=jpackage"
set "JPACKAGE_CMD=%JPACKAGE_CMD% --type msi"
set "JPACKAGE_CMD=%JPACKAGE_CMD% --name "%APP_NAME%""
set "JPACKAGE_CMD=%JPACKAGE_CMD% --app-version %APP_VERSION%"
set "JPACKAGE_CMD=%JPACKAGE_CMD% --input "%STAGING_DIR%""
set "JPACKAGE_CMD=%JPACKAGE_CMD% --main-jar %APP_NAME%.jar"
set "JPACKAGE_CMD=%JPACKAGE_CMD% --main-class %MAIN_CLASS%"
set "JPACKAGE_CMD=%JPACKAGE_CMD% --runtime-image "%JRE_PATH%""
set "JPACKAGE_CMD=%JPACKAGE_CMD% --dest "%DIST_DIR%""
set "JPACKAGE_CMD=%JPACKAGE_CMD% --vendor "PickMyTrade""
set "JPACKAGE_CMD=%JPACKAGE_CMD% --description "PickMyTrade IB Trading Application""
set "JPACKAGE_CMD=%JPACKAGE_CMD% --copyright "Copyright 2025 PickMyTrade""
set "JPACKAGE_CMD=%JPACKAGE_CMD% --win-upgrade-uuid %UPGRADE_UUID%"
set "JPACKAGE_CMD=%JPACKAGE_CMD% --win-shortcut"
set "JPACKAGE_CMD=%JPACKAGE_CMD% --win-menu"
set "JPACKAGE_CMD=%JPACKAGE_CMD% --win-dir-chooser"
set "JPACKAGE_CMD=%JPACKAGE_CMD% --win-menu-group "PickMyTrade""
set "JPACKAGE_CMD=%JPACKAGE_CMD% --java-options "--enable-native-access=javafx.graphics,javafx.controls,javafx.fxml,ALL-UNNAMED""

if not "%ICON_PATH%"=="" (
    set "JPACKAGE_CMD=%JPACKAGE_CMD% --icon "%ICON_PATH%""
)

echo Running: %JPACKAGE_CMD%
echo.

%JPACKAGE_CMD%

if errorlevel 1 (
    echo.
    echo [ERROR] jpackage failed. See output above for details.
    echo.
    echo Common fixes:
    echo   - Ensure WiX Toolset is installed and in PATH
    echo   - Ensure JDK 14+ is being used (not just JRE)
    echo   - Check that the version format is valid (e.g., 10.30.0)
    goto :cleanup_fail
)

:: -------------------------------------------------------------------
:: Verify output
:: -------------------------------------------------------------------

set "MSI_FILE=%DIST_DIR%\%APP_NAME%-%APP_VERSION%.msi"
if exist "%MSI_FILE%" (
    echo.
    echo ============================================================
    echo  SUCCESS!
    echo  MSI created: %MSI_FILE%
    for %%A in ("%MSI_FILE%") do echo  Size: %%~zA bytes
    echo ============================================================
) else (
    :: jpackage may use a slightly different naming convention
    echo.
    echo [WARN] Expected MSI not found at: %MSI_FILE%
    echo        Checking dist directory for output...
    dir /b "%DIST_DIR%\*.msi" 2>nul
    if errorlevel 1 (
        echo [ERROR] No MSI file found in %DIST_DIR%
        goto :cleanup_fail
    )
)

:: -------------------------------------------------------------------
:: Cleanup
:: -------------------------------------------------------------------

:cleanup_success
echo.
echo [INFO] Cleaning up staging directory...
if exist "%STAGING_DIR%" rmdir /s /q "%STAGING_DIR%"
echo [OK] Done.
exit /b 0

:cleanup_fail
echo.
echo [INFO] Cleaning up staging directory...
if exist "%STAGING_DIR%" rmdir /s /q "%STAGING_DIR%"
exit /b 1
