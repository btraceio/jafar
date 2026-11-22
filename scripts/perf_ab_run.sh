#!/usr/bin/env bash
# perf_ab_run.sh — run the performance A/B comparison of two commits on this machine.
#
# Step 3 of doc/plans/perf-ab.md. .github/workflows/perf-ab.yml calls this script; it runs the
# same way on a developer machine, which is how it is tested.
#
#   scripts/perf_ab_run.sh --base REF --head REF --out DIR [options]
#
# What it does, in order:
#   1. The base is `git merge-base <base> <head>` (with --exact-base, <base> itself): the PR's
#      own effect, not that of commits the base branch gained since the PR branched.
#   2. Both commits are checked out as detached worktrees under DIR/wt.
#   3. Benchmark classes whose source differs between the two (parser-codegen/src/main/jmh) are
#      passed to perf_ab.py as --changed-class: each side ran its own version of them, so their
#      numbers are not compared.
#   4. JMH runs in the order base, head, head, base (A B B A), each invocation in its own
#      worktree, so drift during the run does not favour one side. Timing runs carry no profiler.
#   5. scripts/perf_ab.py turns the four result files into DIR/report.md and DIR/report.json.
#   6. Attribution: every benchmark that perf_ab.py calls slower or faster, or, while no
#      calibration exists, every one with p < 0.01, gets one more fork per side under JMH's JFR
#      profiler (stackDepth=2048; JFR keeps 64 frames by default), and the two recordings are
#      compared with `jfr-shell compare --json`, built from this script's own checkout.
#   7. DIR/summary.md is report.md plus the attribution tables; the workflow writes it to the
#      job summary.
#
# Options:
#   --benchmarks REGEX   JMH benchmark regex (default: the six QueryPushdownBenchmark methods of
#                        doc/plans/perf-ab.md section 2)
#   --params "ARGS"      JMH -p options (default: -p generatedReader=true -p targetType=bench.Sampled)
#   --jmh "ARGS"         JMH options per timing invocation (default: -f 3 -wi 3 -i 5)
#   --profile-jmh "ARGS" JMH options for each profiled fork (default: -f 1 -wi 3 -i 5)
#   --calibration FILE   perf_ab.py calibration file (default: scripts/perf-ab-calibration.json
#                        when it exists)
#   --exact-base         compare against REF itself, not the merge base
#   --no-attribution     skip step 6
#   --java25 PATH        JAVA_HOME of a JDK 25 for jfr-shell (default: $JAVA_HOME_25_X64)
#
# Exit status: 0 whenever the comparison ran, whatever it found: timing is never a gate
# (Gates.md, principle 3). Non-zero only when it could not run: a bad ref, a JMH or Gradle
# failure, or perf_ab.py refusing its data (exit 2, e.g. a run where every benchmark failed).
set -euo pipefail

repo="$(cd "$(dirname "$0")/.." && pwd)"

base_ref=""
head_ref=""
out=""
benchmarks='QueryPushdownBenchmark\.(headersWithStartTime|countEventsFromHeaders|typeFilterInParser|decodeAllFilterInHandler|countByDecoding|ceilingSkipEveryEvent)$'
params="-p generatedReader=true -p targetType=bench.Sampled"
jmh="-f 3 -wi 3 -i 5"
profile_jmh="-f 1 -wi 3 -i 5"
calibration=""
exact_base=false
attribution=true
java25="${JAVA_HOME_25_X64:-}"

die() {
    echo "perf_ab_run: $*" >&2
    exit 1
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --base) base_ref="$2"; shift 2 ;;
        --head) head_ref="$2"; shift 2 ;;
        --out) out="$2"; shift 2 ;;
        --benchmarks) benchmarks="$2"; shift 2 ;;
        --params) params="$2"; shift 2 ;;
        --jmh) jmh="$2"; shift 2 ;;
        --profile-jmh) profile_jmh="$2"; shift 2 ;;
        --calibration) calibration="$2"; shift 2 ;;
        --exact-base) exact_base=true; shift ;;
        --no-attribution) attribution=false; shift ;;
        --java25) java25="$2"; shift 2 ;;
        -h|--help) sed -n '2,/^set -euo/p' "$0" | sed 's/^# \{0,1\}//;$d'; exit 0 ;;
        *) die "unknown option $1 (see --help)" ;;
    esac
