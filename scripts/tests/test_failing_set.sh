#!/usr/bin/env bash
# test_failing_set.sh — hermetic tests for scripts/failing-set.sh (R5 on the gate).
#
# Proves the gate fires on bad input and passes good input: a failing test is
# reported, a passing one is not, a half-written XML report is skipped rather
# than fatal, and multiple task dirs (test, test8, endToEndTest) are all read.
set -euo pipefail

repo="$(cd "$(dirname "$0")/../.." && pwd)"
script="$repo/scripts/failing-set.sh"

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

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

# A tree with one failure, one pass, and one error across two task dirs.
mkdir -p "$tmp/mod-a/build/test-results/test" "$tmp/mod-b/build/test-results/test8"
cat > "$tmp/mod-a/build/test-results/test/TEST-a.xml" <<'XML'
<testsuite name="a" tests="2" failures="1" errors="0" skipped="0">
  <testcase classname="pkg.A" name="fails"><failure/></testcase>
  <testcase classname="pkg.A" name="passes"/>
</testsuite>
XML
cat > "$tmp/mod-b/build/test-results/test8/TEST-b.xml" <<'XML'
<testsuite name="b" tests="1" failures="0" errors="1" skipped="0">
  <testcase classname="pkg.B" name="errors"><error/></testcase>
</testsuite>
XML

# A half-written report must be skipped, not fatal.
echo '<testsuite name="truncated' > "$tmp/mod-a/build/test-results/test/TEST-c.xml"

expected="pkg.A.fails
pkg.B.errors"

check "reports the failure and the error by name" 0 "pkg.A.fails" "$script" "$tmp"
check "sorts the failing set" 0 "pkg.B.errors" "$script" "$tmp"
if [ "$("$script" "$tmp")" = "$expected" ]; then
    printf 'ok   exact failing set: both names, nothing else\n'; pass=$((pass + 1))
else
    printf 'FAIL exact failing set: got\n%s\nwanted\n%s\n' "$("$script" "$tmp")" "$expected"; fail=$((fail + 1))
fi

# Flat directory of XMLs (CI artifact-download layout) is also read.
flat=$(mktemp -d)
cat > "$flat/TEST-d.xml" <<'XML'
<testsuite name="d" tests="1" failures="1" errors="0" skipped="0">
  <testcase classname="pkg.D" name="fails"><failure/></testcase>
</testsuite>
XML
if [ "$("$script" "$flat")" = "pkg.D.fails" ]; then
    printf 'ok   flat XML directory is read\n'; pass=$((pass + 1))
else
    printf 'FAIL flat XML directory: got %s\n' "$("$script" "$flat")"; fail=$((fail + 1))
fi
rm -rf "$flat"

# Empty tree -> empty output, exit 0.
empty=$(mktemp -d)
if [ -z "$("$script" "$empty")" ]; then
    printf 'ok   empty tree -> empty set\n'; pass=$((pass + 1))
else
    printf 'FAIL empty tree -> non-empty set\n'; fail=$((fail + 1))
fi
rm -rf "$empty"

printf '%s passed, %s failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
