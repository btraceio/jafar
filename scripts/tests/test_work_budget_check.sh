#!/usr/bin/env bash
# test_work_budget_check.sh — hermetic tests for scripts/work-budget-check.sh (R5 on the gate).
#
# Proves the ratchet and only the ratchet: raising a count, or adding a nonzero
# key to an entry point that already has keys, without a Work-Budget statement is
# blocked and names key, old and new; the same edits pass with a statement;
# lowering, a new entry point, a new zero key, an unchanged file and a file absent
# at base pass; an unreadable base and a deleted file are refused (R3). All cases
# run in scratch git repos.
set -euo pipefail

repo="$(cd "$(dirname "$0")/../.." && pwd)"
script="$repo/scripts/work-budget-check.sh"

pass=0
fail=0
R=""

trap 'rm -rf "$R" 2>/dev/null || true' EXIT

# gate <description> <expected-exit> [required-output-substrings ...] -- <script args...>
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

budget="gradle/work-budgets.properties"

mkrepo() { # scratch repo with a committed budget file the cases then edit
    rm -rf "$R" 2>/dev/null || true
    R=$(mktemp -d)
    git -C "$R" init -q -b main
    # unsigned commits in the hermetic repo (see test_alloc_budget_check.sh)
    git -C "$R" config tag.gpgSign false
    git -C "$R" config commit.gpgSign false
    mkdir -p "$R/gradle"
    cat > "$R/$budget" <<'EOF'
# work budgets
parser-core.untyped.tck-test.jfr.events.decoded=67657
parser-core.untyped.tck-test.jfr.varints.read=811261
parser-core.untyped-filtered.tck-test.jfr.events.decoded=1892
EOF
    git -C "$R" add -A
    git -C "$R" -c user.email=t@t -c user.name=t commit -q -m base
}

edit() { sed -i.bak -e "$1" "$R/$budget" && rm -f "$R/$budget.bak"; }

# 1. Fire: a raise without a statement (local mode) -> blocked, naming key, old and new.
mkrepo
edit 's/filtered.tck-test.jfr.events.decoded=1892/filtered.tck-test.jfr.events.decoded=67657/'
gate "raise, no statement: blocked with key, old and new" 1 \
    "Work-Budget: <reason>" \
    "parser-core.untyped-filtered.tck-test.jfr.events.decoded: 1892 -> 67657" \
    -- --base main

# 2. The same raise with a statement -> passes (CI mode).
printf 'Decode filtered events.\n\nWork-Budget: the filter now needs the payload\n' > "$R/body.md"
gate "raise with statement: passes" 0 "statement present" -- --base main --body-file "$R/body.md"

# 3. Fire: the same raise, CI mode, body without a statement -> blocked.
printf 'Decode filtered events.\n' > "$R/body.md"
gate "raise, body without statement: blocked" 1 "no Work-Budget statement" -- --base main --body-file "$R/body.md"

# 4. Fire: a statement whose reason is empty (spaces only) is not a statement.
printf 'Fix thing.\n\nWork-Budget:   \n' > "$R/body.md"
gate "empty-reason statement: blocked" 1 "no Work-Budget statement" -- --base main --body-file "$R/body.md"

# 5. Fire: a new nonzero key on an entry point budgeted at base -> blocked.
mkrepo
printf 'parser-core.untyped.tck-test.jfr.bytes.read=2318517\n' >> "$R/$budget"
gate "new nonzero key on existing entry point: blocked" 1 \
    "parser-core.untyped.tck-test.jfr.bytes.read: new key = 2318517 on existing entry point parser-core.untyped.tck-test.jfr" \
    -- --base main

# 6. A new zero key on an existing entry point -> passes (nothing counted yet).
mkrepo
printf 'parser-core.untyped.tck-test.jfr.bytes.read=0\n' >> "$R/$budget"
gate "new zero key on existing entry point: passes" 0 "no work budget raised" -- --base main

# 7. A new entry point, nonzero keys included -> passes.
mkrepo
printf 'parser-codegen.typed.tck-test.jfr.events.decoded=1892\nparser-codegen.typed.tck-test.jfr.varints.read=333709\n' >> "$R/$budget"
gate "new entry point: passes" 0 "no work budget raised" -- --base main

# 8. Lowering a value -> passes.
mkrepo
edit 's/varints.read=811261/varints.read=811000/'
gate "lowering: passes" 0 "no work budget raised" -- --base main

# 9. Unchanged -> passes.
mkrepo
gate "unchanged: passes" 0 "no work budget raised" -- --base main

# 10. Fire: a value replaced by something that is not a count -> blocked.
mkrepo
edit 's/varints.read=811261/varints.read=lots/'
gate "non-count value: blocked" 1 \
    "parser-core.untyped.tck-test.jfr.varints.read: 811261 -> lots (not a count)" \
    -- --base main

# 11. Fire: one raise among lowerings is still a raise, and only it is listed.
mkrepo
edit 's/varints.read=811261/varints.read=811262/; s/untyped.tck-test.jfr.events.decoded=67657/untyped.tck-test.jfr.events.decoded=60000/'
gate "raise among lowerings: blocked" 1 \
    "parser-core.untyped.tck-test.jfr.varints.read: 811261 -> 811262" \
    -- --base main

# 12. An unreadable base ref is refused, not passed as "all entry points new" (R3).
mkrepo
gate "bad base: refused, exit 2" 2 "refusing to pass on an unreadable base" -- --base no-such-ref

# 13. A budget file absent at base: every entry point is new -> passes.
rm -rf "$R" 2>/dev/null || true
R=$(mktemp -d)
git -C "$R" init -q -b main
git -C "$R" config tag.gpgSign false
git -C "$R" config commit.gpgSign false
git -C "$R" -c user.email=t@t -c user.name=t commit -q --allow-empty -m base
mkdir -p "$R/gradle"
printf 'parser-core.untyped.tck-test.jfr.events.decoded=999999\n' > "$R/$budget"
gate "file absent at base: every entry point is new" 0 "absent at base" -- --base main

# 14. Fire: deleting the budget file cannot un-gate the gate.
mkrepo
rm "$R/$budget"
gate "budget file deleted: refused" 1 "un-gate" -- --base main

# 15. --file override: the gate watches the file it is pointed at.
mkrepo
edit 's/varints.read=811261/varints.read=900000/'
gate "--file override: raise in the named file is blocked" 1 \
    "parser-core.untyped.tck-test.jfr.varints.read: 811261 -> 900000" \
    -- --base main --file "$R/$budget"

printf '%s passed, %s failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
