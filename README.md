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
- **For code signing (required for distribution):**
  - "Developer ID Application" certificate (signs the `.app` and native binaries)
  - "Developer ID Installer" certificate (signs the `.pkg`)
  - Both from [Apple Developer Program](https://developer.apple.com/account/) → Certificates
- **For notarization:** Keychain profile named "NotaryProfile" (see setup below)

### Full Workflow

```
1. Build fat JARs (Docker, any OS)       →  output/*.jar
2. Package installer (native OS)          →  dist/*.msi or dist-*/*.pkg
   ├── Windows: package-windows.bat       →  jpackage → MSI
   └── macOS:   package-mac.sh            →  jpackage app-image → sign native
                                              libs in JARs → codesign → pkgbuild
                                              → notarize → staple
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

The script performs a 12-phase build:
1. Validate environment and detect signing certificates from Keychain
2. Convert `logo.png` to `.icns` format
3. Create custom JRE via `jlink` (if no JRE path provided, uses Azul Zulu FX)
4. Clean previous build artifacts
5. Create `.app` image with `jpackage --type app-image` (NOT `--type pkg`)
6. Create JVM entitlements file (minimum required permissions)
7. **Sign ALL native libraries inside JARs** (JNA, SQLite, gRPC, Conscrypt, etc.)
8. **Sign app bundle inside-out** (runtime dylibs → executables → main exe → .app)
9. Verify code signature with `codesign -vvv --deep --strict`
10. Build PKG with `pkgbuild` (BundleIsRelocatable=NO)
11. Verify PKG signature
12. Notarize with Apple and staple the ticket

> **Why app-image + pkgbuild instead of jpackage --type pkg?**
> `jpackage --type pkg` cannot sign native libraries inside JARs (JNA, SQLite JDBC, gRPC, Conscrypt all contain unsigned `.dylib`/`.jnilib` files). Apple's notarization scans inside JARs and rejects any unsigned Mach-O binaries. The app-image workflow lets us sign everything before creating the final PKG.

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

1. **Install certificates** from your Apple Developer account into Keychain Access:
   - "Developer ID Application" certificate (for signing `.app` and native binaries)
   - "Developer ID Installer" certificate (for signing `.pkg`)
   - Download from: [developer.apple.com/account](https://developer.apple.com/account/) → Certificates

2. **Verify certificates are installed:**
   ```bash
   security find-identity -v -p codesigning | grep "Developer ID Application"
   security find-identity -v | grep "Developer ID Installer"
   ```

3. **Create a notarization profile** (one-time setup):
   ```bash
   xcrun notarytool store-credentials "NotaryProfile" \
     --apple-id "your@email.com" \
     --team-id "YOUR_TEAM_ID" \
     --password "app-specific-password"
   ```
   Generate the app-specific password at: [appleid.apple.com/account/manage](https://appleid.apple.com/account/manage)

4. Run `./package-mac.sh` — signing and notarization happen automatically when credentials are found

### macOS: What the Script Signs and Why

The fat JAR contains native libraries from several dependencies that Apple requires to be signed:

| Library | Native files inside JAR | Why signing is needed |
|---------|------------------------|----------------------|
| JNA | `com/sun/jna/darwin-*/libjnidispatch.jnilib` | Windows taskbar integration (JNA is cross-platform) |
| SQLite JDBC | `org/sqlite/native/Mac/*/libsqlitejdbc.dylib` | Database access |
| gRPC Netty | `META-INF/native/*osx*.jnilib` | Google Cloud Pub/Sub transport |
| Conscrypt | `META-INF/native/*osx*.dylib` | TLS/SSL for Google Cloud |

Apple's notarization service scans **inside JAR/ZIP files** for Mach-O binaries. Any unsigned native library causes rejection.

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

### Docker Build Issues

**"tws-api-10.30.01.jar not found"**
Place the IB TWS API JAR in the `libs/` directory. See [Setup](#2-obtain-the-ib-tws-api-jar).

**Docker build fails with "connection refused"**
Make sure Docker Desktop is running before executing build commands.

**Build is slow on first run**
The first build downloads all Maven dependencies (~500MB). Subsequent builds use Docker layer caching and complete much faster.

**"No matching variant of org.openjfx"**
Ensure `TARGET_PLATFORM` is one of: `win`, `mac`, `mac-aarch64`. Other values are not supported by JavaFX.

**JAR runs but shows blank window**
Make sure you are using the JAR built for your platform. Using the Windows JAR on macOS (or vice versa) will fail because JavaFX native libraries are platform-specific.

**Clearing Docker Build Cache**
```bash
docker builder prune
```

### macOS PKG Packaging Issues

**Notarization fails: "The binary is not signed"**
This means Apple found an unsigned native library inside a JAR. The `package-mac.sh` script handles this automatically, but if you're packaging manually:
```bash
# Check the notarization log for the specific file:
xcrun notarytool log <submission-id> --keychain-profile "NotaryProfile" log.json
cat log.json | python3 -m json.tool
# Look for entries with "statusSummary": "The binary is not signed"
# The "path" field shows which file inside which JAR needs signing
```

**Notarization fails: "hardened runtime not enabled"**
All `codesign` calls must include `--options runtime`. This is required since macOS 10.14.

**Notarization fails: "secure timestamp missing"**
All `codesign` calls must include `--timestamp`. This contacts Apple's timestamp server.

**"a sealed resource is missing or invalid"**
Files were modified after signing. Common causes:
- Signing the `.app` before signing native libs inside JARs (must sign inside-out)
- Using `jpackage --type pkg` on an already-signed app (jpackage modifies files)
- Fix: Use the app-image workflow (`package-mac.sh` does this correctly)

**"code signature invalid" / "not valid on disk"**
Clear quarantine attributes and re-sign:
```bash
xattr -cr /path/to/PickMyTradeIB.app
# Then re-sign (package-mac.sh does this automatically)
```

**App installs to wrong location (not /Applications)**
This happens when `BundleIsRelocatable` is `true` (default). The `package-mac.sh` script sets it to `NO` via:
```bash
pkgbuild --analyze --root pkgroot component.plist
plutil -replace BundleIsRelocatable -bool NO component.plist
```

**"does not satisfy its designated requirement"**
All binaries must be signed with the **same** Developer ID certificate. Do not mix certificates.

**Gatekeeper blocks the app after installation**
```bash
# If not notarized: right-click the app → Open (first time only)
# If notarized but still blocked, check stapling:
xcrun stapler validate /Applications/PickMyTradeIB.app
# Re-staple if needed:
xcrun stapler staple /path/to/PickMyTradeIB-10.30.0.pkg
```

**Testing a clean installation**
```bash
# Remove previous installation completely
sudo rm -rf /Applications/PickMyTradeIB.app
sudo pkgutil --forget com.pickmytrade.ibapp 2>/dev/null

# Install the new PKG
sudo installer -pkg dist-arm/PickMyTradeIB-10.30.0.pkg -target / -verbose

# Verify installation
ls -la /Applications/PickMyTradeIB.app
open -a PickMyTradeIB
```

**JVM crashes with "code signature invalid" at runtime**
Native libraries extracted from JARs at runtime must be signed. The entitlements file includes `com.apple.security.cs.disable-library-validation` which allows loading these. If you see this error, the native libs inside JARs were not signed correctly.
