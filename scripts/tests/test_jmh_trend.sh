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

# 13. --require: a benchmark that throws is left out of the result JSON while
#     its Gradle task exits 0, so with other result files present the table
#     would just be shorter. A required benchmark with no record is refused.
W=$(mktemp -d)
mkdir "$W/cur"
rec io.jafar.parser.benchmark.X.parse 100 2 test.jfr > "$W/cur/parser-core.json"
rec io.jafar.parser.benchmark.QueryPushdownBenchmark.typeFilterInParser 70 5 > "$W/cur/pushdown.json"
run "required benchmark present, by Class.method: 0" 0 "typeFilterInParser" -- \
    --current "$W/cur" --require QueryPushdownBenchmark.typeFilterInParser
run "required benchmark present, by full name: 0" 0 "typeFilterInParser" -- \
    --current "$W/cur" --require io.jafar.parser.benchmark.QueryPushdownBenchmark.typeFilterInParser
run "one of two required benchmarks absent: 2, names it" 2 \
    "no result for required benchmark(s): QueryPushdownBenchmark.startTimeRangeNothing" -- \
    --current "$W/cur" --require QueryPushdownBenchmark.typeFilterInParser \
    --require QueryPushdownBenchmark.startTimeRangeNothing
# a name must match whole dot-separated parts, not any suffix of the text
run "required name is not a loose suffix match: 2" 2 "no result for required benchmark(s): InParser" -- \
    --current "$W/cur" --require InParser

# 14. The case --require exists for: the pushdown run wrote an empty array
#     (every benchmark threw). Without --require that is a shorter table and
#     exit 0; with it, exit 2.
W=$(mktemp -d)
mkdir "$W/cur"
rec io.jafar.parser.benchmark.X.parse 100 2 test.jfr > "$W/cur/parser-core.json"
printf '[]' > "$W/cur/pushdown.json"
run "empty result file beside a full one, nothing required: 0" 0 "X.parse" -- --current "$W/cur"
run "empty result file beside a full one, benchmark required: 2" 2 "no result for required benchmark(s)" -- \
    --current "$W/cur" --require QueryPushdownBenchmark.typeFilterInParser

# 15. --ratio: one benchmark's score over another's, within a run, paired on
#     the parameters both have. recp writes a record with arbitrary params.
recp() { # recp <benchmark> <score> <unit> [param=value ...]
    local bench="$1" score="$2" unit="$3"
    shift 3
    local params="" kv
    for kv in "$@"; do
        params="$params${params:+,}\"${kv%%=*}\":\"${kv#*=}\""
    done
    printf '{"benchmark":"%s","mode":"avgt","params":{%s},"primaryMetric":{"score":%s,"scoreError":"NaN","scoreUnit":"%s"}}' \
        "$bench" "$params" "$score" "$unit"
}
B=io.jafar.parser.benchmark.Q
ratio_fixture() { # ratio_fixture <dir> <all-rare> <filter-rare> <all-common> <filter-common> <ceiling>
    mkdir -p "$1"
    printf '[%s,%s,%s,%s,%s]' \
        "$(recp $B.all "$2" ms/op chunks=32 type=Rare)" \
        "$(recp $B.filter "$3" ms/op chunks=32 type=Rare)" \
        "$(recp $B.all "$4" ms/op chunks=32 type=Common)" \
        "$(recp $B.filter "$5" ms/op chunks=32 type=Common)" \
        "$(recp $B.ceiling "$6" ms/op chunks=32)" > "$1/pushdown.json"
}
W=$(mktemp -d)
ratio_fixture "$W/cur" 600 75 600 600 50
# the previous run was on a machine half as fast: every score doubles, no ratio moves
ratio_fixture "$W/prev" 1200 150 1200 1200 100
run "ratio paired on shared params: 8 for Rare, 1 for Common" 0 \
    "| speedup | chunks=32,type=Rare | 8 | - | new |" \
    "| speedup | chunks=32,type=Common | 1 | - | new |" \
    "- speedup = Q.all / Q.filter" -- \
    --current "$W/cur" --ratio "speedup=Q.all/Q.filter"
run "a slower machine moves every score and no ratio: +0.0%" 0 \
    "| speedup | chunks=32,type=Rare | 8 | 8 | +0.0% |" "-50.0%" -- \
    --current "$W/cur" --previous "$W/prev" --ratio "speedup=Q.all/Q.filter"
