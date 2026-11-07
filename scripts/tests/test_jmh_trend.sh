#!/usr/bin/env bash
# test_jmh_trend.sh — hermetic tests for scripts/jmh_trend.py (R5 on the gate).
#
# The trend report is advisory, so its exit code must be about the data, never
# the numbers: both sides present -> 0; a 50% regression -> still 0; no previous
# -> "no baseline", 0; malformed JSON on either side -> 2, naming the file
# (R3: refuse to pass on garbage). All fixtures are hand-written JMH result
# JSON; the real Gradle JMH run feeding the same script is exercised in the
# nightly workflow and documented in doc/agents/Gates.md.
set -euo pipefail

repo="$(cd "$(dirname "$0")/../.." && pwd)"
script="$repo/scripts/jmh_trend.py"

pass=0
fail=0
W=""

trap 'rm -rf "$W" 2>/dev/null || true' EXIT

# run <description> <expected-exit> [required-output-substrings ...] -- <script args...>
run() {
    local desc="$1" want_rc="$2"
    shift 2
    local greps=()
    while [ "$#" -gt 0 ] && [ "$1" != "--" ]; do
        greps+=("$1")
        shift
    done
    [ "$#" -gt 0 ] && shift # drop the --
    local out rc=0 g
    out=$(python3 "$script" "$@" 2>&1) || rc=$?
    if [ "$rc" != "$want_rc" ]; then
        printf 'FAIL %s: exit %s, wanted %s\n%s\n' "$desc" "$rc" "$want_rc" "$out"
        fail=$((fail + 1))
        return
    fi
    for g in ${greps[@]+"${greps[@]}"}; do
        if [ -n "$g" ] && ! grep -qF -- "$g" <<<"$out"; then
            printf 'FAIL %s: output lacks %s\n%s\n' "$desc" "$g" "$out"
            fail=$((fail + 1))
            return
        fi
    done
    printf 'ok   %s\n' "$desc"
    pass=$((pass + 1))
}

# One JMH-shaped result record: the real -rf json shape (read from an actual
# run, not guessed): benchmark and params at the top level, the numbers under
# "primaryMetric", and scoreError as the string "NaN" when only one
# measurement iteration ran (the short nightly verification default).
rec() { # rec <benchmark> <score> [scoreError] [paramfile]
    local bench="$1" score="$2" err="${3:-NaN}" filev="${4:-}"
    local params=''
    [ -n "$filev" ] && params=",\"params\":{\"file\":\"$filev\"}"
    printf '[{"benchmark":"%s","mode":"avgt","threads":1,"forks":1%s,"primaryMetric":{"score":%s,"scoreError":"%s","scoreUnit":"us/op"}}]' \
        "$bench" "$params" "$score" "$err"
}

# 1. Both sides present, identical scores -> 0 with a 0.0% delta.
W=$(mktemp -d)
rec io.jafar.parser.benchmark.X.parse 100 2 test.jfr > "$W/prev.json"
mkdir "$W/cur"
rec io.jafar.parser.benchmark.X.parse 100 2 test.jfr > "$W/cur/parser-core.json"
run "both present, no change: 0 with +0.0%" 0 "io.jafar.parser.benchmark.X.parse" "+0.0%" -- --current "$W/cur" --previous "$W"

# 2. A 50% regression (100 -> 50) is advisory: still exit 0, delta shown.
W=$(mktemp -d)
rec io.jafar.parser.benchmark.X.parse 100 2 test.jfr > "$W/prev.json"
mkdir "$W/cur"
rec io.jafar.parser.benchmark.X.parse 50 1 test.jfr > "$W/cur/parser-core.json"
run "50% regression: still 0, delta -50.0%" 0 "-50.0%" -- --current "$W/cur" --previous "$W"

# 3. An improvement is the same story: 0.
W=$(mktemp -d)
rec io.jafar.parser.benchmark.X.parse 100 2 test.jfr > "$W/prev.json"
mkdir "$W/cur"
rec io.jafar.parser.benchmark.X.parse 110 2 test.jfr > "$W/cur/parser-core.json"
run "improvement: 0, delta +10.0%" 0 "+10.0%" -- --current "$W/cur" --previous "$W"

# 4. No --previous at all -> "no baseline", 0.
W=$(mktemp -d)
mkdir "$W/cur"
rec io.jafar.parser.benchmark.X.parse 100 2 test.jfr > "$W/cur/parser-core.json"
run "no previous: no baseline, 0" 0 "no baseline" -- --current "$W/cur"

