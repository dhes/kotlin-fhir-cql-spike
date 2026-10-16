"""Build the WHO immunization test-suite fixtures for step 4.

Usage: python3 -I tools/build_who_suite.py <smart-immunizations clone> <commit> <smart.who.int.immunizations package dir> <spike root>

Reads the Karate feature files of WorldHealthOrganization/smart-immunizations at <commit>
(input/testing/features/plandefinition) and writes, under <spike root>:

  fixtures/who-suite/scenarios.json        one entry per scenario: PlanDefinition, logic library, Today, patient,
                                           test bundle, and what the scenario asserts
  fixtures/who-suite/plandefinitions.json  per PlanDefinition used: logic library and its actions' applicability
                                           conditions (all are single CQL identifiers in this package)
  fixtures/who-suite/data/                 the test bundles the scenarios create
  third_party/smart-immunizations-0.2.0/   all CQL and ValueSets of the package

It also writes, for each contraindication (IMMZ.D5) scenario whose bundle records a contraindication as an
Observation coded DE161 "Potential contraindications", a twin scenario whose bundle records the same
contraindication as a Condition instead, with the Observation's effective time as its onset. A twin expects what
its original expects.
"""

from __future__ import annotations

import base64
import json
import re
import subprocess
import sys
from pathlib import Path

FEATURES = "input/testing/features/plandefinition"
POTENTIAL_CONTRAINDICATIONS = "DE161"


def git_show(repo: Path, commit: str, path: str) -> str:
    return subprocess.run(["git", "-C", str(repo), "show", f"{commit}:{path}"], check=True, capture_output=True).stdout.decode()


def git_ls(repo: Path, commit: str, path: str) -> list[str]:
    out = subprocess.run(["git", "-C", str(repo), "ls-tree", "-r", "--name-only", commit, path], check=True, capture_output=True).stdout.decode()
    return out.split()


def karate_string(s: str) -> str:
    """Unescape a double-quoted JavaScript string literal as Karate reads it."""
    escapes = {"n": "\n", "t": "\t", "r": "\r", '"': '"', "'": "'", "\\": "\\"}
    return re.sub(r"\\(.)", lambda m: escapes.get(m.group(1), m.group(1)), s)


def default_today(repo: Path, commit: str, feature_path: str, text: str) -> str | None:
    m = re.search(r"call read\('(\.\./[^@']+)@defaults'\)", text)
    if not m:
        return None
    parent = str(Path(feature_path).parent / m.group(1)).replace("/plandefinition/IMMZMeasles/../", "/plandefinition/")
    parent = re.sub(r"/[^/]+/\.\./", "/", parent)
    m2 = re.search(r'"Today",\s*"valueDate":\s*"(\d{4}-\d{2}-\d{2})"', git_show(repo, commit, parent))
    return m2.group(1) if m2 else None


def parse_feature(repo: Path, commit: str, path: str) -> list[dict]:
    text = git_show(repo, commit, path)
    if re.match(r"\s*@ignore", text):
        return []
    background = text.split("Scenario:", 1)[0]
    m = re.search(r'parameter\[0\]\.valueDate = "(\d{4}-\d{2}-\d{2})"', background)
    today = m.group(1) if m else default_today(repo, commit, path, background)
    scenarios = []
    # Each scenario starts at its tag line(s) and runs to the next tag line.
    for block in re.split(r"\n(?=\s*@)", text)[1:]:
        lines = block.strip().splitlines()
        if not lines:
            continue
        tags = lines[0]
        if "Scenario" not in block or "@ignore" in tags:
            continue
        patient = re.search(r"@patient=(\S+)", tags)
        bundle = re.search(r'IMMZ\.feature@create\'\) \{ "file": "\./data/([^"]+)" \}', block)
        plan = re.search(r"path 'PlanDefinition/([^/]+)/\$apply'", block)
        if not (patient and bundle and plan):
            continue
        guidance = re.search(r'\.payload\.contentString = "((?:[^"\\]|\\.)*)"', block)
        contained = re.search(r"match response\.contained == '#\[(\d+)\]'", block)
        title = re.search(r"Scenario: (.*)", block)
        scenarios.append(
            {
                "id": f"{Path(path).stem}/{patient.group(1)}",
                "feature": path.removeprefix(FEATURES + "/"),
                "scenario": title.group(1).strip() if title else "",
                "planDefinition": plan.group(1),
                "today": today,
                "patient": patient.group(1),
                "bundle": f"{Path(path).parent.name}/{bundle.group(1)}",
                "expectedGuidance": karate_string(guidance.group(1)) if guidance else None,
                "expectedContained": int(contained.group(1)) if contained else None,
            }
        )
    return scenarios


