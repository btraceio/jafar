#!/usr/bin/env bash
# test_typed_parity_check.sh — hermetic tests for scripts/typed-parity-check.sh (R5 on the gate).
#
# Proves the gate fires on a one-sided typed-generator change without a statement, passes
# with one, passes a two-sided change, and ignores tests, the untyped generator and the
# Java/Go parity statement. Local mode (git diff) is exercised against a scratch repo.
set -euo pipefail

repo="$(cd "$(dirname "$0")/../.." && pwd)"
script="$repo/scripts/typed-parity-check.sh"

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

case_files=()
case_body=""
gate() {
    rm -f "$tmp/files.txt" "$tmp/body.md"
    printf '%s\n' "${case_files[@]}" > "$tmp/files.txt"
    printf '%s\n' "$case_body" > "$tmp/body.md"
    "$script" --files-from "$tmp/files.txt" --body-file "$tmp/body.md"
}

PROC='jafar-processor/src/main/java/io/jafar/processor/JfrTypeProcessor.java'
TEMPLATE='jafar-processor/src/main/resources/templates/HandlerTemplate.java'
GEN='parser-codegen/src/main/java/io/jafar/parser/internal_api/CodeGenerator.java'

# 1. One-sided build-time change, no statement -> blocked, naming the side that changed.
case_files=("$PROC"); case_body=""
check "build-time only, no statement: blocked" 1 "no typed parity statement" gate
check "build-time only: names the build-time side" 1 "build-time (jafar-processor) side only" gate

# 2. A template is production code of the build-time generator.
case_files=("$TEMPLATE"); case_body=""
check "template only, no statement: blocked" 1 "no typed parity statement" gate

# 3. One-sided runtime change, no statement -> blocked, naming the runtime side.
case_files=("$GEN"); case_body=""
check "runtime only: names the runtime side" 1 "runtime (parser-codegen CodeGenerator) side only" gate
case_files=("parser-codegen/src/main/java/io/jafar/parser/internal_api/FieldMapping.java")
check "FieldMapping only, no statement: blocked" 1 "no typed parity statement" gate

# 4. Statements pass.
case_files=("$PROC"); case_body="Fix.

Typed-Parity: not applicable — the runtime classes already keep the pool"
check "build-time only, not-applicable statement: passes" 0 "" gate
case_files=("$GEN"); case_body="Typed-Parity: ported in this PR"
check "runtime only, ported statement: passes" 0 "" gate

# 5. The Java/Go statement is not a typed parity statement.
case_files=("$PROC"); case_body="Parity: not applicable — no untyped change"
check "Java/Go Parity line only: blocked" 1 "no typed parity statement" gate

# 6. An empty statement is not a statement.
case_files=("$PROC"); case_body="Typed-Parity:"
check "empty statement: blocked" 1 "no typed parity statement" gate

# 7. Both generators -> passes without a statement.
case_files=("$PROC" "$GEN"); case_body=""
check "two-sided change: passes" 0 "" gate

# 8. Not watched: tests of either side, the untyped generator, unrelated code.
case_files=("jafar-processor/src/test/java/io/jafar/processor/FooTest.java" \
            "parser-codegen/src/test/java/io/jafar/parser/BarTest.java" \
            "parser-codegen/src/main/java/io/jafar/parser/internal_api/UntypedCodeGenerator.java" \
            "parser-core/src/main/java/io/jafar/parser/impl/TypedJafarParserImpl.java"); case_body=""
check "tests, untyped generator, parser-core: not watched" 0 "" gate

# 9. A build-time change plus a runtime test is still one-sided.
case_files=("$PROC" "parser-codegen/src/test/java/io/jafar/parser/BarTest.java"); case_body=""
check "build-time change plus runtime test: still one-sided" 1 "no typed parity statement" gate

# 10. Local mode against a scratch git repo.
scratch=$(mktemp -d)
git -C "$scratch" init -q -b main
git -C "$scratch" config commit.gpgSign false
git -C "$scratch" config tag.gpgSign false
git -C "$scratch" -c user.email=t@t -c user.name=t commit -q --allow-empty -m base
git -C "$scratch" checkout -q -b feature
mkdir -p "$scratch/jafar-processor/src/main/resources/templates"
echo x > "$scratch/$TEMPLATE"
git -C "$scratch" add -A
git -C "$scratch" -c user.email=t@t -c user.name=t commit -q -m change
rc=0
out=$( cd "$scratch" && "$script" --base main 2>&1 ) || rc=$?
if [ "$rc" = "1" ] && grep -qF "Add the statement" <<<"$out" \
    && grep -qF "build-time (jafar-processor) side only" <<<"$out"; then
    printf 'ok   local mode: one-sided branch asked for a statement\n'; pass=$((pass + 1))
else
    printf 'FAIL local mode: rc=%s out=%s\n' "$rc" "$out"; fail=$((fail + 1))
fi
rm -rf "$scratch"

# 11. An unreadable base ref fails loudly instead of passing as an empty diff (R3).
rc=0
out=$("$script" --base origin/does-not-exist 2>&1) || rc=$?
if [ "$rc" = "2" ] && grep -qF "cannot diff against" <<<"$out"; then
    printf 'ok   unreadable base: refused, not silently passed\n'; pass=$((pass + 1))
else
    printf 'FAIL unreadable base: rc=%s out=%s\n' "$rc" "$out"; fail=$((fail + 1))
fi

printf '%s passed, %s failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
