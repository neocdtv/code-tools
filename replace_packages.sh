#!/bin/bash

OLD_PACKAGE="${1}"
NEW_PACKAGE="${2}"
ROOT_DIR="${3:-.}"

if [ -z "$OLD_PACKAGE" ] || [ -z "$NEW_PACKAGE" ]; then
    echo "Usage: $0 <old_package> <new_package> [root_directory]"
    echo "Example: $0 com.example io.neocdtv /path/to/project"
    exit 1
fi

if [ ! -d "$ROOT_DIR" ]; then
    echo "Error: Directory '$ROOT_DIR' does not exist."
    exit 1
fi

OLD_PATH=$(echo "$OLD_PACKAGE" | tr '.' '/')
NEW_PATH=$(echo "$NEW_PACKAGE" | tr '.' '/')

echo "Starting package refactoring in: $ROOT_DIR"
echo "From: $OLD_PACKAGE -> To: $NEW_PACKAGE"
echo ""

# ==========================================
# STEP 1: Reorganize physical directory structures
# ==========================================
echo "=== STEP 1: Moving physical directories ==="

find "$ROOT_DIR" \
    -not -path "*/.*" \
    -not -path "*/target/*" \
    -not -path "*/build/*" \
    -type d -path "*/src/*/java/$OLD_PATH" | while read -r old_dir; do
    
    base_parent="${old_dir%/src/*}"
    source_type=$(echo "$old_dir" | awk -F'/' '{for(i=1;i<=NF;i++) if($i=="src") print $(i+1)}')
    lang_type=$(echo "$old_dir" | awk -F'/' '{for(i=1;i<=NF;i++) if($i=="src") print $(i+2)}')
    
    target_dir="$base_parent/src/$source_type/$lang_type/$NEW_PATH"
    
    echo "Moving: $old_dir -> $target_dir"
    mkdir -p "$target_dir"
    mv "$old_dir"/* "$target_dir/" 2>/dev/null || true
    
    # Clean up empty legacy parent directories up to the source root
    curr="$old_dir"
    stop_at="$base_parent/src/$source_type/$lang_type"
    while [ "$curr" != "$stop_at" ] && [ "$curr" != "/" ]; do
        rmdir "$curr" 2>/dev/null || break
        curr=$(dirname "$curr")
    done
done

echo "Directory restructuring complete."
echo ""

# ==========================================
# STEP 2: Replace package names and imports inside files
# ==========================================
echo "=== STEP 2: Updating package declarations and imports ==="

find "$ROOT_DIR" \
    -not -path "*/.*" \
    -not -path "*/target/*" \
    -not -path "*/build/*" \
    -type f \( -name "*.java" -o -name "*.kt" -o -name "*.xml" -o -name "*.gradle" -o -name "*.properties" -o -name "*.yml" \) -print0 | while IFS= read -r -d '' file; do
    
    # Using Perl -s switch to safely inject variables without shell escaping conflicts
    perl -s -pi -e 's/\Q$old\E/$new/g' -- -old="$OLD_PACKAGE" -new="$NEW_PACKAGE" "$file"
    echo "Updated: $file"
done

echo ""
echo "Package replacement and restructuring complete successfully."
