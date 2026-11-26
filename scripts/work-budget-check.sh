#!/usr/bin/env bash
# work-budget-check.sh — the blocking statement gate of the work-budget ratchet.
#
# gradle/work-budgets.properties records, per parse entry point and fixture, the
# exact counts of the work one parse does (<module>.<entry>.<fixture>.<counter>=<n>;
# see test-support WorkBudgets and doc/plans/work-budgets.md). The workTest tasks
# fail on any count that differs from the file, in either direction, so the file
# always holds the current counts. This gate decides which edits to it need a
# reason in the PR body:
#
#   Work-Budget: <non-empty reason>
#
# Needs the statement:
#   - raising a value: the parse now does more of some work;
#   - adding a key with a nonzero value to an entry point that already has keys at
#     base: a new counter that starts above zero is more work counted for an
#     existing parse, and is reviewed like a raise.
# Free:
#   - lowering a value (the parse does less);
#   - adding a new entry point (all its keys are new);
#   - adding a key with value 0;
#   - leaving the file unchanged.
#
# A counter is always two words (events.decoded), so a key's entry point is the
# key without its last two dot-separated words (WorkProbesTest holds the counter
# names to that).
#
# Inputs:
#   --base REF      the ref the budget file is compared against (required)
#   --body-file B   the PR body carrying the statement (CI mode); without it the
#                   gate runs in local mode and any raise fails with
#                   "add Work-Budget: <reason>" — which is the point
#   --file F        the budget file; default gradle/work-budgets.properties. A
#                   relative path is resolved against the repository root, and the
#                   file must live inside the repository (the base copy is read
#                   from git).
#
# Exit codes: 0 pass; 1 a raise without a statement (or a deleted budget file);
# 2 bad usage or an unreadable base ref (R3: a bad ref must fail loudly, never
# resolve to an empty comparison and pass).
set -euo pipefail

base=""
body=""
file="gradle/work-budgets.properties"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --base)      base="${2:?--base needs a ref}";       shift 2 ;;
        --body-file) body="${2:?--body-file needs a path}"; shift 2 ;;
        --file)      file="${2:?--file needs a path}";       shift 2 ;;
        *) printf 'Unknown argument: %s\n' "$1" >&2; exit 2 ;;
    esac
done

if [[ -z "$base" ]]; then
    printf 'usage: %s --base REF [--body-file B] [--file gradle/work-budgets.properties]\n' "$0" >&2
    exit 2
fi

# An unreadable base must fail loudly (R3): treating it as "no budgets at base"
# would let every raise pass as a new entry point.
if ! git rev-parse --verify --quiet "${base}^{commit}" >/dev/null 2>&1; then
    printf 'work-budget-check: cannot resolve base %s — refusing to pass on an unreadable base\n' "$base" >&2
    exit 2
fi

toplevel=$(git rev-parse --show-toplevel)

# Canonicalize a user-supplied absolute path (macOS /var -> /private/var), as
# alloc-budget-check.sh does, so an inside-repo file never looks outside the repo.
if [[ "$file" = /* ]]; then
    if ! file_dir=$(cd "$(dirname "$file")" 2>/dev/null && pwd -P); then
        printf 'work-budget-check: cannot resolve --file %s\n' "$file" >&2
        exit 2
    fi
    abs="$file_dir/$(basename "$file")"
else
    abs="$toplevel/$file"
fi
if [[ "$abs" != "$toplevel"/* ]]; then
    printf 'work-budget-check: --file %s is not inside the repository\n' "$file" >&2
    exit 2
fi
rel="${abs#"$toplevel"/}"

tmp=$(mktemp -d "${TMPDIR:-/tmp}/work-budget-check.XXXXXX")
trap 'rm -rf "$tmp"' EXIT

# A budget file absent at base: every entry point is new, so nothing can have
# been raised. Deliberately distinct from the bad-ref case above.
if ! git show "${base}:${rel}" > "$tmp/old.raw" 2>/dev/null; then
    printf 'work-budget-check: %s absent at base %s — every entry point is new\n' "$rel" "$base"
    exit 0
fi

if [[ ! -f "$abs" ]]; then
    printf 'work-budget-check: %s is absent from the working tree.\n' "$rel" >&2
    printf 'Deleting the budget file would un-gate every entry point — refusing.\n' >&2
    exit 1
fi

tab=$(printf '\t')

extract() { # extract <props-file> -> "key<TAB>value" lines, comments and blanks dropped
    awk -F= '($1 ~ /^[A-Za-z0-9._-]+$/) { v = substr($0, length($1) + 2); gsub(/^[ \t]+|[ \t]+$/, "", v); print $1 "\t" v }' "$1" \
        | LC_ALL=C sort
}
extract "$tmp/old.raw" > "$tmp/old"
extract "$abs"         > "$tmp/new"

# The entry points budgeted at base: each key without its last two words.
cut -f1 "$tmp/old" | sed -E 's/\.[^.]+\.[^.]+$//' | LC_ALL=C sort -u > "$tmp/old-entries"

raises="$tmp/raises"; : > "$raises"

# Keys at both: a raise is a larger count. A value the gate cannot read as a
# count is reported as a raise: it can only un-gate the key (WorkBudgets fails
# on it, but the gate does not rely on that).
LC_ALL=C join -t "$tab" "$tmp/old" "$tmp/new" | while IFS="$tab" read -r key o n; do
    [ "$o" = "$n" ] && continue
    case "$o" in ''|*[!0-9]*) continue ;; esac
    case "$n" in
        ''|*[!0-9]*) printf '%s: %s -> %s (not a count)\n' "$key" "$o" "$n" >> "$raises" ;;
        *) if [ "$n" -gt "$o" ]; then printf '%s: %s -> %s\n' "$key" "$o" "$n" >> "$raises"; fi ;;
    esac
done

# Keys new to the branch: nonzero, on an entry point that already had keys.
LC_ALL=C join -t "$tab" -v 2 "$tmp/old" "$tmp/new" | while IFS="$tab" read -r key n; do
    entry=$(printf '%s' "$key" | sed -E 's/\.[^.]+\.[^.]+$//')
    grep -qxF "$entry" "$tmp/old-entries" || continue
    [ "$n" = "0" ] && continue
    printf '%s: new key = %s on existing entry point %s\n' "$key" "$n" "$entry" >> "$raises"
done

if [[ ! -s "$raises" ]]; then
    echo "work-budget-check: no work budget raised"
    exit 0
fi

if [[ -n "$body" ]]; then
    if [[ -s "$body" ]] && grep -Eq '^[[:space:]]*Work-Budget:[[:space:]]*[^[:space:]]' "$body"; then
        printf 'work-budget-check: work budget raised, statement present:\n'
        sed 's/^/  /' "$raises"
        exit 0
    fi
    printf 'work-budget-check: this branch raises work budgets:\n' >&2
    sed 's/^/  /' "$raises" >&2
    printf 'The PR body carries no Work-Budget statement. Add one line:\n' >&2
    printf '  Work-Budget: <reason>\n' >&2
    exit 1
fi

printf 'work-budget-check: this branch raises work budgets:\n' >&2
sed 's/^/  /' "$raises" >&2
printf 'Add to the PR body:\n' >&2
printf '  Work-Budget: <reason>\n' >&2
exit 1
