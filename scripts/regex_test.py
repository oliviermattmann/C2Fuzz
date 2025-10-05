#!/usr/bin/env python3
import re

FILE_PATH = "output.txt"

# Allow 1+ spaces before "(hot)" and optional trailing whitespace at line end.
p = re.compile(r"inline(?:\s+\(hot\))?\s*$")

count = 0
with open(FILE_PATH, "r", encoding="utf-8") as f:
    for line_no, line in enumerate(f, start=1):
        if p.search(line):  # no strip; regex handles trailing whitespace
            print(f"Line {line_no}: {line.rstrip()}")
            count += 1

print("No matches found." if count == 0 else f"\nTotal matches: {count}")
