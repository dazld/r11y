# r11y

A lightning fast, GraalVM-compiled CLI tool for extracting readable content from web pages as Markdown.

`r11y` as in `readability` - or "oh rlly?" if you're ancient and remember the terrible owl meme.

![r11y converting a web page to clean Markdown from the command line](demo.gif)

## Features

- Extract main content from any URL as clean Markdown
- **Preserves whitespace** in preformatted blocks
- Correctly nested lists: sub-lists indent by marker width, and paragraphs, blockquotes, tables and code fences inside a list item stay part of that item; ordered lists keep their numbering
- Rich metadata extraction with YAML frontmatter (title, author, date, description, canonical URL, hero image, favicon, sitename)
- JSON-LD structured data support, including `@graph` walking and multi-script preference for article-typed objects
- Markdown content negotiation — sends `Accept: text/markdown` and recognises markdown bodies even when servers mis-label them as `text/html` (e.g. Cloudflare-fronted docs)
- Standardises React/Next.js semantic divs (`role=paragraph`, `role=list`) into proper HTML so content structure survives extraction
- Removes decorative SVGs, spacer images, layout tables, and duplicated UI chrome
- GitHub-optimized extraction (README files, blob content)
- Reads HTML or Markdown from stdin, for pages saved from a browser or fetched another way
- Bounded fetches: redirect, size and time limits, with an `:allow-url?` hook for servers that fetch user-supplied URLs
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

