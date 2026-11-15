#!/usr/bin/env bash
set -euo pipefail

# ---------------------------------------------------------------------------
# api-baseline.sh — materialize the japicmp baseline artifact for every gated
# API surface in gradle/api-surfaces.txt (the single list of surfaces, R7).
#
# Per row, the baseline tag is derived per plane via
# scripts/derive-version.sh --plane <P> --baseline-tag — never a hardcoded
# version, never 0.0.0: a plane with no qualifying tag fails loudly, naming
# the plane and the surface (R3).
#
#   maven rows  download io.btrace:<artifactId>:<version> directly from
#              ${JAFAR_MAVEN_REPO_URL:-https://repo1.maven.org/maven2} with
#              curl and verify the jar's .sha1. Baselines are NEVER resolved
#              through Gradle: settings.gradle's composite substitution would
#              replace io.btrace:jafar-* with the current project and compare
#              current code with itself (spec Risk 1 / AC-1.4). When the repo
#              override is set it is printed, so a test repo is never silent
#              (R3).
#   tag rows   build :<project>:jar in a TEMPORARY git worktree of the plane's
#              baseline tag — outside the repo tree, so the doc-link and
#              failing-set scans never see it — then copy the plain jar
#              (never -sources, -javadoc, -all or -plain). Rows sharing a tag
#              are built in ONE gradle invocation. The worktrees are removed
#              and pruned on exit, even on failure.
#
# Output (default build/api-baseline/), per surface <s>:
#   <out>/<s>/baseline.jar  the baseline jar
#   <out>/<s>/BASELINE      provenance stamp (key=value): surface, kind, tag,
#                           version, sha256; maven rows add coordinates and
#                           url, tag rows add commit. gradle/api-compat.gradle
#                           logs this provenance and refuses a baseline equal
#                           to the current output (AC-1.4).
#   <out>/manifest.txt      one line per surface:
#                           <surface>\t<path>\t<sha256-of-jar>
#
# A row whose stamp already carries the same tag and a matching jar sha256 is
# skipped with "cached".
#
# Run from inside the repository whose tags define the baselines. Gradle in
# the tag worktree inherits this shell's JAVA_HOME and runs
# ${JAFAR_API_BASELINE_GRADLEW:-./gradlew} --no-daemon (the override exists so
# the hermetic tests can stub the build; the real fetch against
# repo1.maven.org runs in CI, never in a test — network in tests is flake).
#
# Usage: api-baseline.sh [--surfaces F] [--out DIR] [--only S[,S...]]
#
# Every function is defined before main(), and main() only runs when this
# file is executed, so scripts/tests/test_api_baseline.sh can source it and
# unit-test the table parsing. The surface->plane mapping lives in the table,
# not in code (one list, R7).
# ---------------------------------------------------------------------------

die() { printf 'api-baseline: %s\n' "$*" >&2; exit 1; }

# Directory of this script (works when executed and when sourced).
self_dir() { cd "$(dirname "${BASH_SOURCE[0]}")" && pwd; }

sha_of() { # sha_of <1|256> <file> -> hex digest
    if command -v shasum >/dev/null 2>&1; then
        shasum -a "$1" "$2" | awk '{print $1}'
    elif command -v "sha$1sum" >/dev/null 2>&1; then
        "sha$1sum" "$2" | awk '{print $1}'
    else
        die "no sha tool found (need shasum or sha$1sum)"
    fi
}

stamp_get() { # stamp_get <BASELINE file> <key> -> value (empty when absent)
    [[ -f "$1" ]] || return 0
    awk -F= -v k="$2" '$1 == k { sub(/^[^=]*=/, ""); print; exit }' "$1"
}

