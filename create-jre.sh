#!/usr/bin/env bash
set -euo pipefail

# ============================================================================
# PickMyTrade IB App - Custom JRE Creator (macOS/Linux)
# Downloads Azul Zulu JDK 21 FX (bundled JavaFX) and creates a minimal JRE
# using jlink for use with jpackage.
#
# For macOS: Uses Azul Zulu JDK 21 FX which includes JavaFX jmods
# For Linux: Uses Azul Zulu JDK 21 FX
#
# Usage:
#   ./create-jre.sh [platform]
#
# Arguments:
#   platform  - Target platform: mac, mac-aarch64 (default: auto-detect)
#
# Output:
#   jre-<platform>/  - Custom JRE directory
# ============================================================================

PLATFORM="${1:-auto}"
ZULU_VERSION="21.48.17"
JDK_VERSION="21.0.10"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

JLINK_MODULES="java.base,java.sql,java.net.http,java.logging,java.desktop,java.management,java.naming,java.xml,java.xml.crypto,java.security.sasl,jdk.unsupported,jdk.crypto.ec,javafx.base,javafx.controls,javafx.fxml,javafx.graphics,javafx.media,javafx.swing,javafx.web"

# -------------------------------------------------------------------
# Auto-detect platform
# -------------------------------------------------------------------
if [[ "$PLATFORM" == "auto" ]]; then
    OS="$(uname -s)"
    ARCH="$(uname -m)"
    case "$OS" in
        Darwin)
            case "$ARCH" in
                x86_64) PLATFORM="mac" ;;
                arm64)  PLATFORM="mac-aarch64" ;;
                *)      echo "[ERROR] Unknown macOS arch: $ARCH"; exit 1 ;;
            esac
            ;;
        Linux)
            PLATFORM="linux"
            ;;
        *)
            echo "[ERROR] Unsupported OS: $OS. Use this script on macOS or Linux."
            exit 1
            ;;
    esac
    echo "[INFO] Auto-detected platform: ${PLATFORM}"
fi

JRE_DIR="${SCRIPT_DIR}/jre-${PLATFORM}"
TEMP_DIR="${SCRIPT_DIR}/jre-build-temp"

echo ""
echo "============================================================"
echo " Custom JRE Creator - Azul Zulu ${ZULU_VERSION} (JDK ${JDK_VERSION} + FX)"
echo " Platform: ${PLATFORM}"
echo "============================================================"
echo ""

# -------------------------------------------------------------------
# Check if JRE already exists
# -------------------------------------------------------------------
if [[ -f "${JRE_DIR}/bin/java" ]]; then
    echo "[INFO] JRE already exists at: ${JRE_DIR}"
    echo "       Delete it first if you want to rebuild."
    exit 0
fi

# -------------------------------------------------------------------
# Determine Azul Zulu FX download URL
# Azul Zulu FX bundles JavaFX jmods inside the JDK
# -------------------------------------------------------------------
case "$PLATFORM" in
    mac)
        ZULU_ARCH="macosx_x64"
        ARCHIVE_EXT="tar.gz"
        ;;
    mac-aarch64)
        ZULU_ARCH="macosx_aarch64"
        ARCHIVE_EXT="tar.gz"
        ;;
    linux)
        ZULU_ARCH="linux_x64"
        ARCHIVE_EXT="tar.gz"
        ;;
    *)
        echo "[ERROR] Unsupported platform: ${PLATFORM}"
        echo "        Supported: mac, mac-aarch64, linux"
        exit 1
        ;;
esac

ZULU_DIR_NAME="zulu${ZULU_VERSION}-ca-fx-jdk${JDK_VERSION}-${ZULU_ARCH}"
ZULU_URL="https://cdn.azul.com/zulu/bin/${ZULU_DIR_NAME}.${ARCHIVE_EXT}"
ZULU_ARCHIVE="${TEMP_DIR}/${ZULU_DIR_NAME}.${ARCHIVE_EXT}"

# -------------------------------------------------------------------
# Download Azul Zulu FX JDK
# -------------------------------------------------------------------
mkdir -p "$TEMP_DIR"

