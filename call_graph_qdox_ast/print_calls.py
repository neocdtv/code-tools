import json
import sys


def collect_nodes(node, seen_entries):
    """Recursively collects unique method call pairs with file paths."""
    # Use fullyQualifiedSymbol to resolve true declaring class if inherited
    fqn_symbol = node.get("fullyQualifiedSymbol") or ""
    class_name = node.get("className") or ""
    method_name = node.get("methodName") or ""
    start_line = node.get("startLine", 0)
    status = node.get("status")
    file_path = node.get("filePath") or "N/A"

    if fqn_symbol and "." in fqn_symbol:
        actual_class_name = fqn_symbol.rsplit(".", 1)[0]
    else:
        actual_class_name = class_name

    # Filter out unresolved calls or missing methods (startLine > 0)
    if start_line > 0 and status != "method_not_found" and actual_class_name:
        simple_class_name = actual_class_name.split(".")[-1]

        # Build formatted output lines including filePath
        fqn_line = f"{actual_class_name}->{method_name}"
        simple_line = f"{simple_class_name}:{start_line} ({file_path})"

        # Store as a tuple to ensure unique pairs
        seen_entries.add((fqn_line, simple_line, actual_class_name, start_line))

    # Traverse all callees
    callees = node.get("callees") or []
    for callee in callees:
        collect_nodes(callee, seen_entries)


def main():
    if len(sys.argv) < 2:
        print("Usage: python3 print_calls.py <json_file>")
        sys.exit(1)

    json_file_path = sys.argv[1]

    try:
        with open(json_file_path, "r", encoding="utf-8") as f:
            data = json.load(f)
    except FileNotFoundError:
        print(f"Error: File '{json_file_path}' not found.")
        sys.exit(1)
    except json.JSONDecodeError:
        print(f"Error: '{json_file_path}' contains invalid JSON.")
        sys.exit(1)

    unique_entries = set()

    # Extract root nodes and collect entries
    if isinstance(data, dict):
        root_node = data.get("callGraph")
        if root_node:
            collect_nodes(root_node, unique_entries)
        else:
            collect_nodes(data, unique_entries)
    elif isinstance(data, list):
        for item in data:
            root_node = item.get("callGraph", item)
            collect_nodes(root_node, unique_entries)

    # Sort entries by full class name, then by start line
    sorted_entries = sorted(unique_entries, key=lambda x: (x[2], x[3]))

    # Print formatted output
    for fqn_line, simple_line, _, _ in sorted_entries:
        print(fqn_line)
        print(simple_line)


if __name__ == "__main__":
    main()