# parse_surfaces <file>: the unit-testable read of the surface table. Prints
# one TSV row per surface: surface, project, plane, baseline, includes.
# Blank lines and '#' comments are skipped; malformed rows are refused (R3).
parse_surfaces() {
    local f="$1" line surface project plane baseline includes rest
    [[ -f "$f" ]] || die "surfaces file not found: $f"
    while IFS= read -r line || [[ -n "$line" ]]; do
        [[ -z "${line//[[:space:]]/}" ]] && continue
        [[ "$line" =~ ^[[:space:]]*\# ]] && continue
        read -r surface project plane baseline includes rest <<<"$line"
        if [[ -z "$surface" || -z "$project" || -z "$plane" || -z "$baseline" || -z "$includes" ]]; then
            die "malformed surfaces row (want: surface project plane baseline includes): $line"
        fi
        [[ -z "$rest" ]] || die "unexpected extra column in surfaces row: $line"
        [[ "$project" == :* ]] || die "project column must start with ':' (got '$project'): $line"
        case "$baseline" in
            tag) ;;
            maven:*)
                [[ "${baseline#maven:}" =~ ^[A-Za-z0-9._-]+$ ]] \
                    || die "bad artifactId in baseline column: $line"
                ;;
            *) die "baseline column must be 'tag' or 'maven:<artifactId>': $line" ;;
        esac
        printf '%s\t%s\t%s\t%s\t%s\n' "$surface" "$project" "$plane" "$baseline" "$includes"
    done <"$f"
}

# derive <plane> <mode> <surface>: run scripts/derive-version.sh; a failed
# derivation never passes silently — it names the surface and the plane
# (derive-version itself prints "no baseline tag for plane P", never 0.0.0).
derive() {
    local result
    if ! result=$("$(self_dir)/derive-version.sh" --plane "$1" "$2"); then
        die "surface $3: baseline derivation failed for plane $1 (see message above)"
    fi
    printf '%s\n' "$result"
}

install_baseline() { # install_baseline <surface> <jar>
    mkdir -p "$OUT/$1"
    cp "$2" "$OUT/$1/baseline.jar"
}

write_stamp() { # write_stamp <surface> <key=value>...
    local s="$1"; shift
    mkdir -p "$OUT/$s"
    : >"$OUT/$s/BASELINE"
    local kv
    for kv in "$@"; do printf '%s\n' "$kv" >>"$OUT/$s/BASELINE"; done
}

# is_cached <surface> <tag>: true when the stamp's tag matches and the local
# jar's sha256 still equals the stamped one.
is_cached() {
    local s="$1" t="$2" stamp="$OUT/$1/BASELINE" want have
    [[ -f "$stamp" && -f "$OUT/$s/baseline.jar" ]] || return 1
    [[ "$(stamp_get "$stamp" tag)" == "$t" ]] || return 1
    want=$(stamp_get "$stamp" sha256)
    [[ -n "$want" ]] || return 1
    have=$(sha_of 256 "$OUT/$s/baseline.jar")
    [[ "$want" == "$have" ]]
}

# fetch_maven <surface> <artifactId> <version> <tag>
fetch_maven() {
    local s="$1" a="$2" v="$3" tag="$4"
    local repo_url="${JAFAR_MAVEN_REPO_URL:-https://repo1.maven.org/maven2}"
    if [[ -n "${JAFAR_MAVEN_REPO_URL:-}" ]]; then
        printf 'api-baseline: maven repo override in effect: %s\n' "$repo_url"
    fi
    local url="$repo_url/io/btrace/$a/$v/$a-$v.jar"
    local dl="$SCRATCH/$a-$v.jar"
    if ! curl -fsSL -o "$dl" "$url"; then
        die "surface $s: failed to fetch $url (missing or unreachable)"
    fi
    if ! curl -fsSL -o "$dl.sha1" "$url.sha1"; then
        die "surface $s: failed to fetch $url.sha1"
    fi
    local expected actual
    # .sha1 files may carry "hash  filename"; only the first word is the digest
    expected=$(awk '{print $1}' "$dl.sha1")
    actual=$(sha_of 1 "$dl")
    if [[ "$expected" != "$actual" ]]; then
        die "surface $s: sha1 mismatch for $url: expected $expected, got $actual"
    fi
    install_baseline "$s" "$dl"
    local sha256; sha256=$(sha_of 256 "$OUT/$s/baseline.jar")
    write_stamp "$s" "surface=$s" "kind=maven" "tag=$tag" "version=$v" \
        "sha256=$sha256" "coordinates=io.btrace:$a:$v" "url=$url"
    printf 'api-baseline: %s: maven io.btrace:%s:%s (tag %s) sha256=%s\n' \
        "$s" "$a" "$v" "$tag" "$sha256"
}

