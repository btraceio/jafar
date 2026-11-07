#!/usr/bin/env bash
# test_alloc_budget_check.sh — hermetic tests for scripts/alloc-budget-check.sh (R5 on the gate).
#
# Proves the ratchet and only the ratchet: raising a value (or a tolerance, or
# excluding a budgeted cell) without an Alloc-Budget statement is blocked and
# lists key, old and new; the same raise passes with a statement; lowering,
# adding a key, an unchanged file and a file absent at base pass; an unreadable
# base is refused, not silently passed (R3). All cases run in scratch git repos.
set -euo pipefail

repo="$(cd "$(dirname "$0")/../.." && pwd)"
script="$repo/scripts/alloc-budget-check.sh"

pass=0
fail=0
R=""

trap 'rm -rf "$R" 2>/dev/null || true' EXIT

# gate <description> <expected-exit> [required-output-substrings ...] -- <script args...>
# Runs the gate inside the scratch repo $R (already prepared by the case).
gate() {
    local desc="$1" want_rc="$2"
    shift 2
    local greps=()
    while [ "$#" -gt 0 ] && [ "$1" != "--" ]; do
        greps+=("$1")
        shift
    done
    [ "$#" -gt 0 ] && shift # drop the --
    local out rc=0 g
    out=$(cd "$R" && "$script" "$@" 2>&1) || rc=$?
    if [ "$rc" != "$want_rc" ]; then
        printf 'FAIL %s: exit %s, wanted %s\n%s\n' "$desc" "$rc" "$want_rc" "$out"
        fail=$((fail + 1))
        return
    fi
    for g in ${greps[@]+"${greps[@]}"}; do
        if [ -n "$g" ] && ! grep -qF "$g" <<<"$out"; then
            printf 'FAIL %s: output lacks %s\n%s\n' "$desc" "$g" "$out"
            fail=$((fail + 1))
            return
        fi
    done
    printf 'ok   %s\n' "$desc"
    pass=$((pass + 1))
}

mkrepo() { # scratch repo with a committed budget file the cases then edit
    rm -rf "$R" 2>/dev/null || true
    R=$(mktemp -d)
    git -C "$R" init -q -b main
    # Local to the scratch repo: the developer's global commit.gpgSign / tag.gpgSign
    # would route every test commit through an SSH signing agent that is not
    # available to tests; a hermetic repo commits unsigned
    # (same reason as test_derive_version's tag.gpgSign).
    git -C "$R" config tag.gpgSign false
    git -C "$R" config commit.gpgSign false
    mkdir -p "$R/gradle"
    cat > "$R/gradle/allocation-budgets.properties" <<'EOF'
# measured on Temurin 21.0.5
parser-core.untyped.tck-test.jfr.jdk21=83000
parser-core.untyped.tck-test.jfr.jdk21.tolerance=1000
parser-codegen.typed.tck-test.jfr.jdk21=90000
EOF
    git -C "$R" add -A
    git -C "$R" -c user.email=t@t -c user.name=t commit -q -m base
}

budget="gradle/allocation-budgets.properties"
raise_body() { printf 'Fix thing.\n\nAlloc-Budget: decode path now buffers one event\n' > "$R/body.md"; }

# 1. A raise without a statement (local mode) -> blocked, naming key, old and new.
mkrepo
sed -i.bak -e 's/jdk21=83000/jdk21=84000/' "$R/$budget" && rm -f "$R/$budget.bak"
gate "raise, no statement: blocked with key, old and new" 1 \
    "Alloc-Budget: <reason>" \
    "parser-core.untyped.tck-test.jfr.jdk21: 83000 -> 84000" \
    -- --base main

# 2. The same raise with a statement -> passes (CI mode).
raise_body
gate "raise with statement: passes" 0 "" -- --base main --body-file "$R/body.md"
rm -rf "$R" 2>/dev/null || true

# 3. A statement whose reason is empty (spaces only) is not a statement.
mkrepo
sed -i.bak -e 's/jdk21=83000/jdk21=84000/' "$R/$budget" && rm -f "$R/$budget.bak"
printf 'Fix thing.\n\nAlloc-Budget:   \n' > "$R/body.md"
gate "empty-reason statement: blocked" 1 "no Alloc-Budget statement" -- --base main --body-file "$R/body.md"
rm -rf "$R" 2>/dev/null || true

# 4. Lowering a value -> passes (a re-tighten needs no statement).
mkrepo
sed -i.bak -e 's/jdk21=83000/jdk21=82000/' "$R/$budget" && rm -f "$R/$budget.bak"
gate "lowering: passes" 0 "" -- --base main
rm -rf "$R" 2>/dev/null || true

# 5. A new key -> passes (a key absent at base cannot have been raised).
mkrepo
printf 'hdump-parser.parse.a5-instance-fields.jdk21=5000\n' >> "$R/$budget"
gate "new key: passes" 0 "" -- --base main
rm -rf "$R" 2>/dev/null || true

# 6. An untouched file -> passes.
mkrepo
gate "unchanged: passes" 0 "" -- --base main
rm -rf "$R" 2>/dev/null || true

# 7. A budgeted cell changed to excluded: <reason> -> a raise to infinity.
mkrepo
sed -i.bak -e 's/jdk21=83000/jdk21=excluded: flaky on this JDK build/' "$R/$budget" && rm -f "$R/$budget.bak"
gate "numeric to excluded: blocked" 1 \
    "parser-core.untyped.tck-test.jfr.jdk21: 83000 -> excluded: flaky on this JDK build" \
    -- --base main
rm -rf "$R" 2>/dev/null || true

# 8. Raising a .tolerance raises the effective ceiling -> a raise.
mkrepo
sed -i.bak -e 's/tolerance=1000/tolerance=2000/' "$R/$budget" && rm -f "$R/$budget.bak"
gate "tolerance raise: blocked" 1 \
    "parser-core.untyped.tck-test.jfr.jdk21.tolerance: 1000 -> 2000" \
    -- --base main
rm -rf "$R" 2>/dev/null || true

# 9. An unreadable base ref is refused instead of passing as "all keys new" (R3).
mkrepo
gate "bad base: refused, exit 2" 2 "refusing to pass on an unreadable base" -- --base no-such-ref
rm -rf "$R" 2>/dev/null || true

# 10. A budget file absent at base: every key is new -> passes. Distinct from
#     case 9: the ref resolves, the path simply did not exist yet.
rm -rf "$R" 2>/dev/null || true
R=$(mktemp -d)
git -C "$R" init -q -b main
git -C "$R" config tag.gpgSign false
git -C "$R" config commit.gpgSign false
git -C "$R" -c user.email=t@t -c user.name=t commit -q --allow-empty -m base
mkdir -p "$R/gradle"
printf 'parser-core.untyped.tck-test.jfr.jdk21=999999\n' > "$R/$budget"
gate "file absent at base: every key is new" 0 "absent at base" -- --base main
rm -rf "$R" 2>/dev/null || true

# 11. Deleting the budget file cannot un-gate the gate.
mkrepo
rm "$R/$budget"
gate "budget file deleted: refused" 1 "un-gate" -- --base main
rm -rf "$R" 2>/dev/null || true

# 12. --file override: the gate watches the file it is pointed at.
mkrepo
sed -i.bak -e 's/jdk21=83000/jdk21=84000/' "$R/$budget" && rm -f "$R/$budget.bak"
gate "--file override: raise in the named file is blocked" 1 \
    "parser-core.untyped.tck-test.jfr.jdk21: 83000 -> 84000" \
    -- --base main --file "$R/$budget"
rm -rf "$R" 2>/dev/null || true

printf '%s passed, %s failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