done
[ -n "$base_ref" ] && [ -n "$head_ref" ] && [ -n "$out" ] || die "--base, --head and --out are required"
case "$benchmarks" in *" "*) die "--benchmarks must not contain spaces (Gradle splits -PjmhArgs at spaces)" ;; esac
if [ -z "$calibration" ] && [ -f "$repo/scripts/perf-ab-calibration.json" ]; then
    calibration="$repo/scripts/perf-ab-calibration.json"
fi

mkdir -p "$out"
out="$(cd "$out" && pwd)"
logs="$out/logs"
mkdir -p "$logs"

# ------------------------------------------------------------------------------- 1. commits
head_sha="$(git -C "$repo" rev-parse --verify "$head_ref^{commit}")" || die "no commit $head_ref"
base_tip="$(git -C "$repo" rev-parse --verify "$base_ref^{commit}")" || die "no commit $base_ref"
if $exact_base; then
    base_sha="$base_tip"
else
    base_sha="$(git -C "$repo" merge-base "$base_tip" "$head_sha")" || die "no merge base of $base_ref and $head_ref"
fi
echo "perf_ab_run: base $base_sha ($base_ref$($exact_base || echo ', merge base')), head $head_sha ($head_ref)"

# ---------------------------------------------------------------------------- runner facts
{
    echo "base: $base_sha"
    echo "head: $head_sha"
    echo "nproc: $(nproc)"
    grep -m1 'model name' /proc/cpuinfo 2>/dev/null || true
    grep -m1 MemTotal /proc/meminfo 2>/dev/null || true
    echo "jmh: $jmh $params"
    echo "benchmarks: $benchmarks"
} > "$out/runner.txt"
cat "$out/runner.txt"

# ------------------------------------------------------------------------------ 2. worktrees
wt="$out/wt"
cleanup() {
    for side in base head; do
        if [ -d "$wt/$side" ]; then
            git -C "$repo" worktree remove --force "$wt/$side" >/dev/null 2>&1 || true
        fi
    done
}
trap cleanup EXIT
for side in base head; do
    sha="$base_sha"
    [ "$side" = head ] && sha="$head_sha"
    if [ -d "$wt/$side" ]; then
        git -C "$repo" worktree remove --force "$wt/$side" >/dev/null 2>&1 || rm -rf "${wt:?}/$side"
    fi
    git -C "$repo" worktree add --detach "$wt/$side" "$sha" >/dev/null
done

# ---------------------------------------------------------------- 3. benchmarks changed by the PR
changed=()
while IFS= read -r path; do
    [ -n "$path" ] || continue
    name="$(basename "$path" .java)"
    changed+=(--changed-class "$name")
    echo "perf_ab_run: benchmark source changed, not compared: $name"
done < <(git -C "$repo" diff --name-only "$base_sha" "$head_sha" -- 'parser-codegen/src/main/jmh/*.java')

# ------------------------------------------------------------------------- 4. timing rounds
run_jmh() { # run_jmh <side> <log name> <jmh args...>
    local side="$1" log="$2"
    shift 2
    echo "perf_ab_run: $(date -u +%H:%M:%S) $log"
    if ! (cd "$wt/$side" && ./gradlew --no-daemon -q :parser-codegen:jmh -PjmhArgs="$*") > "$logs/$log.log" 2>&1; then
        tail -40 "$logs/$log.log" >&2
        die "JMH failed in the $side worktree ($logs/$log.log)"
    fi
}

round=0
for side in base head head base; do
    round=$((round + 1))
    # shellcheck disable=SC2086 # the option strings are word-split on purpose
    run_jmh "$side" "timing-$round-$side" $jmh $params -rf json -rff "$out/$side-r$round.json" "$benchmarks"
done

# ------------------------------------------------------------------------------ 5. verdicts
ab=(python3 "$repo/scripts/perf_ab.py")
for f in "$out"/base-r*.json; do ab+=(--base "$f"); done
for f in "$out"/head-r*.json; do ab+=(--head "$f"); done
[ -n "$calibration" ] && ab+=(--calibration "$calibration")
ab+=(${changed[@]+"${changed[@]}"} --json "$out/report.json")
"${ab[@]}" > "$out/report.md"
cp "$out/report.md" "$out/summary.md"