run "a denominator with fewer params pairs with every numerator row" 0 \
    "| over ceiling | chunks=32,type=Rare | 1.5 | - | new |" \
    "| over ceiling | chunks=32,type=Common | 12 | - | new |" -- \
    --current "$W/cur" --ratio "over ceiling=Q.filter/Q.ceiling"
run "a side narrowed with [param=value]" 0 \
    "| ceiling share | chunks=32 | 0.667 | - | new |" \
    "- ceiling share = Q.ceiling / Q.filter[type=Rare]" -- \
    --current "$W/cur" --ratio "ceiling share=Q.ceiling/Q.filter[type=Rare]"
# a real change in one benchmark shows as a ratio delta
ratio_fixture "$W/regressed" 600 150 600 600 50
run "the filter got twice as slow: the ratio halves" 0 \
    "| speedup | chunks=32,type=Rare | 4 | 8 | -50.0% |" -- \
    --current "$W/regressed" --previous "$W/prev" --ratio "speedup=Q.all/Q.filter"
# the previous run predates the benchmark: new, not an error
mkdir "$W/old"
rec io.jafar.parser.benchmark.X.parse 100 2 test.jfr > "$W/old/parser-core.json"
run "previous run without the benchmarks: ratio is new, 0" 0 \
    "| speedup | chunks=32,type=Rare | 8 | - | new |" -- \
    --current "$W/cur" --previous "$W/old" --ratio "speedup=Q.all/Q.filter"

# 16. A ratio the current run cannot provide is refused (R3), naming the reason.
run "ratio numerator absent: 2" 2 "cannot be computed" "no result for the numerator Q.missing" -- \
    --current "$W/cur" --ratio "x=Q.missing/Q.filter"
run "ratio denominator absent: 2" 2 "no result for the denominator Q.missing" -- \
    --current "$W/cur" --ratio "x=Q.all/Q.missing"
run "ambiguous pairing: 2, says how to fix it" 2 "pairs with 2 results of Q.filter, not 1" "[param=value]" -- \
    --current "$W/cur" --ratio "x=Q.ceiling/Q.filter"
run "narrowed to nothing: 2" 2 "no result for the denominator Q.filter" -- \
    --current "$W/cur" --ratio "x=Q.ceiling/Q.filter[type=Nope]"
mkdir "$W/units"
printf '[%s,%s]' "$(recp $B.all 600 ms/op chunks=32)" "$(recp $B.filter 5 ops/s chunks=32)" > "$W/units/a.json"
run "different units: 2" 2 "Q.all is in ms/op and Q.filter in ops/s" -- \
    --current "$W/units" --ratio "x=Q.all/Q.filter"
mkdir "$W/zero"
printf '[%s,%s]' "$(recp $B.all 600 ms/op chunks=32)" "$(recp $B.filter 0 ms/op chunks=32)" > "$W/zero/a.json"
run "zero denominator: n/a, still 0" 0 "| x | chunks=32 | n/a | - | new |" -- \
    --current "$W/zero" --ratio "x=Q.all/Q.filter"

# 17. A malformed --ratio is the caller's bug: 2 before any data is read.
run "ratio without a label: 2" 2 "expected LABEL=NUMERATOR/DENOMINATOR" -- \
    --current "$W/no-such-dir" --ratio "Q.all/Q.filter"
run "ratio without a denominator: 2" 2 "expected LABEL=NUMERATOR/DENOMINATOR" -- \
    --current "$W/no-such-dir" --ratio "x=Q.all"
run "ratio with a bad parameter: 2" 2 "is not param=value" -- \
    --current "$W/no-such-dir" --ratio "x=Q.all[type]/Q.filter"
# a slash inside [...] belongs to the parameter value (a file path)
mkdir "$W/path"
printf '[%s,%s]' "$(recp $B.all 600 ms/op file=a/b.jfr)" "$(recp $B.filter 200 ms/op file=a/b.jfr)" > "$W/path/a.json"
run "slash inside a parameter value is not the separator" 0 "| x | file=a/b.jfr | 3 | - | new |" -- \
    --current "$W/path" --ratio "x=Q.all[file=a/b.jfr]/Q.filter"

printf '%s passed, %s failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
