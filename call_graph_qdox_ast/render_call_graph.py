#!/usr/bin/env python3
import json
import sys
import argparse
from pathlib import Path


def is_loop_node(node):
    """Checks if node is flagged with a circular dependency or loop status."""
    status = node.get("status", "")
    return status in ("circular_dependency_detected", "loop_detected")


def print_ascii_tree(node, indent="", is_last=True):
    """Recursively prints the call graph as an ASCII tree with loop indicators."""
    marker = "+-- "

    # Extract details
    method = node.get("methodName", "unknown")
    cls = node.get("className", "unknown")
    status = node.get("status")

    # Format status flag and explicit loop warning
    status_str = f" [{status}]" if status else ""
    if is_loop_node(node):
        status_str = " 🔄 [LOOP DETECTED]"

    # Shorten class name for cleaner CLI output
    short_class = cls.split(".")[-1]

    print(f"{indent}{marker}{method}()  <-- {short_class}{status_str}")

    # Prepare indentation for children
    indent += "    " if is_last else "|   "
    callees = node.get("callees", [])

    for i, child in enumerate(callees):
        last_child = (i == len(callees) - 1)
        print_ascii_tree(child, indent, last_child)


def collect_loops(node, loops=None):
    """Traverses graph to collect all nodes involved in cycles/loops."""
    if loops is None:
        loops = []

    if is_loop_node(node):
        loops.append({
            "symbol": node.get("fullyQualifiedSymbol", f"{node.get('className')}.{node.get('methodName')}"),
            "class": node.get("className", "").split(".")[-1],
            "method": node.get("methodName", ""),
            "depth": node.get("depth", 0)
        })

    for child in node.get("callees", []):
        collect_loops(child, loops)

    return loops


def export_to_dot(root_node, dot_filepath):
    """Exports the tree to Graphviz DOT format with loop highlights."""
    edges = set()
    nodes = set()

    def collect_edges(node):
        parent_id = f"{node.get('className')}.{node.get('methodName')}"
        nodes.add((
            parent_id,
            node.get('methodName'),
            node.get('className').split('.')[-1],
            node.get('status')
        ))

        for child in node.get("callees", []):
            child_id = f"{child.get('className')}.{child.get('methodName')}"

            # Style loop edge
            is_loop = is_loop_node(child)
            edges.add((parent_id, child_id, is_loop))
            collect_edges(child)

    collect_edges(root_node)

    with open(dot_filepath, "w", encoding="utf-8") as f:
        f.write("digraph CallGraph {\n")
        f.write("    rankdir=LR;\n")  # Left-to-Right orientation
        f.write("    node [shape=box, fontname=\"Courier\", style=\"rounded,filled\", fillcolor=\"#f9f9f9\"];\n")
        f.write("    edge [fontname=\"Courier\"];\n\n")

        # Write nodes
        for node_id, method, short_cls, status in nodes:
            label = f"{method}()\\n({short_cls})"
            fillcolor = "#f9f9f9"

            if status == "interface_endpoint":
                fillcolor = "#e1f5fe"  # Light blue
            elif status in ("circular_dependency_detected", "loop_detected"):
                fillcolor = "#ffcdd2"  # Red / Orange highlight for loop nodes
                label += "\\n[LOOP]"

            f.write(f'    "{node_id}" [label="{label}", fillcolor="{fillcolor}"];\n')

        f.write("\n")
        # Write edges
        for parent_id, child_id, is_loop in edges:
            edge_style = ' [color="red", style="dashed", constraint=false]' if is_loop else ''
            f.write(f'    "{parent_id}" -> "{child_id}"{edge_style};\n')

        f.write("}\n")

    print(f"✅ Graphviz DOT file exported to: {dot_filepath}")


def main():
    parser = argparse.ArgumentParser(description="Render call_graph.json to ASCII tree or DOT graph.")
    parser.add_argument("file", nargs="?", default="call_graph.json", help="Path to input JSON file (default: call_graph.json)")
    parser.add_argument("--dot", help="Optional output path for Graphviz DOT file (e.g., graph.dot)")

    args = parser.parse_args()
    json_path = Path(args.file)

    if not json_path.exists():
        print(f"❌ Error: File '{json_path}' not found.", file=sys.stderr)
        sys.exit(1)

    with open(json_path, "r", encoding="utf-8") as f:
        data = json.load(f)

    # Unwrap callGraph envelope if present, else fallback to raw object
    root_node = data.get("callGraph", data)

    if "gitCommitId" in data and data["gitCommitId"]:
        print(f"📌 Git Commit: {data['gitCommitId']}")
    if "generatedAt" in data and data["generatedAt"]:
        print(f"🕒 Generated At: {data['generatedAt']}")

    print("\n🌳 --- CALL GRAPH ASCII TREE --- 🌳\n")
    print_ascii_tree(root_node)
    print("\n-----------------------------------\n")

    if args.dot:
        export_to_dot(root_node, args.dot)


if __name__ == "__main__":
    main()