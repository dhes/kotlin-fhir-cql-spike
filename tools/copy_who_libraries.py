#!/usr/bin/env python3
"""Copy the Library resources of WHO's immunization package into third_party, keeping their CQL only.

Usage: python3 tools/copy_who_libraries.py <package dir>

Each Library keeps resourceType, id, url, version, name, status, type, relatedArtifact and its text/cql
content; the ELM XML attachment and the other elements are left out. Exits non-zero if any library's CQL
differs from third_party/smart-immunizations-0.2.0/cql/<name>.cql, line endings aside.
"""

import base64
import json
import os
import sys

KEEP = ["resourceType", "id", "url", "version", "name", "status", "type", "relatedArtifact"]
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
OUT = os.path.join(ROOT, "third_party", "smart-immunizations-0.2.0", "libraries")
CQL = os.path.join(ROOT, "third_party", "smart-immunizations-0.2.0", "cql")


def main(package_dir):
    os.makedirs(OUT, exist_ok=True)
    count, differ = 0, []
    for name in sorted(os.listdir(package_dir)):
        if not (name.startswith("Library-") and name.endswith(".json")):
            continue
        with open(os.path.join(package_dir, name), encoding="utf-8") as f:
            library = json.load(f)
        content = [c for c in library.get("content", []) if c.get("contentType") == "text/cql"]
        if len(content) != 1:
            sys.exit(f"{name}: expected one text/cql attachment, found {len(content)}")
        copy = {k: library[k] for k in KEEP if k in library}
        copy["content"] = content
        cql = base64.b64decode(content[0]["data"]).decode("utf-8").replace("\r\n", "\n")
        with open(os.path.join(CQL, library["name"] + ".cql"), encoding="utf-8") as f:
            if f.read().replace("\r\n", "\n") != cql:
                differ.append(library["name"])
        with open(os.path.join(OUT, name), "w", encoding="utf-8") as f:
            json.dump(copy, f, indent=2, ensure_ascii=False)
            f.write("\n")
        count += 1
    print(f"{count} Library resources written to {os.path.relpath(OUT, ROOT)}")
    if differ:
        sys.exit(f"CQL differs from third_party cql/ for: {', '.join(differ)}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
