#!/usr/bin/env bash
set -euo pipefail

# ============================================================================
# PickMyTrade IB App - macOS PKG Packaging Script (Production-Ready)
#
# Creates a signed, notarized macOS PKG installer using the correct workflow:
#   1. jpackage --type app-image  (NOT --type pkg directly)
#   2. Sign native libraries inside JARs (JNA, SQLite, gRPC, Conscrypt, etc.)
#   3. Sign app bundle inside-out (innermost components first)
#   4. pkgbuild with BundleIsRelocatable=NO
#   5. Sign the PKG with Developer ID Installer certificate
#   6. Notarize with Apple and staple the ticket
#
# WHY THIS APPROACH:
#   - jpackage --type pkg CANNOT sign native libs inside JARs (notarization fails)
#   - --deep codesign is deprecated and misses libs inside JARs
#   - pkgbuild gives control over BundleIsRelocatable (prevents install hijacking)
#   - Inside-out signing is required by Apple's code signing architecture
#
# Usage:
#   ./package-mac.sh [version] [arch] [jre-path]
#
# Arguments:
#   version   - App version (default: 10.30.0)
#   arch      - Architecture: intel, arm, or auto (default: auto)
#   jre-path  - Path to pre-built JRE (optional; will create via create-jre.sh)
#
# Examples:
#   ./package-mac.sh                              Auto-detect, default version
#   ./package-mac.sh 10.31.0                      Specify version
#   ./package-mac.sh 10.31.0 arm                  Build for Apple Silicon
#   ./package-mac.sh 10.31.0 intel /path/to/jre   Use pre-built JRE
#
# Prerequisites:
#   - macOS with Xcode Command Line Tools (xcode-select --install)
#   - JDK 14+ with jpackage (Azul Zulu FX recommended)
#   - For code signing: "Developer ID Application" + "Developer ID Installer"
#     certificates in Keychain (from Apple Developer account)
#   - For notarization: notarytool keychain profile named "NotaryProfile"
#     Setup: xcrun notarytool store-credentials "NotaryProfile" \
#              --apple-id "you@email.com" --team-id "TEAM_ID" \
#              --password "app-specific-password"
# ============================================================================

# ============================= Configuration ==================================

APP_NAME="PickMyTradeIB"
APP_VERSION="${1:-10.30.0}"
ARCH_INPUT="${2:-auto}"
JRE_PATH="${3:-}"
MAIN_CLASS="com.pickmytrade.ibapp.Launcher"
PACKAGE_ID="com.pickmytrade.ibapp"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR_DIR="${SCRIPT_DIR}/output"
ICON_PATH="${SCRIPT_DIR}/src/main/resources/logo.png"

# Build directories (cleaned up at end)
BUILD_DIR=""
STAGING_DIR=""
PKGROOT_DIR=""
ICNS_PATH=""
ENTITLEMENTS_PATH=""
COMPONENT_PLIST=""

echo ""
echo "============================================================"
echo " PickMyTrade IB App - macOS PKG Packager"
echo " Version: ${APP_VERSION}"
echo "============================================================"
echo ""

# ============================= Functions ======================================

cleanup() {
    echo ""
    echo "[INFO] Cleaning up build artifacts..."
    [[ -n "$BUILD_DIR" && -d "$BUILD_DIR" ]] && rm -rf "$BUILD_DIR"
    [[ -n "$STAGING_DIR" && -d "$STAGING_DIR" ]] && rm -rf "$STAGING_DIR"
    [[ -n "$PKGROOT_DIR" && -d "$PKGROOT_DIR" ]] && rm -rf "$PKGROOT_DIR"
    [[ -n "$ICNS_PATH" && -f "$ICNS_PATH" ]] && rm -f "$ICNS_PATH"
    [[ -n "$ENTITLEMENTS_PATH" && -f "$ENTITLEMENTS_PATH" ]] && rm -f "$ENTITLEMENTS_PATH"
    [[ -n "$COMPONENT_PLIST" && -f "$COMPONENT_PLIST" ]] && rm -f "$COMPONENT_PLIST"
    [[ -d "${SCRIPT_DIR}/AppIcon.iconset" ]] && rm -rf "${SCRIPT_DIR}/AppIcon.iconset"
    echo "[OK] Cleanup done."
}

# Cleanup on exit (success or failure)
trap cleanup EXIT

die() {
    echo ""
    echo "[ERROR] $1"
    exit 1
}

