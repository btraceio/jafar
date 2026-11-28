#!/usr/bin/env bash
# typed-parity-check.sh — the blocking gate for the typed-generator parity rule.
#
# The typed parser has two generators of handlers for the same @JfrType interfaces:
#
#   build time  jafar-processor (JfrTypeProcessor + templates/*.java)
#   runtime     parser-codegen's CodeGenerator (+ CodeGeneratorDeserializerFactory,
#               FieldMapping)
#
# They must read a recording into the same values, and do comparable work per value
# (how a reference resolves its pool, how an undeclared field is skipped, what is
# done per event): AGENTS.md, "Keep the two typed handler generators at parity". The
# build-time getters once looked their pool up on every call while the runtime classes
# kept it; same values, about 40% of the build-time throughput on reference-heavy reads.
# A value test cannot see that, so a PR that changes exactly one generator must state
# the outcome for the other in its body:
#
#   Typed-Parity: ported in this PR
#   Typed-Parity: not applicable — <reason>
#
# Test-only changes do not count; the processor's templates are production code.
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

build_re='^jafar-processor/src/main/'
runtime_re='^parser-codegen/src/main/java/io/jafar/parser/internal_api/(CodeGenerator|CodeGeneratorDeserializerFactory|FieldMapping)\.java$'

build_touched=0
runtime_touched=0

classify() { # classify <path>
    local path="$1"
    if [[ "$path" =~ $build_re ]]; then
        build_touched=1
    elif [[ "$path" =~ $runtime_re ]]; then
        runtime_touched=1
    fi
}

if [[ -n "$base" ]]; then
    # A bad ref must fail loudly, not resolve to an empty diff and pass (R3).
    if ! diff_files=$(git diff --name-only "${base}...HEAD" 2>/dev/null); then
        if ! diff_files=$(git diff --name-only "${base}" HEAD); then
            printf 'typed-parity-check: cannot diff against %s — refusing to pass on an unreadable base\n' "${base}" >&2
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

if [[ $build_touched -eq $runtime_touched ]]; then
    echo "typed-parity-check: no one-sided typed-generator change"
    exit 0
fi

# the side that changed; the statement is about the other one
side="build-time (jafar-processor)"
[[ $runtime_touched -eq 1 ]] && side="runtime (parser-codegen CodeGenerator)"

if [[ -n "$base" ]]; then
    printf 'typed-parity-check: this branch changes the typed generator on the %s side only.\n' "$side"
    printf 'Add the statement to the PR body:\n  Typed-Parity: ported in this PR\n  Typed-Parity: not applicable — <reason>\n' >&2
    exit 1
fi

if [[ ! -s "$body" ]] || ! grep -Eq '^[[:space:]]*Typed-Parity:[[:space:]]*[^[:space:]]' "$body"; then
    printf 'typed-parity-check: the PR changes the typed generator on the %s side only,\n' "$side" >&2
    printf 'but its body carries no typed parity statement. Add one line:\n' >&2
    printf '  Typed-Parity: ported in this PR\n  Typed-Parity: not applicable — <reason>\n' >&2
    exit 1
fi

echo "typed-parity-check: one-sided typed-generator change, typed parity statement present"
exit 0
