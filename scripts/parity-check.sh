#!/usr/bin/env bash
# parity-check.sh — the blocking gate for the Java/Go untyped-parser parity rule.
#
# The Java untyped parser (parser-core/src/main/java/io/jafar/parser/{impl,internal_api})
# and the Go port (go-parser/jfr) are two implementations of the same format and the
# same value model. A PR that changes production code on exactly one side must state
# the outcome for the other side in its body:
#
#   Parity: ported in this PR
#   Parity: not applicable — <reason>
#
# (name the direction: Java → Go or Go → Java). The obligation is symmetric.
#
# Test-only changes, fixtures and go-parser testdata do not count; parser-codegen
# output has no Go counterpart and is not watched here either.
#
# Inputs:
#   --files-from F --body-file B   explicit list of changed paths + the PR body
#   --base REF                     local mode: changed paths from git diff REF...HEAD;
#                                  no PR body exists locally, so a one-sided change
#                                  fails with "add the statement" — which is the point
set -euo pipefail

files=""
body=""
base=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --files-from) files="${2:?--files-from needs a path}"; shift 2 ;;
        --body-file)  body="${2:?--body-file needs a path}";  shift 2 ;;
        --base)       base="${2:?--base needs a ref}";        shift 2 ;;
        *) printf 'Unknown argument: %s\n' "$1" >&2; exit 2 ;;
    esac
done

if [[ -z "$files" && -z "$base" ]]; then
    printf 'usage: %s --files-from F --body-file B  |  %s --base REF\n' "$0" "$0" >&2
    exit 2
fi

java_re='^parser-core/src/main/java/io/jafar/parser/(impl|internal_api)/'
go_re='^go-parser/jfr/'

java_touched=0
go_touched=0

classify() { # classify <path>
    local path="$1"
    if [[ "$path" =~ $java_re ]]; then
        java_touched=1
    elif [[ "$path" =~ $go_re && ! "$path" =~ _test\.go$ && ! "$path" =~ /testdata/ ]]; then
        go_touched=1
    fi
}

if [[ -n "$base" ]]; then
    # A bad ref must fail loudly, not resolve to an empty diff and pass — that
    # would be R3's fallback hiding a misconfiguration.
    if ! diff_files=$(git diff --name-only "${base}...HEAD" 2>/dev/null); then
        if ! diff_files=$(git diff --name-only "${base}" HEAD); then
            printf 'parity-check: cannot diff against %s — refusing to pass on an unreadable base\n' "${base}" >&2
            git diff --name-only "${base}" HEAD 2>&1 | sed 's/^/  /' >&2 || true
            exit 2
        fi
    fi
    while IFS= read -r path; do
        [[ -n "$path" ]] && classify "$path"
    done <<<"$diff_files"
else
    while IFS= read -r path; do
        [[ -n "$path" ]] && classify "$path"
    done < "$files"
fi

if [[ $java_touched -eq $go_touched ]]; then
    # both sides changed (statement still welcome), or neither is a parser change
    echo "parity-check: no one-sided untyped-parser change"
    exit 0
fi

# Exactly one of the two flags is set here; name the side that changed.
side="parser-core (Java)"
[[ $go_touched -eq 1 ]] && side="go-parser (Go)"

if [[ -n "$base" ]]; then
    printf 'parity-check: this branch changes the untyped parser on the %s side only.\n' "$side"
    printf 'Add the statement to the PR body:\n  Parity: ported in this PR\n  Parity: not applicable — <reason>\n' >&2
    exit 1
fi

if [[ ! -s "$body" ]] || ! grep -Eq '^[[:space:]]*Parity:[[:space:]]*[^[:space:]]' "$body"; then
    printf 'parity-check: the PR changes the untyped parser on the %s side only,\n' "$side" >&2
    printf 'but its body carries no parity statement. Add one line:\n' >&2
    printf '  Parity: ported in this PR\n  Parity: not applicable — <reason>\n' >&2
    exit 1
fi

echo "parity-check: one-sided untyped-parser change, parity statement present"
exit 0
