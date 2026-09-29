#!/usr/bin/env bash
set -euo pipefail

# ---------------------------------------------------------------------------
# release.sh — automate major / minor / patch releases for Jafar
#
# Usage:  scripts/release.sh [--dry-run] <major|minor|patch>
#         scripts/release.sh <major|minor|patch> [--dry-run]
# ---------------------------------------------------------------------------

DRY_RUN=0
RELEASE_TYPE=""

for arg in "$@"; do
    case "$arg" in
        --dry-run) DRY_RUN=1 ;;
        major|minor|patch) RELEASE_TYPE="$arg" ;;
        *) printf 'Unknown argument: %s\n' "$arg" >&2; exit 1 ;;
    esac
done

[[ -n "$RELEASE_TYPE" ]] || { printf 'Usage: release.sh [--dry-run] <major|minor|patch>\n' >&2; exit 1; }

HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." >/dev/null 2>&1 && pwd)
cd "$HERE"

# ── helpers ────────────────────────────────────────────────────────────────

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# Execute a command, or print it when DRY_RUN=1
run() {
    if [[ "$DRY_RUN" == "1" ]]; then
        printf '[DRY RUN] %s\n' "$*"
    else
        printf '==> %s\n' "$*"
        "$@"
    fi
}

# ── 1. Preflight ──────────────────────────────────────────────────────────

echo "--- Preflight checks ---"

if [[ "$DRY_RUN" != "1" ]]; then
    [[ -z "$(git status --porcelain)" ]] || die "Working tree is not clean. Commit or stash changes first."
fi

gh auth status >/dev/null 2>&1 || die "Not authenticated with GitHub CLI. Run 'gh auth login'."

DEFAULT_BRANCH=$(gh repo view --json defaultBranchRef -q '.defaultBranchRef.name')
CURRENT_BRANCH=$(git branch --show-current)

case "$RELEASE_TYPE" in
    major|minor)
        [[ "$CURRENT_BRANCH" == "$DEFAULT_BRANCH" ]] \
            || die "major/minor releases must start from '$DEFAULT_BRANCH' (currently on '$CURRENT_BRANCH')"
        ;;
    patch)
        [[ "$CURRENT_BRANCH" =~ ^release/[0-9]+\.[0-9]+\._$ ]] \
            || die "patch releases must be on a release/X.Y._ branch (currently on '$CURRENT_BRANCH')"
        ;;
esac

echo "Branch OK: $CURRENT_BRANCH"

# ── 2. Version detection ──────────────────────────────────────────────────

# Version is tag-derived (gradle/version-from-tag.gradle; the shell mirror is
# scripts/derive-version.sh): the project version comes from git tags, never
# from a number baked into a build script. The newest vX.Y.Z tag in the repo is
# the base for the next release, and after tagging, main and release branches
# automatically report <base>-SNAPSHOT. No version numbers are edited anywhere
# in this script.

echo "--- Detecting current version ---"

DERIVED_VERSION=$(scripts/derive-version.sh --newest)
if [[ "$DERIVED_VERSION" == "0.0.0" ]]; then
    # No local release tags: either a fresh clone without fetched tags or a repo
    # that never released. Fetch once before giving up, so the error is
    # actionable rather than a bare "no tags found".
    echo "No local vX.Y.Z release tags - fetching tags from origin"
    git fetch --tags origin || die "Could not fetch tags from origin. Run 'git fetch --tags' and retry."
    DERIVED_VERSION=$(scripts/derive-version.sh --newest)
fi

CURRENT_VERSION="$DERIVED_VERSION"
[[ "$CURRENT_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] \
    || die "Could not determine the latest release version (derived: '$DERIVED_VERSION'). Is this a repository with vX.Y.Z release tags?"

echo "Latest release tag: v$CURRENT_VERSION"

# ── 3. Version derivation ─────────────────────────────────────────────────

IFS='.' read -r CUR_MAJOR CUR_MINOR CUR_PATCH <<< "$CURRENT_VERSION"

case "$RELEASE_TYPE" in
    major) RELEASE_VERSION="$((CUR_MAJOR + 1)).0.0" ;;
    minor) RELEASE_VERSION="${CUR_MAJOR}.$((CUR_MINOR + 1)).0" ;;
    patch) RELEASE_VERSION="${CUR_MAJOR}.${CUR_MINOR}.$((CUR_PATCH + 1))" ;;
esac
echo "Release version: $RELEASE_VERSION"

IFS='.' read -r MAJOR MINOR PATCH <<< "$RELEASE_VERSION"

RELEASE_TAG="v${RELEASE_VERSION}"
RELEASE_BRANCH="release/${MAJOR}.${MINOR}._"

case "$RELEASE_TYPE" in
    patch)
        # The plugin catalog must never be downgraded (see RELEASING.md), so the
        # automation only supports patching the newest release line. The branch
        # (required to be release/X.Y._ by the preflight) must match the line of
        # the newest tag — deriving the release version from anything else would
        # silently tag a version on the wrong line.
        [[ "$CURRENT_BRANCH" =~ ^release/([0-9]+)\.([0-9]+)\._$ ]] \
            || die "patch releases must be on a release/X.Y._ branch (currently on '$CURRENT_BRANCH')"
        BR_LINE="${BASH_REMATCH[1]}.${BASH_REMATCH[2]}"
        [[ "$BR_LINE" == "${CUR_MAJOR}.${CUR_MINOR}" ]] \
            || die "patch releases must continue the newest release line (latest tag is v$CURRENT_VERSION on line ${CUR_MAJOR}.${CUR_MINOR}, but this is release/$BR_LINE)"
        ;;
esac

echo "Release tag:    $RELEASE_TAG"
echo "Release branch: $RELEASE_BRANCH"

# ── 4. Branch management ──────────────────────────────────────────────────

echo "--- Branch management ---"

case "$RELEASE_TYPE" in
    major|minor)
        if git show-ref --verify --quiet "refs/heads/$RELEASE_BRANCH"; then
            die "Release branch '$RELEASE_BRANCH' already exists locally"
        fi
        run git checkout -b "$RELEASE_BRANCH"
        ;;
    patch)
        [[ "$CURRENT_BRANCH" == "$RELEASE_BRANCH" ]] \
            || die "Expected to be on '$RELEASE_BRANCH' but on '$CURRENT_BRANCH'"
        ;;
esac

# ── 5. Tag ─────────────────────────────────────────────────────────────────

# Nothing to edit or commit: the version lives in the tag. Commits already made
# on the release branch (stabilization fixes) are pushed as-is.

echo "--- Tagging $RELEASE_TAG ---"
run git tag -a "$RELEASE_TAG" -m "Release ${RELEASE_VERSION}"

# ── 6. Push ────────────────────────────────────────────────────────────────

echo "--- Pushing ---"
run git push --no-verify origin "$RELEASE_BRANCH"
run git push --no-verify origin "$RELEASE_TAG"

# ── 7. GitHub release ─────────────────────────────────────────────────────

echo "--- Creating GitHub release ---"
run gh release create "$RELEASE_TAG" --title "$RELEASE_TAG" --generate-notes

# ── 8. Post-release ────────────────────────────────────────────────────────

# No post-release version bump: main and release branches derive their
# development version (<latest tag>-SNAPSHOT) from git automatically via
# gradle/version-from-tag.gradle, and the release workflow commits the
# jfr-shell-plugins.json catalog update to main (see RELEASING.md).

echo "--- Release $RELEASE_TAG complete! ---"
