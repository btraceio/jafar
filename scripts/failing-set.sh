#!/usr/bin/env bash
# failing-set.sh — the R6 tool: failure sets are compared by name, never by count.
#
# Prints one "classname.name" line per failing or errored test found in JUnit
# XML reports, sorted. A count that stays the same can still hide a swap, so
# compare two dumps with diff instead of comparing numbers:
#
#   scripts/failing-set.sh > /tmp/fail_base.txt   # before changing code
#   ...run the suite...
#   scripts/failing-set.sh | diff /tmp/fail_base.txt - && echo "zero new failures"
#
# Reports are discovered as <root>/**/build/test-results/<task>/*.xml for every
# task that produces JUnit XML (test, test8, endToEndTest, ...), or as *.xml
# directly inside <root> (the layout CI's artifact download produces). A report
# that cannot be parsed is skipped, not fatal: a half-written XML file is not
# a test result.
#
# Usage: scripts/failing-set.sh [root ...]   (default: the repository root)
set -euo pipefail

roots=("$@")
if [ ${#roots[@]} -eq 0 ]; then
    roots=("$(cd "$(dirname "$0")/.." && pwd)")
fi

exec python3 - "${roots[@]}" <<'PY'
import pathlib, sys, xml.etree.ElementTree as ET

names = set()
for root in sys.argv[1:]:
    base = pathlib.Path(root)
    files = set(base.glob("**/build/test-results/*/*.xml")) | set(base.glob("*.xml"))
    for f in sorted(files):
        try:
            parsed = ET.parse(f)
        except (ET.ParseError, OSError):
            continue  # a half-written report is not a test result
        for tc in parsed.getroot().iter("testcase"):
            if tc.find("failure") is not None or tc.find("error") is not None:
                names.add(f"{tc.get('classname')}.{tc.get('name')}")
print("\n".join(sorted(names)))
PY