Download the latest binary for macOS (arm64), Linux (x86_64) or Linux (arm64, including Asahi) from [GitHub Releases](https://github.com/dazld/r11y/releases).

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

# Read HTML from stdin, e.g. a page saved from a browser for a site
# that blocks non-browser clients. --base-url resolves relative links.
r11y --base-url https://example.com/article < page.html

# Allow a slow or large page more time and size than the defaults (30 s, 10 MB)
r11y --timeout 60 --max-size 25 https://example.com/long-report

# Show help
r11y --help
```

### Options

- `-m, --with-metadata` - Include YAML frontmatter with metadata (title, author, date, description, etc.)
- `-l, --link-density N` - Link density threshold 0-1 (default: 0.5). Lower values are more aggressive at filtering link-heavy content.
- `-u, --base-url URL` - URL of HTML read from stdin, used to resolve relative links and images and for metadata. Must be an absolute http(s) URL. Without it, r11y uses the page's canonical link or `og:url`, if it has one.
- `-t, --timeout SECONDS` - Longest time to fetch a page, redirects and download included (default: 30). Only for URLs, not stdin.
- `--max-size MB` - Largest page to fetch, after decompression (default: 10). Only for URLs, not stdin.
- `-v, --version` - Show version
- `-h, --help` - Show help message

With no URL, r11y reads HTML from stdin. `-` also means stdin. Markdown on stdin is passed through, with any YAML frontmatter rebuilt by `-m`. `--` ends the options, so what follows is the URL even if it starts with `-`.

### Exit status

- `0` - success
- `1` - fetching, extracting or writing the output failed. The error is on stderr.
- `2` - the command line is wrong, such as an unknown option or a bad value. The error and usage are on stderr.

When r11y cannot write its output, it exits 1. This includes a reader that closes the pipe early, as in `r11y URL | head -1`; r11y then prints no error, much as tools stopped by SIGPIPE do.

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

End to end, fetch included: the native binary against two other readability extractors, 5 runs each after a warm-up, wall-clock seconds (mean ± stdev). Measured on an Apple Silicon Mac (macOS arm64 native build).

| Page | r11y | [defuddle][def] 0.19 | [trafilatura][trf] 2.3 |
|------|------|----------------------|------------------------|
| Long essay (Orwell, ~8k words) | **0.34 ± 0.08** | 0.59 ± 0.11 | 0.64 ± 0.32 |
| Docs page (Cloudflare Workers)  | **0.18 ± 0.01** | 0.51 ± 0.01 | 0.36 ± 0.01 |
| Landing page (Next.js)          | **0.18 ± 0.03** | 0.48 ± 0.02 | 0.42 ± 0.04 |
| Wikipedia article (Clojure)     | **0.38 ± 0.02** | 0.93 ± 0.02 | 0.67 ± 0.21 |

r11y is **1.7–2.8×** faster than defuddle and **1.7–2.3×** faster than trafilatura on these pages. Network variance dominates the stdev, so the gap in processing time is wider than the table shows.

Processing alone, without the network, is a few milliseconds for a typical page: `extract-content` on saved copies of the pages above (JVM, median of 20 runs after warm-up).

| Page | HTML size | Median |
|------|-----------|--------|
| Long essay (Orwell)         | 100 KB | 12.6 ms |
| Docs page (Cloudflare)      | 214 KB | 18.5 ms |
| Landing page (Next.js)      | 266 KB |  9.3 ms |
| Wikipedia article (Clojure) | 546 KB | 63.1 ms |

To reproduce:

```bash
bb bench/compare.bb [url ...]               # end to end; tools not on the PATH are skipped
bench/fetch.sh pages <url> [url ...]        # save pages, then
clojure -M bench/extract.clj pages          # processing time only
```

[def]: https://github.com/kepano/defuddle
[trf]: https://trafilatura.readthedocs.io/

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
bb -Sdeps '{:deps {io.github.dazld/r11y {:git/tag "v1.0.7" :git/sha "59a594a"}}}' \
  -e '(require (quote [r11y.lib.html :as html]))
      (println (:markdown (html/extract-content-from-url "https://example.com" :format :markdown)))'
```

No GraalVM required — bb resolves the dep, downloads JSoup transitively, and runs the extractor. Useful for one-off scripts where you don't want to install the native binary.

### Fetching addresses your users type in

r11y follows redirects itself and limits every fetch. These options of `extract-content-from-url` set the limits:

| Option | Default | When it is exceeded |
|---|---|---|
| `:allow-url?` | none | `(fn [url] bool)`, called before every request, redirects included. False stops with `:type :r11y/blocked-url`. |
| `:max-redirects` | 10 | `:r11y/too-many-redirects` |
| `:max-bytes` | 10 MB | `:r11y/body-too-large` |
| `:connect-timeout` | 10 s | `:r11y/connect-timeout` |
| `:timeout` | 30 s, for the whole fetch: redirects and body included | `:r11y/timeout` |

Each failure is an `ex-info` whose data has `:type` and `:url`. A response other than 2xx stops with `:r11y/http-error` and `:status`. Redirects go to `http` and `https` addresses only.

`:allow-url?` sees the URL, not the address it resolves to. A host name that resolves to a private or link-local address passes unless your predicate resolves it and checks the result. Keep that in mind when you use it to block internal addresses such as `169.254.169.254`.

```clojure
(html/extract-content-from-url url
  :format :markdown
  :allow-url? my-allowed-url?
  :max-bytes (* 2 1024 1024)
  :timeout 15000)
```

To control fetching completely, pass `:fetch-fn`, a `(fn [url] response)`. The options above are then ignored. Every request r11y makes goes through it, including the second fetch when a GitHub URL is rewritten to its raw content. It returns `{:status int, :headers {:content-type string}, :body bytes-or-string, :uri final-url}`. r11y uses `:uri` to resolve relative links. r11y extracts any body it is given, so throw for a response you do not want extracted, such as one other than 2xx.

## How it works

r11y uses content extraction algorithms inspired by Mozilla's Readability and trafilatura to identify and extract the main content from web pages:

1. **Content negotiation**: Sends `Accept: text/markdown` and uses the response directly when servers honour it. Sniffs response bodies to recognise markdown returned with a `text/html` content-type (common with Cloudflare-fronted sites). Strips and rebuilds upstream YAML frontmatter in our schema.
2. **Metadata extraction**: Pulls structured data from JSON-LD (walking `@graph` and preferring article-typed objects across multiple scripts), OpenGraph and Twitter Card meta tags, `<time>` elements, and URL date patterns. Filters placeholder values (`{template.literal}`, `#author.fullName`) and rejects misused `og:site_name` values.
3. **Content cleaning**: Removes boilerplate elements (scripts, styles, navigation, footers, ads), decorative SVGs and `[role=img]` containers, layout tables, spacer/duplicate images. Standardises `<div role=paragraph>`, `<div role=list>`, and `<div role=listitem>` into proper HTML before pruning.
4. **Pattern filtering**: Filters elements based on class/id patterns to remove unlikely content
5. **Link density analysis**: Removes navigation-heavy sections based on configurable threshold
6. **Main content identification**: Finds the primary article/content element via a CSS-selector cascade with a body fallback if the chosen subtree turns out to be too small
7. **Markdown conversion**: Converts cleaned HTML to clean Markdown with proper formatting for headings, lists (nested by list depth rather than DOM depth, with block content kept inside the item), ordered lists, tables, code blocks, links, and images

### Special handling

- **GitHub**: Automatically extracts README content from repo pages and fetches raw content for blob URLs while preserving metadata
- **Cloudflare-fronted sites**: Recognises markdown bodies returned via `Accept: text/markdown` even when the response is mis-labelled as `text/html`
- **Tables**: Converts HTML tables to Markdown table format; detects layout tables (no `<th>`, `border=0`, block-level content) and unwraps rather than rendering
- **Code blocks**: Preserves formatting in `<pre>` and `<code>` elements
- **Images**: Includes alt text and image URLs; deduplicates images repeated more than twice (UI chrome)

## License

MIT License. See [LICENSE](LICENSE) for details.
