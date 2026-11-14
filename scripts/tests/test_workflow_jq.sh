#!/usr/bin/env bash
# test_workflow_jq.sh — every `gh ... --jq '<expr>'` in .github/workflows compiles as jq.
#
# Why this exists: nightly-jmh.yml shipped `--jq '.[0].databaseId || ""'`. jq has no `||`
# (the alternative operator is `//`), so `gh` failed with "failed to parse jq expression"
# and, under `set -e`, took the step down. That step only ever runs on a scheduled runner,
# so nothing before merge could see it (doc/agents/Gates.md, "what is not verified before
# merge"), and the nightly was red from its first run.
#
# A workflow's shell cannot be executed here, but its jq can be compiled: `jq` exits 3 on
# a compile error and anything else (0, or 5 for a runtime error on the null input used
# here) once the program parsed. `gh --jq` is gojq rather than jq; the two agree on what
# parses for everything this repo uses, and `||` is rejected by both.
#
# Only single-quoted expressions are checked: a double-quoted one is assembled by the
# shell at run time and cannot be compiled from the file. One of those fails the test, so
# that it is rewritten rather than silently skipped (R3).
set -euo pipefail

repo="$(cd "$(dirname "$0")/../.." && pwd)"

if ! command -v jq >/dev/null 2>&1; then
    echo "FAIL jq is not on PATH; this test compiles the workflows' --jq expressions with it" >&2
    exit 1
fi

# scan <dir>: prints one line per expression; returns 1 if any does not compile.
scan() {
    local dir="$1" bad=0 file line expr rc
    while IFS= read -r file; do
        while IFS= read -r hit; do
            line="${hit%%:*}"
            expr="${hit#*:}"
            if [[ "$expr" != "--jq '"*"'" ]]; then
                echo "UNCHECKED $file:$line: $expr (not a single-quoted expression)"
                bad=1
                continue
            fi
            expr="${expr#--jq \'}"
            expr="${expr%\'}"
            rc=0
            jq -n "$expr" >/dev/null 2>&1 || rc=$?
            if [ "$rc" = 3 ]; then
                echo "INVALID $file:$line: $expr"
                bad=1
            else
                echo "valid $file:$line: $expr"
            fi
        done < <(grep -n -o -E -- "--jq +('[^']*'|\"[^\"]*\"|[^ '\"]+)" "$file" || true)
    done < <(find "$dir" -type f \( -name '*.yml' -o -name '*.yaml' \) | sort)
    return $bad
}

pass=0
fail=0

check() { # check <description> <expected-exit> <expected-grep> <cmd...>
    local desc="$1" want_rc="$2" want_grep="$3"
    shift 3
    local out rc=0
    out=$("$@" 2>&1) || rc=$?
    if [ "$rc" != "$want_rc" ]; then
        printf 'FAIL %s: exit %s, wanted %s\n%s\n' "$desc" "$rc" "$want_rc" "$out"
        fail=$((fail + 1))
    elif [ -n "$want_grep" ] && ! grep -qF -- "$want_grep" <<<"$out"; then
        printf 'FAIL %s: output lacks %s\n%s\n' "$desc" "$want_grep" "$out"
        fail=$((fail + 1))
    else
        printf 'ok   %s\n' "$desc"
        pass=$((pass + 1))
    fi
}

T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT

# The gate fires on the expression that broke the nightly, naming file and line.
mkdir -p "$T/bad"
printf '%s\n' 'steps:' '  - run: |' \
    "      id=\"\$(gh run list --json databaseId --jq '.[0].databaseId || \"\"')\"" \
    >"$T/bad/nightly.yml"
check "the || expression is rejected" 1 "INVALID $T/bad/nightly.yml:3: .[0].databaseId || \"\"" \
    scan "$T/bad"

# ...and passes its corrected form.
mkdir -p "$T/good"
printf '%s\n' 'steps:' '  - run: |' \
    "      id=\"\$(gh run list --json databaseId --jq '.[0].databaseId // \"\"')\"" \
    >"$T/good/nightly.yml"
check "the // expression is accepted" 0 "valid $T/good/nightly.yml:3: .[0].databaseId // \"\"" \
    scan "$T/good"

# A program that parses but fails on the null input is still a valid program.
mkdir -p "$T/runtime"
printf '%s\n' "  - run: gh api x --jq '.[] | .name'" >"$T/runtime/a.yml"
check "a runtime error on null input is not a compile error" 0 "valid $T/runtime/a.yml:1" \
    scan "$T/runtime"

# An expression the shell assembles cannot be compiled from the file: fail, do not skip.
mkdir -p "$T/dq"
printf '%s\n' '  - run: gh api x --jq ".[] | select(.name == \"$NAME\")"' >"$T/dq/a.yml"
check "a double-quoted expression is reported, not skipped" 1 "UNCHECKED $T/dq/a.yml:1" \
    scan "$T/dq"

# The real workflows: all valid, and at least one expression found - an extraction
# pattern that matches nothing would otherwise pass forever.
check "the repository's workflows compile" 0 "valid $repo/.github/workflows/nightly-jmh.yml" \
    scan "$repo/.github/workflows"

echo "test_workflow_jq: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