# build_tag_group <tag> <rows>: rows are newline-separated "surface project"
# pairs sharing <tag>. Creates ONE detached worktree per tag, runs ONE gradle
# invocation for all its projects, then copies each jar and writes its stamp.
build_tag_group() {
    local tag="$1" rows="$2"
    local wt_name; wt_name="checkout-$(printf '%s' "$tag" | tr '/' '-')"
    local wt="$TAGTMP/$wt_name"
    if ! git worktree add --detach "$wt" "$tag" >/dev/null; then
        die "failed to create a git worktree for tag $tag"
    fi
    WORKTREES[${#WORKTREES[@]}]="$wt"

    # build once per tag: every project's :jar in a single invocation
    local surface project tasks="" row
    while IFS=' ' read -r surface project; do
        [[ -n "$surface" ]] || continue
        tasks="$tasks $project:jar"
    done <<<"$rows"
    # shellcheck disable=SC2086 # tasks is a deliberately word-split task list
    if ! (cd "$wt" && ${JAFAR_API_BASELINE_GRADLEW:-./gradlew} --no-daemon -q $tasks); then
        die "surface build failed in the tag worktree $wt (tag $tag, tasks:$tasks)"
    fi

    local projdir jar v commit sha256
    while IFS=' ' read -r surface project; do
        [[ -n "$surface" ]] || continue
        projdir="${project#:}" # jar base name == the Gradle project name
        jar=$(ls "$wt/$projdir/build/libs/$projdir-"*.jar 2>/dev/null \
            | grep -vE -- '-(sources|javadoc|all|plain)\.jar$' | head -1 || true)
        [[ -n "$jar" ]] || die "surface $surface: no jar built for $project at tag $tag under $projdir/build/libs"
        v=$(basename "$jar"); v="${v#$projdir-}"; v="${v%.jar}"
        commit=$(git rev-list -n1 "$tag")
        install_baseline "$surface" "$jar"
        sha256=$(sha_of 256 "$OUT/$surface/baseline.jar")
        write_stamp "$surface" "surface=$surface" "kind=tag" "tag=$tag" \
            "version=$v" "sha256=$sha256" "commit=$commit"
        printf 'api-baseline: %s: tag %s@%s (built) sha256=%s\n' \
            "$surface" "$tag" "$commit" "$sha256"
    done <<<"$rows"
}

# State shared by the helpers above; set by main(), also defaulted here so
# sourcing this file for its functions is safe.
OUT="" SCRATCH="" TAGTMP="" WORKTREES=()

main() {
    local surfaces="" outdir="" only=""
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --surfaces) surfaces="${2:?--surfaces needs a path}"; shift 2 ;;
            --out)      outdir="${2:?--out needs a path}";       shift 2 ;;
            --only)     only="${2:?--only needs surface names}";  shift 2 ;;
            *) printf 'usage: %s [--surfaces F] [--out DIR] [--only S[,S...]]\n' "$0" >&2; exit 2 ;;
        esac
    done
    command -v curl >/dev/null 2>&1 || die "curl not found"

    local repo; repo=$(cd "$(self_dir)/.." && pwd)
    local surfaces_file="${surfaces:-$repo/gradle/api-surfaces.txt}"
    OUT="${outdir:-$repo/build/api-baseline}"
    mkdir -p "$OUT"
    # temp dirs must sit OUTSIDE the repo tree so the doc-link and failing-set
    # scans never see a tag checkout (RUNNER_TEMP wins in CI)
    SCRATCH=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/jafar-api-baseline-dl.XXXX")
    TAGTMP=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/jafar-api-baseline.XXXX")
    WORKTREES=()
    cleanup() {
        local wt
        for wt in "${WORKTREES[@]:-}"; do
            [[ -n "$wt" ]] && git worktree remove --force "$wt" 2>/dev/null || true
        done
        git worktree prune 2>/dev/null || true
        rm -rf "$SCRATCH" "$TAGTMP" 2>/dev/null || true
    }
    trap cleanup EXIT

    local rows
    rows=$(parse_surfaces "$surfaces_file")

    # --only: filter to named surfaces; unknown names are refused (R3)
    if [[ -n "$only" ]]; then
        local WANT filtered="" want surface rest ok
        IFS=',' read -r -a WANT <<<"$only"
        for want in "${WANT[@]}"; do
            ok=""
            while IFS=$'\t' read -r surface rest; do
                [[ "$surface" == "$want" ]] && ok=1
            done <<<"$rows"
            [[ -n "$ok" ]] || die "unknown surface '$want' in --only (not in $surfaces_file)"
        done
        while IFS=$'\t' read -r surface rest; do
            for want in "${WANT[@]}"; do
                if [[ "$surface" == "$want" ]]; then
                    filtered="$filtered$surface"$'\t'"$rest"$'\n'
                    break
                fi
            done
        done <<<"$rows"
        # strip the trailing newline: a herestring appends one itself, and a
        # blank line would read as an all-empty row
        rows="${filtered%$'\n'}"
    fi

    # Pass 1: maven rows fetch immediately; tag rows collect per tag
    local surface project plane baseline includes tag version
    local tag_rows="" # newline-separated: tag \t surface \t project
    while IFS=$'\t' read -r surface project plane baseline includes; do
        [[ -n "$surface" ]] || continue # tolerate a stray blank line
        tag=$(derive "$plane" --baseline-tag "$surface")
        if is_cached "$surface" "$tag"; then
            printf 'api-baseline: %s: cached (tag %s)\n' "$surface" "$tag"
            continue
        fi
        if [[ "$baseline" == tag ]]; then
            # the compared project must exist at its baseline tag: an absent
            # project is a misconfigured row, never a silent skip
            if ! git cat-file -e "$tag:${project#:}" 2>/dev/null; then
                die "surface $surface: project $project is absent at tag $tag"
            fi
            tag_rows="$tag_rows$tag"$'\t'"$surface"$'\t'"$project"$'\n'
        else
            version=$(derive "$plane" --baseline "$surface")
            fetch_maven "$surface" "${baseline#maven:}" "$version" "$tag"
        fi
    done <<<"$rows"

    # Pass 2: one worktree + one gradle invocation per distinct tag
    if [[ -n "$tag_rows" ]]; then
        local t group
        while IFS= read -r t; do
            [[ -n "$t" ]] || continue
            group=$(printf '%s\n' "$tag_rows" | awk -F'\t' -v t="$t" '$1 == t {print $2" "$3}')
            build_tag_group "$t" "$group"
        done < <(printf '%s\n' "$tag_rows" | awk -F'\t' '{print $1}' | awk '!seen[$0]++')
    fi

    # manifest: one line per surface, from the jars actually on disk
    local manifest="$OUT/manifest.txt" sha
    : >"$manifest"
    while IFS=$'\t' read -r surface rest; do
        [[ -n "$surface" ]] || continue # tolerate a stray blank line
        [[ -f "$OUT/$surface/baseline.jar" ]] \
            || die "internal: no baseline.jar for $surface under $OUT"
        sha=$(sha_of 256 "$OUT/$surface/baseline.jar")
        printf '%s\t%s\t%s\n' "$surface" "$OUT/$surface/baseline.jar" "$sha" >>"$manifest"
    done <<<"$rows"
    printf 'api-baseline: manifest %s: %s surface(s)\n' "$manifest" "$(grep -c . "$manifest")"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    main "$@"
fi
