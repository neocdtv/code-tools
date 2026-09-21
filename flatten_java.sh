#!/bin/bash

# Usage: ./flatten_java.sh /path/to/source [/path/to/destination]
SRC_DIR="${1:-.}"

# Resolve absolute path for source
SRC_DIR=$(realpath "$SRC_DIR") || { echo "Error: Source directory not found"; exit 1; }

# If destination is not provided, append '_flattened' to the source directory path
if [ -z "$2" ]; then
    DEST_DIR="${SRC_DIR}_flattened"
else
    DEST_DIR=$(realpath "$2")
fi

mkdir -p "$DEST_DIR"

echo "Copying Java files from $SRC_DIR to $DEST_DIR..."

# Process files safely, handling spaces or special characters
cd "$SRC_DIR" || exit 1

find . -type f -name "*.java" -print0 | while IFS= read -r -d '' file; do
    # Remove leading './'
    clean_path="${file#./}"
    
    # Replace slashes with underscores
    new_name="${clean_path//\//_}"
    
    cp "$file" "$DEST_DIR/$new_name"
done

echo "Done. Flattened files are located in: $DEST_DIR"