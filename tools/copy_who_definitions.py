#!/usr/bin/env python3
"""Copy the PlanDefinition and ActivityDefinition resources of WHO's immunization package into third_party.

Usage: python3 -I tools/copy_who_definitions.py <package dir>

Each resource is copied as it is, without its narrative (`text`), to
third_party/smart-immunizations-0.2.0/definitions/<resourceType>-<id>.json.
"""

import json
import os
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
OUT = os.path.join(ROOT, "third_party", "smart-immunizations-0.2.0", "definitions")


def main() -> None:
    package = sys.argv[1]
    os.makedirs(OUT, exist_ok=True)
    count = 0
    for name in sorted(os.listdir(package)):
        if not (name.startswith("PlanDefinition-") or name.startswith("ActivityDefinition-")) or not name.endswith(".json"):
            continue
        with open(os.path.join(package, name), encoding="utf-8") as f:
            resource = json.load(f)
        resource.pop("text", None)
        with open(os.path.join(OUT, f"{resource['resourceType']}-{resource['id']}.json"), "w", encoding="utf-8") as f:
            json.dump(resource, f, indent=1, ensure_ascii=False)
            f.write("\n")
        count += 1
    print(f"{count} resources written to {OUT}")


if __name__ == "__main__":
    main()
