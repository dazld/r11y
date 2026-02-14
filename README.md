# r11y

A fast, GraalVM-compiled CLI tool for extracting readable content from web pages as Markdown.

## Features

- Extract main content from any URL as clean Markdown
- Preserves whitespace in preformatted blocks
- Rich metadata extraction with YAML frontmatter (title, author, date, description)
- JSON-LD structured data support
- GitHub-optimized extraction (README files, blob content)
- Configurable link density threshold for content filtering
- Fast startup with GraalVM native compilation (~40ms)

## Installation

### Prebuilt Binary (Linux x86_64)

Download the latest binary from [GitHub Releases](https://github.com/dazld/r11y/releases).

### Quick Build

```bash
# macOS
make build-macos

# Linux (via Docker)
make build-linux
```

### Building with Docker (For Linux deployment)

If you want to build a Linux binary (e.g., for deployment to Linux servers):

```bash
make build-linux
# or
./docker-build.sh
```

This creates a native **Linux x86_64** binary using Docker with GraalVM.

**Prerequisites:** Only [Docker](https://www.docker.com/) is required.

The first build will take several minutes as it downloads GraalVM and dependencies. Subsequent builds will be faster due to Docker layer caching.

### Building Locally (Alternative)

If you prefer to build without Docker:

#### Prerequisites

- [GraalVM](https://www.graalvm.org/) with native-image installed
- [Clojure CLI tools](https://clojure.org/guides/install_clojure)

#### Installing GraalVM

**Option 1: Using Homebrew (recommended for macOS):**
```bash
brew install --cask graalvm-jdk
```

**Option 2: Using SDKMAN:**
```bash
sdk install java 22-graal
sdk use java 22-graal
```

#### Building

```bash
make build-macos
# or
export JAVA_HOME=/path/to/graalvm && ./build-native.sh
```

This will compile the native binary to `./r11y`.

**Note:** The build process may take 1-2 minutes and use significant RAM (2-3GB). If you encounter out-of-memory errors, you can limit heap usage by modifying the build script to add `-J-Xmx3g` (or higher) to the `native-image` command.

## Usage

```bash
# Basic usage (outputs markdown)
r11y https://example.com

# Include metadata as YAML frontmatter
r11y --with-metadata https://www.bbc.com/news/article-123

# Adjust link density threshold (0.0-1.0)
r11y --link-density 0.3 https://example.com

# GitHub blob URLs (automatically fetches raw content with metadata)
r11y -m https://github.com/user/repo/blob/main/README.md

# Show help
r11y --help
```

### Options

- `-m, --with-metadata` - Include YAML frontmatter with metadata (title, author, date, description, etc.)
- `-l, --link-density N` - Link density threshold 0-1 (default: 0.5). Lower values are more aggressive at filtering link-heavy content.
- `-h, --help` - Show help message

### Example Output with Metadata

```markdown
---
title: Intelligence on Earth Evolved Independently at Least Twice
author: Yasemin Saplakoglu
url: https://www.wired.com/story/intelligence-evolved...
hostname: www.wired.com
description: Complex neural circuits likely arose independently...
sitename: WIRED
date: 2025-05-11T07:00:00.000-04:00
---

# Article content here...
```

## Development

### Run with Clojure CLI

```bash
clj -M -m r11y.core https://example.com
```

### Test the library directly

```clojure
clj -e "(require '[r11y.lib.html :as html]) (println (html/extract-content-from-url \"https://clojure.org\" :format :markdown))"
```

## How it works

r11y uses content extraction algorithms inspired by Mozilla's Readability and trafilatura to identify and extract the main content from web pages:

1. **Metadata extraction**: Pulls structured data from JSON-LD, OpenGraph tags, meta tags, `<time>` elements, and URL patterns
2. **Content cleaning**: Removes boilerplate elements (scripts, styles, navigation, footers, ads)
3. **Pattern filtering**: Filters elements based on class/id patterns to remove unlikely content
4. **Link density analysis**: Removes navigation-heavy sections based on configurable threshold
5. **Main content identification**: Finds the primary article/content element
6. **Markdown conversion**: Converts cleaned HTML to clean Markdown with proper formatting for headings, lists, tables, code blocks, links, and images

### Special handling

- **GitHub**: Automatically extracts README content from repo pages and fetches raw content for blob URLs while preserving metadata
- **Tables**: Converts HTML tables to Markdown table format
- **Code blocks**: Preserves formatting in `<pre>` and `<code>` elements
- **Images**: Includes alt text and image URLs

## License

MIT License. See [LICENSE](LICENSE) for details.
