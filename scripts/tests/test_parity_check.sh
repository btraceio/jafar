#!/usr/bin/env bash
# test_parity_check.sh — hermetic tests for scripts/parity-check.sh (R5 on the gate).
#
# Proves the gate fires on a one-sided untyped-parser change without a statement,
# passes with a statement, passes a two-sided change, and ignores tests, fixtures
# and unrelated paths. Local mode (git diff) is exercised against a scratch repo.
set -euo pipefail

repo="$(cd "$(dirname "$0")/../.." && pwd)"
script="$repo/scripts/parity-check.sh"

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

# Helper: run a command, keep its output and exit status, but turn the status into 3
# if the output contains the given fixed string.
not_in_output() { # not_in_output <forbidden> <cmd...>
    local forbidden="$1"
    shift
    local out rc=0
    out=$("$@" 2>&1) || rc=$?
    printf '%s\n' "$out"
    if grep -qF "$forbidden" <<<"$out"; then
        printf 'output contains forbidden text: %s\n' "$forbidden"
        return 3
    fi
    return "$rc"
}

# Helper: write changed-file list and body, then run the script.
case_files=""
case_body=""
gate() {
    rm -f "$tmp/files.txt" "$tmp/body.md"
    printf '%s\n' "${case_files[@]}" > "$tmp/files.txt"
    printf '%s\n' "$case_body" > "$tmp/body.md"
    "$script" --files-from "$tmp/files.txt" --body-file "$tmp/body.md"
}

JAVA='parser-core/src/main/java/io/jafar/parser/impl/Foo.java'
GO='go-parser/jfr/parser.go'

# 1. One-sided Java change, no statement -> the gate must fail.
case_files=("$JAVA"); case_body=""
check "one-sided Java, no statement: blocked" 1 "no parity statement" gate

# 2. One-sided Java change with a not-applicable statement -> passes.
case_files=("$JAVA")
case_body="Fix thing.

Parity: not applicable — depends on run-time bytecode generation, no Go counterpart"
check "one-sided Java, not-applicable statement: passes" 0 "" gate

# 3. One-sided Go change with a ported statement -> passes.
case_files=("$GO"); case_body="x

Parity: ported in this PR"
check "one-sided Go, ported statement: passes" 0 "" gate

# 4. Both sides -> passes without a statement.
case_files=("$JAVA" "$GO"); case_body=""
check "two-sided change: passes" 0 "" gate

# 5. Neither side -> passes.
case_files=("shell-core/src/main/java/x/Y.java"); case_body=""
check "unrelated change: passes" 0 "" gate

# 6. Java tests and parser-codegen do not count.
case_files=("parser-core/src/test/java/io/jafar/parser/impl/FooTest.java" \
            "parser-codegen/src/main/java/io/jafar/parser/impl/Gen.java"); case_body=""
check "tests and codegen: not watched" 0 "" gate

# 7. Go tests and testdata do not count.
case_files=("go-parser/jfr/parser_test.go" "go-parser/jfr/testdata/rec.jfr"); case_body=""
check "Go tests and testdata: not watched" 0 "" gate

# 8. A statement with nothing after the colon is not a statement.
case_files=("$JAVA"); case_body="Fix.

Parity:"
check "empty statement: blocked" 1 "no parity statement" gate

# 9. One-sided Java change where the Go test file also changed: still one-sided.
case_files=("$JAVA" "go-parser/jfr/parser_test.go"); case_body=""
check "Java change plus Go test: still one-sided" 1 "no parity statement" gate

# 9a. The failure names the side that changed, not its counterpart (PR-body mode).
#     Each side is asserted both ways: the right label present, the wrong one absent.
case_files=("$JAVA"); case_body=""
check "one-sided Java, no statement: names the Java side" 1 \
    "untyped parser on the parser-core (Java) side only" gate
check "one-sided Java, no statement: does not name the Go side" 1 "" \
    not_in_output "go-parser (Go)" gate
case_files=("$GO"); case_body=""
check "one-sided Go, no statement: names the Go side" 1 \
    "untyped parser on the go-parser (Go) side only" gate
check "one-sided Go, no statement: does not name the Java side" 1 "" \
    not_in_output "parser-core (Java)" gate

# 10. Local mode against a scratch git repo: a one-sided branch is asked for a
#     statement, and the message names the side that changed.
local_gate() { # local_gate <changed-path>
    local path="$1" scratch rc=0
    scratch=$(mktemp -d)
    git -C "$scratch" init -q -b main
    # Hermetic: fixture commits must not need the developer's signing agent.
    git -C "$scratch" config commit.gpgSign false
    git -C "$scratch" config tag.gpgSign false
    git -C "$scratch" -c user.email=t@t -c user.name=t commit -q --allow-empty -m base
    git -C "$scratch" checkout -q -b feature
    mkdir -p "$scratch/$(dirname "$path")"
    echo x > "$scratch/$path"
    git -C "$scratch" add -A
    git -C "$scratch" -c user.email=t@t -c user.name=t commit -q -m change
    ( cd "$scratch" && "$script" --base main ) || rc=$?
    rm -rf "$scratch"
    return "$rc"
}

check "local mode: one-sided branch asked for a statement" 1 "Add the statement" \
    local_gate "$JAVA"
check "local mode, Java-only branch: names the Java side" 1 \
    "untyped parser on the parser-core (Java) side only" local_gate "$JAVA"
check "local mode, Java-only branch: does not name the Go side" 1 "" \
    not_in_output "go-parser (Go)" local_gate "$JAVA"
check "local mode, Go-only branch: names the Go side" 1 \
    "untyped parser on the go-parser (Go) side only" local_gate "$GO"
check "local mode, Go-only branch: does not name the Java side" 1 "" \
    not_in_output "parser-core (Java)" local_gate "$GO"

# 11. An unreadable base ref fails loudly instead of passing as an empty diff (R3).
out=$("$script" --base origin/does-not-exist 2>&1) && rc=0 || rc=$?
if [ "$rc" = "2" ] && grep -qF "cannot diff against" <<<"$out"; then
    printf 'ok   unreadable base: refused, not silently passed\n'; pass=$((pass + 1))
else
    printf 'FAIL unreadable base: rc=%s out=%s\n' "$rc" "$out"; fail=$((fail + 1))
fi

printf '%s passed, %s failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
