#!/usr/bin/env python3
import os
import re
import sys


def remove_java_comments(text):
  # Matches strings, char literals, and comments. Preserves strings, deletes comments.
  pattern = re.compile(
      r'//.*?$|/\*.*?\*/|\'(?:\\.|[^\\\'])*\'|"(?:\\.|[^\\"])*"',
      re.DOTALL | re.MULTILINE,
  )

  def replacer(match):
    s = match.group(0)
    if s.startswith('/') or s.startswith('/*'):
      return ''  # Delete comment
    return s  # Keep string/char literal intact

  return pattern.sub(replacer, text)


py_code_cleaner = lambda path: open(path, 'r', encoding='utf-8').read()


def process_directory(base_dir):
  if not os.path.isdir(base_dir):
    print(f'Error: Directory "{base_dir}" does not exist.')
    sys.exit(1)

  for root, _, files in os.walk(base_dir):
    for file in files:
      if file.endswith('.java'):
        file_path = os.path.join(root, file)
        try:
          with open(file_path, 'r', encoding='utf-8') as f:
            content = f.read()

          cleaned_content = remove_java_comments(content)

          with open(file_path, 'w', encoding='utf-8') as f:
            f.write(cleaned_content)

          print(f'Cleaned: {file_path}')
        except Exception as e:
          print(f'Failed to process {file_path}: {e}')


if __name__ == '__main__':
  if len(sys.argv) < 2:
    print('Usage: python remove_comments.py <base_dir_with_javafiles>')
    sys.exit(1)
  process_directory(sys.argv[1])