def condition_twin(bundle: dict) -> dict | None:
    """The bundle with each "Potential contraindications" Observation replaced by a Condition, or None."""
    entries, replaced = [], False
    for entry in bundle["entry"]:
        r = entry["resource"]
        codes = [c.get("code") for c in r.get("code", {}).get("coding", [])]
        if r["resourceType"] == "Observation" and POTENTIAL_CONTRAINDICATIONS in codes and "valueCodeableConcept" in r:
            condition = {
                "resourceType": "Condition",
                "id": f"{r['id']}-condition",
                "clinicalStatus": {"coding": [{"system": "http://terminology.hl7.org/CodeSystem/condition-clinical", "code": "active"}]},
                "verificationStatus": {"coding": [{"system": "http://terminology.hl7.org/CodeSystem/condition-ver-status", "code": "confirmed"}]},
                "code": r["valueCodeableConcept"],
                "subject": r["subject"],
                # WHO's CQL counts a contraindication Condition only while its prevalence interval, which starts
                # at the onset, includes Today; the Observation's effective time is the closest equivalent.
                **({"onsetDateTime": r["effectiveDateTime"], "recordedDate": r["effectiveDateTime"]} if "effectiveDateTime" in r else {}),
            }
            entries.append({**entry, "resource": condition, "fullUrl": f"Condition/{condition['id']}",
                            **({"request": {"method": "PUT", "url": f"Condition/{condition['id']}"}} if "request" in entry else {})})
            replaced = True
        else:
            entries.append(entry)
    return {**bundle, "entry": entries} if replaced else None


def main() -> None:
    repo, commit, package, root = Path(sys.argv[1]), sys.argv[2], Path(sys.argv[3]), Path(sys.argv[4])
    out = root / "fixtures/who-suite"
    (out / "data").mkdir(parents=True, exist_ok=True)

    # Package content: libraries by url, all CQL, all ValueSets.
    third = root / "third_party/smart-immunizations-0.2.0"
    (third / "cql").mkdir(parents=True, exist_ok=True)
    (third / "valuesets").mkdir(parents=True, exist_ok=True)
    library_names = {}
    for p in sorted(package.glob("Library-*.json")):
        d = json.loads(p.read_text())
        library_names[d["url"]] = d["name"]
        for c in d.get("content", []):
            if c["contentType"] == "text/cql":
                (third / "cql" / f"{d['name']}.cql").write_bytes(base64.b64decode(c["data"]))
    for p in sorted(package.glob("ValueSet-*.json")):
        (third / "valuesets" / p.name).write_bytes(p.read_bytes())

    plans = {}
    for p in sorted(package.glob("PlanDefinition-*.json")):
        d = json.loads(p.read_text())
        plans[d["id"]] = {
            "library": library_names[d["library"][0]],
            "actions": [
                {"definition": a.get("definitionCanonical", "").split("/")[-1], "condition": a["condition"][0]["expression"]["expression"]}
                for a in d.get("action", [])
            ],
        }

    scenarios = []
    for path in sorted(git_ls(repo, commit, FEATURES)):
        if path.endswith(".feature") and path.count("/") > FEATURES.count("/") + 1:
            scenarios += parse_feature(repo, commit, path)

    twins = []
    for s in scenarios:
        bundle = json.loads(git_show(repo, commit, f"{FEATURES}/{s['bundle'].replace('/', '/data/', 1)}"))
        target = out / "data" / s["bundle"]
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(bundle, indent=2) + "\n")
        if s["planDefinition"].startswith("IMMZD5DT"):
            twin = condition_twin(bundle)
            if twin is not None:
                twin_path = target.with_name("condition-" + target.name)
                twin_path.write_text(json.dumps(twin, indent=2) + "\n")
                twins.append({**s, "id": s["id"] + " (Condition)", "bundle": str(twin_path.relative_to(out / "data")), "twinOf": s["id"]})

    used = sorted({s["planDefinition"] for s in scenarios})
    missing = [p for p in used if p not in plans]
    (out / "plandefinitions.json").write_text(json.dumps({p: plans[p] for p in used if p in plans}, indent=2) + "\n")
    (out / "scenarios.json").write_text(json.dumps(scenarios + twins, indent=2, ensure_ascii=False) + "\n")
    print(f"{len(scenarios)} scenarios, {len(twins)} Condition twins, {len(used)} PlanDefinitions"
          f"{', missing from package: ' + ', '.join(missing) if missing else ''}; "
          f"{sum(1 for s in scenarios if s['today'] is None)} without a Today date", file=sys.stderr)


if __name__ == "__main__":
    main()