# ======================== Phase 1: Validate Environment =======================

# Verify macOS
if [[ "$(uname -s)" != "Darwin" ]]; then
    die "This script must be run on macOS. macOS PKG creation requires native macOS tools."
fi

# Determine architecture
detect_arch() {
    case "$(uname -m)" in
        x86_64) echo "intel" ;;
        arm64)  echo "arm" ;;
        *)      echo "unknown" ;;
    esac
}

if [[ "$ARCH_INPUT" == "auto" ]]; then
    ARCH="$(detect_arch)"
    [[ "$ARCH" == "unknown" ]] && die "Could not detect architecture. Specify 'intel' or 'arm'."
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
        die "Invalid architecture: ${ARCH}. Use 'intel', 'arm', or 'auto'."
        ;;
esac

BUILD_DIR="${SCRIPT_DIR}/build"
STAGING_DIR="${SCRIPT_DIR}/staging-${ARCH}"
PKGROOT_DIR="${SCRIPT_DIR}/pkgroot"
COMPONENT_PLIST="${SCRIPT_DIR}/${APP_NAME}-component.plist"

# Check jpackage
command -v jpackage &>/dev/null || die "jpackage not found. JDK 14+ required. Download Azul Zulu FX from: https://www.azul.com/downloads/"
echo "[OK] jpackage found: $(which jpackage)"

# Check fat JAR
FAT_JAR="${JAR_DIR}/${JAR_NAME}"
[[ -f "$FAT_JAR" ]] || die "Fat JAR not found: ${FAT_JAR}. Build it first: ./build.sh ${PLATFORM}"
echo "[OK] Fat JAR: ${FAT_JAR}"

# Detect signing identities from Keychain
APP_SIGN_ID=""
PKG_SIGN_ID=""
SIGNING_ENABLED=false

APP_SIGN_ID=$(security find-identity -v -p codesigning 2>/dev/null \
    | grep "Developer ID Application" \
    | head -1 \
    | sed 's/.*"\(.*\)"/\1/' || true)

PKG_SIGN_ID=$(security find-identity -v 2>/dev/null \
    | grep "Developer ID Installer" \
    | head -1 \
    | sed 's/.*"\(.*\)"/\1/' || true)

if [[ -n "$APP_SIGN_ID" && -n "$PKG_SIGN_ID" ]]; then
    SIGNING_ENABLED=true
    echo "[OK] App signing identity: ${APP_SIGN_ID}"
    echo "[OK] PKG signing identity: ${PKG_SIGN_ID}"
else
    echo "[WARN] Code signing certificates not found in Keychain."
    if [[ -z "$APP_SIGN_ID" ]]; then
        echo "       Missing: 'Developer ID Application' certificate"
    fi
    if [[ -z "$PKG_SIGN_ID" ]]; then
        echo "       Missing: 'Developer ID Installer' certificate"
    fi
    echo "       The app will be built but NOT signed or notarized."
    echo "       Install certificates from your Apple Developer account to enable signing."
fi

# ======================== Phase 2: Convert Icon ===============================

if [[ -f "$ICON_PATH" ]]; then
    echo ""
    echo "[INFO] Converting logo.png to .icns format..."

    ICONSET_DIR="${SCRIPT_DIR}/AppIcon.iconset"
    mkdir -p "$ICONSET_DIR"

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
    rm -rf "$ICONSET_DIR"

    ICNS_PATH="${SCRIPT_DIR}/logo.icns"
    echo "[OK] Icon converted: ${ICNS_PATH}"
else
    echo "[WARN] Logo not found: ${ICON_PATH}. App will use default icon."
fi

# ======================== Phase 3: Prepare JRE =================================

if [[ -n "$JRE_PATH" ]]; then
    [[ -f "${JRE_PATH}/bin/java" ]] || die "Provided JRE path does not contain bin/java: ${JRE_PATH}"
    echo "[OK] Using provided JRE: ${JRE_PATH}"
else
    echo ""
    echo "[INFO] No JRE path provided. Creating custom JRE via jlink..."
    bash "${SCRIPT_DIR}/create-jre.sh" "$JRE_PLATFORM"
    JRE_PATH="${SCRIPT_DIR}/jre-${JRE_PLATFORM}"
fi

[[ -f "${JRE_PATH}/bin/java" ]] || die "JRE not found at: ${JRE_PATH}"
echo "[OK] JRE ready: ${JRE_PATH}"

