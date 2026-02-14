.PHONY: build-macos build-linux clean help

# Default target
help:
	@echo "Available targets:"
	@echo "  make build-macos   - Build native macOS binary"
	@echo "  make build-linux   - Build native Linux binary (via Docker)"
	@echo "  make clean         - Remove build artifacts"

# Build macOS native binary
build-macos:
	@echo "Building macOS native binary..."
	export JAVA_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home && ./build-native.sh

# Build Linux native binary via Docker
build-linux:
	@echo "Building Linux native binary via Docker..."
	./docker-build.sh

# Clean build artifacts
clean:
	@echo "Cleaning build artifacts..."
	rm -rf target/ classes/ r11y
	@echo "Clean complete"
