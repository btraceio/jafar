#!/usr/bin/env bash
set -euo pipefail

# ---------------------------------------------------------------------------
# derive-version.sh — the shell-side mirror of gradle/version-from-tag.gradle
#
# Prints the project version derived from git release tags:
#
#   exactly on tag vX.Y.Z         -> X.Y.Z            (release build)
#   elsewhere, git metadata found -> <newest vX.Y.Z tag>-SNAPSHOT
#   no tags in the repo           -> 0.0.0-SNAPSHOT
#
# The newest release tag is the newest tag in the repo (not just one reachable
# from HEAD): release tags may sit on release/X.Y._ branches that main does not
# contain. Only well-formed vX.Y.Z tags count; other v* tags are ignored — the
# for-each-ref glob is looser than the strict pattern, so every glob hit is
# re-checked.
#
# gradle/version-from-tag.gradle is the authority for Gradle builds; this script
# must implement the same derivation for the workflows and scripts/release.sh.
# See RELEASING.md ("Version Numbering").
# ---------------------------------------------------------------------------

RELEASE_TAG_RE='^v[0-9]+\.[0-9]+\.[0-9]+$'

# Reads tag names from stdin, newest first. Prints the first strictly
# well-formed tag name (with the v prefix), or nothing.
first_release_tag() {
    while IFS= read -r candidate; do
        [[ -n "$candidate" ]] || continue
        if [[ "$candidate" =~ $RELEASE_TAG_RE ]]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done
    return 1
}

# --newest: print only the newest well-formed release tag anywhere in the repo
# (without the v prefix, or 0.0.0 when none exists). release.sh uses this as the
# base for the next release: it must see tags on other lines even when HEAD is
# itself sitting exactly on a tag.
if [[ "${1:-}" == "--newest" ]]; then
    NEWEST=$(git for-each-ref 'refs/tags/v[0-9]*.[0-9]*.[0-9]*' \
                 --sort=-v:refname --format='%(refname:short)' | first_release_tag || true)
    if [[ -n "$NEWEST" ]]; then
        printf '%s\n' "${NEWEST#v}"
    else
        printf '0.0.0\n'
    fi
    exit 0
fi

# 1. Exactly on a well-formed vX.Y.Z tag -> release version.
if EXACT=$(git describe --tags --exact-match --match 'v[0-9]*' 2>/dev/null); then
    if [[ "$EXACT" =~ $RELEASE_TAG_RE ]]; then
        printf '%s\n' "${EXACT#v}"
        exit 0
    fi
fi

# 2. Newest well-formed release tag anywhere in the repo -> dev version.
NEWEST=$(git for-each-ref 'refs/tags/v[0-9]*.[0-9]*.[0-9]*' \
             --sort=-v:refname --format='%(refname:short)' | first_release_tag || true)
if [[ -n "$NEWEST" ]]; then
    printf '%s\n' "${NEWEST#v}-SNAPSHOT"
    exit 0
fi

# 3. No release tags (fresh repo, source tarball without .git).
printf '0.0.0-SNAPSHOT\n'