# --------------------------------------------------------------------------- 6. attribution
if $attribution; then
    # One line per benchmark to attribute: <benchmark>\t<JMH -p options>.
    python3 - "$out/report.json" > "$out/attribute.tsv" <<'PY'
import json, sys
report = json.load(open(sys.argv[1]))
alpha = report["alpha"]
for row in report["results"]:
    verdict = row["verdict"]
    p = (row.get("test") or {}).get("p")
    if verdict in ("slower", "faster") or (verdict == "uncalibrated" and p is not None and p < alpha):
        params = " ".join(f"-p {k}={v}" for k, v in sorted((row.get("params") or {}).items()))
        print(f"{row['benchmark']}\t{params}")
PY
    if [ -s "$out/attribute.tsv" ]; then
        [ -n "$java25" ] && [ -x "$java25/bin/java" ] || die "attribution needs a JDK 25 for jfr-shell: pass --java25 or set JAVA_HOME_25_X64"
        # From this script's own checkout, not the head worktree: `jfr-shell compare` exists
        # only from the commit this script came with on, and a head (or a dispatched ref) may
        # predate it. In CI on a PR the two are the same commit.
        echo "perf_ab_run: building jfr-shell in $repo for attribution"
        if ! (cd "$repo" && ./gradlew --no-daemon -q :jfr-shell:shadowJar :jfr-shell-jafar:jar) > "$logs/jfr-shell-build.log" 2>&1; then
            tail -40 "$logs/jfr-shell-build.log" >&2
            die "building jfr-shell failed ($logs/jfr-shell-build.log)"
        fi
        # The newest: an earlier build of another version can leave its jars in build/libs.
        shell_jar="$(ls -t "$repo"/jfr-shell/build/libs/jfr-shell-*-all.jar | head -1)"
        backend_jar="$(ls -t "$repo"/jfr-shell-jafar/build/libs/jfr-shell-jafar-*.jar | grep -v -E -- '-(sources|javadoc)\.jar$' | head -1)"
        mkdir -p "$out/attribution" "$out/no-plugins"
        {
            echo
            echo "## Attribution (JFR, one profiled fork per side)"
            echo
            echo "_Where time moved inside each benchmark with a significant change. A share shift is attribution, not proof: only the table above says whether a benchmark got slower._"
        } >> "$out/summary.md"
        index=0
        while IFS=$'\t' read -r bench bench_params; do
            index=$((index + 1))
            regex="^$(printf '%s' "$bench" | sed 's/[.$]/\\&/g')\$"
            for side in base head; do
                # shellcheck disable=SC2086
                run_jmh "$side" "profile-$index-$side" $profile_jmh $bench_params \
                    "-prof" "jfr:dir=$out/jfr/$index/$side;stackDepth=2048" "$regex"
            done
            base_jfr="$(find "$out/jfr/$index/base" -name profile.jfr | head -1)"
            head_jfr="$(find "$out/jfr/$index/head" -name profile.jfr | head -1)"
            [ -n "$base_jfr" ] && [ -n "$head_jfr" ] || die "the JFR profiler wrote no recording for $bench"
            compare=("$java25/bin/java" -Djfr.shell.plugin.dir="$out/no-plugins" -Djfr.shell.registry.remote=false
                -Djfr.shell.backend.jars="$backend_jar" -jar "$shell_jar" compare "$base_jfr" "$head_jfr")
            "${compare[@]}" --json > "$out/attribution/$index.json" 2> "$logs/compare-$index.log" \
                || die "jfr-shell compare failed for $bench ($logs/compare-$index.log)"
            "${compare[@]}" > "$out/attribution/$index.txt" 2>> "$logs/compare-$index.log" \
                || die "jfr-shell compare failed for $bench ($logs/compare-$index.log)"
            {
                echo
                echo "### ${bench##*.} ${bench_params//-p /}"
                echo
                echo '```text'
                # A frame name comes from the PR's code: keep it from closing the fence.
                sed 's/```/` ` `/g' "$out/attribution/$index.txt"
                echo '```'
            } >> "$out/summary.md"
        done < "$out/attribute.tsv"
    else
        echo "perf_ab_run: no benchmark to attribute"
        printf '\n_No JFR attribution was run: no compared benchmark changed significantly._\n' >> "$out/summary.md"
    fi
fi

echo "perf_ab_run: done: $out/summary.md"
