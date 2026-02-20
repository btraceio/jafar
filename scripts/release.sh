#!/usr/bin/env bash
set -euo pipefail

# ---------------------------------------------------------------------------
# release.sh — automate major / minor / patch releases for Jafar
#
# Usage:  scripts/release.sh [--dry-run] <major|minor|patch>
# ---------------------------------------------------------------------------

DRY_RUN=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --dry-run) DRY_RUN=1; shift ;;
        -*) printf 'Unknown option: %s\n' "$1" >&2; exit 1 ;;
        *) break ;;
    esac
done

RELEASE_TYPE="${1:?Usage: release.sh [--dry-run] <major|minor|patch>}"

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

# Portable sed-in-place (macOS vs GNU)
if sed --version 2>/dev/null | grep -q GNU; then
    sed_i() { sed -i "$@"; }
else
    sed_i() { sed -i '' "$@"; }
fi

# ── 1. Preflight ──────────────────────────────────────────────────────────

echo "--- Preflight checks ---"

[[ -z "$(git status --porcelain)" ]] || die "Working tree is not clean. Commit or stash changes first."

gh auth status >/dev/null 2>&1 || die "Not authenticated with GitHub CLI. Run 'gh auth login'."

DEFAULT_BRANCH=$(gh repo view --json defaultBranchRef -q '.defaultBranchRef.name')
CURRENT_BRANCH=$(git branch --show-current)

case "$RELEASE_TYPE" in
    major|minor)
        [[ "$CURRENT_BRANCH" == "$DEFAULT_BRANCH" ]] \
            || die "major/minor releases must start from '$DEFAULT_BRANCH' (currently on '$CURRENT_BRANCH')"
        ;;
    patch)
        [[ "$CURRENT_BRANCH" == release/* ]] \
            || die "patch releases must be on a release/* branch (currently on '$CURRENT_BRANCH')"
        ;;
    *)
        die "Invalid release type '$RELEASE_TYPE'. Use major, minor, or patch."
        ;;
esac

echo "Branch OK: $CURRENT_BRANCH"

# ── 2. Version detection ──────────────────────────────────────────────────

echo "--- Detecting current version ---"

CURRENT_VERSION=$(sed -n 's/^project\.version="\(.*\)"/\1/p' build.gradle)
[[ -n "$CURRENT_VERSION" ]] || die "Could not parse project.version from build.gradle"
echo "Current version: $CURRENT_VERSION"

RELEASE_VERSION="${CURRENT_VERSION%-SNAPSHOT}"
echo "Release version: $RELEASE_VERSION"

# ── 3. Version derivation ─────────────────────────────────────────────────

IFS='.' read -r MAJOR MINOR PATCH <<< "$RELEASE_VERSION"

RELEASE_TAG="v${RELEASE_VERSION}"
RELEASE_BRANCH="release/${MAJOR}.${MINOR}"

case "$RELEASE_TYPE" in
    major) NEXT_VERSION="$((MAJOR + 1)).0.0-SNAPSHOT" ;;
    minor) NEXT_VERSION="${MAJOR}.$((MINOR + 1)).0-SNAPSHOT" ;;
    patch) NEXT_VERSION="${MAJOR}.${MINOR}.$((PATCH + 1))-SNAPSHOT" ;;
esac

echo "Release tag:    $RELEASE_TAG"
echo "Release branch: $RELEASE_BRANCH"
echo "Next version:   $NEXT_VERSION"

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

# ── 5. Version replacement ────────────────────────────────────────────────

echo "--- Updating versions: $CURRENT_VERSION -> $RELEASE_VERSION ---"

# Escape dots for sed regex
ESCAPED_OLD=$(printf '%s' "$CURRENT_VERSION" | sed 's/[.]/\\./g')
ESCAPED_NEW="$RELEASE_VERSION"

# Replace SNAPSHOT version in all tracked files that contain it
git ls-files -z | xargs -0 grep -lFZ "$CURRENT_VERSION" 2>/dev/null \
    | xargs -0 sed_i "s/${ESCAPED_OLD}/${ESCAPED_NEW}/g" 2>/dev/null || true

# Update jfr-shell-plugins.json catalog version (only upgrade, never downgrade)
if [[ -f jfr-shell-plugins.json ]]; then
    CATALOG_VERSION=$(sed -n 's/.*"latestVersion"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' jfr-shell-plugins.json | head -1)
    if [[ -n "$CATALOG_VERSION" ]]; then
        IFS='.' read -r CAT_MAJOR CAT_MINOR CAT_PATCH <<< "$CATALOG_VERSION"
        if (( MAJOR > CAT_MAJOR \
           || (MAJOR == CAT_MAJOR && MINOR > CAT_MINOR) \
           || (MAJOR == CAT_MAJOR && MINOR == CAT_MINOR && PATCH >= CAT_PATCH) )); then
            echo "Updating jfr-shell-plugins.json: $CATALOG_VERSION -> $RELEASE_VERSION"
            ESCAPED_CAT=$(printf '%s' "$CATALOG_VERSION" | sed 's/[.]/\\./g')
            sed_i "s/\"latestVersion\": \"${ESCAPED_CAT}\"/\"latestVersion\": \"${RELEASE_VERSION}\"/g" jfr-shell-plugins.json
        else
            echo "Skipping jfr-shell-plugins.json update: catalog $CATALOG_VERSION >= release $RELEASE_VERSION"
        fi
    fi
fi

# ── 6. Commit ──────────────────────────────────────────────────────────────

echo "--- Committing release ---"
run git -c commit.gpgsign=false commit --no-verify -am "Preparing release ${RELEASE_VERSION}"

# ── 7. Tag ─────────────────────────────────────────────────────────────────

echo "--- Tagging $RELEASE_TAG ---"
run git -c tag.gpgsign=false tag -a "$RELEASE_TAG" -m "Release ${RELEASE_VERSION}"

# ── 8. Push ────────────────────────────────────────────────────────────────

echo "--- Pushing ---"
run git push --no-verify origin "$RELEASE_BRANCH"
run git push --no-verify origin "$RELEASE_TAG"

# ── 9. GitHub release ─────────────────────────────────────────────────────

echo "--- Creating GitHub release ---"
run gh release create "$RELEASE_TAG" --title "$RELEASE_TAG" --generate-notes

# ── 10. Post-release bump (major/minor only) ──────────────────────────────

if [[ "$RELEASE_TYPE" != "patch" ]]; then
    echo "--- Post-release: bumping $DEFAULT_BRANCH to $NEXT_VERSION ---"
    run git checkout "$DEFAULT_BRANCH"

    # Replace release version -> next SNAPSHOT in *.gradle files only
    # (jfr-shell-plugins.json must keep the release version)
    ESCAPED_REL=$(printf '%s' "$RELEASE_VERSION" | sed 's/[.]/\\./g')
    git ls-files -z -- '*.gradle' | xargs -0 grep -lFZ "$RELEASE_VERSION" 2>/dev/null \
        | xargs -0 sed_i "s/${ESCAPED_REL}/${NEXT_VERSION}/g" 2>/dev/null || true

    run git -c commit.gpgsign=false commit --no-verify -am "Opening work on ${NEXT_VERSION}"
    run git push --no-verify origin "$DEFAULT_BRANCH"
fi

echo "--- Release $RELEASE_TAG complete! ---"
