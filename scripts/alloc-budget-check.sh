#!/usr/bin/env bash
# alloc-budget-check.sh — the blocking gate for the allocation-budget ratchet.
#
# gradle/allocation-budgets.properties budgets parsed bytes per entry point,
# fixture and JDK major (plus <key>.tolerance=... and excluded: <reason> cells;
# see test-support AllocationBudgets for the format). The file is a ratchet:
# lowering a value, adding a key or leaving it unchanged is free, but raising a
# budget — including a tolerance, which raises the effective ceiling — or
# excluding a cell that used to be budgeted must be deliberate and visible, with
# a reason in the PR body:
#
#   Alloc-Budget: <non-empty reason>
#
# (kb/design-allocation item 2, kb/design-principles rule 2: the harness cannot
# demand taste, it can demand a written reason at the exact moment the invariant
# bends.)
#
# Inputs:
#   --base REF      the ref the budget file is compared against (required)
#   --body-file B   the PR body carrying the statement (CI mode); without it the
#                   gate runs in local mode and any raise fails with
#                   "add Alloc-Budget: <reason>" — which is the point
#   --file F        the budget file; default gradle/allocation-budgets.properties.
#                   A relative path is resolved against the repository root, and
#                   the file must live inside the repository (the base copy is
#                   read from git).
#
# Only keys present at BOTH base and the working tree are compared: a key new to
# the branch cannot have been raised. A budget file absent at base means every
# key is new — that is distinct from an unreadable base, which refuses to run.
#
# Exit codes: 0 pass; 1 a raise without a statement (or a deleted budget file);
# 2 bad usage or an unreadable base ref (R3: a bad ref must fail loudly, never
# resolve to an empty comparison and pass).
set -euo pipefail

base=""
body=""
file="gradle/allocation-budgets.properties"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --base)      base="${2:?--base needs a ref}";       shift 2 ;;
        --body-file) body="${2:?--body-file needs a path}"; shift 2 ;;
        --file)      file="${2:?--file needs a path}";       shift 2 ;;
        *) printf 'Unknown argument: %s\n' "$1" >&2; exit 2 ;;
    esac
done

if [[ -z "$base" ]]; then
    printf 'usage: %s --base REF [--body-file B] [--file gradle/allocation-budgets.properties]\n' "$0" >&2
    exit 2
fi

# An unreadable base must fail loudly (R3): treating it as "no budgets at base"
# would let every raise pass as a new key.
if ! git rev-parse --verify --quiet "${base}^{commit}" >/dev/null 2>&1; then
    printf 'alloc-budget-check: cannot resolve base %s — refusing to pass on an unreadable base\n' "$base" >&2
    exit 2
fi

toplevel=$(git rev-parse --show-toplevel)

# A user-supplied absolute path may cross a symlink (macOS /var ->
# /private/var) while `git rev-parse --show-toplevel` is physical; canonicalize
# so an inside-repo file never looks outside the repo.
if [[ "$file" = /* ]]; then
    if ! file_dir=$(cd "$(dirname "$file")" 2>/dev/null && pwd -P); then
        printf 'alloc-budget-check: cannot resolve --file %s\n' "$file" >&2
        exit 2
    fi
    abs="$file_dir/$(basename "$file")"
else
    abs="$toplevel/$file"
fi
if [[ "$abs" != "$toplevel"/* ]]; then
    printf 'alloc-budget-check: --file %s is not inside the repository\n' "$file" >&2
    exit 2
fi
rel="${abs#"$toplevel"/}"

tmp=$(mktemp -d "${TMPDIR:-/tmp}/alloc-budget-check.XXXXXX")
trap 'rm -rf "$tmp"' EXIT

# A budget file absent at base: every current key is new, so nothing can have
# been raised. Deliberately distinct from the bad-ref case above.
if ! git show "${base}:${rel}" > "$tmp/old.raw" 2>/dev/null; then
    printf 'alloc-budget-check: %s absent at base %s — every key is new\n' "$rel" "$base"
    exit 0
fi

if [[ ! -f "$abs" ]]; then
    printf 'alloc-budget-check: %s is absent from the working tree.\n' "$rel" >&2
    printf 'Deleting the budget file would un-gate every key — refusing. Restore it\n' >&2
    printf '(re-added keys count as new, but the file must exist for the gate to see them).\n' >&2
    exit 1
fi

tab=$(printf '\t')

extract() { # extract <props-file> -> "key<TAB>value" lines, comments and blanks dropped
    # Keys are [A-Za-z0-9._-]+. The value is everything after the first '=' so
    # the reason text in "excluded: <reason>" survives intact.
    awk -F= '($1 ~ /^[A-Za-z0-9._-]+$/) { print $1 "\t" substr($0, length($1) + 2) }' "$1" | LC_ALL=C sort
}
extract "$tmp/old.raw" > "$tmp/old"
extract "$abs"         > "$tmp/new"

raises="$tmp/raises"; : > "$raises"
LC_ALL=C join -t "$tab" "$tmp/old" "$tmp/new" | while IFS="$tab" read -r key o n; do
    [ "$o" = "$n" ] && continue
    case "$o" in
        # An old value that is not numeric (excluded cell, malformed) can only
        # be re-tightened or reworded, never raised.
        ''|*[!0-9]*) continue ;;
    esac
    case "$n" in
        excluded*)
            # Numeric -> excluded removes a budget: that is a raise to infinity.
            printf '%s: %s -> %s (budgeted cell excluded)\n' "$key" "$o" "$n" >> "$raises" ;;
        ''|*[!0-9]*) ;; # a non-numeric replacement the gate does not model: not a raise
        *)
            if [ "$n" -gt "$o" ]; then
                printf '%s: %s -> %s\n' "$key" "$o" "$n" >> "$raises"
            fi ;;
    esac
done

if [[ ! -s "$raises" ]]; then
    echo "alloc-budget-check: no allocation budget raised"
    exit 0
fi

if [[ -n "$body" ]]; then
    if [[ -s "$body" ]] && grep -Eq '^[[:space:]]*Alloc-Budget:[[:space:]]*[^[:space:]]' "$body"; then
        printf 'alloc-budget-check: allocation budget raised, statement present:\n'
        sed 's/^/  /' "$raises"
        exit 0
    fi
    printf 'alloc-budget-check: this branch raises allocation budgets:\n' >&2
    sed 's/^/  /' "$raises" >&2
    printf 'The PR body carries no Alloc-Budget statement. Add one line:\n' >&2
    printf '  Alloc-Budget: <reason>\n' >&2
    exit 1
fi

printf 'alloc-budget-check: this branch raises allocation budgets:\n' >&2
sed 's/^/  /' "$raises" >&2
printf 'Add to the PR body:\n' >&2
printf '  Alloc-Budget: <reason>\n' >&2
exit 1
