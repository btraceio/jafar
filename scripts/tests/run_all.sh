#!/usr/bin/env bash
# run_all.sh — the guardrail regression suite: every gate's hermetic tests, in one place.
#
# A change to any gate (scripts/*.sh) runs the whole suite, so a gate cannot be "fixed"
# while breaking another. Each test proves its gate fires on bad input and passes good
# input (R5 on the gates); a gate that has never failed is an assumption.
#
# CI runs this on every PR (the `guardrails` job in pr-tests.yml).
set -euo pipefail

repo="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$repo"

status=0
run=0
for test in scripts/tests/test_*.sh; do
    echo "== $test"
    run=$((run + 1))
    if ! "$test"; then
        status=1
        echo "== $test FAILED" >&2
    fi
done

echo "guardrail suite: $run test file(s), $([ $status -eq 0 ] && echo all green || echo FAILURES)"
exit $status
