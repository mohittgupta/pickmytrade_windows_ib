#!/bin/bash
# ============================================================
# PickMyTrade IB App - Build Script
# Usage: ./build.sh [platform] [output-dir]
#   platform: win | mac | mac-arm | all (default: all)
#   output-dir: directory for JAR output (default: ./output)
# ============================================================

set -e

PLATFORM="${1:-all}"
OUTPUT_DIR="${2:-./output}"
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${GREEN}============================================${NC}"
echo -e "${GREEN} PickMyTrade IB App - Docker Build${NC}"
echo -e "${GREEN}============================================${NC}"

# Check Docker is running
if ! docker info > /dev/null 2>&1; then
    echo -e "${RED}ERROR: Docker is not running. Please start Docker Desktop.${NC}"
    exit 1
fi

# Check tws-api JAR exists
if [ ! -f "$PROJECT_DIR/libs/tws-api-10.30.01.jar" ]; then
    echo -e "${YELLOW}WARNING: libs/tws-api-10.30.01.jar not found!${NC}"
    echo -e "${YELLOW}The build will fail without the IB TWS API JAR.${NC}"
    echo -e "${YELLOW}Download from: https://interactivebrokers.github.io/${NC}"
    echo -e "${YELLOW}Place the JAR in the libs/ directory.${NC}"
    echo ""
    read -p "Continue anyway? (y/N) " -n 1 -r
    echo ""
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        exit 1
    fi
fi

mkdir -p "$OUTPUT_DIR"

build_platform() {
    local platform_arg=$1
    local platform_name=$2

    echo ""
    echo -e "${GREEN}Building for ${platform_name}...${NC}"
    echo "-------------------------------------------"

    DOCKER_BUILDKIT=1 docker build \
        --target output \
        --build-arg TARGET_PLATFORM="${platform_arg}" \
        --output "type=local,dest=${OUTPUT_DIR}" \
        "$PROJECT_DIR"

    if [ -f "${OUTPUT_DIR}/pickmytrade-ib-${platform_arg}.jar" ]; then
        local size=$(du -h "${OUTPUT_DIR}/pickmytrade-ib-${platform_arg}.jar" | cut -f1)
        echo -e "${GREEN}SUCCESS: pickmytrade-ib-${platform_arg}.jar (${size})${NC}"
    else
        echo -e "${RED}FAILED: JAR not found for ${platform_name}${NC}"
        return 1
    fi
}

case "$PLATFORM" in
    win|windows)
        build_platform "win" "Windows"
        ;;
    mac|macos)
        build_platform "mac" "macOS (Intel)"
        ;;
    mac-arm|mac-aarch64|macos-arm)
        build_platform "mac-aarch64" "macOS (Apple Silicon)"
        ;;
    all)
        build_platform "win" "Windows"
        build_platform "mac" "macOS (Intel)"
        build_platform "mac-aarch64" "macOS (Apple Silicon)"
        ;;
    *)
        echo "Usage: $0 [win|mac|mac-arm|all] [output-dir]"
        echo ""
        echo "Platforms:"
        echo "  win       - Windows (x64)"
        echo "  mac       - macOS (Intel x64)"
        echo "  mac-arm   - macOS (Apple Silicon ARM64)"
        echo "  all       - All platforms (default)"
        exit 1
        ;;
esac

echo ""
echo -e "${GREEN}============================================${NC}"
echo -e "${GREEN} Build complete! Output in: ${OUTPUT_DIR}/${NC}"
echo -e "${GREEN}============================================${NC}"
ls -lh "$OUTPUT_DIR"/*.jar 2>/dev/null
