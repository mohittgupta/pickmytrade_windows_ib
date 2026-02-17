# ============================================================
# PickMyTrade IB App - Multi-stage Docker Build
# Supports cross-platform builds for Windows and macOS
# from any host OS (including Windows)
# ============================================================

# ============================================================
# Stage 1: BASE - JDK + Maven + tws-api + dependency cache
# ============================================================
FROM eclipse-temurin:21-jdk AS base

ARG MAVEN_VERSION=3.9.9

# Install Maven
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    curl -fsSL https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz | \
    tar xz -C /opt && \
    ln -s /opt/apache-maven-${MAVEN_VERSION}/bin/mvn /usr/bin/mvn && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

ENV MAVEN_HOME=/opt/apache-maven-${MAVEN_VERSION}
ENV MAVEN_CONFIG=/root/.m2

WORKDIR /build

# ------------------------------------------------------------------
# Install IB TWS API into Maven local repo
# The TWS API JAR is pure Java (platform-independent)
# Place TwsApi.jar in the libs/ directory before building
# ------------------------------------------------------------------
COPY libs/ /build/libs/
RUN JAR_FILE="" && \
    if [ -f /build/libs/tws-api-10.30.01.jar ]; then \
        JAR_FILE=/build/libs/tws-api-10.30.01.jar; \
    elif [ -f /build/libs/TwsApi.jar ]; then \
        JAR_FILE=/build/libs/TwsApi.jar; \
    elif [ -f /build/libs/TwsApimac.jar ]; then \
        JAR_FILE=/build/libs/TwsApimac.jar; \
    fi && \
    if [ -n "$JAR_FILE" ]; then \
        echo "=== Installing tws-api from $JAR_FILE ===" && \
        mvn install:install-file \
            -Dfile="$JAR_FILE" \
            -DgroupId=com.ib \
            -DartifactId=tws-api \
            -Dversion=10.30.01 \
            -Dpackaging=jar \
            -DgeneratePom=true && \
        echo "=== tws-api installed successfully ==="; \
    else \
        echo "ERROR: No TWS API JAR found in libs/" && \
        echo "Place TwsApi.jar in the libs/ directory" && \
        exit 1; \
    fi

# Copy pom.xml and pre-download dependencies (cached Docker layer)
COPY pom.xml .
RUN mvn dependency:resolve -U || true
RUN mvn dependency:resolve-plugins || true

# ============================================================
# Stage 2: BUILDER - Compile and package the application
# ============================================================
FROM base AS builder

# TARGET_PLATFORM: win | mac | mac-aarch64
ARG TARGET_PLATFORM=win

# Copy source code
COPY src/ src/

# Build the fat JAR for the target platform
# -Djavafx.platform overrides OS detection so we can cross-compile
# e.g. build macOS JAR from a Windows/Linux Docker host
RUN mvn clean package -DskipTests \
        -Djavafx.platform=${TARGET_PLATFORM} \
        -Dmaven.javadoc.skip=true \
        -Dmaven.source.skip=true && \
    mkdir -p /output && \
    cp target/ib-desktop-app-final-DA-1.0-SNAPSHOT.jar \
       /output/pickmytrade-ib-${TARGET_PLATFORM}.jar && \
    echo "Build complete for platform: ${TARGET_PLATFORM}" && \
    echo "Output JAR: /output/pickmytrade-ib-${TARGET_PLATFORM}.jar" && \
    ls -lh /output/

# ============================================================
# Stage 3: DEV - Full development environment
# ============================================================
FROM base AS dev

RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        git \
        vim \
        curl \
        wget \
        unzip \
        tree \
        jq \
        bash-completion && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace

# Volume for mounting project source
VOLUME /workspace

# Expose trade server port (for local dev testing)
EXPOSE 7507

CMD ["/bin/bash"]

# ============================================================
# Stage 4: OUTPUT - Extract build artifacts (use with --output)
# ============================================================
FROM scratch AS output
COPY --from=builder /output/ /
