#!/usr/bin/env bash

dir1="$(realpath "$1")"
dir2="$(realpath "$2")"

tmp1=$(mktemp)
tmp2=$(mktemp)
trap 'rm -f "$tmp1" "$tmp2"' EXIT

(cd "$dir1" && find . -name "*.java" -type f -exec sha256sum {} +) | while read -r hash relpath; do
    echo "$hash  $dir1/${relpath#./}"
done > "$tmp1"

(cd "$dir2" && find . -name "*.java" -type f -exec sha256sum {} +) | while read -r hash relpath; do
    echo "$hash  $dir2/${relpath#./}"
done > "$tmp2"

echo "# missing in $dir2"
awk 'NR==FNR {h[$1]; next} !($1 in h) {print $2}' "$tmp2" "$tmp1"

echo "# missing in $dir1"
awk 'NR==FNR {h[$1]; next} !($1 in h) {print $2}' "$tmp1" "$tmp2"