# ======================== Phase 4: Clean Previous Builds ======================

echo ""
echo "[INFO] Cleaning previous build artifacts..."
rm -rf "$BUILD_DIR" "$STAGING_DIR" "$PKGROOT_DIR"
rm -f "$COMPONENT_PLIST"
mkdir -p "$STAGING_DIR" "$DIST_DIR"

# Copy fat JAR to staging
cp "$FAT_JAR" "${STAGING_DIR}/${APP_NAME}.jar"
echo "[OK] Staging ready: ${STAGING_DIR}"

# ======================== Phase 5: Create App Image ===========================
# Use jpackage --type app-image (NOT --type pkg)
# This creates a .app bundle that we can modify before final packaging.
# Using --type pkg directly would skip native lib signing and fail notarization.

echo ""
echo "================================================================"
echo " Phase 5: Creating app image with jpackage"
echo "================================================================"
echo ""

JPACKAGE_ARGS=(
    --type app-image
    --name "$APP_NAME"
    --app-version "$APP_VERSION"
    --input "$STAGING_DIR"
    --main-jar "${APP_NAME}.jar"
    --main-class "$MAIN_CLASS"
    --runtime-image "$JRE_PATH"
    --dest "$BUILD_DIR"
    --vendor "PickMyTrade"
    --description "PickMyTrade IB Trading Application"
    --copyright "Copyright 2025 PickMyTrade"
    --mac-package-identifier "$PACKAGE_ID"
    --mac-package-name "$APP_NAME"
    --java-options "--enable-native-access=ALL-UNNAMED"
    --verbose
)

if [[ -n "$ICNS_PATH" ]]; then
    JPACKAGE_ARGS+=(--icon "$ICNS_PATH")
fi

# Do NOT pass --mac-sign here. We sign manually after native lib signing.

echo "Running: jpackage ${JPACKAGE_ARGS[*]}"
echo ""
jpackage "${JPACKAGE_ARGS[@]}" || die "jpackage --type app-image failed."

APP_BUNDLE="${BUILD_DIR}/${APP_NAME}.app"
[[ -d "$APP_BUNDLE" ]] || die "App bundle not created: ${APP_BUNDLE}"
echo ""
echo "[OK] App image created: ${APP_BUNDLE}"

# ======================== Phase 6: Create Entitlements ========================
# JVM requires specific entitlements for JIT compilation and dynamic code loading.
# These are the MINIMUM entitlements needed - no unnecessary permissions.
#
# Security notes:
#   - allow-jit: Required for JVM JIT compiler (HotSpot)
#   - allow-unsigned-executable-memory: Required for JVM dynamic code generation
#   - disable-library-validation: Required to load native libs extracted from JARs
#     at runtime (JNA, SQLite, etc. extract to temp dirs)
# We deliberately do NOT include:
#   - allow-dyld-environment-variables (attack vector, not needed for this app)
#   - disable-executable-page-protection (not needed)

echo ""
echo "================================================================"
echo " Phase 6: Creating entitlements for JVM"
echo "================================================================"

ENTITLEMENTS_PATH="${SCRIPT_DIR}/entitlements.plist"
cat > "$ENTITLEMENTS_PATH" << 'ENTITLEMENTS_EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
  "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <!-- Required for JVM JIT compilation (HotSpot compiler) -->
    <key>com.apple.security.cs.allow-jit</key>
    <true/>
    <!-- Required for JVM dynamic code generation -->
    <key>com.apple.security.cs.allow-unsigned-executable-memory</key>
    <true/>
    <!-- Required to load native libs extracted from JARs at runtime -->
    <key>com.apple.security.cs.disable-library-validation</key>
    <true/>
</dict>
</plist>
ENTITLEMENTS_EOF

echo "[OK] Entitlements created: ${ENTITLEMENTS_PATH}"

# ======================== Phase 7: Sign Native Libs in JARs ===================
# CRITICAL: JARs contain native .dylib/.jnilib files (JNA, SQLite JDBC, gRPC,
# Conscrypt, Netty). Apple's notarization scans INSIDE JARs/ZIPs and rejects
# any unsigned Mach-O binaries. We must extract, sign, and repack each one.
#
# Known libraries with native macOS binaries:
#   - jna-*.jar:                  com/sun/jna/darwin-*/libjnidispatch.jnilib
#   - sqlite-jdbc-*.jar:          org/sqlite/native/Mac/*/libsqlitejdbc.dylib
#   - grpc-netty-shaded-*.jar:    META-INF/native/*osx*.jnilib
#   - conscrypt-openjdk-uber-*.jar: META-INF/native/*osx*.dylib
#   - netty-transport-native-*:   META-INF/native/*kqueue*.jnilib

