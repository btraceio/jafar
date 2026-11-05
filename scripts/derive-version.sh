#!/usr/bin/env bash
set -euo pipefail

# ---------------------------------------------------------------------------
# derive-version.sh — the shell-side mirror of gradle/version-from-tag.gradle
#
# Prints the version derived from git release tags, per version plane:
#
#   exactly on the plane's tag     -> X.Y.Z            (release build)
#   elsewhere, tags exist          -> X.Y.Z-SNAPSHOT   (development build)
#   no tags for the plane          -> falls back to the newest bare vX.Y.Z tag
#                                     (also -SNAPSHOT), or 0.0.0-SNAPSHOT
#
# Modes besides the default (all take --plane P):
#   --newest        -> the newest plane version, X.Y.Z (0.0.0 when none)
#   --baseline      -> the API-baseline version, X.Y.Z
#   --baseline-tag  -> the API-baseline tag name (vX.Y.Z or <plane>/vX.Y.Z)
#
# The baseline is what the versioned artifact is compared against, so it is
# the *previous* release, never the one being made: the newest well-formed
# plane tag anywhere in the repo, EXCLUDING the tag HEAD sits exactly on.
# A plane with no qualifying tag of its own rides the newest bare vX.Y.Z
# (same fallback as --newest). Non-conforming tags (rc, untagged-*) are
# rejected through plane_release_re. When nothing qualifies, the mode fails
# with "no baseline tag for plane P" on stderr and exit 1 — it never prints
# 0.0.0, which would silently compare against a nonexistent release (R3).
# scripts/api-baseline.sh is the consumer.
#
# Planes (tag shape -> plane name):
#   vX.Y.Z                -> shell          (the project's original line)
#   core/vX.Y.Z           -> core           (parser, tools, gradle plugin; also
#                                            go-parser/vX.Y.Z at the same commit)
#   mcp/vX.Y.Z            -> mcp            (jfr-mcp)
#   llm-anthropic/vX.Y.Z  -> llm-anthropic
#   llm-openai/vX.Y.Z     -> llm-openai
#
# The newest plane tag is the newest tag of that shape anywhere in the repo
# (not just one reachable from HEAD): plane tags may sit on release/X.Y._
# branches that main does not contain.
#
# gradle/version-from-tag.gradle is the authority for Gradle builds; this script
# must implement the same derivation for the workflows and scripts/release.sh.
# See RELEASING.md ("Version planes").
# ---------------------------------------------------------------------------

VERSION_PART='[0-9]+\.[0-9]+\.[0-9]+'
PLAIN_RELEASE_RE="^v${VERSION_PART}\$"

plane_for() {
    case "$1" in
        shell|core|mcp|llm-anthropic|llm-openai) return 0 ;;
        *) printf 'Unknown plane: %s (expected shell, core, mcp, llm-anthropic or llm-openai)\n' "$1" >&2; return 1 ;;
    esac
}

# The tag name for the plane is vX.Y.Z (shell) or <plane>/vX.Y.Z; the regex is
# built from the whitelisted plane name, never from free text.
plane_release_re() {
    if [[ "$1" == "shell" ]]; then
        printf '%s\n' "$PLAIN_RELEASE_RE"
    else
        printf '%s\n' "^$1/v${VERSION_PART}\$"
    fi
}

# Read tag names from stdin, newest first. Print the first tag conforming to
# the plane's shape; empty when none matches.
first_release_tag() {
    PLANE_TAG_RE=$(plane_release_re "$PLANE")
    while IFS= read -r candidate; do
        [[ -n "$candidate" ]] || continue
        if [[ "$candidate" =~ $PLANE_TAG_RE ]]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done
    return 1
}

# vX.Y.Z or <plane>/vX.Y.Z -> X.Y.Z
strip_plane() {
    local last="${1##*/}"
    printf '%s\n' "${last#v}"
}

