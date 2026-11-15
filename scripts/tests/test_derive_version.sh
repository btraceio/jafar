#!/usr/bin/env bash
# test_derive_version.sh — hermetic tests for scripts/derive-version.sh (R5 on the gate).
#
# Pins the version gates the release workflow trusts: exactly-on-tag -> release
# version, elsewhere -> <newest>-SNAPSHOT, plane isolation, the fallback for a
# plane with no tags of its own, no tags at all -> 0.0.0-SNAPSHOT, and the
# "newest anywhere, not just reachable" rule (plane tags may sit on release
# branches main does not contain). Also pins the API-baseline modes
# (--baseline / --baseline-tag, consumed by scripts/api-baseline.sh): the
# newest plane tag excluding the tag HEAD sits exactly on (a release commit's
# baseline is the previous release), the bare-tag fallback, rc tags ignored,
# and a loud exit 1 — never 0.0.0 — when no tag qualifies (R3).
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
    [ -n "${R:-}" ] && rm -rf "$R" 2>/dev/null || true # drop the previous scratch repo
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

# 9. Baseline: the newest plane tag anywhere; the tag HEAD sits exactly on
#    never qualifies, so a release commit's baseline is the previous release.
mkrepo; tag v0.29.0; commit after; tag core/v0.30.0; commit after-again
expect "baseline, core plane: newest core tag" "0.30.0" "$(cd "$R" && "$script" --plane core --baseline)"
expect "baseline, shell plane: unaffected by core tag" "0.29.0" "$(cd "$R" && "$script" --plane shell --baseline)"
expect "baseline, untagged plane rides the bare line" "0.29.0" "$(cd "$R" && "$script" --plane mcp --baseline)"
expect "baseline-tag, core plane" "core/v0.30.0" "$(cd "$R" && "$script" --plane core --baseline-tag)"
expect "baseline-tag, untagged plane bare fallback" "v0.29.0" "$(cd "$R" && "$script" --plane mcp --baseline-tag)"

# 10. Baseline: HEAD exactly on the newest core tag -> the previous core tag.
mkrepo; tag core/v0.29.5; commit after; tag core/v0.30.0
expect "baseline: release commit takes the previous release" "0.29.5" "$(cd "$R" && "$script" --plane core --baseline)"
expect "baseline-tag: release commit takes the previous release" "core/v0.29.5" "$(cd "$R" && "$script" --plane core --baseline-tag)"

# 11. Baseline: HEAD exactly on the only core tag -> the bare fallback.
mkrepo; tag v0.29.0; commit after; tag core/v0.30.0
expect "baseline: only core tag at HEAD -> bare fallback" "0.29.0" "$(cd "$R" && "$script" --plane core --baseline)"

# 12. Baseline: HEAD exactly on the newest bare tag -> the previous release.
mkrepo; tag v0.29.0; commit after; tag v0.30.0
expect "baseline: shell release commit takes the previous release" "0.29.0" "$(cd "$R" && "$script" --plane shell --baseline)"
expect "baseline-tag: shell release commit takes the previous release" "v0.29.0" "$(cd "$R" && "$script" --plane shell --baseline-tag)"

# 13. Baseline: non-conforming tags (rc) are ignored in both tag shapes.
mkrepo; tag v0.29.0; commit after; tag v0.31.0-rc1
expect "baseline: bare rc tag ignored" "0.29.0" "$(cd "$R" && "$script" --plane shell --baseline)"
mkrepo; tag v0.29.0; commit after; tag core/v0.30.0; commit after-again; tag core/v0.31.0-rc1
expect "baseline: plane rc tag ignored" "0.30.0" "$(cd "$R" && "$script" --plane core --baseline)"

# 14. Baseline: no qualifying tag -> exit 1 with the message, never 0.0.0 (R3).
mkrepo
out=$(cd "$R" && "$script" --plane core --baseline 2>&1) && rc=0 || rc=$?
if [ "$rc" = "1" ] && [ "$out" = "no baseline tag for plane core" ]; then
    printf 'ok   baseline: no tags -> exit 1 with the message, no 0.0.0\n'; pass=$((pass + 1))
else
    printf 'FAIL baseline no tags: rc=%s out=%s\n' "$rc" "$out"; fail=$((fail + 1))
fi
out=$(cd "$R" && "$script" --plane shell --baseline-tag 2>&1) && rc=0 || rc=$?
if [ "$rc" = "1" ] && [ "$out" = "no baseline tag for plane shell" ]; then
    printf 'ok   baseline-tag: no tags -> exit 1 with the message\n'; pass=$((pass + 1))
else
    printf 'FAIL baseline-tag no tags: rc=%s out=%s\n' "$rc" "$out"; fail=$((fail + 1))
fi

# 15. Baseline: only a non-conforming tag -> still exit 1, never 0.0.0 (R3).
mkrepo; tag v0.31.0-rc1
out=$(cd "$R" && "$script" --plane shell --baseline 2>&1) && rc=0 || rc=$?
if [ "$rc" = "1" ] && [ "$out" = "no baseline tag for plane shell" ]; then
    printf 'ok   baseline: only an rc tag -> exit 1, never 0.0.0\n'; pass=$((pass + 1))
else
    printf 'FAIL baseline rc-only: rc=%s out=%s\n' "$rc" "$out"; fail=$((fail + 1))
fi

rm -rf "$R" 2>/dev/null || true
printf '%s passed, %s failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
