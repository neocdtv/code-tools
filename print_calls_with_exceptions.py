import sys
import json
import re

# Refined regex pattern:
# 1. \bthrow\b: Catches any explicit throw, whether 'throw new ...' or 'throw helperMethod(...)'
# 2. \borElseThrow\b: Catches optional/functional throws
# 3. \bnew\s+[A-Z]\w*Exception\b: Catches unthrown instantiations (e.g. orphan exception objects)
EXCEPTION_PATTERN = re.compile(
    r'\bthrow\b|\borElseThrow\b|\bnew\s+[A-Z]\w*Exception\b'
)

def find_exceptions(node, results=None):
    if results is None:
        results = []

    source_code = node.get('sourceCode')
    if source_code:
        start_line = node.get('startLine', 0)
        class_name = node.get('className', '')
        simple_class_name = class_name.split('.')[-1] if class_name else ''
        file_path = node.get('filePath', '')
        fq_symbol = node.get('fullyQualifiedSymbol', '')

        # Construct package.ClassName->methodName format
        if '.' in fq_symbol:
            method_name = fq_symbol.split('.')[-1]
            method_fq = f"{class_name}->{method_name}"
        else:
            method_fq = fq_symbol

        # Track line matches within the method's source code
        for idx, line in enumerate(source_code):
            if EXCEPTION_PATTERN.search(line):
                line_number = start_line + idx

                output = (
                    f"{method_fq}\n"
                    f"{simple_class_name}:{line_number} ({file_path})"
                )

                if output not in results:
                    results.append(output)

    # Recurse through child callees
    for callee in node.get('callees', []):
        find_exceptions(callee, results)

    return results

def load_call_graph():
    # Read from stdin if piped
    if not sys.stdin.isatty():
        return json.load(sys.stdin)

    # Read from CLI parameter if passed
    if len(sys.argv) > 1:
        file_path = sys.argv[1]
    else:
        file_path = 'call_graph.json'

    with open(file_path, 'r', encoding='utf-8') as f:
        return json.load(f)

def main():
    try:
        data = load_call_graph()
        root_graph = data.get('callGraph', {})
        found_exceptions = find_exceptions(root_graph)

        if found_exceptions:
            print("\n\n".join(found_exceptions))
        else:
            print("No exception throw sites found.")

    except Exception as e:
        print(f"Error loading call graph: {e}", file=sys.stderr)
        sys.exit(1)

if __name__ == '__main__':
    main()