if [[ "$SIGNING_ENABLED" == true ]]; then
    echo ""
    echo "================================================================"
    echo " Phase 7: Signing native libraries inside JARs"
    echo "================================================================"
    echo ""

    # Clear quarantine attributes before signing (safe: just removes download flags)
    echo "[INFO] Clearing quarantine attributes..."
    xattr -cr "$APP_BUNDLE" 2>/dev/null || true

    SIGNED_NATIVE_COUNT=0
    PROCESSED_JAR_COUNT=0

    # Find ALL JARs in the app bundle
    while IFS= read -r -d '' jar_file; do
        PROCESSED_JAR_COUNT=$((PROCESSED_JAR_COUNT + 1))
        jar_basename="$(basename "$jar_file")"

        # Create temp directory for extraction
        TEMP_EXTRACT="$(mktemp -d)"

        # Extract JAR
        unzip -q "$jar_file" -d "$TEMP_EXTRACT" 2>/dev/null || {
            rm -rf "$TEMP_EXTRACT"
            continue
        }

        # Find Mach-O native binaries (dylib, jnilib, so)
        JAR_HAS_NATIVES=false
        while IFS= read -r -d '' native_lib; do
            # Verify it's actually a Mach-O binary (not a Linux .so or Windows file)
            if file "$native_lib" 2>/dev/null | grep -q "Mach-O"; then
                JAR_HAS_NATIVES=true
                relative_path="${native_lib#$TEMP_EXTRACT/}"
                echo "  Signing: ${jar_basename} -> ${relative_path}"

                codesign --force \
                         --options runtime \
                         --timestamp \
                         --sign "$APP_SIGN_ID" \
                         --entitlements "$ENTITLEMENTS_PATH" \
                         "$native_lib" || {
                    echo "  [WARN] Failed to sign: ${relative_path}"
                }

                SIGNED_NATIVE_COUNT=$((SIGNED_NATIVE_COUNT + 1))
            fi
        done < <(find "$TEMP_EXTRACT" \( -name "*.dylib" -o -name "*.jnilib" -o -name "*.so" \) -print0)

        # Repack JAR only if we signed native libraries
        if [[ "$JAR_HAS_NATIVES" == true ]]; then
            echo "  Repacking: ${jar_basename}"
            # Use zip to repack (preserves signed binaries correctly)
            pushd "$TEMP_EXTRACT" > /dev/null
            zip -r -q "${jar_file}.new" . || die "Failed to repack ${jar_basename}"
            popd > /dev/null
            mv "${jar_file}.new" "$jar_file"
        fi

        rm -rf "$TEMP_EXTRACT"
    done < <(find "$APP_BUNDLE" -name "*.jar" -print0)

    echo ""
    echo "[OK] Processed ${PROCESSED_JAR_COUNT} JARs, signed ${SIGNED_NATIVE_COUNT} native libraries"

    # ==================== Phase 8: Sign App Bundle (Inside-Out) =================
    # Apple requires inside-out signing: innermost components first, then outer.
    # --deep is DEPRECATED and insufficient. We sign explicitly in order:
    #   1. Runtime dylibs
    #   2. Runtime executables (java, jspawnhelper)
    #   3. Main app executable
    #   4. Entire .app bundle (outermost)

    echo ""
    echo "================================================================"
    echo " Phase 8: Signing app bundle (inside-out)"
    echo "================================================================"
    echo ""

    # 8a. Sign all dylibs in the runtime
    echo "[INFO] Signing runtime dylibs..."
    RUNTIME_DIR="${APP_BUNDLE}/Contents/runtime"
    if [[ -d "$RUNTIME_DIR" ]]; then
        while IFS= read -r -d '' dylib; do
            codesign --force --options runtime --timestamp \
                     --sign "$APP_SIGN_ID" \
                     --entitlements "$ENTITLEMENTS_PATH" \
                     "$dylib" 2>/dev/null || true
        done < <(find "$RUNTIME_DIR" -name "*.dylib" -print0)
        echo "[OK] Runtime dylibs signed"
    fi

    # 8b. Sign runtime executables
    echo "[INFO] Signing runtime executables..."
    if [[ -d "$RUNTIME_DIR" ]]; then
        for exe_name in java jspawnhelper keytool; do
            exe_path="${RUNTIME_DIR}/Contents/Home/bin/${exe_name}"
            if [[ -f "$exe_path" ]]; then
                codesign --force --options runtime --timestamp \
                         --sign "$APP_SIGN_ID" \
                         --entitlements "$ENTITLEMENTS_PATH" \
                         "$exe_path"
            fi
        done
        echo "[OK] Runtime executables signed"
    fi

    # 8c. Sign the main app executable
    echo "[INFO] Signing main executable..."
    MAIN_EXE="${APP_BUNDLE}/Contents/MacOS/${APP_NAME}"
    if [[ -f "$MAIN_EXE" ]]; then
        codesign --force --options runtime --timestamp \
                 --sign "$APP_SIGN_ID" \
                 --entitlements "$ENTITLEMENTS_PATH" \
                 "$MAIN_EXE"
        echo "[OK] Main executable signed"
    fi

    # 8d. Sign the entire .app bundle (outermost seal)
    echo "[INFO] Signing app bundle..."
    codesign --force --options runtime --timestamp \
             --sign "$APP_SIGN_ID" \
             --entitlements "$ENTITLEMENTS_PATH" \
             "$APP_BUNDLE" || die "Failed to sign app bundle"
    echo "[OK] App bundle signed"

    # ==================== Phase 9: Verify Signature =============================

    echo ""
    echo "================================================================"
    echo " Phase 9: Verifying code signature"
    echo "================================================================"
    echo ""

    echo "[INFO] Running codesign verification..."
    if codesign -vvv --deep --strict "$APP_BUNDLE" 2>&1; then
        echo "[OK] Code signature verified: VALID"
    else
        echo "[WARN] Signature verification reported issues (see above)."
        echo "       Continuing anyway — notarization may still succeed."
    fi

    echo ""
    echo "[INFO] Running spctl assessment..."
    if spctl --assess --type execute --verbose "$APP_BUNDLE" 2>&1; then
        echo "[OK] Gatekeeper assessment: ACCEPTED"
    else
        echo "[WARN] Gatekeeper assessment failed. This may be resolved after notarization."
    fi

