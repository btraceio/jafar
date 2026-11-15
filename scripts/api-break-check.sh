#!/usr/bin/env bash
# api-break-check.sh — the blocking gate for API evolution (step 1 of the
# generated-code safety harness).
#
# gradle/api-compat.gradle runs japicmp once per gated surface
# (gradle/api-surfaces.txt) and writes the txt/html reports, but its tasks
# never fail on a break: a legitimate API-Break PR must be able to turn
# green. This script is the deciding half. It is a sibling of
# parity-check.sh: same gate pattern, loud failure on an unreadable base,
# hermetic tests in scripts/tests/test_api_break_check.sh, and a
# pull_request-only CI job that reads the PR body via gh.
#
# Marker grammar, derived from the REAL japicmp samples recorded in step 1
# (the T3 base run and its case-(a)..(f) mutation runs):
#   Every line of a txt report starts with a marker token:
#     class headers at column 0:   +++   ---   ***   ===
#     member lines, one tab deep:  the same four markers
#   The marker may carry an incompatibility flag as a 4th character:
#     '!'  binary-incompatible:       "---! REMOVED METHOD", "***! MODIFIED
#          METHOD", "***! MODIFIED CLASS" headers (cases a, b, d, e)
#     '*'  source-incompatible only,  "**** MODIFIED INTERFACE" headers and
#          binary compatible:         "+++* NEW METHOD" lines — a new
#          abstract interface method breaks implementers at recompile time
#          but not at runtime (case f)
#   Plain three-character markers ("***  MODIFIED METHOD", "+++  NEW
#   METHOD", the widening "***  MODIFIED CONSTRUCTOR" of the llm-spi base
#   report) are compatible changes and are ignored.
# A change is incompatible iff its marker token is exactly four characters
# and the fourth is '!' or '*'. Each incompatible change is normalized to
#     <surface>|<nearest preceding class header>|<member line>
# with every field trimmed and the two report lines otherwise verbatim, so
# entries in the accepted-breaks file are byte-identical to what this script
# extracts. A class header that is itself incompatible but has no
# incompatible member line beneath it (a whole removed class, a class made
# final) is kept as <surface>|<header>|<header>.
#
# The ratchet (D5): gradle/api-accepted-breaks.txt can only shrink. An
# accepted entry that no longer matches a report change is stale and fails
# ("delete stale accepted entries"). An entry that is new since --base is
# growth: accepting a new break needs a written reason in the PR body,
#     API-Break: <non-empty reason>
# matched by ^[[:space:]]*API-Break:[[:space:]]*[^[:space:]] — "API-Break:"
# followed by spaces only is not a statement.
#
# Day one: when the accepted-breaks file does not exist at --base (the gate
# is new on this branch), the committed entries ARE the ratchet's starting
# point, not growth — the base-green half of AC-1.7 requires the branch base
# to pass this check. The step-1 PR body still carries an API-Break:
# statement for the day-one list (D5). A file that exists at base but holds
# no entries is NOT day one: adding entries to it is growth.
#
# Inputs (D6):
#   --reports DIR   the japicmp report directory (build/reports/api-compat;
#                   replaces parity-check's --files-from)
#   --base REF      the ratchet reference; an unreadable ref exits 2
#   --body-file B   CI mode: the PR body. Without it the script runs in
#                   local mode and a break fails with "add to the PR body"
#   --surfaces F    the surface table (default gradle/api-surfaces.txt)
#   --accepted F    the accepted-breaks file (default
#                   gradle/api-accepted-breaks.txt)
#
# Exit codes: 0 green; 1 break/stale/growth without a statement, stale
# entry, missing report; 2 unreadable base, missing input file, bad usage.
set -euo pipefail

reports=""
base=""
body=""
surfaces_file="gradle/api-surfaces.txt"
accepted="gradle/api-accepted-breaks.txt"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --reports)   reports="${2:?--reports needs a dir}";        shift 2 ;;
        --base)      base="${2:?--base needs a ref}";             shift 2 ;;
        --body-file) body="${2:?--body-file needs a path}";        shift 2 ;;
        --surfaces)  surfaces_file="${2:?--surfaces needs a path}"; shift 2 ;;
        --accepted)  accepted="${2:?--accepted needs a path}";     shift 2 ;;
        *) printf 'Unknown argument: %s\n' "$1" >&2; exit 2 ;;
    esac
done

if [[ -z "$reports" || -z "$base" ]]; then
    printf 'usage: %s --reports DIR --base REF [--body-file B] [--surfaces F] [--accepted F]\n' "$0" >&2
    exit 2
fi

# Step 1: a bad base must fail loudly, never resolve to an empty comparison
# and pass (R3).
if ! git rev-parse --verify --quiet "${base}^{commit}" >/dev/null 2>&1; then
    printf 'api-break-check: refusing to pass on an unreadable base: %s\n' "$base" >&2
    exit 2
fi

[[ -s "$surfaces_file" ]] || {
    printf 'api-break-check: surface table not found: %s\n' "$surfaces_file" >&2
    exit 2
}
[[ -s "$accepted" ]] || {
    printf 'api-break-check: accepted-breaks file not found: %s\n' "$accepted" >&2
    exit 2
}

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

