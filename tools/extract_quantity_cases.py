"""Extract the CQL test cases that use a quantity with a unit from cqframework's copy of the CQL test suite.

Usage: python3 -I tools/extract_quantity_cases.py <dir of Cql*Test.xml> <output .json>

The test files are in cqframework/clinical_quality_language at
engine-fhir/src/jvmTest/resources/org/hl7/fhirpath/cql. A case is kept when its expression holds a number followed
by a quoted unit (`5 'mg'`) or calls ConvertQuantity or CanConvertQuantity. The output lists each case's file, group
and test name, its expression, the outputs the test file expects, and whether the test file marks it invalid.
"""

from __future__ import annotations

import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

NS = {"t": "http://hl7.org/fhirpath/tests"}
QUANTITY = re.compile(r"\d\s*'[^']*'|ConvertQuantity|CanConvertQuantity")


def cases(test_file: Path) -> list[dict]:
    root = ET.parse(test_file).getroot()
    found = []
    for group in root.findall("t:group", NS):
        for test in group.findall("t:test", NS):
            expression = test.find("t:expression", NS)
            if expression is None or expression.text is None or not QUANTITY.search(expression.text):
                continue
            found.append(
                {
                    "file": test_file.stem,
                    "group": group.get("name"),
                    "name": test.get("name"),
                    "expression": expression.text.strip(),
                    "invalid": expression.get("invalid"),
                    "expected": [o.text for o in test.findall("t:output", NS)],
                }
            )
    return found


def main() -> None:
    source_dir, output = Path(sys.argv[1]), Path(sys.argv[2])
    found = [c for f in sorted(source_dir.glob("Cql*.xml")) for c in cases(f)]
    output.write_text(json.dumps(found, indent=1, ensure_ascii=False) + "\n")
    print(f"{len(found)} cases written to {output}")


if __name__ == "__main__":
    main()
