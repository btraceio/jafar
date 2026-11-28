#!/usr/bin/env bash
# test_perf_ab_run.sh — the argument checks of scripts/perf_ab_run.sh, the perf-ab driver.
#
# The driver's real work (worktrees, JMH rounds, JFR attribution) takes tens of minutes and is
# verified by running it end to end (see doc/plans/perf-ab.md, section 3.6). What can be checked
# in a second is that it refuses what it cannot run, before touching anything: a missing
# required option, an unknown option, a benchmark regex Gradle would split at its spaces, and a
# ref that names no commit. Each case must exit non-zero with a message naming the problem and
# leave no output directory behind it.
set -euo pipefail

repo="$(cd "$(dirname "$0")/../.." && pwd)"
script="$repo/scripts/perf_ab_run.sh"

pass=0
fail=0
W=$(mktemp -d)
trap 'rm -rf "$W" 2>/dev/null || true' EXIT

# refuse <description> <expected message substring> <args...>
refuse() {
    local desc="$1" want="$2"
    shift 2
    local out rc=0
    out=$("$script" "$@" 2>&1) || rc=$?
    if [ "$rc" -eq 0 ]; then
        printf 'FAIL %s: exit 0\n%s\n' "$desc" "$out"
        fail=$((fail + 1))
    elif ! grep -qF -- "$want" <<<"$out"; then
        printf 'FAIL %s: output lacks %s\n%s\n' "$desc" "$want" "$out"
        fail=$((fail + 1))
    else
        printf 'ok   %s\n' "$desc"
        pass=$((pass + 1))
    fi
}

refuse "no --base" "--base, --head and --out are required" --head HEAD --out "$W/a"
refuse "no --out" "--base, --head and --out are required" --base HEAD --head HEAD
refuse "unknown option" "unknown option --frobnicate" --frobnicate
refuse "a benchmark regex with a space" "must not contain spaces" \
    --base HEAD --head HEAD --out "$W/b" --benchmarks 'Foo bar'
refuse "an unknown round order" "--order must be abba or baab" \
    --base HEAD --head HEAD --out "$W/e" --order abab
refuse "an attribution floor that is not a number" "--attribution-min-delta must be a number" \
    --base HEAD --head HEAD --out "$W/f" --attribution-min-delta '3; rm -rf /'
refuse "a base that names no commit" "no commit no-such-ref-perf-ab" \
    --base no-such-ref-perf-ab --head HEAD --out "$W/c"
refuse "a head that names no commit" "no commit no-such-ref-perf-ab" \
    --base HEAD --head no-such-ref-perf-ab --out "$W/d"

# The refused runs must not have started: no worktree under any of their output directories.
if git -C "$repo" worktree list | grep -qF "$W"; then
    printf 'FAIL refused runs left a worktree\n'
    fail=$((fail + 1))
else
    printf 'ok   refused runs left no worktree\n'
    pass=$((pass + 1))
fi

if "$script" --help | grep -qF "perf_ab_run.sh --base REF --head REF --out DIR"; then
    printf 'ok   --help prints the usage\n'
    pass=$((pass + 1))
else
    printf 'FAIL --help does not print the usage\n'
    fail=$((fail + 1))
fi

echo "perf_ab_run: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
