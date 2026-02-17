# PickMyTrade IB App

A JavaFX desktop application that bridges the PickMyTrade trading platform with Interactive Brokers (IB) via the TWS API. It receives trade signals from the PickMyTrade backend and executes them as orders on IB TWS/Gateway.

## Prerequisites

- **Docker Desktop** (v20.10+ with BuildKit support)
- **IB TWS API JAR** (`TwsApi.jar`) - see [Setup](#setup) below
- **JDK 21+** on target machines to run the built JARs

## Setup

### 1. Install Docker Desktop

- **Windows**: Download from [docker.com/products/docker-desktop](https://www.docker.com/products/docker-desktop/)
- **macOS**: Download from [docker.com/products/docker-desktop](https://www.docker.com/products/docker-desktop/)

### 2. Obtain the IB TWS API JAR

The Interactive Brokers TWS API is **not available on Maven Central**. You must download it manually:

1. Go to [https://interactivebrokers.github.io/](https://interactivebrokers.github.io/)
2. Download the **TWS API** package for your platform
3. Extract the archive and locate `TwsApi.jar` (or build from source)
4. Place the JAR in the `libs/` directory (any of these names work):
   ```
   libs/TwsApi.jar
   libs/TwsApimac.jar
   libs/tws-api-10.30.01.jar
   ```

> The `libs/` directory is already created in this project. The TWS API JAR is pure Java and platform-independent, so the same JAR works for all target platforms.

### Cross-Platform Builds from Windows

You can build macOS JARs directly from a Windows machine using Docker. The Docker build uses `-Djavafx.platform=<target>` to cross-compile JavaFX natives for any platform. No Mac hardware is required for building.

Three separate builds are produced:
- **win** - Windows (x64) with `.dll` native libraries
- **mac** - macOS Intel (x64) with `.dylib` native libraries
- **mac-aarch64** - macOS Apple Silicon (ARM64) with ARM `.dylib` native libraries

## Building with Docker

### Quick Start - Build All Platforms

**Windows (Command Prompt / PowerShell):**
```cmd
build.bat all
```

**macOS / Linux (Terminal):**
```bash
chmod +x build.sh
./build.sh all
```

Output JARs will be in the `output/` directory:
```
output/
  pickmytrade-ib-win.jar          # Windows build
  pickmytrade-ib-mac.jar          # macOS Intel build
  pickmytrade-ib-mac-aarch64.jar  # macOS Apple Silicon build
```

### Build for a Specific Platform

```bash
# Windows only
./build.sh win

# macOS Intel only
./build.sh mac

# macOS Apple Silicon only
./build.sh mac-arm
```

Or specify a custom output directory:
```bash
./build.sh win ./dist
```

### Build Using Docker Directly

If you prefer raw Docker commands:

```bash
# Build for Windows
docker build --target output --build-arg TARGET_PLATFORM=win --output type=local,dest=./output .

# Build for macOS (Intel)
docker build --target output --build-arg TARGET_PLATFORM=mac --output type=local,dest=./output .

# Build for macOS (Apple Silicon)
docker build --target output --build-arg TARGET_PLATFORM=mac-aarch64 --output type=local,dest=./output .
```

### Build Using Docker Compose

```bash
# Build and export Windows JAR
docker compose run --rm build-windows

# Build and export macOS JAR
docker compose run --rm build-mac

# Build and export macOS ARM JAR
docker compose run --rm build-mac-arm

# Build ALL platforms sequentially
docker compose run --rm build-all
```

## Development Environment

Docker provides a full development environment with JDK 21, Maven, Git, and all dependencies pre-installed.

### Start the Dev Container

```bash
docker compose run --rm dev
```

This drops you into a bash shell inside the container with the project mounted at `/workspace`.

### Common Dev Commands Inside the Container

```bash
# Compile the project
mvn clean compile

# Build fat JAR (for current container platform - Linux)
mvn clean package -DskipTests

# Build fat JAR for Windows
mvn clean package -DskipTests -Djavafx.platform=win

# Build fat JAR for macOS
mvn clean package -DskipTests -Djavafx.platform=mac

# Run dependency tree
mvn dependency:tree

# Check for dependency updates
mvn versions:display-dependency-updates
```

### Using the Maven Cache Volume

The dev service uses a persistent Docker volume (`pickmytrade-maven-cache`) for the Maven repository. This means dependencies are downloaded only once and persist across container restarts.

To clear the cache:
```bash
docker volume rm pickmytrade-maven-cache
```

## Docker Architecture

```
Dockerfile (multi-stage)
  |
  +-- base       : JDK 21 + Maven 3.9 + tws-api installed + dependencies cached
  |
  +-- builder    : Compiles source and creates fat JAR for TARGET_PLATFORM
  |
  +-- dev        : Full dev environment (git, vim, curl, etc.) for interactive use
  |
  +-- output     : Minimal stage for extracting JARs via --output flag
```

### Build Arguments

| Argument | Default | Description |
|----------|---------|-------------|
| `TARGET_PLATFORM` | `win` | JavaFX platform: `win`, `mac`, `mac-aarch64` |
| `MAVEN_VERSION` | `3.9.9` | Maven version to install |

## Running the Application

After building, run the JAR on the target platform:

**Windows:**
```cmd
java -jar pickmytrade-ib-win.jar
```

**macOS (Intel):**
```bash
java -jar pickmytrade-ib-mac.jar
```

**macOS (Apple Silicon):**
```bash
java -jar pickmytrade-ib-mac-aarch64.jar
```

> **Note:** The application requires JDK 21+ installed on the target machine. JavaFX native libraries are bundled in the fat JAR.

## Native Installer Packaging

After building fat JARs (via Docker), you can create native installers (`.msi` for Windows, `.pkg` for macOS) using `jpackage`. These installers bundle a custom JRE so end users **do not need Java installed**.

> **Important:** Windows MSI must be built on Windows. macOS PKG must be built on macOS. Docker (Linux) cannot create these native installers.

### Prerequisites

**Windows (for MSI):**
- JDK 14+ (for `jpackage` tool; the script downloads [Azul Zulu JDK 21 FX](https://www.azul.com/downloads/) for JRE creation)
- [WiX Toolset](https://wixtoolset.org/releases/) v3.x or v4+ in PATH

**macOS (for PKG):**
- [Azul Zulu JDK 21 FX](https://www.azul.com/downloads/) (includes JavaFX; used for `jpackage`, `jlink`, and JRE creation)
- Xcode Command Line Tools (`xcode-select --install`)
- (Optional) Apple Developer certificate for code signing
- (Optional) Notarization profile for distribution outside the App Store

### Full Workflow

```
1. Build fat JARs (Docker, any OS)     →  output/*.jar
2. Package installer (native OS)        →  dist/*.msi or dist-*/*.pkg
```

### Windows MSI

```cmd
:: Build fat JAR first (if not already done)
build.bat win

:: Create MSI installer (default version 10.30.0)
package-windows.bat

:: Or specify version
package-windows.bat 10.30.1

:: Or use a pre-built JRE
package-windows.bat 10.30.1 jre-win
```

Output: `dist\PickMyTradeIB-<version>.msi`

The script will automatically:
1. Download JavaFX 21 jmods
2. Create a custom JRE via `jlink` (if no JRE path provided)
3. Build the MSI using `jpackage`

### macOS PKG

```bash
# Build fat JAR first (if not already done)
./build.sh mac        # Intel
./build.sh mac-arm    # Apple Silicon

# Create PKG installer (auto-detect architecture)
chmod +x package-mac.sh
./package-mac.sh

# Or specify version and architecture
./package-mac.sh 10.30.1 intel
./package-mac.sh 10.30.1 arm

# Or use a pre-built JRE
./package-mac.sh 10.30.1 arm jre-mac-aarch64
```

Output: `dist-intel/PickMyTradeIB-<version>.pkg` or `dist-arm/PickMyTradeIB-<version>.pkg`

The script will automatically:
1. Convert `logo.png` to `.icns` format
2. Download JavaFX 21 jmods for the target architecture
3. Create a custom JRE via `jlink` (if no JRE path provided)
4. Build the PKG using `jpackage`
5. Sign the PKG (if a Developer ID certificate is found)
6. Submit for notarization (if notarization profile exists)

### Custom JRE Creation (Standalone)

You can create a custom JRE independently for reuse across multiple builds:

**Windows:**
```cmd
create-jre.bat win
:: Output: jre-win/
```

**macOS / Linux:**
```bash
chmod +x create-jre.sh
./create-jre.sh mac            # Intel
./create-jre.sh mac-aarch64    # Apple Silicon
# Output: jre-mac/ or jre-mac-aarch64/
```

### macOS Code Signing Setup

To sign and notarize PKG installers for distribution:

1. **Install a Developer ID certificate** from your Apple Developer account into Keychain Access
2. **Create a notarization profile:**
   ```bash
   xcrun notarytool store-credentials "NotaryProfile" \
     --apple-id "your@email.com" \
     --team-id "YOUR_TEAM_ID" \
     --password "app-specific-password"
   ```
3. Run `package-mac.sh` — signing and notarization happen automatically when credentials are found

## Project Structure

```
pickmytrade_windows_ib/
  |-- src/main/java/com/pickmytrade/ibapp/
  |     |-- Launcher.java              # Fat JAR entry point (delegates to MainApp)
  |     |-- MainApp.java               # JavaFX Application class
  |     |-- TradeServer.java           # HTTP server for trade requests
  |     |-- config/                    # Configuration classes
  |     |-- bussinesslogic/            # Trade execution (PlaceOrderService, TwsEngine)
  |     +-- db/                        # SQLite database layer
  |-- src/main/resources/              # logback.xml, logo.png, logo.ico, spinner.gif
  |-- libs/                            # Place tws-api-10.30.01.jar here
  |-- output/                          # Built JARs appear here (from Docker)
  |-- dist/                            # Windows MSI output (from jpackage)
  |-- dist-intel/                      # macOS Intel PKG output
  |-- dist-arm/                        # macOS ARM PKG output
  |-- Dockerfile                       # Multi-stage build
  |-- docker-compose.yml               # Build/dev service definitions
  |-- build.sh                         # Linux/macOS Docker build script
  |-- build.bat                        # Windows Docker build script
  |-- package-windows.bat              # Windows MSI packaging (jpackage)
  |-- package-mac.sh                   # macOS PKG packaging (jpackage + signing)
  |-- create-jre.bat                   # Custom JRE creator (Windows)
  |-- create-jre.sh                    # Custom JRE creator (macOS/Linux)
  +-- pom.xml                          # Maven project configuration
```

## Troubleshooting

### "tws-api-10.30.01.jar not found"
Place the IB TWS API JAR in the `libs/` directory. See [Setup](#2-obtain-the-ib-tws-api-jar).

### Docker build fails with "connection refused"
Make sure Docker Desktop is running before executing build commands.

### Build is slow on first run
The first build downloads all Maven dependencies (~500MB). Subsequent builds use Docker layer caching and complete much faster.

### "No matching variant of org.openjfx"
Ensure `TARGET_PLATFORM` is one of: `win`, `mac`, `mac-aarch64`. Other values are not supported by JavaFX.

### JAR runs but shows blank window
Make sure you are using the JAR built for your platform. Using the Windows JAR on macOS (or vice versa) will fail because JavaFX native libraries are platform-specific.

### Clearing Docker Build Cache
```bash
docker builder prune
```
