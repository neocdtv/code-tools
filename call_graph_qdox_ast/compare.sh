#!/usr/bin/env bash

dir1="$1"
dir2="$2"

echo "# missing in $dir2"
comm -23 <(cd "$dir1" && find . -name "*.java" | sort) <(cd "$dir2" && find . -name "*.java" | sort) | while read -r file; do
    realpath "$dir1/$file"
done

echo "# missing in $dir1"
comm -13 <(cd "$dir1" && find . -name "*.java" | sort) <(cd "$dir2" && find . -name "*.java" | sort) | while read -r file; do
    realpath "$dir2/$file"
done