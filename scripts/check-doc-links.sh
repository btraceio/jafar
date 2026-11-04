#!/usr/bin/env bash
# check-doc-links.sh — the R4 gate: a relative link in the docs must resolve.
#
# Scans the markdown under <root>/doc (plus the top-level AGENTS.md, CLAUDE.md,
# CONTRIBUTING.md, README.md) for relative links and fails when one does not
# point at an existing path. Fenced code blocks are skipped: a regex or a
# markdown snippet inside ``` fences is not a link.
#
# Legacy misses are frozen in the allowlist below: each entry is a "file ->
# target" pair that already missed when this gate was introduced. An allowlisted
# miss is tolerated; a new miss fails the gate; an allowlisted entry that no
# longer misses also fails, asking you to delete it — the list can only shrink.
#
# Usage: scripts/check-doc-links.sh [root]   (default: the repository root)
set -euo pipefail

root="${1:-$(cd "$(dirname "$0")/.." && pwd)}"

exec python3 - "$root" <<'PY'
import os, re, sys, pathlib

root = pathlib.Path(sys.argv[1]).resolve()

# Missing links that predate the gate. Delete an entry when its document is
# fixed; adding one requires fixing the document instead.
ALLOWED = {
    "doc/hdumppath.md": ["jfrpath.md"],
    "doc/README.md": ["filename.md", "../filename.md", "../cli/filename.md"],
    "doc/design/jfr2pprof.md": ["1", "2", "3", "4"],  # footnote refs, not links
    "doc/cli/Usage.md": [
        "tutorials/jfr-shell-tutorial.md",
        "hdumppath.md",
        "hdump-shell-quickstart.md",
    ],
    "doc/cli/Tutorial.md": [
        "../jfr-shell/src/main/resources/examples/",
        "../jfr-shell/README.md",
    ],
    "doc/cli/Scripting.md": ["../jfr-shell/src/main/resources/examples/"],
    "doc/parser/UntypedAPITutorial.md": [
        "../parser-core/src/main/java/io/jafar/parser/api/Values.java"
    ],
    "doc/jbang/JFRShellUsage.md": ["../jfr-shell/README.md"],
    "doc/performance/BuildTimeBenchmarks.md": [
        "../jafar-processor/README.md",
        "../parser/README.md",
    ],
    "doc/performance/PerformanceReport.md": [
        "../jafar-processor/",
        "../parser-core/src/main/java/io/jafar/parser/api/HandlerFactory.java",
    ],
}

def rel(path: pathlib.Path) -> str:
    return str(path.relative_to(root))

# Exact-case, lexical resolution: never ask the filesystem whether a link resolves.
# A case-insensitive filesystem (macOS) resolves a wrongly-cased link locally, so
# only the Linux CI run catches it — and the gate must say locally what CI says
# (first CI run of this gate caught three such links: jfrpath.md, unTypedAPITutorial.md).
actual = {os.path.normpath(str(f.relative_to(root))) for f in root.rglob("*")}

docs = sorted(root.glob("doc/**/*.md")) + [
    root / f for f in ("AGENTS.md", "CLAUDE.md", "CONTRIBUTING.md", "README.md")
    if (root / f).exists()
]

misses = {}
for p in docs:
    if not p.is_file():
        continue
    body, visible = [], False
    for line in p.read_text(encoding="utf-8", errors="replace").split("\n"):
        if line.lstrip().startswith("```"):
            visible = not visible  # a regex in a code block is not a link
        elif not visible:
            body.append(line)
    for m in re.finditer(r"\]\((?!https?://)([^)#]+)(#[^)]*)?\)", "\n".join(body)):
        target = m.group(1)
        if os.path.normpath(os.path.join(rel(p.parent), target)) not in actual:
            misses.setdefault(rel(p), set()).add(target)

new = {f: t - set(ALLOWED.get(f, ())) for f, t in misses.items()}
new = {f: t for f, t in new.items() if t}

stale = {}
for f, allowed in ALLOWED.items():
    # Exact-case: a case-insensitive filesystem says a differently-cased file exists,
    # and an allowlist entry must not go stale because of a file that is not there.
    if os.path.normpath(f) not in actual:
        continue
    still = misses.get(f, set()) & set(allowed)
    for target in set(allowed) - still:
        stale.setdefault(f, set()).add(target)

for f in sorted(new):
    for t in sorted(new[f]):
        print(f"MISSING {f} -> {t}")
for f in sorted(stale):
    for t in sorted(stale[f]):
        print(f"STALE-ALLOWLIST {f} -> {t} (link fixed; delete the entry)")

if new:
    sys.exit("check-doc-links: new broken links; see MISSING above")
if stale:
    sys.exit("check-doc-links: allowlist entries to delete; see STALE-ALLOWLIST above")
print(f"check-doc-links: clean ({len(docs)} documents)")
PY
