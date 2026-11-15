#!/usr/bin/env bash
# test_api_baseline.sh — hermetic tests for scripts/api-baseline.sh (R5 on the gate).
#
# The real fetch hits repo1.maven.org and builds tag checkouts with Gradle —
# that runs in CI, never here (network in tests is flake). These tests
# exercise the local logic paths only: a fake file:// Maven layout, a stub
# gradlew (JAFAR_API_BASELINE_GRADLEW) writing fake jars into a git worktree
# of a scratch repo, and the surface-table parsing (the script is sourced
# for parse_surfaces/stamp_get).
#
# Cases: maven and tag rows end to end; one stamp per surface with tag,
# version, sha256 (and coordinates/url or commit); the manifest gets one line
# per surface; tag rows are built once per tag in a temp worktree that is
# removed afterwards; cache skip on a second run; a bad .sha1, a 404, a
# project absent at the tag, a plane with no baseline tag, an unknown plane,
# a failing gradlew, and --only (working and refused) each exit 1 loudly.
set -euo pipefail

repo="$(cd "$(dirname "$0")/../.." && pwd)"
script="$repo/scripts/api-baseline.sh"
# source for parse_surfaces/stamp_get; the guard in the script keeps main()
# from running (it only runs when executed)
# shellcheck source=scripts/api-baseline.sh
source "$script"

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
    # Local to the scratch repo: the developer's global tag.gpgSign and
    # commit.gpgSign force signed (and editor-opening) tags and commits on
    # every `git tag`/`git commit`; a hermetic test repo commits and tags
    # lightweight and unauthenticated, without the signing agent.
    git -C "$R" config tag.gpgSign false
    git -C "$R" config commit.gpgSign false
    git -C "$R" -c user.email=t@t -c user.name=t commit -q --allow-empty -m base
    git -C "$R" config advice.detachedHead false
}

commit() { git -C "$R" -c user.email=t@t -c user.name=t commit -q --allow-empty -m "$1"; }
commit_all() { git -C "$R" add -A && commit "$1"; } # stage created files, then commit
tag()     { git -C "$R" tag "$@"; }

hash1() { if command -v shasum >/dev/null 2>&1; then shasum -a 1 "$1" | awk '{print $1}'; else sha1sum "$1" | awk '{print $1}'; fi; }
hash256() { if command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | awk '{print $1}'; else sha256sum "$1" | awk '{print $1}'; fi; }

# run <maven-url> <out-dir> [args...] -> RC and OUT (combined stdout+stderr)
run() {
    local m2="$1" o="$2"; shift 2
    OUT=$( (cd "$R" && JAFAR_MAVEN_REPO_URL="$m2" \
        JAFAR_API_BASELINE_GRADLEW="$R/stub-gradlew" \
        JAFAR_STUB_LOG="$R/stub.log" \
        "$script" --surfaces "$R/surfaces.txt" --out "$o" "$@") 2>&1 ) && RC=0 || RC=$?
}

# fake local Maven layout: $R/m2/io/btrace/<a>/<v>/<a>-<v>.{jar,jar.sha1}
mk_m2() { # mk_m2 <artifactId> <version>
    local a="$1" v="$2"
    local dir="$R/m2/io/btrace/$a/$v"
    mkdir -p "$dir"
    printf 'fake jar %s %s\n' "$a" "$v" >"$dir/$a-$v.jar"
    hash1 "$dir/$a-$v.jar" >"$dir/$a-$v.jar.sha1"
}

# stub gradlew: logs its invocation (to prove one build per tag) and writes a
# fake jar for every ":<proj>:jar" argument, versioned like the tag checkout
# itself would be (git describe --exact-match -> the tag's number)
mkstub_gradlew() {
    cat >"$R/stub-gradlew" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"$JAFAR_STUB_LOG"
v=$(git describe --tags --exact-match 2>/dev/null)
v="${v##*/}"; v="${v#v}"
for arg in "$@"; do
    case "$arg" in
        :*:jar)
            proj="${arg%:jar}"; proj="${proj#:}"
            mkdir -p "$proj/build/libs"
            printf 'stub baseline jar for %s at %s\n' "$proj" "$v" \
                >"$proj/build/libs/${proj}-${v}.jar"
            ;;
    esac