else
    echo ""
    echo "[INFO] Skipping code signing (no signing certificates found)."
fi

# ======================== Phase 10: Build PKG with pkgbuild ===================
# Use pkgbuild instead of jpackage --type pkg because:
#   - We can set BundleIsRelocatable=NO (prevents installation to wrong location)
#   - We have full control over the installer behavior
#   - pkgbuild is Apple's first-party tool (more reliable)

echo ""
echo "================================================================"
echo " Phase 10: Building PKG installer with pkgbuild"
echo "================================================================"
echo ""

# Create pkgroot with the app in Applications/
rm -rf "$PKGROOT_DIR"
mkdir -p "${PKGROOT_DIR}/Applications"
cp -R "$APP_BUNDLE" "${PKGROOT_DIR}/Applications/"
echo "[OK] PKG root created: ${PKGROOT_DIR}/Applications/${APP_NAME}.app"

# Generate component plist and set BundleIsRelocatable=NO
# BundleIsRelocatable=NO prevents macOS from installing updates to a different
# location if it finds an existing copy elsewhere on the system.
echo "[INFO] Generating component plist..."
pkgbuild --analyze --root "$PKGROOT_DIR" "$COMPONENT_PLIST" 2>/dev/null || \
    die "pkgbuild --analyze failed"

plutil -replace BundleIsRelocatable -bool NO "$COMPONENT_PLIST" || \
    die "Failed to set BundleIsRelocatable=NO"
echo "[OK] BundleIsRelocatable set to NO"

# Build the PKG
PKG_FILE="${DIST_DIR}/${APP_NAME}-${APP_VERSION}.pkg"
mkdir -p "$DIST_DIR"

PKGBUILD_ARGS=(
    --root "$PKGROOT_DIR"
    --identifier "$PACKAGE_ID"
    --version "$APP_VERSION"
    --install-location /
    --component-plist "$COMPONENT_PLIST"
)

# Sign the PKG with Developer ID Installer certificate
if [[ "$SIGNING_ENABLED" == true ]]; then
    PKGBUILD_ARGS+=(--sign "$PKG_SIGN_ID")
    echo "[INFO] PKG will be signed with: ${PKG_SIGN_ID}"
