#!/usr/bin/env bash

set -euo pipefail

echo "Preparing classes directory..."
mkdir -p classes

echo "Compiling Clojure with direct linking..."
clojure -M -e "(binding [*compile-path* \"classes\"] (compile 'r11y.core))"

echo ""
echo "Building uberjar..."
clojure -X:uberjar

echo ""
echo "Building native image with GraalVM..."
echo "This may take several minutes..."

# Resolve version from VERSION env var, git tag, or fallback to "dev"
VERSION="${VERSION:-$(git describe --tags --always 2>/dev/null || echo "dev")}"
echo "Version: $VERSION"

# Find native-image (prefer $JAVA_HOME, fall back to PATH)
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/native-image" ]; then
  NATIVE_IMAGE="$JAVA_HOME/bin/native-image"
else
  NATIVE_IMAGE="native-image"
fi

$NATIVE_IMAGE \
  -jar target/r11y.jar \
  -H:Name=r11y \
  -Dr11y.version="$VERSION" \
  -H:+ReportExceptionStackTraces \
  --features=clj_easy.graal_build_time.InitClojureClasses \
  --no-fallback \
  --report-unsupported-elements-at-runtime \
  --enable-url-protocols=http,https \
  -H:ConfigurationFileDirectories=graal-config

echo ""
echo "Native image built successfully: ./r11y"
echo "You can now run it with: ./r11y https://example.com"
