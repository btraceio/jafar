#!/usr/bin/env bash
# test_check_doc_links.sh — hermetic tests for scripts/check-doc-links.sh
# (R5 on the gate).
#
# Proves the gate fires on a dead link, passes a clean tree, ignores fenced
# code blocks, and drops an anchor when deciding whether a target exists.
set -euo pipefail

repo="$(cd "$(dirname "$0")/../.." && pwd)"
script="$repo/scripts/check-doc-links.sh"

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
    elif [ -n "$want_grep" ] && ! grep -qF "$want_grep" <<<"$out"; then
        printf 'FAIL %s: output lacks %s\n%s\n' "$desc" "$want_grep" "$out"
        fail=$((fail + 1))
    else
        printf 'ok   %s\n' "$desc"
        pass=$((pass + 1))
    fi
}

new_tree() { # new_tree <dir-with-doc>
    rm -rf "$T"; T=$(mktemp -d)
    mkdir -p "$T/doc"
    cp -R "$1"/. "$T"/
}

T=""
trap 'rm -rf "$T"' EXIT

# A tree with a dead relative link -> the gate must fail and name it.
dead=$(mktemp -d)
mkdir -p "$dead/doc"
printf 'see [the tutorial](tutorial.md) and [site](https://example.com).\n' > "$dead/doc/Intro.md"
new_tree "$dead"
check "dead link fails and is named" 1 "MISSING doc/Intro.md -> tutorial.md" "$script" "$T"

# Anchor links resolve against the file, not the heading.
anchor=$(mktemp -d)
mkdir -p "$anchor/doc"
printf '# rules\n\nsee [R6](Verification.md#r6-compare-failure-sets-by-name-never-by-count)\n' > "$anchor/doc/Agents.md"
printf '# R6. Compare failure sets by name\n\nrule text\n' > "$anchor/doc/Verification.md"
new_tree "$anchor"
check "anchor to an existing file passes" 0 "" "$script" "$T"

# A regex inside fences is not a link.
fenced=$(mktemp -d)
mkdir -p "$fenced/doc"
printf 'snippet:\n\n```\nsee [x](nope.md)\n```\n\nreal: [ok](Real.md)\n' > "$fenced/doc/Fenced.md"
printf 'target\n' > "$fenced/doc/Real.md"
new_tree "$fenced"
check "fenced code block is not a link" 0 "" "$script" "$T"

# A wrongly-cased link must fail even on a case-insensitive filesystem: macOS
# resolves it locally and only the Linux CI run catches it — the gate must say
# locally what CI says.
case=$(mktemp -d)
mkdir -p "$case/doc"
printf 'see [the doc](readme.md)\n' > "$case/doc/Cased.md"
printf 'target\n' > "$case/doc/Readme.md"
new_tree "$case"
check "case-mismatched link fails like on CI" 1 "MISSING doc/Cased.md -> readme.md" "$script" "$T"

# Top-level documents are scanned too: AGENTS.md counts.
toplevel=$(mktemp -d)
mkdir -p "$toplevel/doc"
printf 'nothing\n' > "$toplevel/doc/Fine.md"
printf 'see [gone](doc/gone.md)\n' > "$toplevel/AGENTS.md"
new_tree "$toplevel"
check "top-level AGENTS.md is scanned" 1 "MISSING AGENTS.md -> doc/gone.md" "$script" "$T"

printf '%s passed, %s failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