fi

echo "[INFO] Running pkgbuild..."
pkgbuild "${PKGBUILD_ARGS[@]}" "$PKG_FILE" || die "pkgbuild failed"

[[ -f "$PKG_FILE" ]] || die "PKG file not created: ${PKG_FILE}"

PKG_SIZE=$(du -h "$PKG_FILE" | cut -f1)
echo ""
echo "[OK] PKG created: ${PKG_FILE} (${PKG_SIZE})"

# ======================== Phase 11: Verify PKG Signature ======================

if [[ "$SIGNING_ENABLED" == true ]]; then
    echo ""
    echo "================================================================"
    echo " Phase 11: Verifying PKG signature"
    echo "================================================================"
    echo ""

    if pkgutil --check-signature "$PKG_FILE" 2>/dev/null; then
        echo "[OK] PKG signature verified"
    else
        echo "[WARN] PKG signature verification had issues"
    fi
fi

# ======================== Phase 12: Notarize & Staple =========================

if [[ "$SIGNING_ENABLED" == true ]]; then
    echo ""
    echo "================================================================"
    echo " Phase 12: Notarization"
    echo "================================================================"
    echo ""

    # Check if notarization profile exists
    if xcrun notarytool history --keychain-profile "NotaryProfile" &>/dev/null 2>&1; then
        echo "[INFO] Submitting PKG for Apple notarization..."
        echo "       This may take several minutes..."
        echo ""

        if xcrun notarytool submit "$PKG_FILE" \
            --keychain-profile "NotaryProfile" \
            --wait; then
            echo ""
            echo "[OK] Notarization successful!"

            # Staple the notarization ticket to the PKG
            # This embeds Apple's approval so users don't need internet for verification
            echo "[INFO] Stapling notarization ticket..."
            if xcrun stapler staple "$PKG_FILE"; then
                echo "[OK] Notarization ticket stapled to PKG"
            else
                echo "[WARN] Stapling failed. Users may need internet for Gatekeeper verification."
            fi
        else
            echo ""
            echo "[WARN] Notarization failed!"
            echo ""
            echo "  Common causes and fixes:"
            echo "  ----------------------------------------"
            echo "  1. Unsigned native library inside a JAR"
            echo "     Fix: Check notarization log for the specific file:"
            echo "       xcrun notarytool log <submission-id> --keychain-profile \"NotaryProfile\" log.json"
            echo "       cat log.json | python3 -m json.tool"
            echo ""
            echo "  2. Missing hardened runtime on a binary"
            echo "     Fix: Ensure all codesign calls use --options runtime"
            echo ""
            echo "  3. Missing secure timestamp"
            echo "     Fix: Ensure all codesign calls use --timestamp"
            echo ""
            echo "  4. Binary linked against old SDK"
            echo "     Fix: Check which binary and rebuild with macOS 10.9+ SDK"
            echo ""
            echo "  The PKG can still be installed but will trigger Gatekeeper warnings."
        fi
    else
        echo "[INFO] Notarization profile 'NotaryProfile' not found. Skipping notarization."
        echo ""
        echo "  To set up notarization (one-time setup):"
        echo "    xcrun notarytool store-credentials \"NotaryProfile\" \\"
        echo "      --apple-id \"your@email.com\" \\"
        echo "      --team-id \"YOUR_TEAM_ID\" \\"
        echo "      --password \"app-specific-password\""
        echo ""
        echo "  The app-specific password is generated at: https://appleid.apple.com/account/manage"
    fi
fi

# ======================== Final Summary =======================================

echo ""
echo "============================================================"
echo " BUILD COMPLETE"
echo "============================================================"
echo " Installer: ${PKG_FILE}"
echo " Size:      ${PKG_SIZE}"
echo " Signed:    ${SIGNING_ENABLED}"
if [[ "$SIGNING_ENABLED" == true ]]; then
echo " App cert:  ${APP_SIGN_ID}"
echo " PKG cert:  ${PKG_SIGN_ID}"
fi
echo "============================================================"
echo ""
echo " To test installation:"
echo "   sudo installer -pkg \"${PKG_FILE}\" -target / -verbose"
echo "   open -a ${APP_NAME}"
echo ""
echo " To remove previous installation before testing:"
echo "   sudo rm -rf /Applications/${APP_NAME}.app"
echo "   sudo pkgutil --forget ${PACKAGE_ID}"
echo "============================================================"