done
EOF
    chmod +x "$R/stub-gradlew"
}

# 1. Happy path: maven rows (file:// fake repo) + tag rows (stub gradlew in a
#    worktree of the tag), stamps, manifest, one build per tag, worktree
#    cleanup, override echoed (R3).
mkrepo
for m in hdump-parser shell-core pprof-parser otlp-parser; do
    mkdir -p "$R/$m"
    printf 'stub module %s\n' "$m" >"$R/$m/build.gradle"
done
commit_all modules
tag v0.29.0
commit after-tag # HEAD past the tag: the baseline is the tag, not HEAD
mk_m2 jafar-parser 0.29.0
mk_m2 jafar-shell 0.29.0
mk_m2 jfr-mcp 0.29.0
mkstub_gradlew
: >"$R/stub.log"
cat >"$R/surfaces.txt" <<'EOF'
parser :parser core maven:jafar-parser pkg:io.jafar.parser.api
shell :shell-core shell maven:jafar-shell pkg:io.jafar.shell.backend,class:io.jafar.shell.core.Session
mcp :jfr-mcp mcp maven:jfr-mcp pkg:io.jafar.mcp
hdump :hdump-parser shell tag pkg:io.jafar.hdump.api
llm-spi :shell-core shell tag pkg:io.jafar.shell.core.llm
EOF
run "file://$R/m2" "$R/out"
expect "happy run exits 0" "0" "$RC"
expect "override echoed once per maven row (R3)" "3" "$(grep -c "maven repo override in effect: file://$R/m2" <<<"$OUT")"

# maven stamps
expect "parser stamp: kind" "maven" "$(stamp_get "$R/out/parser/BASELINE" kind)"
expect "parser stamp: version" "0.29.0" "$(stamp_get "$R/out/parser/BASELINE" version)"
expect "parser stamp: coordinates" "io.btrace:jafar-parser:0.29.0" "$(stamp_get "$R/out/parser/BASELINE" coordinates)"
expect "parser stamp: url" "file://$R/m2/io/btrace/jafar-parser/0.29.0/jafar-parser-0.29.0.jar" "$(stamp_get "$R/out/parser/BASELINE" url)"
expect "parser stamp: sha256 matches jar" "$(hash256 "$R/out/parser/baseline.jar")" "$(stamp_get "$R/out/parser/BASELINE" sha256)"
# core has no core/v tags: the untagged plane rides the bare v0.29.0 tag
expect "untagged plane rides the bare tag" "v0.29.0" "$(stamp_get "$R/out/parser/BASELINE" tag)"

# tag-checkout stamps
expect "hdump stamp: kind" "tag" "$(stamp_get "$R/out/hdump/BASELINE" kind)"
expect "hdump stamp: version (from the built jar)" "0.29.0" "$(stamp_get "$R/out/hdump/BASELINE" version)"
expect "hdump stamp: commit is the tag's commit" "$(git -C "$R" rev-list -n1 v0.29.0)" "$(stamp_get "$R/out/hdump/BASELINE" commit)"
expect "hdump jar copied from the tag build" "stub baseline jar for hdump-parser at 0.29.0" "$(cat "$R/out/hdump/baseline.jar")"
expect "llm-spi stamp: kind" "tag" "$(stamp_get "$R/out/llm-spi/BASELINE" kind)"
expect "llm-spi jar copied from the tag build" "stub baseline jar for shell-core at 0.29.0" "$(cat "$R/out/llm-spi/baseline.jar")"

# one gradle invocation per tag: both :jar tasks on a single stub log line
expect "tag rows built once per tag" "1" "$(wc -l <"$R/stub.log" | tr -d ' ')"
expect "both projects in the same build" "1" "$(grep ':hdump-parser:jar' "$R/stub.log" | grep -c ':shell-core:jar')"
expect "stub saw --no-daemon" "1" "$(grep -c -- '--no-daemon' "$R/stub.log")"