if [[ ! -f "$ZULU_ARCHIVE" ]]; then
    echo "[INFO] Downloading Azul Zulu FX JDK ${JDK_VERSION}..."
    echo "       URL: ${ZULU_URL}"
    echo ""
    if command -v curl &>/dev/null; then
        curl -fSL -o "$ZULU_ARCHIVE" "$ZULU_URL"
    elif command -v wget &>/dev/null; then
        wget -q --show-progress -O "$ZULU_ARCHIVE" "$ZULU_URL"
    else
        echo "[ERROR] Neither curl nor wget found. Install one to proceed."
        rm -rf "$TEMP_DIR"
        exit 1
    fi
    echo "[OK] Downloaded Azul Zulu FX JDK"
else
    echo "[OK] Azul Zulu FX JDK already downloaded"
fi

# -------------------------------------------------------------------
# Extract the JDK
# -------------------------------------------------------------------
echo "[INFO] Extracting Azul Zulu FX JDK..."
tar -xzf "$ZULU_ARCHIVE" -C "$TEMP_DIR"

# Find the extracted JDK directory
ZULU_JDK_DIR=""
for dir in "${TEMP_DIR}"/zulu*; do
    if [[ -d "$dir" ]]; then
        # On macOS, Zulu may have a Contents/Home structure or be flat
        if [[ -d "${dir}/Contents/Home/jmods" ]]; then
            ZULU_JDK_DIR="${dir}/Contents/Home"
            break
        elif [[ -d "${dir}/jmods" ]]; then
            ZULU_JDK_DIR="${dir}"
            break
        fi
    fi
done

if [[ -z "$ZULU_JDK_DIR" ]]; then
    echo "[ERROR] Could not find Azul Zulu JDK jmods after extraction."
    echo "        Contents of temp dir:"
    ls -la "$TEMP_DIR"
    rm -rf "$TEMP_DIR"
    exit 1
fi

ZULU_JMODS="${ZULU_JDK_DIR}/jmods"
echo "[OK] Azul Zulu JDK at: ${ZULU_JDK_DIR}"
echo "[OK] jmods at: ${ZULU_JMODS}"

# Verify JavaFX jmods are present (FX variant includes them)
if [[ ! -f "${ZULU_JMODS}/javafx.base.jmod" ]]; then
    echo "[ERROR] JavaFX jmods not found in Azul Zulu JDK."
    echo "        Make sure you're using the Zulu FX variant."
    echo "        Available jmods:"
    ls "${ZULU_JMODS}/" | grep javafx || echo "        (none)"
    rm -rf "$TEMP_DIR"
    exit 1
fi
echo "[OK] JavaFX jmods found in Azul Zulu FX JDK"

# Use jlink from the downloaded Zulu JDK itself (ensures version consistency)
JLINK_CMD="${ZULU_JDK_DIR}/bin/jlink"
if [[ ! -f "$JLINK_CMD" ]]; then
    # Fall back to system jlink
    if command -v jlink &>/dev/null; then
        JLINK_CMD="jlink"
        echo "[WARN] Using system jlink instead of bundled one."
    else
        echo "[ERROR] jlink not found. JDK is required."
        rm -rf "$TEMP_DIR"
        exit 1
    fi
fi
echo "[OK] jlink: ${JLINK_CMD}"

# -------------------------------------------------------------------
# Create custom JRE with jlink
# The Azul Zulu FX JDK has all jmods (JDK + JavaFX) in one directory
# -------------------------------------------------------------------
echo ""
echo "[INFO] Creating custom JRE with jlink..."
echo "       Modules: ${JLINK_MODULES}"
echo ""

"$JLINK_CMD" \
    --module-path "${ZULU_JMODS}" \
    --add-modules "$JLINK_MODULES" \
    --output "$JRE_DIR" \
    --strip-debug \
    --no-man-pages \
    --no-header-files \
    --compress zip-6

# Verify JRE
if [[ -f "${JRE_DIR}/bin/java" ]]; then
    JRE_SIZE=$(du -sh "$JRE_DIR" | cut -f1)
    echo ""
    echo "============================================================"
    echo " Custom JRE created: ${JRE_DIR}"
    echo " Size: ${JRE_SIZE}"
    echo " Source: Azul Zulu ${ZULU_VERSION} FX (JDK ${JDK_VERSION})"
    echo "============================================================"
else
    echo "[ERROR] JRE creation appeared to succeed but java binary not found."
    rm -rf "$TEMP_DIR"
    exit 1
fi

# -------------------------------------------------------------------
# Cleanup
# -------------------------------------------------------------------
echo ""
echo "[INFO] Cleaning up temp files..."
rm -rf "$TEMP_DIR"
echo "[OK] Done."