# newest_plane_tag: the newest well-formed tag of the plane's shape anywhere in
# the repo (without any prefix, or 0.0.0 when none exists). Sorted with sort -V:
# git's -v:refname sort treats prefixed tags as versionless strings and returns
# arbitrary order for them.
newest_plane_tag() {
    local glob='refs/tags/v[0-9]*.[0-9]*.[0-9]*'
    [[ "$PLANE" != "shell" ]] && glob="refs/tags/${PLANE}/v[0-9]*.[0-9]*.[0-9]*"
    local newest
    local re=$(plane_release_re "$PLANE")
    newest=$(git for-each-ref "$glob" --format='%(refname:short)' \
                 | grep -E "$re" | sort -rV | head -1 || true)
    if [[ -z "$newest" && "$PLANE" != "shell" ]]; then
        # Fallback: a plane with no tags of its own rides the shared line's
        # newest number, same as the gradle derivation
        newest=$(git for-each-ref 'refs/tags/v[0-9]*.[0-9]*.[0-9]*' --format='%(refname:short)' \
                     | grep -E "$PLAIN_RELEASE_RE" | sort -rV | head -1 || true)
    fi
    if [[ -n "$newest" ]]; then
        strip_plane "$newest"
    else
        printf '0.0.0\n'
    fi
}

# newest_qualifying_tag <re> <glob> <excluded>: print the newest tag (sort
# -rV) among <glob> whose name matches <re>, skipping every tag listed in
# <excluded> (newline-separated; the tags HEAD sits exactly on). Returns 1
# when none matches.
newest_qualifying_tag() {
    local re="$1" glob="$2" excluded="$3" candidate
    while IFS= read -r candidate; do
        [[ -n "$candidate" ]] || continue
        if [[ -n "$excluded" ]] && grep -qxF -- "$candidate" <<<"$excluded"; then
            continue
        fi
        printf '%s\n' "$candidate"
        return 0
    done < <(git for-each-ref "$glob" --format='%(refname:short)' \
                 | grep -E "$re" | sort -rV)
    return 1
}

# baseline_tag: print the name of the API-baseline tag for $PLANE — the
# newest well-formed plane tag anywhere in the repo, excluding the tag HEAD
# sits exactly on, so a release commit's baseline is the previous release.
# A plane with no qualifying tag of its own rides the newest bare vX.Y.Z
# (the same fallback as newest_plane_tag; for the shell plane the bare shape
# IS the plane shape, so the exact-tag exclusion keeps applying there too,
# otherwise the fallback would undo it). Fails loudly when nothing
# qualifies; never falls back to 0.0.0 (R3). See the header comment.
baseline_tag() {
    # every tag pointing exactly at HEAD; none of them may be the baseline
    local excluded
    excluded=$(git tag --points-at HEAD 2>/dev/null || true)

    local glob='refs/tags/v[0-9]*.[0-9]*.[0-9]*'
    [[ "$PLANE" != "shell" ]] && glob="refs/tags/${PLANE}/v[0-9]*.[0-9]*.[0-9]*"
    if newest_qualifying_tag "$(plane_release_re "$PLANE")" "$glob" "$excluded"; then
        return 0
    fi

    local fallback_excluded=""
    [[ "$PLANE" == "shell" ]] && fallback_excluded="$excluded"
    if newest_qualifying_tag "$PLAIN_RELEASE_RE" \
            'refs/tags/v[0-9]*.[0-9]*.[0-9]*' "$fallback_excluded"; then
        return 0
    fi

    printf 'no baseline tag for plane %s\n' "$PLANE" >&2
    return 1
}

PLANE="shell"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --plane) plane_for "${2:?--plane requires a plane name}"; PLANE="$2"; shift 2 ;;
        --newest)
            newest_plane_tag
            exit 0
            ;;
        --baseline-tag)
            baseline_tag
            exit 0
            ;;
        --baseline)
            BASELINE=$(baseline_tag)
            strip_plane "$BASELINE"
            exit 0
            ;;
        *) printf 'Unknown argument: %s\n' "$1" >&2; exit 1 ;;
    esac
done

# 1. Exactly on a well-formed tag of this plane -> release version.
MATCH_GLOB='v[0-9]*'
[[ "$PLANE" != "shell" ]] && MATCH_GLOB="${PLANE}/v[0-9]*"
if EXACT=$(git describe --tags --exact-match --match "$MATCH_GLOB" 2>/dev/null); then
    if [[ "$EXACT" =~ $(plane_release_re "$PLANE") ]]; then
        strip_plane "$EXACT"
        exit 0
    fi
fi

# 2. Newest well-formed tag of this plane anywhere in the repo -> dev version.
printf '%s-SNAPSHOT\n' "$(newest_plane_tag)"