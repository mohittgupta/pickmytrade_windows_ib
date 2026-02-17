#!/usr/bin/env bash
set -euo pipefail

# ============================================================================
# PickMyTrade IB App - macOS PKG Packaging Script
# Creates a macOS PKG installer using jpackage with optional code signing
# and notarization.
#
# Usage:
#   ./package-mac.sh [version] [arch] [jre-path]
#
# Arguments:
#   version   - App version (default: 10.30.0)
#   arch      - Architecture: intel, arm, or auto (default: auto)
#   jre-path  - Path to pre-built JRE (optional; will create via jlink if omitted)
#
# Examples:
#   ./package-mac.sh                          Auto-detect arch, default version
#   ./package-mac.sh 10.30.1                  Specify version
#   ./package-mac.sh 10.30.1 arm              Build for Apple Silicon
#   ./package-mac.sh 10.30.1 intel jre-mac    Use pre-built JRE
#
# Prerequisites:
#   - macOS with Xcode Command Line Tools (xcode-select --install)
#   - JDK 14+ with jpackage (Azul Zulu FX recommended: https://www.azul.com/downloads/)
#   - For code signing: Apple Developer certificate in Keychain
#   - For notarization: notarytool keychain profile named "NotaryProfile"
#     Setup: xcrun notarytool store-credentials "NotaryProfile" \
#              --apple-id "you@email.com" --team-id "TEAM_ID" --password "app-password"
# ============================================================================

APP_NAME="PickMyTradeIB"
APP_VERSION="${1:-10.30.0}"
ARCH_INPUT="${2:-auto}"
JRE_PATH="${3:-}"
MAIN_CLASS="com.pickmytrade.ibapp.Launcher"
PACKAGE_ID="com.pickmytrade.ibapp"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR_DIR="${SCRIPT_DIR}/output"
ICON_PATH="${SCRIPT_DIR}/src/main/resources/logo.png"

echo ""
echo "============================================================"
echo " PickMyTrade IB App - macOS PKG Packager"
echo " Version: ${APP_VERSION}"
echo "============================================================"
echo ""

# -------------------------------------------------------------------
# Verify we're on macOS
# -------------------------------------------------------------------
if [[ "$(uname -s)" != "Darwin" ]]; then
    echo "[ERROR] This script must be run on macOS."
    echo "        macOS PKG creation requires native macOS tools."
    exit 1
fi

# -------------------------------------------------------------------
# Determine architecture
# -------------------------------------------------------------------
detect_arch() {
    local machine
    machine="$(uname -m)"
    case "$machine" in
        x86_64)  echo "intel" ;;
        arm64)   echo "arm" ;;
        *)       echo "unknown" ;;
    esac
}

if [[ "$ARCH_INPUT" == "auto" ]]; then
    ARCH="$(detect_arch)"
    if [[ "$ARCH" == "unknown" ]]; then
        echo "[ERROR] Could not detect architecture. Specify 'intel' or 'arm'."
        exit 1
    fi
    echo "[INFO] Auto-detected architecture: ${ARCH}"
else
    ARCH="$ARCH_INPUT"
fi

case "$ARCH" in
    intel)
        PLATFORM="mac"
        JAR_NAME="pickmytrade-ib-mac.jar"
        DIST_DIR="${SCRIPT_DIR}/dist-intel"
        JRE_PLATFORM="mac"
        ;;
    arm)
        PLATFORM="mac-aarch64"
        JAR_NAME="pickmytrade-ib-mac-aarch64.jar"
        DIST_DIR="${SCRIPT_DIR}/dist-arm"
        JRE_PLATFORM="mac-aarch64"
        ;;
    *)
        echo "[ERROR] Invalid architecture: ${ARCH}. Use 'intel', 'arm', or 'auto'."
        exit 1
        ;;
esac

STAGING_DIR="${SCRIPT_DIR}/staging-${ARCH}"

# -------------------------------------------------------------------
# Check prerequisites
# -------------------------------------------------------------------

# Check jpackage
if ! command -v jpackage &>/dev/null; then
    echo "[ERROR] jpackage not found. JDK 14+ is required."
    echo "        Download Azul Zulu FX from: https://www.azul.com/downloads/"
    echo "        Make sure JAVA_HOME/bin is in your PATH."
    exit 1
fi
echo "[OK] jpackage found: $(which jpackage)"

# Check for the fat JAR
FAT_JAR="${JAR_DIR}/${JAR_NAME}"
if [[ ! -f "$FAT_JAR" ]]; then
    echo "[ERROR] Fat JAR not found: ${FAT_JAR}"
    echo "        Build it first:"
    echo "          ./build.sh ${PLATFORM}"
    echo "        Or via Docker:"
    echo "          docker compose run --rm build-${PLATFORM/mac-aarch64/mac-arm}"
    exit 1