# manifest: one line per surface, <surface>\t<path>\t<sha256>
expect "manifest: one line per surface" "5" "$(grep -c . "$R/out/manifest.txt")"
want_line=$(printf 'parser\t%s/parser/baseline.jar\t%s' "$R/out" "$(hash256 "$R/out/parser/baseline.jar")")
expect "manifest: parser line" "$want_line" "$(awk -F'\t' '$1 == "parser"' "$R/out/manifest.txt")"
expect "manifest: hdump line" "1" "$(awk -F'\t' '$1 == "hdump" && $3 == sha' sha="$(hash256 "$R/out/hdump/baseline.jar")" "$R/out/manifest.txt" | wc -l | tr -d ' ')"

# no worktree left behind
expect "worktrees removed after the run" "1" "$(git -C "$R" worktree list | wc -l | tr -d ' ')"

# 2. Cache: the second run skips fetched/built rows with the same tag and
#    a still-matching jar sha256.
run "file://$R/m2" "$R/out"
expect "cached run exits 0" "0" "$RC"
expect "every row cached" "5" "$(grep -c 'cached (tag v0.29.0)' <<<"$OUT")"
expect "no rebuild when cached" "1" "$(wc -l <"$R/stub.log" | tr -d ' ')"

# 3. sha1 mismatch -> exit 1 naming the surface, the URL and both hashes.
printf '0000000000000000000000000000000000000000  faked\n' \
    >"$R/m2/io/btrace/jafar-parser/0.29.0/jafar-parser-0.29.0.jar.sha1"
run "file://$R/m2" "$R/out-badsha"
expect "bad sha1 exits 1" "1" "$RC"
expect "bad sha1 names the surface" "1" "$(grep -c 'surface parser: sha1 mismatch' <<<"$OUT")"
expect "bad sha1 names the URL" "1" "$(grep -cF "file://$R/m2/io/btrace/jafar-parser/0.29.0/jafar-parser-0.29.0.jar" <<<"$OUT")"
expect "bad sha1 names expected and actual" "1" "$(grep -c 'expected 0000000000000000000000000000000000000000, got' <<<"$OUT")"

# 4. 404 -> exit 1 naming the URL.
run "file://$R/m2-empty" "$R/out-404"
expect "404 exits 1" "1" "$RC"
expect "404 names the URL" "1" "$(grep -cF "failed to fetch file://$R/m2-empty/io/btrace/jafar-parser/0.29.0/jafar-parser-0.29.0.jar" <<<"$OUT")"

# 5. Project absent at the baseline tag -> exit 1 naming project and tag.
cat >"$R/surfaces-ghost.txt" <<'EOF'
ghost :ghost-mod shell tag pkg:io.jafar.ghost.api
EOF
OUT=$( (cd "$R" && JAFAR_MAVEN_REPO_URL="file://$R/m2" \
    JAFAR_API_BASELINE_GRADLEW="$R/stub-gradlew" JAFAR_STUB_LOG="$R/stub.log" \
    "$script" --surfaces "$R/surfaces-ghost.txt" --out "$R/out-ghost") 2>&1 ) && RC=0 || RC=$?
expect "absent project exits 1" "1" "$RC"
expect "absent project names project and tag" "1" "$(grep -cF 'surface ghost: project :ghost-mod is absent at tag v0.29.0' <<<"$OUT")"

# 6. A failing gradlew -> exit 1 (loud, never a silent skip) and the worktree
#    is still removed.
cat >"$R/surfaces-tags.txt" <<'EOF'
hdump :hdump-parser shell tag pkg:io.jafar.hdump.api
EOF
OUT=$( (cd "$R" && JAFAR_MAVEN_REPO_URL="file://$R/m2" \
    JAFAR_API_BASELINE_GRADLEW=/bin/false JAFAR_STUB_LOG="$R/stub.log" \
    "$script" --surfaces "$R/surfaces-tags.txt" --out "$R/out-fail") 2>&1 ) && RC=0 || RC=$?
expect "failing gradlew exits 1" "1" "$RC"
expect "failing gradlew names the tag" "1" "$(grep -c 'tag v0.29.0' <<<"$OUT")"
expect "worktree removed after a failed build" "1" "$(git -C "$R" worktree list | wc -l | tr -d ' ')"

