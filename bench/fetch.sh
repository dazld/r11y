#!/usr/bin/env bash
# Save pages for bench/extract.clj: NAME.html plus NAME.url per address.
#
#   bench/fetch.sh <dir> <url> [url ...]
set -euo pipefail
dir="$1"; shift
mkdir -p "$dir"
for url in "$@"; do
  name=$(echo "$url" | sed -E 's#https?://(www\.)?##; s#[/.]+#_#g; s#_$##')
  curl -sL -m 30 -A "Mozilla/5.0" -o "$dir/$name.html" "$url"
  echo "$url" > "$dir/$name.url"
done
