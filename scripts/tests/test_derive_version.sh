#!/usr/bin/env bash
# test_derive_version.sh — hermetic tests for scripts/derive-version.sh (R5 on the gate).
#
# Pins the version gates the release workflow trusts: exactly-on-tag -> release
# version, elsewhere -> <newest>-SNAPSHOT, plane isolation, the fallback for a
# plane with no tags of its own, no tags at all -> 0.0.0-SNAPSHOT, and the
# "newest anywhere, not just reachable" rule (plane tags may sit on release
# branches main does not contain).
set -euo pipefail

repo="$(cd "$(dirname "$0")/../.." && pwd)"
script="$repo/scripts/derive-version.sh"

pass=0
fail=0

expect() { # expect <description> <want> <got>
    if [ "$2" = "$3" ]; then
        printf 'ok   %s\n' "$1"; pass=$((pass + 1))
    else
        printf 'FAIL %s: got %s, wanted %s\n' "$1" "$3" "$2"; fail=$((fail + 1))
    fi
}

mkrepo() { # mkrepo -> scratch repo with default config
    R=$(mktemp -d)
    git -C "$R" init -q -b main
    # Local to the scratch repo: the developer's global tag.gpgSign forces an
    # annotated signed tag (and an editor) on every `git tag`, and commit.gpgSign
    # makes every fixture commit need a reachable signing agent. A hermetic test
    # must not depend on either; real repositories are untouched.
    git -C "$R" config tag.gpgSign false
    git -C "$R" config commit.gpgSign false
    git -C "$R" -c user.email=t@t -c user.name=t commit -q --allow-empty -m base
    git -C "$R" config advice.detachedHead false
}

commit() { git -C "$R" -c user.email=t@t -c user.name=t commit -q --allow-empty -m "$1"; }
tag()     { git -C "$R" tag "$@"; }

# 1. Exactly on the plane's tag -> release version.
mkrepo; tag v1.2.3
expect "on bare v tag: release version" "1.2.3" "$(cd "$R" && "$script")"

# 2. Past the tag -> snapshot of the newest tag.
commit after
expect "past the tag: snapshot" "1.2.3-SNAPSHOT" "$(cd "$R" && "$script")"

# 3. No tags at all -> 0.0.0-SNAPSHOT.
mkrepo
expect "no tags: 0.0.0-SNAPSHOT" "0.0.0-SNAPSHOT" "$(cd "$R" && "$script")"

# 4. Plane isolation: a core tag does not feed the shell plane, and vice versa.
mkrepo; tag v1.2.3; commit after; tag core/v0.5.1
expect "core plane: its own tag at HEAD is a release" "0.5.1" "$(cd "$R" && "$script" --plane core)"
expect "shell plane: unaffected by core tag" "1.2.3-SNAPSHOT" "$(cd "$R" && "$script" --plane shell)"

# 5. Fallback: a plane with no tags of its own rides the newest bare vX.Y.Z.
mkrepo; tag v1.2.3; commit after
expect "untagged plane rides the shell line" "1.2.3-SNAPSHOT" "$(cd "$R" && "$script" --plane mcp)"

# 6. Newest tag anywhere in the repo, not just reachable from HEAD.
mkrepo; tag v1.2.3; commit after
git -C "$R" checkout -q -b side
commit side-change
tag v1.2.4
git -C "$R" checkout -q main
expect "unreachable newer tag still counts" "1.2.4-SNAPSHOT" "$(cd "$R" && "$script")"
expect "--newest sees the newest anywhere" "1.2.4" "$(cd "$R" && "$script" --newest)"

# 7. Non-release tags (untagged-* etc.) are never mistaken for versions.
mkrepo; tag untagged-d57b23bd2755b7411023; commit after
expect "non-version tags ignored" "0.0.0-SNAPSHOT" "$(cd "$R" && "$script")"

# 8. Unknown plane is an error, not a silent default.
out=$(cd "$R" && "$script" --plane nonsense 2>&1) && rc=0 || rc=$?
if [ "$rc" = "1" ] && grep -qF "Unknown plane" <<<"$out"; then
    printf 'ok   unknown plane rejected\n'; pass=$((pass + 1))
else
    printf 'FAIL unknown plane: rc=%s out=%s\n' "$rc" "$out"; fail=$((fail + 1))
fi

rm -rf "$R" 2>/dev/null || true
printf '%s passed, %s failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