# 5. A --previous directory that does not exist (first nightly run, download
#    skipped) is the same advisory case: no baseline, 0.
W=$(mktemp -d)
mkdir "$W/cur"
rec io.jafar.parser.benchmark.X.parse 100 2 test.jfr > "$W/cur/parser-core.json"
run "missing previous dir: no baseline, 0" 0 "no baseline" -- --current "$W/cur" --previous "$W/no-such-dir"

# 6. Malformed JSON in the current side -> 2, naming the file.
W=$(mktemp -d)
mkdir "$W/cur" "$W/prev"
printf '{"benchmark": ' > "$W/cur/parser-core.json"
rec io.jafar.parser.benchmark.X.parse 100 2 test.jfr > "$W/prev/parser-core.json"
run "malformed current: 2, names the file" 2 "cur/parser-core.json" -- --current "$W/cur" --previous "$W/prev"

# 7. Malformed JSON in the previous side -> 2, naming the file.
W=$(mktemp -d)
mkdir "$W/cur" "$W/prev"
rec io.jafar.parser.benchmark.X.parse 100 2 test.jfr > "$W/cur/parser-core.json"
printf 'not json at all' > "$W/prev/parser-core.json"
run "malformed previous: 2, names the file" 2 "prev/parser-core.json" -- --current "$W/cur" --previous "$W/prev"

# 8. A --current directory with no JSON files: nothing to report -> 2 (R3).
W=$(mktemp -d)
mkdir "$W/cur"
run "empty current dir: refused, 2" 2 "no JMH result JSON files" -- --current "$W/cur"

# 9. A record lacking the fields the table is built from is malformed -> 2.
W=$(mktemp -d)
mkdir "$W/cur"
printf '[{"mode":"avgt","primaryMetric":{"score":1}}]' > "$W/cur/parser-core.json"
run "record without benchmark: 2" 2 "lacks 'benchmark'" -- --current "$W/cur"

W=$(mktemp -d)
mkdir "$W/cur"
printf '[{"benchmark":"io.jafar.X","mode":"avgt","score":1}]' > "$W/cur/parser-core.json"
run "record without primaryMetric: 2" 2 "lacks 'primaryMetric'" -- --current "$W/cur"

# 9b. A non-numeric primaryMetric.score is malformed -> 2.
W=$(mktemp -d)
mkdir "$W/cur"
printf '[{"benchmark":"io.jafar.X","primaryMetric":{"score":"fast"}}]' > "$W/cur/parser-core.json"
run "non-numeric score: 2" 2 "is not a number" -- --current "$W/cur"

# 10. Two result files in the current dir merge into one table (the nightly
#     uploads parser-core.json and pprof-parser.json side by side); a benchmark
#     whose params differ from the previous run shows up as new.
W=$(mktemp -d)
mkdir "$W/cur" "$W/prev"
rec io.jafar.parser.benchmark.X.parse 100 2 test.jfr > "$W/cur/parser-core.json"
rec io.jafar.pprof.benchmark.P.parse 200 4 > "$W/cur/pprof-parser.json"
rec io.jafar.parser.benchmark.X.parse 100 2 old.jfr > "$W/prev/parser-core.json"
rec io.jafar.pprof.benchmark.P.parse 200 4 > "$W/prev/pprof-parser.json"
run "merged modules: both benchmarks, new params flagged" 0 \
    "io.jafar.parser.benchmark.X.parse" "io.jafar.pprof.benchmark.P.parse" "new" \
    -- --current "$W/cur" --previous "$W/prev"

# 11. A benchmark present only in the previous run is reported as missing, not
#     silently dropped — but the run is still 0.
W=$(mktemp -d)
mkdir "$W/cur" "$W/prev"
rec io.jafar.parser.benchmark.X.parse 100 2 test.jfr > "$W/cur/parser-core.json"
rec io.jafar.parser.benchmark.X.parse 100 2 test.jfr > "$W/prev/parser-core.json"
rec io.jafar.pprof.benchmark.P.parse 200 4 > "$W/prev/pprof-parser.json"
run "benchmark gone since previous: noted, still 0" 0 "not this one" -- --current "$W/cur" --previous "$W/prev"

# 12. `gh run download` unpacks the artifact at its root, but an artifact
#     uploaded from a higher directory unpacks nested (e.g. <dir>/build/jmh/).
#     Both layouts are compared, not just the flat one.
W=$(mktemp -d)
mkdir -p "$W/cur" "$W/prev/build/jmh"
rec io.jafar.parser.benchmark.X.parse 110 2 test.jfr > "$W/cur/parser-core.json"
rec io.jafar.parser.benchmark.X.parse 100 2 test.jfr > "$W/prev/build/jmh/parser-core.json"
run "nested previous layout: still compared" 0 "+10.0%" -- --current "$W/cur" --previous "$W/prev"

printf '%s passed, %s failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