fi
echo "[OK] Fat JAR: ${FAT_JAR}"

# -------------------------------------------------------------------
# Convert PNG to ICNS (macOS icon format)
# -------------------------------------------------------------------
ICNS_PATH=""
if [[ -f "$ICON_PATH" ]]; then
    echo "[INFO] Converting logo.png to .icns format..."

    ICONSET_DIR="${SCRIPT_DIR}/AppIcon.iconset"
    mkdir -p "$ICONSET_DIR"

    # Generate all required icon sizes
    sips -z 16 16     "$ICON_PATH" --out "${ICONSET_DIR}/icon_16x16.png"      >/dev/null 2>&1
    sips -z 32 32     "$ICON_PATH" --out "${ICONSET_DIR}/icon_16x16@2x.png"   >/dev/null 2>&1
    sips -z 32 32     "$ICON_PATH" --out "${ICONSET_DIR}/icon_32x32.png"      >/dev/null 2>&1
    sips -z 64 64     "$ICON_PATH" --out "${ICONSET_DIR}/icon_32x32@2x.png"   >/dev/null 2>&1
    sips -z 128 128   "$ICON_PATH" --out "${ICONSET_DIR}/icon_128x128.png"    >/dev/null 2>&1
    sips -z 256 256   "$ICON_PATH" --out "${ICONSET_DIR}/icon_128x128@2x.png" >/dev/null 2>&1
    sips -z 256 256   "$ICON_PATH" --out "${ICONSET_DIR}/icon_256x256.png"    >/dev/null 2>&1
    sips -z 512 512   "$ICON_PATH" --out "${ICONSET_DIR}/icon_256x256@2x.png" >/dev/null 2>&1
    sips -z 512 512   "$ICON_PATH" --out "${ICONSET_DIR}/icon_512x512.png"    >/dev/null 2>&1
    sips -z 1024 1024 "$ICON_PATH" --out "${ICONSET_DIR}/icon_512x512@2x.png" >/dev/null 2>&1

    iconutil -c icns "$ICONSET_DIR" -o "${SCRIPT_DIR}/logo.icns"

    # Cleanup iconset
    rm -rf "$ICONSET_DIR"

    ICNS_PATH="${SCRIPT_DIR}/logo.icns"
    echo "[OK] Icon converted: ${ICNS_PATH}"
else
    echo "[WARN] Logo not found: ${ICON_PATH}"
    echo "       PKG will use default icon."
fi

# -------------------------------------------------------------------
# Handle JRE (use provided path or create via jlink)
# -------------------------------------------------------------------
if [[ -n "$JRE_PATH" ]]; then
    if [[ ! -f "${JRE_PATH}/bin/java" ]]; then
        echo "[ERROR] Provided JRE path does not contain bin/java: ${JRE_PATH}"
        exit 1
    fi
    echo "[OK] Using provided JRE: ${JRE_PATH}"
else
    echo ""
    echo "[INFO] No JRE path provided. Creating custom JRE via jlink..."
    bash "${SCRIPT_DIR}/create-jre.sh" "$JRE_PLATFORM"
    JRE_PATH="${SCRIPT_DIR}/jre-${JRE_PLATFORM}"
fi

# Verify JRE
if [[ ! -f "${JRE_PATH}/bin/java" ]]; then
    echo "[ERROR] JRE not found at: ${JRE_PATH}"
    exit 1
fi
echo "[OK] JRE ready: ${JRE_PATH}"

# -------------------------------------------------------------------
# Prepare staging directory
# -------------------------------------------------------------------
echo ""
echo "[INFO] Preparing staging directory..."
rm -rf "$STAGING_DIR"
mkdir -p "$STAGING_DIR"
cp "$FAT_JAR" "${STAGING_DIR}/${APP_NAME}.jar"
echo "[OK] Staging directory ready: ${STAGING_DIR}"

# Create output directory
mkdir -p "$DIST_DIR"

# -------------------------------------------------------------------
# Build PKG with jpackage
# -------------------------------------------------------------------
echo ""
echo "[INFO] Building PKG installer with jpackage..."
echo ""

JPACKAGE_ARGS=(
    --type pkg
    --name "$APP_NAME"
    --app-version "$APP_VERSION"
    --input "$STAGING_DIR"
    --main-jar "${APP_NAME}.jar"
    --main-class "$MAIN_CLASS"
    --runtime-image "$JRE_PATH"
    --dest "$DIST_DIR"
    --vendor "PickMyTrade"
    --description "PickMyTrade IB Trading Application"
    --copyright "Copyright 2025 PickMyTrade"
    --mac-package-identifier "$PACKAGE_ID"
    --mac-package-name "$APP_NAME"
    --java-options "--enable-native-access=ALL-UNNAMED"
    --java-options "-Dprism.order=sw"
    --java-options "-Dprism.verbose=true"
)

