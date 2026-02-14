FROM --platform=linux/amd64 ghcr.io/graalvm/native-image-community:22 AS builder

# Install Clojure
RUN curl -L -O https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh && \
    chmod +x linux-install.sh && \
    ./linux-install.sh && \
    rm linux-install.sh

WORKDIR /build

# Copy project files
COPY deps.edn .
COPY src ./src
COPY graal-config ./graal-config

# Download dependencies
RUN clojure -P

# Prepare classes directory
RUN mkdir -p classes

# Compile Clojure code with direct linking
RUN clojure -M -e "(binding [*compile-path* \"classes\"] (compile 'r11y.core))"

# Build uberjar
RUN clojure -X:uberjar

# Build native image
RUN native-image \
    -jar target/r11y.jar \
    -H:Name=r11y \
    -H:+ReportExceptionStackTraces \
    --features=clj_easy.graal_build_time.InitClojureClasses \
    --no-fallback \
    --report-unsupported-elements-at-runtime \
    -H:ConfigurationFileDirectories=graal-config \
    -J-Xmx3g

# Create minimal runtime image
FROM debian:bookworm-slim
COPY --from=builder /build/r11y /usr/local/bin/r11y
ENTRYPOINT ["/usr/local/bin/r11y"]
