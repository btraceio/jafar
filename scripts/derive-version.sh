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

PLANE="shell"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --plane) plane_for "${2:?--plane requires a plane name}"; PLANE="$2"; shift 2 ;;
        --newest)
            newest_plane_tag
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