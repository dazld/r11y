#!/usr/bin/env bash

set -euo pipefail

echo "Building native binary with Docker + GraalVM..."
echo "This may take several minutes on first build..."
echo ""

# Resolve version from VERSION env var, git tag, or fallback to "dev"
VERSION="${VERSION:-$(git describe --tags --always 2>/dev/null || echo "dev")}"
echo "Version: $VERSION"

# Build the Docker image
docker build --build-arg VERSION="$VERSION" -t r11y-builder .

# Create a temporary container to extract the binary
CONTAINER_ID=$(docker create r11y-builder)

# Extract the binary
docker cp "$CONTAINER_ID:/usr/local/bin/r11y" ./r11y

# Clean up
docker rm "$CONTAINER_ID"

echo ""
echo "✓ Native binary built successfully: ./r11y"
echo ""
echo "You can now run it with:"
echo "  ./r11y https://example.com"
echo ""
echo "Or test with:"
echo "  ./r11y --help"