# 7. --only: builds just the named surface; unknown names are refused (R3).
run "file://$R/m2" "$R/out-only" --only hdump
expect "--only exits 0" "0" "$RC"
expect "--only: one manifest line" "1" "$(grep -c . "$R/out-only/manifest.txt")"
expect "--only: only the named surface materialized" "hdump" "$(find "$R/out-only" -name BASELINE | sed "s|$R/out-only/||;s|/BASELINE||")"
run "file://$R/m2" "$R/out-only-bad" --only nope
expect "unknown --only name exits 1" "1" "$RC"
expect "unknown --only name is refused" "1" "$(grep -c "unknown surface 'nope'" <<<"$OUT")"

# 8. Plane with no baseline tag at all -> exit 1 naming the plane (never
#    0.0.0, R3); the surface is named too.
mkrepo # fresh repo, no tags
mkdir -p "$R/hdump-parser"
printf 'stub module\n' >"$R/hdump-parser/build.gradle"
commit_all modules
cat >"$R/surfaces-notag.txt" <<'EOF'
weird :hdump-parser mcp tag pkg:io.jafar.hdump.api
EOF
OUT=$( (cd "$R" && JAFAR_MAVEN_REPO_URL="file://$R/m2" \
    JAFAR_API_BASELINE_GRADLEW="$R/stub-gradlew" \
    "$script" --surfaces "$R/surfaces-notag.txt" --out "$R/out-notag") 2>&1 ) && RC=0 || RC=$?
expect "no baseline tag exits 1" "1" "$RC"
expect "no baseline tag names the plane" "1" "$(grep -c 'no baseline tag for plane mcp' <<<"$OUT")"
expect "no baseline tag names the surface" "1" "$(grep -cF 'surface weird: baseline derivation failed for plane mcp' <<<"$OUT")"

# 9. Unknown plane in the table -> exit 1 (derive-version rejects it).
cat >"$R/surfaces-badplane.txt" <<'EOF'
bad :hdump-parser nonsense tag pkg:io.jafar.hdump.api
EOF
OUT=$( (cd "$R" && JAFAR_MAVEN_REPO_URL="file://$R/m2" \
    JAFAR_API_BASELINE_GRADLEW="$R/stub-gradlew" \
    "$script" --surfaces "$R/surfaces-badplane.txt" --out "$R/out-badplane") 2>&1 ) && RC=0 || RC=$?
expect "unknown plane exits 1" "1" "$RC"
expect "unknown plane named" "1" "$(grep -c 'Unknown plane: nonsense' <<<"$OUT")"
expect "unknown plane names the surface" "1" "$(grep -cF 'surface bad: baseline derivation failed for plane nonsense' <<<"$OUT")"

# 10. parse_surfaces: the sourced surface->plane/table mapping (dispatch
#     contract), plus malformed rows refused (R3).
mkrepo
cat >"$R/surfaces.txt" <<'EOF'
# comment line, ignored
parser :parser core maven:jafar-parser pkg:io.jafar.parser.api
llm-spi :shell-core shell tag pkg:io.jafar.shell.core.llm
EOF
map=$(parse_surfaces "$R/surfaces.txt")
expect "mapping: parser row" "$(printf 'parser\t:parser\tcore\tmaven:jafar-parser\tpkg:io.jafar.parser.api')" "$(printf '%s\n' "$map" | head -1)"
expect "mapping: llm-spi row" "$(printf 'llm-spi\t:shell-core\tshell\ttag\tpkg:io.jafar.shell.core.llm')" "$(printf '%s\n' "$map" | tail -1)"
cat >"$R/surfaces-malformed.txt" <<'EOF'
parser :parser core maven:jafar-parser
EOF
OUT=$( (parse_surfaces "$R/surfaces-malformed.txt") 2>&1 ) && RC=0 || RC=$?
expect "malformed row exits 1" "1" "$RC"
expect "malformed row is named" "1" "$(grep -c 'malformed surfaces row' <<<"$OUT")"
cat >"$R/surfaces-badproj.txt" <<'EOF'
parser parser core maven:jafar-parser pkg:io.jafar.parser.api
EOF
OUT=$( (parse_surfaces "$R/surfaces-badproj.txt") 2>&1 ) && RC=0 || RC=$?
expect "project without ':' refused" "1" "$RC"

rm -rf "$R" 2>/dev/null || true
printf '%s passed, %s failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
