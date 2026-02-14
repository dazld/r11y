#!/usr/bin/env bash

set -euo pipefail

# Check if URL is provided
if [ $# -eq 0 ]; then
    echo "Usage: $0 <url>"
    echo ""
    echo "Compares output from r11y and trafilatura for a given URL"
    echo ""
    echo "Example:"
    echo "  $0 https://example.com"
    exit 1
fi

URL="$1"

echo "Comparing r11y vs trafilatura for: $URL"
echo "=========================================="
echo ""

# Create temp directory
TMPDIR=$(mktemp -d)
trap "rm -rf $TMPDIR" EXIT

R11Y_OUTPUT="$TMPDIR/r11y.md"
TRAF_OUTPUT="$TMPDIR/trafilatura.md"

# Run r11y
echo "Running r11y..."
if ! ./r11y "$URL" > "$R11Y_OUTPUT" 2>&1; then
    echo "❌ r11y failed"
    cat "$R11Y_OUTPUT"
    exit 1
fi

# Run trafilatura
echo "Running trafilatura..."
if ! trafilatura -u "$URL" --markdown --links --with-metadata --images > "$TRAF_OUTPUT" 2>&1; then
    echo "❌ trafilatura failed"
    cat "$TRAF_OUTPUT"
    exit 1
fi

echo ""
echo "=========================================="
echo "Results:"
echo "=========================================="
echo ""

# Compare sizes
R11Y_SIZE=$(wc -c < "$R11Y_OUTPUT" | tr -d ' ')
TRAF_SIZE=$(wc -c < "$TRAF_OUTPUT" | tr -d ' ')
R11Y_LINES=$(wc -l < "$R11Y_OUTPUT" | tr -d ' ')
TRAF_LINES=$(wc -l < "$TRAF_OUTPUT" | tr -d ' ')

printf "%-20s %10s %10s\n" "Tool" "Size" "Lines"
printf "%-20s %10s %10s\n" "----" "----" "-----"
printf "%-20s %10s %10s\n" "r11y" "$R11Y_SIZE bytes" "$R11Y_LINES"
printf "%-20s %10s %10s\n" "trafilatura" "$TRAF_SIZE bytes" "$TRAF_LINES"

echo ""
echo "=========================================="
echo "Preview (first 20 lines):"
echo "=========================================="
echo ""
echo "--- r11y ---"
head -20 "$R11Y_OUTPUT"
echo ""
echo "--- trafilatura ---"
head -20 "$TRAF_OUTPUT"

echo ""
echo "=========================================="
echo "Full output saved to:"
echo "  r11y:        $R11Y_OUTPUT"
echo "  trafilatura: $TRAF_OUTPUT"
echo ""
echo "To view full diff:"
echo "  diff -u $R11Y_OUTPUT $TRAF_OUTPUT | less"
echo "Or:"
echo "  code --diff $R11Y_OUTPUT $TRAF_OUTPUT"
echo "=========================================="
