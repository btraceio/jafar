#!/usr/bin/env bash
# check-knowledgebase.sh — keep .knowledgebase/memories honest.
#
# Sphinx review feeds every memory under .knowledgebase/memories/ to every
# reviewer as prior-learnings, so a stale memory is worse than none. Each
# memory carries frontmatter with an executable `check:` line that proves it
# still describes the tree. This gate:
#   1. requires the frontmatter keys id, title, kind, status and check, and
#      id == the file's stem;
#   2. requires every `applies_to:` path to exist;
#   3. runs each active memory's `check:` from the repository root under
#      `bash -o pipefail` (so `cmd | tail -1` fails when cmd fails) and fails
#      naming the memory when it exits nonzero;
#   4. requires every kb/<slug> citation in tracked files to resolve to a
#      memory id or to the last segment of a memory's `source:`. Citations
#      that predate this gate are frozen in LEGACY below: a legacy slug is
#      tolerated, a new unresolved slug fails, and a legacy slug that now
#      resolves or is no longer cited fails asking you to delete it — the
#      list can only shrink.
#
# Usage: scripts/check-knowledgebase.sh [root]   (default: the repository root)
set -euo pipefail

root="${1:-$(cd "$(dirname "$0")/.." && pwd)}"

exec python3 - "$root" <<'PY'
import pathlib, re, subprocess, sys

root = pathlib.Path(sys.argv[1]).resolve()
mem_dir = root / ".knowledgebase" / "memories"

# kb/<slug> citations that resolved to no in-repo memory when this gate landed
# (they name nodes of an investigation graph kept outside the repository).
# Distill the node into a memory, or drop the citation, then delete the entry.
LEGACY = {
    "a1-japicmp-baselines",
    "a2-jazzer-jdk-floor",
    "a4-jdk8-alloc",
    "dead-ends",
    "design-principles",
    "fuzz-robustness-baseline",
    "probe-pitfalls",
}
REQUIRED = ("id", "title", "kind", "status", "check")

def frontmatter(path):
    lines = path.read_text(encoding="utf-8", errors="replace").split("\n")
    if not lines or lines[0].strip() != "---":
        return None
    fm = {}
    for line in lines[1:]:
        if line.strip() == "---":
            return fm
        m = re.match(r"^([A-Za-z_]+):\s?(.*)$", line)
        if m:
            fm[m.group(1)] = m.group(2).strip()
    return None

errors, known = [], set()
memories = sorted(mem_dir.glob("*.md")) if mem_dir.is_dir() else []
for p in memories:
    name = str(p.relative_to(root))
    fm = frontmatter(p)
    if fm is None:
        errors.append(f"{name}: no frontmatter block (--- ... ---)")
        continue
    for key in REQUIRED:
        if not fm.get(key):
            errors.append(f"{name}: missing frontmatter key '{key}'")
    if fm.get("id") and fm["id"] != p.stem:
        errors.append(f"{name}: id '{fm['id']}' does not match the file name")
    known.add(p.stem)
    if fm.get("source"):
        known.add(fm["source"].rstrip("/").split("/")[-1])
    for a in (x.strip() for x in fm.get("applies_to", "").strip("[]").split(",")):
        if a and not (root / a).exists():
            errors.append(f"{name}: applies_to path does not exist: {a}")
    if fm.get("status") == "active" and fm.get("check"):
        r = subprocess.run(["bash", "-o", "pipefail", "-c", fm["check"]], cwd=root,
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        if r.returncode != 0:
            tail = "\n    ".join(r.stdout.strip().split("\n")[-5:])
            errors.append(f"{name}: check failed (exit {r.returncode}): {fm['check']}\n    {tail}")

grep = subprocess.run(["git", "-C", str(root), "grep", "-ohE", "kb/[a-z0-9-]+"],
                      stdout=subprocess.PIPE, text=True)
if grep.returncode not in (0, 1):
    sys.exit("check-knowledgebase: git grep failed; run from a git checkout")
cited = {s[len("kb/"):] for s in grep.stdout.split()}

for slug in sorted(cited - known - LEGACY):
    errors.append(f"UNRESOLVED kb/{slug}: no memory id or source matches; add the memory or drop the citation")
for slug in sorted(LEGACY & known):
    errors.append(f"STALE-LEGACY kb/{slug}: now resolves to a memory; delete it from LEGACY")
for slug in sorted(LEGACY - cited):
    errors.append(f"STALE-LEGACY kb/{slug}: no longer cited; delete it from LEGACY")

for e in errors:
    print(e)
if errors:
    sys.exit(f"check-knowledgebase: {len(errors)} problem(s); see above")
print(f"check-knowledgebase: clean ({len(memories)} memories, {len(cited)} kb/ slugs cited)")
PY
