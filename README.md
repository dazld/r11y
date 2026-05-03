# r11y

A lightning fast, GraalVM-compiled CLI tool for extracting readable content from web pages as Markdown.

`r11y` as in `readability` - or "oh rlly?" if you're ancient and remember the terrible owl meme.

## Features

- Extract main content from any URL as clean Markdown
- **Preserves whitespace** in preformatted blocks
- Rich metadata extraction with YAML frontmatter (title, author, date, description, canonical URL, hero image, favicon, sitename)
- JSON-LD structured data support, including `@graph` walking and multi-script preference for article-typed objects
- Markdown content negotiation — sends `Accept: text/markdown` and recognises markdown bodies even when servers mis-label them as `text/html` (e.g. Cloudflare-fronted docs)
- Standardises React/Next.js semantic divs (`role=paragraph`, `role=list`) into proper HTML so content structure survives extraction
- Removes decorative SVGs, spacer images, layout tables, and duplicated UI chrome
- GitHub-optimized extraction (README files, blob content)
- Configurable link density threshold for content filtering
- Babashka-compatible — usable from `bb` scripts via `:git/tag` deps, no GraalVM required
- Fast startup with GraalVM native compilation (~40ms)

## Installation

### Homebrew (macOS arm64, Linux x86_64)

```bash
brew tap dazld/tap
brew install r11y
```

### Prebuilt Binary

Download the latest binary for macOS (arm64) or Linux (x86_64) from [GitHub Releases](https://github.com/dazld/r11y/releases).

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
sdk install java 25-graal
sdk use java 25-graal
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
- `-v, --version` - Show version
- `-h, --help` - Show help message

### Example Output with Metadata

```markdown
---
title: Intelligence on Earth Evolved Independently at Least Twice
author: Yasemin Saplakoglu
url: https://www.wired.com/story/intelligence-evolved-at-least-twice-in-vertebrate-animals/
canonical-url: https://www.wired.com/story/intelligence-evolved-at-least-twice-in-vertebrate-animals/
is-canonical: true
hostname: www.wired.com
description: Complex neural circuits likely arose independently in birds and mammals...
sitename: WIRED
date: 2025-05-11T07:00:00.000-04:00
icon: https://www.wired.com/verso/static/wired-us/assets/favicon.ico
image: https://media.wired.com/photos/.../NeuralIntelligence-crSamanthaMash-Lede.jpeg
---

# Article content here...
```

`icon` is the site favicon (largest available, Apple touch icon preferred). `image` is the article hero / social-card image (`og:image` / `twitter:image` / JSON-LD `image`).

## Performance

Quick comparison against three readability extractors on representative URLs. 5 runs each, wall-clock seconds (mean ± stdev):

| URL | r11y | [defuddle][def] | [trafilatura][trf] | [readability-rust][rdr] |
|-----|------|-----------------|--------------------|-------------------------|
| Long essay (Orwell, ~8k words) | **0.43 ± 0.09** | 0.74 ± 0.18 | 0.99 ± 0.14 | 0.57 ± 0.14 |
| Docs page (Cloudflare)         | **0.26 ± 0.06** | 0.54 ± 0.02 | 0.53 ± 0.01 | 0.36 ± 0.04 |
| Next.js landing page           | 0.30 ± 0.04     | 0.63 ± 0.04 | 0.52 ± 0.03 | **0.27 ± 0.05** |

r11y is consistently **1.5–2×** faster than the typical Node and Python alternatives. Against `readability-rust` r11y is ahead on larger pages, roughly tied on short ones.

Wall-clock includes the network fetch, so the processing-time gap is wider than the table shows. Network variance dominates the stdev. `readability-rust` doesn't fetch URLs natively and was benchmarked as `curl URL | readability-rust -i - -f text`.

[def]: https://github.com/kepano/defuddle
[trf]: https://trafilatura.readthedocs.io/
[rdr]: https://github.com/dreampuf/readability-rust

## Development

### Run with Clojure CLI

```bash
clj -M -m r11y.core https://example.com
```

### Test the library directly

```clojure
clj -e "(require '[r11y.lib.html :as html]) (println (html/extract-content-from-url \"https://clojure.org\" :format :markdown))"
```

### Use from a babashka script

```bash
bb -Sdeps '{:deps {io.github.dazld/r11y {:git/tag "v1.0.6" :git/sha "87a3baa"}}}' \
  -e '(require (quote [r11y.lib.html :as html]))
      (println (:markdown (html/extract-content-from-url "https://example.com" :format :markdown)))'
```

No GraalVM required — bb resolves the dep, downloads JSoup transitively, and runs the extractor. Useful for one-off scripts where you don't want to install the native binary.

## How it works

r11y uses content extraction algorithms inspired by Mozilla's Readability and trafilatura to identify and extract the main content from web pages:

1. **Content negotiation**: Sends `Accept: text/markdown` and uses the response directly when servers honour it. Sniffs response bodies to recognise markdown returned with a `text/html` content-type (common with Cloudflare-fronted sites). Strips and rebuilds upstream YAML frontmatter in our schema.
2. **Metadata extraction**: Pulls structured data from JSON-LD (walking `@graph` and preferring article-typed objects across multiple scripts), OpenGraph and Twitter Card meta tags, `<time>` elements, and URL date patterns. Filters placeholder values (`{template.literal}`, `#author.fullName`) and rejects misused `og:site_name` values.
3. **Content cleaning**: Removes boilerplate elements (scripts, styles, navigation, footers, ads), decorative SVGs and `[role=img]` containers, layout tables, spacer/duplicate images. Standardises `<div role=paragraph>`, `<div role=list>`, and `<div role=listitem>` into proper HTML before pruning.
4. **Pattern filtering**: Filters elements based on class/id patterns to remove unlikely content
5. **Link density analysis**: Removes navigation-heavy sections based on configurable threshold
6. **Main content identification**: Finds the primary article/content element via a CSS-selector cascade with a body fallback if the chosen subtree turns out to be too small
7. **Markdown conversion**: Converts cleaned HTML to clean Markdown with proper formatting for headings, lists, tables, code blocks, links, and images

### Special handling

- **GitHub**: Automatically extracts README content from repo pages and fetches raw content for blob URLs while preserving metadata
- **Cloudflare-fronted sites**: Recognises markdown bodies returned via `Accept: text/markdown` even when the response is mis-labelled as `text/html`
- **Tables**: Converts HTML tables to Markdown table format; detects layout tables (no `<th>`, `border=0`, block-level content) and unwraps rather than rendering
- **Code blocks**: Preserves formatting in `<pre>` and `<code>` elements
- **Images**: Includes alt text and image URLs; deduplicates images repeated more than twice (UI chrome)

## License

MIT License. See [LICENSE](LICENSE) for details.
