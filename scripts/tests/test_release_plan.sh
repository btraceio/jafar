#!/usr/bin/env bash
# test_release_plan.sh — hermetic tests for the release.sh preflight and version
# derivation (R5 on the gate): the dry-run derives the next version from the
# newest plane tag, and a repository with no release tags is refused with the
# intended message instead of publishing a 0.0.0 release.
#
# release.sh operates on the repository it lives in, so the tests copy it (and
# derive-version.sh, its version authority) into a scratch clone with a bare
# origin, and stub `gh` on PATH: auth is fine and the default branch is "main".
set -euo pipefail

repo="$(cd "$(dirname "$0")/../.." && pwd)"

pass=0
fail=0

expect() { # expect <description> <want-grep> <got-output> <got-rc> <want-rc>
    local desc="$1" want="$2" got="$3" rc="$4" want_rc="$5"
    if [ "$rc" != "$want_rc" ]; then
        printf 'FAIL %s: exit %s, wanted %s\n%s\n' "$desc" "$rc" "$want_rc" "$got"
        fail=$((fail + 1))
    elif [ -n "$want" ] && ! grep -qF "$want" <<<"$got"; then
        printf 'FAIL %s: output lacks %s\n%s\n' "$desc" "$want" "$got"
        fail=$((fail + 1))
    else
        printf 'ok   %s\n' "$desc"; pass=$((pass + 1))
    fi
}

mkscratch() { # mkscratch -> scratch repo with a bare origin, with the scripts and stub gh
    S=$(mktemp -d)
    ORIGIN="$S/origin.git"
    git init -q --bare "$ORIGIN"
    R="$S/work"
    git init -q -b main "$R"
    git -C "$R" remote add origin "$ORIGIN"
    git -C "$R" -c user.email=t@t -c user.name=t commit -q --allow-empty -m base
    git -C "$R" push -q -u origin main
    git -C "$R" config advice.detachedHead false
    # Local to the scratch repo: the developer's global tag.gpgSign forces an
    # annotated signed tag (and an editor) on every `git tag`.
    git -C "$R" config tag.gpgSign false
    mkdir -p "$R/scripts"
    cp "$repo/scripts/derive-version.sh" "$repo/scripts/release.sh" "$R/scripts/"
    chmod +x "$R/scripts/"*.sh
    mkdir -p "$S/bin"
    cat > "$S/bin/gh" <<'GH'
#!/usr/bin/env bash
case "$1" in
    auth) exit 0 ;;                       # gh auth status: authenticated
    repo) echo "main" ;;                  # gh repo view -q defaultBranchRef.name
    *) exit 0 ;;
esac
GH
    chmod +x "$S/bin/gh"
    export PATH="$S/bin:$PATH"
}

# 1. Dry-run derives the next version from the newest plane tag and plans the tag.
mkscratch
git -C "$R" tag v1.2.3
git -C "$R" -c user.email=t@t -c user.name=t commit -q --allow-empty -m after
git -C "$R" push -q origin main  # (tag stays local; derivation reads local refs)
out=$(cd "$R" && ./scripts/release.sh --dry-run minor 2>&1) && rc=0 || rc=$?
expect "dry-run minor on main: v1.3.0 planned" "Release version: 1.3.0 (plane shell)" "$out" "$rc" 0

# 2. Non-default branch: major/minor releases are refused by the branch policy.
git -C "$R" checkout -q -b feature
out=$(cd "$R" && ./scripts/release.sh --dry-run minor 2>&1) && rc=0 || rc=$?
expect "branch policy: minor off main refused" "must start from 'main'" "$out" "$rc" 1

# 3. No release tags anywhere (the 'untagged-*' incident): refused, not 0.0.0.
# minor, not patch: the shell branch policy for patch fires first, and the
# point here is the version guard.
mkscratch
out=$(cd "$R" && ./scripts/release.sh --dry-run minor 2>&1) && rc=0 || rc=$?
expect "no release tags: refused with intended message" "Could not determine the latest release version" "$out" "$rc" 1

rm -rf "$S" 2>/dev/null || true
printf '%s passed, %s failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