if [[ -n "$ICNS_PATH" ]]; then
    JPACKAGE_ARGS+=(--icon "$ICNS_PATH")
fi

# Check if signing identity is available
SIGN_IDENTITY=""
if security find-identity -v -p codesigning 2>/dev/null | grep -q "Developer ID Installer"; then
    SIGN_IDENTITY="found"
    JPACKAGE_ARGS+=(--mac-sign)
    echo "[INFO] Code signing identity found. PKG will be signed."
else
    echo "[WARN] No 'Developer ID Installer' certificate found in Keychain."
    echo "       PKG will NOT be signed. Install an Apple Developer certificate to enable signing."
    echo "       Continuing without --mac-sign..."
fi

echo "Running: jpackage ${JPACKAGE_ARGS[*]}"
echo ""

jpackage "${JPACKAGE_ARGS[@]}"

if [[ $? -ne 0 ]]; then
    echo ""
    echo "[ERROR] jpackage failed. See output above for details."
    rm -rf "$STAGING_DIR"
    [[ -f "${SCRIPT_DIR}/logo.icns" ]] && rm -f "${SCRIPT_DIR}/logo.icns"
    exit 1
fi

# -------------------------------------------------------------------
# Verify output
# -------------------------------------------------------------------
PKG_FILE="${DIST_DIR}/${APP_NAME}-${APP_VERSION}.pkg"
if [[ ! -f "$PKG_FILE" ]]; then
    # jpackage may use slightly different naming
    PKG_FILE=$(find "$DIST_DIR" -name "*.pkg" -type f | head -1)
fi

if [[ -z "$PKG_FILE" || ! -f "$PKG_FILE" ]]; then
    echo "[ERROR] No PKG file found in ${DIST_DIR}"
    rm -rf "$STAGING_DIR"
    [[ -f "${SCRIPT_DIR}/logo.icns" ]] && rm -f "${SCRIPT_DIR}/logo.icns"
    exit 1
fi

echo ""
echo "============================================================"
echo " PKG Created: ${PKG_FILE}"
echo " Size: $(du -h "$PKG_FILE" | cut -f1)"
echo "============================================================"

# -------------------------------------------------------------------
# Code signing verification (if signed)
# -------------------------------------------------------------------
if [[ -n "$SIGN_IDENTITY" ]]; then
    echo ""
    echo "[INFO] Verifying code signature..."
    if pkgutil --check-signature "$PKG_FILE" 2>/dev/null; then
        echo "[OK] PKG signature verified."
    else
        echo "[WARN] PKG signature verification failed. The installer may not pass Gatekeeper."
    fi
fi

# -------------------------------------------------------------------
# Notarization (optional - only if signing was enabled)
# -------------------------------------------------------------------
if [[ -n "$SIGN_IDENTITY" ]]; then
    echo ""
    echo "[INFO] Checking for notarization profile..."
    if xcrun notarytool history --keychain-profile "NotaryProfile" &>/dev/null 2>&1; then
        echo "[INFO] Submitting PKG for Apple notarization..."
        echo "       This may take several minutes..."
        echo ""

        if xcrun notarytool submit "$PKG_FILE" \
            --keychain-profile "NotaryProfile" \
            --wait; then
            echo ""
            echo "[OK] Notarization successful!"

            # Staple the notarization ticket
            echo "[INFO] Stapling notarization ticket..."
            if xcrun stapler staple "$PKG_FILE"; then
                echo "[OK] Notarization ticket stapled to PKG."
            else
                echo "[WARN] Stapling failed. Users may need internet for Gatekeeper verification."
            fi
        else
            echo ""
            echo "[WARN] Notarization failed. The PKG can still be installed but may trigger Gatekeeper warnings."
            echo "       Check notarization log for details:"
            echo "       xcrun notarytool log <submission-id> --keychain-profile \"NotaryProfile\""
        fi
    else
        echo "[INFO] Notarization profile 'NotaryProfile' not found. Skipping notarization."
        echo "       To set up notarization, run:"
        echo "       xcrun notarytool store-credentials \"NotaryProfile\" \\"
        echo "         --apple-id \"your@email.com\" \\"
        echo "         --team-id \"YOUR_TEAM_ID\" \\"
        echo "         --password \"app-specific-password\""
    fi
fi

# -------------------------------------------------------------------
# Cleanup
# -------------------------------------------------------------------
echo ""
echo "[INFO] Cleaning up..."
rm -rf "$STAGING_DIR"
[[ -f "${SCRIPT_DIR}/logo.icns" ]] && rm -f "${SCRIPT_DIR}/logo.icns"

echo ""
echo "============================================================"
echo " Done! Installer: ${PKG_FILE}"
echo "============================================================"