# Step 2: every surface in the table needs a non-empty report; a missing one
# means the apiCompat task was skipped or not run (guards AC-1.3).
surfaces=$(awk '!/^[[:space:]]*#/ && NF { print $1 }' "$surfaces_file")
[[ -n "$surfaces" ]] || {
    printf 'api-break-check: no surfaces listed in %s\n' "$surfaces_file" >&2
    exit 2
}
for s in $surfaces; do
    if [[ ! -s "$reports/$s.txt" ]]; then
        printf 'api-break-check: missing report for %s (task skipped or not run): %s\n' \
            "$s" "$reports/$s.txt" >&2
        exit 1
    fi
done

# Step 3: extract incompatible changes (grammar in the header comment).
extract() { # extract <surface> <report> — one normalized change per line
    awk -v surface="$1" '
        function emit(h, m) {
            sub(/[ \t]+$/, "", h)
            sub(/[ \t]+$/, "", m)
            print surface "|" h "|" m
        }
        # class header at column 0 (the "Comparing ..." and "No changes."
        # prologue lines land here too; they never carry a bad marker)
        /^[^\t ]/ {
            if (header != "" && header_bad && !member_bad)
                emit(header, header)
            header = $0
            header_bad = ($1 ~ /^(\+\+\+|---|\*\*\*|===)[!*]$/)
            member_bad = 0
            next
        }
        # member line, one tab deep
        $1 ~ /^(\+\+\+|---|\*\*\*|===)[!*]$/ {
            member = $0
            sub(/^[ \t]+/, "", member)
            emit(header, member)
            member_bad = 1
        }
        END {
            if (header != "" && header_bad && !member_bad)
                emit(header, header)
        }
    ' "$2"
}

: > "$tmp/changes"
for s in $surfaces; do
    extract "$s" "$reports/$s.txt" >> "$tmp/changes"
done

# Step 4: read the accepted file — '#' comments (including the mandatory
# "# reason:" lines), one entry per line — and subtract it.
entries() { # entries <file> — the entry lines, trimmed
    sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' \
        -e '/^#/d' -e '/^$/d' "$1"
}

entries "$accepted" > "$tmp/accepted"

export LC_ALL=C
sort -o "$tmp/changes.sorted" "$tmp/changes"
sort -o "$tmp/accepted.sorted" "$tmp/accepted"
remaining=$(comm -23 "$tmp/changes.sorted" "$tmp/accepted.sorted")
stale=$(comm -13 "$tmp/changes.sorted" "$tmp/accepted.sorted")

# Step 5: the accepted list at --base. A path absent at base is day one
# (see header); a present file gives the list to grow against.
growth=""
root=$(git rev-parse --show-toplevel)
# git's toplevel is a physical path; resolve the accepted file physically
# too — on macOS TMPDIR sits behind the /var -> /private/var symlink, which
# a logical pwd would leave unresolved and the prefix match below would
# wrongly report the file as outside the repository.
accepted_abs="$(cd "$(dirname "$accepted")" && pwd -P)/$(basename "$accepted")"
case "$accepted_abs" in
    "$root"/*)
        accepted_rel=${accepted_abs#"$root"/}
        if git cat-file -e "${base}:${accepted_rel}" 2>/dev/null; then
            git show "${base}:${accepted_rel}" > "$tmp/base.raw"
            entries "$tmp/base.raw" > "$tmp/base"
            sort -o "$tmp/base.sorted" "$tmp/base"
            growth=$(comm -23 "$tmp/accepted.sorted" "$tmp/base.sorted")
        else
            echo "api-break-check: accepted-breaks file absent at ${base} — day one, the committed entries are the ratchet's starting point, not growth"
        fi
        ;;
    *)
        printf 'api-break-check: %s is outside the repository — cannot read it at %s, growth check skipped\n' \
            "$accepted" "$base" >&2
        ;;
esac

# An accepted entry that matches nothing is stale: never excused by a
# statement, fixed only by deleting it (D5).
if [[ -n "$stale" ]]; then
    printf 'api-break-check: accepted entries with no matching break — delete stale accepted entries:\n' >&2
    while IFS= read -r e; do printf '  %s\n' "$e" >&2; done <<<"$stale"
    exit 1
fi

# Steps 6–8: remaining breaks or growth need the statement.
if [[ -z "$remaining" && -z "$growth" ]]; then
    echo "api-break-check: no API breaks beyond the accepted baseline"
    exit 0
fi

if [[ -n "$remaining" ]]; then
    printf 'api-break-check: incompatible changes not accepted in %s:\n' "$accepted" >&2
    while IFS= read -r e; do printf '  %s\n' "$e" >&2; done <<<"$remaining"
fi
if [[ -n "$growth" ]]; then
    printf 'api-break-check: accepted-breaks entries new since %s (growth):\n' "$base" >&2
    while IFS= read -r e; do printf '  %s\n' "$e" >&2; done <<<"$growth"
fi

if [[ -z "$body" ]]; then
    printf 'add to the PR body: API-Break: <reason>\n' >&2
    exit 1
fi

if [[ ! -s "$body" ]] || ! grep -Eq '^[[:space:]]*API-Break:[[:space:]]*[^[:space:]]' "$body"; then
    printf 'api-break-check: API breaks are reported but the PR body carries no API-Break statement. Add one line:\n' >&2
    printf '  API-Break: <reason>\n' >&2
    exit 1
fi

echo "api-break-check: API breaks reported, API-Break statement present"
exit 0
