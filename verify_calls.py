import sys
import os
import re

def verify_output(out_file_path, project_root):
    if not os.path.isfile(out_file_path):
        print(f"❌ Output file not found: {out_file_path}")
        sys.exit(1)

    if not os.path.isdir(project_root):
        print(f"❌ Project root directory not found: {project_root}")
        sys.exit(1)

    with open(out_file_path, "r", encoding="utf-8") as f:
        lines = [line.strip() for line in f if line.strip()]

    if len(lines) % 2 != 0:
        print("❌ Error: Invalid format in out.txt. Lines must come in matching pairs (FQN line followed by File/Line detail line).")
        sys.exit(1)

    total_checks = 0
    passed_checks = 0
    failed_checks = 0

    # Pattern to match: SimpleClassName:line_num (relative/path/to/File.java)
    detail_pattern = re.compile(r"^([^:]+):(\d+)\s*\((.+)\)$")

    print(f"🔍 Verifying call entries against project root: {os.path.abspath(project_root)}\n" + "="*80)

    for i in range(0, len(lines), 2):
        fqn_line = lines[i]
        detail_line = lines[i+1]
        total_checks += 1

        # Parse FQN line: "de.pkg.ClassName->methodName"
        if "->" not in fqn_line:
            print(f"❌ [FORMAT ERROR] Invalid FQN line format: '{fqn_line}'")
            failed_checks += 1
            continue

        fqn_class, method_name = fqn_line.split("->", 1)

        # Parse Detail line: "ClassName:123 (path/to/File.java)"
        match = detail_pattern.match(detail_line)
        if not match:
            print(f"❌ [FORMAT ERROR] Invalid detail line format: '{detail_line}'")
            failed_checks += 1
            continue

        simple_class, line_str, rel_file_path = match.groups()
        line_num = int(line_str)

        # 1. Check file existence
        full_file_path = os.path.normpath(os.path.join(project_root, rel_file_path))
        if not os.path.exists(full_file_path):
            print(f"❌ [MISSING FILE] {fqn_line}")
            print(f"   File not found at path: {full_file_path}\n")
            failed_checks += 1
            continue

        # Read Java file lines
        try:
            with open(full_file_path, "r", encoding="utf-8", errors="ignore") as jf:
                file_lines = jf.readlines()
        except Exception as e:
            print(f"❌ [READ ERROR] Could not read file: {full_file_path} ({e})\n")
            failed_checks += 1
            continue

        # 2. Line number sanity check
        if line_num < 1 or line_num > len(file_lines):
            print(f"❌ [LINE OUT OF BOUNDS] {fqn_line}")
            print(f"   Line {line_num} does not exist in {rel_file_path} (total lines: {len(file_lines)})\n")
            failed_checks += 1
            continue

        # 3. Method name match check (searches target line ± 2 lines tolerance for annotations/formatting)
        search_start = max(0, line_num - 3)
        search_end = min(len(file_lines), line_num + 2)
        window_content = "".join(file_lines[search_start:search_end])

        # Regex checks for method declaration or call site containing method_name
        method_regex = re.compile(rf"\b{re.escape(method_name)}\b")

        if method_regex.search(window_content):
            print(f"✅ [OK] {fqn_class}->{method_name} at line {line_num}")
            passed_checks += 1
        else:
            actual_line_text = file_lines[line_num - 1].strip()
            print(f"⚠️ [METHOD MISMATCH] {fqn_class}->{method_name}")
            print(f"   Expected method '{method_name}' around line {line_num} in {rel_file_path}")
            print(f"   Actual content on line {line_num}: \"{actual_line_text}\"\n")
            failed_checks += 1

    print("="*80)
    print(f"📊 Summary: Total: {total_checks} | Passed: {passed_checks} | Failed: {failed_checks}")

    if failed_checks > 0:
        sys.exit(1)

if __name__ == "__main__":
    if len(sys.argv) < 3:
        print("Usage: python3 verify_calls.py <out.txt> <path_to_java_project_root>")
        sys.exit(1)

    verify_output(sys.argv[1], sys.argv[2])