#!/usr/bin/env bash
# test_perf_ab.sh — hermetic tests for scripts/perf_ab.py (the perf-ab A/B report).
#
# The report is advisory, so its exit code must be about the data, never the numbers:
# a "slower" verdict exits 0; malformed or inconsistent data exits 2, naming the problem
# (R3). The fire test (head 20% slower -> "slower") and its mirror (-> "faster") are the
# proof that the verdict fires (R5); the p-value checks pin the exact Mann-Whitney test
# to hand-computed counts. All fixtures are hand-written JMH result JSON in the real
# `-rf json` shape (benchmark/mode/params at the top level, primaryMetric.rawData one
# list of iteration scores per fork; read from a real QueryPushdownBenchmark run).
set -euo pipefail

repo="$(cd "$(dirname "$0")/../.." && pwd)"
script="$repo/scripts/perf_ab.py"

pass=0
fail=0
W=$(mktemp -d)
trap 'rm -rf "$W" 2>/dev/null || true' EXIT

# run <description> <expected-exit> [required-output-substrings ...] -- <script args...>
# The output checked is stdout + stderr; the last run's stdout is kept in $W/last.out.
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
    out=$(python3 "$script" "$@" 2>"$W/last.err") || rc=$?
    printf '%s\n' "$out" > "$W/last.out"
    out="$out
$(cat "$W/last.err")"
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

# check <description> <python expression over r, the parsed --json report>
check() {
    local desc="$1" expr="$2"
    if python3 -c "
import json, math, sys
r = json.load(open(sys.argv[1]))
rows = {x['benchmark'].rpartition('.')[2]: x for x in r['results']}
sys.exit(0 if ($expr) else 1)
" "$W/report.json"; then
        printf 'ok   %s\n' "$desc"
        pass=$((pass + 1))
    else
        printf 'FAIL %s: %s is false over %s\n' "$desc" "$expr" "$W/report.json"
        python3 -m json.tool "$W/report.json" 2>/dev/null | head -40 || true
        fail=$((fail + 1))
    fi
}

# rec <benchmark> <mode> <unit> <params-json-or-empty> <fork> [<fork> ...]
# One record; each fork is a comma-separated list of iteration scores.
rec() {
    local bench="$1" mode="$2" unit="$3" params="$4"
    shift 4
    local raw="" fork
    for fork in "$@"; do
        raw="${raw:+$raw,}[$fork]"
    done
    local p=""
    [ -n "$params" ] && p=",\"params\":$params"
    printf '{"jmhVersion":"1.37","benchmark":"%s","mode":"%s","threads":1,"forks":%s%s,"primaryMetric":{"score":1.0,"scoreError":"NaN","scoreUnit":"%s","rawData":[%s]}}' \
        "$bench" "$mode" "$#" "$p" "$unit" "$raw"
}

# file <path> <record> [<record> ...] — a JMH result file holding those records.
file() {
    local path="$1"
    shift
    mkdir -p "$(dirname "$path")"
    local IFS=,
    printf '[%s]\n' "$*" > "$path"
}

B=io.jafar.parser.benchmark.QueryPushdownBenchmark
P='{"chunks":"32","generatedReader":"true"}'

# Fork means of the base build: 100, 101, 99 (round 1) and 100.5, 99.5, 100.2 (round 2).
base_r1() { rec "$B.$1" "${2:-avgt}" "${3:-ms/op}" "$P" "99,100,101" "100,101,102" "98,99,100"; }
base_r2() { rec "$B.$1" "${2:-avgt}" "${3:-ms/op}" "$P" "100,100.5,101" "99,99.5,100" "99.7,100.2,100.7"; }
# The same forks, every score x 1.2 (20% more time per op, or 20% more ops per time).
slow_r1() { rec "$B.$1" "${2:-avgt}" "${3:-ms/op}" "$P" "118.8,120,121.2" "120,121.2,122.4" "117.6,118.8,120"; }
slow_r2() { rec "$B.$1" "${2:-avgt}" "${3:-ms/op}" "$P" "120,120.6,121.2" "118.8,119.4,120" "119.64,120.24,120.84"; }

# The calibration format, as documented in the script's header.
cat > "$W/calibration.json" <<'EOF'
{
  "version": 1,
  "benchmarks": {
    "QueryPushdownBenchmark.headersWithStartTime": {"min_effect": 0.05, "noise_bound": 0.10},
    "QueryPushdownBenchmark.countEventsFromHeaders": {"min_effect": 0.05, "noise_bound": 0.10},
    "QueryPushdownBenchmark.typeFilterInParser": {"min_effect": 0.50},
    "QueryPushdownBenchmark.typeFilterInParser[chunks=32]": {"min_effect": 0.05, "noise_bound": 0.10},
    "QueryPushdownBenchmark.decodeAllFilterInHandler": {"min_effect": 0.05, "noise_bound": 0.10},
    "QueryPushdownBenchmark.ceilingSkipEveryEvent": {"min_effect": 0.05, "noise_bound": 0.10},
    "QueryPushdownBenchmark.neverRun": {"min_effect": 0.05}
  }
}
EOF

# ---------------------------------------------------------------- fire test (R5) and mirror
# Head 20% slower, tight forks, min_effect 5%, rounds in two files per side (A B B A):
# complete separation, p = 2/924, ratio 1.200 -> slower. Exit 0: advisory.
file "$W/fire/base-r1.json" "$(base_r1 headersWithStartTime)"
file "$W/fire/head-r1.json" "$(slow_r1 headersWithStartTime)"
file "$W/fire/head-r2.json" "$(slow_r2 headersWithStartTime)"
file "$W/fire/base-r2.json" "$(base_r2 headersWithStartTime)"
run "fire: head 20% slower -> slower, exit 0" 0 \
    "| 1.200 |" "| 0.0022 |" "| 6 | 6 | slower |" -- \
    --base "$W/fire/base-r1.json" --base "$W/fire/base-r2.json" \
    --head "$W/fire/head-r1.json" --head "$W/fire/head-r2.json" \
    --calibration "$W/calibration.json" --json "$W/report.json"
check "fire: verdict slower, exact p 2/924, 6 forks pooled per side" \
    "rows['headersWithStartTime']['verdict'] == 'slower' and rows['headersWithStartTime']['test']['p_count'] == 2 and rows['headersWithStartTime']['test']['p_total'] == 924 and rows['headersWithStartTime']['base']['n'] == 6 and rows['headersWithStartTime']['head']['n'] == 6"
check "fire: 99% interval from the ordered differences (k=3, coverage 1-8/924)" \
    "abs(rows['headersWithStartTime']['interval']['low'] - 1.18218) < 1e-4 and abs(rows['headersWithStartTime']['interval']['high'] - 1.21809) < 1e-4 and rows['headersWithStartTime']['interval']['order_statistic_k'] == 3 and abs(rows['headersWithStartTime']['interval']['coverage_achieved'] - (1 - 8/924)) < 1e-12"
check "fire: report.json names its inputs and method" \
    "len(r['inputs']['base']) == 2 and len(r['inputs']['head']) == 2 and all(len(i['sha256']) == 64 for i in r['inputs']['base']) and rows['headersWithStartTime']['test']['method'] == 'exact'"
cp "$W/last.out" "$W/fire.md"

run "mirror: head 20% faster -> faster, ratio 0.833" 0 "| 0.833 |" "| 6 | 6 | faster |" -- \
    --base "$W/fire/head-r1.json" --base "$W/fire/head-r2.json" \
    --head "$W/fire/base-r1.json" --head "$W/fire/base-r2.json" \
    --calibration "$W/calibration.json"

# ---------------------------------------------------------------- thrpt orientation
# thrpt: a higher score is better, so head scores x 1.2 is FASTER (time ratio 1/1.2),
# and the reverse is slower.
file "$W/thrpt/base.json" "$(base_r1 countEventsFromHeaders thrpt ops/ms)"
file "$W/thrpt/base2.json" "$(base_r2 countEventsFromHeaders thrpt ops/ms)"
file "$W/thrpt/head.json" "$(slow_r1 countEventsFromHeaders thrpt ops/ms)"
file "$W/thrpt/head2.json" "$(slow_r2 countEventsFromHeaders thrpt ops/ms)"
run "thrpt: 20% more ops/ms -> faster, ratio 0.833" 0 "| thrpt |" "| ops/ms |" "| 0.833 |" "| faster |" -- \
    --base "$W/thrpt/base.json" --base "$W/thrpt/base2.json" \
    --head "$W/thrpt/head.json" --head "$W/thrpt/head2.json" --calibration "$W/calibration.json"
run "thrpt: 20% fewer ops/ms (reversed) -> slower, ratio 1.200" 0 "| 1.200 |" "| slower |" -- \
    --base "$W/thrpt/head.json" --base "$W/thrpt/head2.json" \
    --head "$W/thrpt/base.json" --head "$W/thrpt/base2.json" --calibration "$W/calibration.json"

# ---------------------------------------------------------------- A/A
# The same forks on both sides, shuffled across files: p = 1, ratio 1.000.
file "$W/aa/base.json" "$(base_r1 headersWithStartTime)"
file "$W/aa/base2.json" "$(base_r2 headersWithStartTime)"
file "$W/aa/head.json" "$(rec "$B.headersWithStartTime" avgt ms/op "$P" "99,99.5,100" "100,101,102" "99.7,100.2,100.7")"
file "$W/aa/head2.json" "$(rec "$B.headersWithStartTime" avgt ms/op "$P" "98,99,100" "100,100.5,101" "99,100,101")"
run "A/A identical forks: no change detected, p 1.0000, ratio 1.000" 0 "| 1.000 |" "| 1.0000 |" "| no change detected |" -- \
    --base "$W/aa/base.json" --base "$W/aa/base2.json" --head "$W/aa/head.json" --head "$W/aa/head2.json" \
    --calibration "$W/calibration.json"
# Same distribution, different draws (interleaved values).
file "$W/aa2/base.json" "$(rec "$B.headersWithStartTime" avgt ms/op "$P" 100 101 99 100.5 99.5 100.2)"
file "$W/aa2/head.json" "$(rec "$B.headersWithStartTime" avgt ms/op "$P" 100.1 100.9 99.2 100.4 99.6 100.3)"
run "A/A interleaved draws: no change detected" 0 "| no change detected |" -- \
    --base "$W/aa2/base.json" --head "$W/aa2/head.json" --calibration "$W/calibration.json"

# ---------------------------------------------------------------- below the minimum effect
# Head 2% slower, one overlapping pair: U = 35, p = 4/924 = 0.0043 < 0.01 (significant),
# but |ratio - 1| = 0.02 < min_effect 0.05 -> no change detected.
file "$W/small/base.json" "$(rec "$B.ceilingSkipEveryEvent" avgt ms/op "$P" 100 101 99 100.5 99.5 100.2)"
file "$W/small/head.json" "$(rec "$B.ceilingSkipEveryEvent" avgt ms/op "$P" 102 103.02 100.98 102.51 101.49 102.204)"
run "significant but below min_effect: no change detected" 0 "| 0.0043 |" "| no change detected |" -- \
    --base "$W/small/base.json" --head "$W/small/head.json" --calibration "$W/calibration.json" --json "$W/report.json"
check "below min_effect: p count 4/924, ratio about 1.02" \
    "rows['ceilingSkipEveryEvent']['test']['p_count'] == 4 and abs(rows['ceilingSkipEveryEvent']['ratio'] - 1.02) < 0.001"

# ---------------------------------------------------------------- noisy flag
# A base fork at 130: spread (130 - 95) / 100 = 0.35 > noise_bound 0.10.
file "$W/noisy/base.json" "$(rec "$B.decodeAllFilterInHandler" avgt ms/op "$P" 100 130 95 100 98 102)"
file "$W/noisy/head.json" "$(rec "$B.decodeAllFilterInHandler" avgt ms/op "$P" 101 99 100 102 98 100)"
run "noisy: one side's spread over the bound -> flagged" 0 "| no change detected, noisy |" "noisy: one side" -- \
    --base "$W/noisy/base.json" --head "$W/noisy/head.json" --calibration "$W/calibration.json"

# ---------------------------------------------------------------- uncalibrated
run "no calibration file: uncalibrated, numbers still shown" 0 "| 1.200 |" "| 0.0022 |" "| uncalibrated |" -- \
    --base "$W/fire/base-r1.json" --base "$W/fire/base-r2.json" \
    --head "$W/fire/head-r1.json" --head "$W/fire/head-r2.json"

# ---------------------------------------------------------------- calibration specificity
# typeFilterInParser has a generic entry (min 0.50) and one narrowed to [chunks=32]
# (min 0.05): the narrower one wins, so the 20% slowdown is "slower".
file "$W/spec/base.json" "$(base_r1 typeFilterInParser)" "$(base_r2 typeFilterInParser)"
file "$W/spec/head.json" "$(slow_r1 typeFilterInParser)" "$(slow_r2 typeFilterInParser)"
run "calibration: the selector naming more params wins; unused entries reported" 0 "| slower |" \
    "calibration entry 'QueryPushdownBenchmark.neverRun' applied to no benchmark" -- \
    --base "$W/spec/base.json" --head "$W/spec/head.json" --calibration "$W/calibration.json" --json "$W/report.json"
check "calibration: selector and unused list in report.json" \
    "rows['typeFilterInParser']['calibration_selector'] == 'QueryPushdownBenchmark.typeFilterInParser[chunks=32]' and 'QueryPushdownBenchmark.neverRun' in r['calibration']['unused_selectors']"
check "calibration: a selector shadowed by a more specific one is listed as unused" \
    "'QueryPushdownBenchmark.typeFilterInParser' in r['calibration']['unused_selectors']"

# ---------------------------------------------------------------- new / removed / changed class
file "$W/nr/base.json" "$(base_r1 headersWithStartTime)" "$(rec io.jafar.parser.benchmark.Other.gone avgt ms/op "" 5 5)"
file "$W/nr/head.json" "$(slow_r1 headersWithStartTime)" "$(rec io.jafar.parser.benchmark.Other.added avgt ms/op "" 6 6)"
run "new and removed keys" 0 "| io.jafar.parser.benchmark.Other.added | - | avgt | - | 6 | ms/op | - | - | - | 0 | 2 | new |" \
    "| io.jafar.parser.benchmark.Other.gone | - | avgt | 5 | - | ms/op | - | - | - | 2 | 0 | removed |" -- \
    --base "$W/nr/base.json" --head "$W/nr/head.json" --calibration "$W/calibration.json"
run "--changed-class: its benchmarks are not compared, others are" 0 \
    "| benchmark changed, not compared |" "benchmark changed: the PR changed" "| removed |" -- \
    --base "$W/nr/base.json" --head "$W/nr/head.json" --calibration "$W/calibration.json" \
    --changed-class QueryPushdownBenchmark --json "$W/report.json"
check "--changed-class: no ratio and no test for the changed class" \
    "rows['headersWithStartTime']['verdict'] == 'benchmark changed, not compared' and rows['headersWithStartTime']['ratio'] is None and rows['headersWithStartTime']['test'] is None"

# ---------------------------------------------------------------- exact p-values
# Hand-computed counts of the two-sided permutation test on midrank sums.
pv() { # pv <name> <base forks...> -- <head forks...>
    local name="$1" b=() h=()
    shift
    while [ "$1" != "--" ]; do b+=("$1"); shift; done
    shift
    h=("$@")
    file "$W/pv/$name-base.json" "$(rec "$B.$name" avgt ms/op "" "${b[@]}")"
    file "$W/pv/$name-head.json" "$(rec "$B.$name" avgt ms/op "" "${h[@]}")"
    python3 "$script" --base "$W/pv/$name-base.json" --head "$W/pv/$name-head.json" --json "$W/report.json" > /dev/null 2>&1
}
pv sep66 1 2 3 4 5 6 -- 7 8 9 10 11 12
check "exact p, 6 vs 6 complete separation: 2/924" \
    "rows['sep66']['test']['p_count'] == 2 and rows['sep66']['test']['p_total'] == 924 and abs(rows['sep66']['test']['p'] - 2/924) < 1e-15"
pv swap66 1 2 3 4 5 7 -- 6 8 9 10 11 12
check "exact p, 6 vs 6 one swapped pair (U = 35): 4/924" \
    "rows['swap66']['test']['p_count'] == 4 and rows['swap66']['test']['p_total'] == 924"
pv sep33 1 2 3 -- 4 5 6
check "exact p, 3 vs 3 complete separation: 2/20 (unreachable below 0.01)" \
    "rows['sep33']['test']['p_count'] == 2 and rows['sep33']['test']['p_total'] == 20 and rows['sep33']['interval'] is None"
pv ties22 1 2 -- 2 3
check "ties: [1,2] vs [2,3], midranks 1, 2.5, 2.5, 4 -> 4/6" \
    "rows['ties22']['test']['p_count'] == 4 and rows['ties22']['test']['p_total'] == 6"
pv tiesall 1 1 1 -- 1 1 2
check "ties: [1,1,1] vs [1,1,2], every assignment equally far -> 20/20" \
    "rows['tiesall']['test']['p_count'] == 20 and rows['tiesall']['test']['p'] == 1.0"
pv equal 5 5 5 -- 5 5 5
check "all equal: p 1, ratio 1" \
    "rows['equal']['test']['p'] == 1.0 and rows['equal']['ratio'] == 1.0"
# 10 vs 10: C(20, 10) = 184756 > 20000 -> normal approximation, labelled as such.
file "$W/pv/norm-base.json" "$(rec "$B.norm" avgt ms/op "" 1 2 3 4 5 6 7 8 9 10)"
file "$W/pv/norm-head.json" "$(rec "$B.norm" avgt ms/op "" 11 12 13 14 15 16 17 18 19 20)"
run "10 vs 10: normal approximation, said so" 0 "≈0.0002" "normal approximation" -- \
    --base "$W/pv/norm-base.json" --head "$W/pv/norm-head.json" --json "$W/report.json"
check "normal approximation: p = erfc(((50 - 0.5) / sqrt(175)) / sqrt(2))" \
    "rows['norm']['test']['method'] == 'normal' and abs(rows['norm']['test']['p'] - math.erfc((49.5 / math.sqrt(175)) / math.sqrt(2))) < 1e-12"

# ---------------------------------------------------------------- escaping untrusted names
# A benchmark name and a param value that try to break the table, open a tag, a code
# span, a link and a mention.
EVIL='io.x.Evil.a|b`c<img src=x onerror=alert(1)>[l](http://e)@everyone_x_'
python3 - "$W/evil/base.json" "$W/evil/head.json" "$EVIL" <<'EOF'
import json, os, sys
for path in sys.argv[1:3]:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as fh:
        json.dump([{"benchmark": sys.argv[3], "mode": "avgt",
                    "params": {"p|<b>": "v|`</td>\n| x"},
                    "primaryMetric": {"scoreUnit": "ms/op<script>", "rawData": [[1.0], [1.1]]}}], fh)
EOF
run "escaping: a malicious name renders inert" 0 "io.x.Evil.a&#124;b&#96;c&#60;img src=x onerror=alert&#40;1&#41;&#62;" \
    "p&#124;&#60;b&#62;=v&#124;&#96;&#60;/td&#62;U+000A&#124; x" -- \
    --base "$W/evil/base.json" --head "$W/evil/head.json"
row=$(grep 'Evil' "$W/last.out" || true)
pipes=$(tr -cd '|' <<<"$row" | wc -c)
if [ "$pipes" = 13 ] && ! grep -qE '[<>`@]|\[|\]|_' <<<"$row" && [ "$(grep -c 'Evil' "$W/last.out")" = 1 ]; then
    printf 'ok   escaping: the row keeps 12 cells, no raw < > ` @ [ ] _ or newline\n'
    pass=$((pass + 1))
else
    printf 'FAIL escaping: row has %s pipes or raw markup:\n%s\n' "$pipes" "$row"
    fail=$((fail + 1))
fi

# ---------------------------------------------------------------- refusals (exit 2)
printf '[{"benchmark": ' > "$W/bad-json.json"
run "refuse: malformed JSON, names the file" 2 "malformed JMH result JSON" "bad-json.json" -- \
    --base "$W/bad-json.json" --head "$W/fire/head-r1.json"
printf '{"benchmark":"x"}' > "$W/bad-notlist.json"
run "refuse: top level not a list" 2 "expected a list" -- --base "$W/fire/base-r1.json" --head "$W/bad-notlist.json"
printf '[{"mode":"avgt","primaryMetric":{"scoreUnit":"ms/op","rawData":[[1]]}}]' > "$W/bad-nobench.json"
run "refuse: record without benchmark" 2 "lacks 'benchmark'" -- --base "$W/bad-nobench.json" --head "$W/fire/head-r1.json"
printf '[{"benchmark":"a.B.c","primaryMetric":{"scoreUnit":"ms/op","rawData":[[1]]}}]' > "$W/bad-nomode.json"
run "refuse: record without mode" 2 "lacks 'mode'" -- --base "$W/bad-nomode.json" --head "$W/fire/head-r1.json"
printf '[{"benchmark":"a.B.c","mode":"avgt"}]' > "$W/bad-nometric.json"
run "refuse: record without primaryMetric" 2 "lacks 'primaryMetric'" -- --base "$W/bad-nometric.json" --head "$W/fire/head-r1.json"
printf '[{"benchmark":"a.B.c","mode":"avgt","primaryMetric":{"rawData":[[1]]}}]' > "$W/bad-nounit.json"
run "refuse: record without scoreUnit" 2 "lacks 'primaryMetric.scoreUnit'" -- --base "$W/fire/base-r1.json" --head "$W/bad-nounit.json"
printf '[{"benchmark":"a.B.c","mode":"sample","primaryMetric":{"scoreUnit":"ms/op","rawDataHistogram":[[[[1.0,3]]]]}}]' > "$W/bad-noraw.json"
run "refuse: rawData missing (sample-mode histogram named)" 2 "lacks 'primaryMetric.rawData'" "rawDataHistogram" -- \
    --base "$W/bad-noraw.json" --head "$W/fire/head-r1.json"
printf '[{"benchmark":"a.B.c","mode":"avgt","primaryMetric":{"scoreUnit":"ms/op","rawData":[]}}]' > "$W/bad-rawempty.json"
run "refuse: rawData empty" 2 "'primaryMetric.rawData' is missing or empty" -- --base "$W/bad-rawempty.json" --head "$W/fire/head-r1.json"
printf '[{"benchmark":"a.B.c","mode":"avgt","primaryMetric":{"scoreUnit":"ms/op","rawData":[[1.0],[]]}}]' > "$W/bad-forkempty.json"
run "refuse: an empty fork" 2 "holds an empty fork" -- --base "$W/fire/base-r1.json" --head "$W/bad-forkempty.json"
printf '[{"benchmark":"a.B.c","mode":"avgt","primaryMetric":{"scoreUnit":"ms/op","rawData":[[1.0,0]]}}]' > "$W/bad-zero.json"
run "refuse: a non-positive score" 2 "non-positive score" -- --base "$W/bad-zero.json" --head "$W/fire/head-r1.json"
printf '[{"benchmark":"a.B.c","mode":"avgt","primaryMetric":{"scoreUnit":"ms/op","rawData":[[1.0,"NaN"]]}}]' > "$W/bad-nan.json"
run "refuse: a non-number score" 2 "non-number" -- --base "$W/bad-nan.json" --head "$W/fire/head-r1.json"
printf '[{"benchmark":"a.B.c","mode":"weird","primaryMetric":{"scoreUnit":"ms/op","rawData":[[1.0]]}}]' > "$W/bad-mode.json"
run "refuse: an unknown mode" 2 "unknown mode 'weird'" -- --base "$W/bad-mode.json" --head "$W/fire/head-r1.json"
file "$W/unit/head.json" "$(slow_r1 headersWithStartTime avgt us/op)"
run "refuse: base and head in different units" 2 "is in ms/op on --base and us/op on --head" -- \
    --base "$W/fire/base-r1.json" --head "$W/unit/head.json"
run "refuse: two units within one side" 2 "in one file and us/op" -- \
    --base "$W/fire/base-r1.json" --base "$W/unit/head.json" --head "$W/fire/head-r1.json"
run "refuse: --require absent on head" 2 "--head has no result for required benchmark Other.gone" -- \
    --base "$W/nr/base.json" --head "$W/nr/head.json" --require Other.gone
run "refuse: --require absent on base" 2 "--base has no result for required benchmark Other.added" -- \
    --base "$W/nr/base.json" --head "$W/nr/head.json" --require Other.added
run "accept: --require present on both sides" 0 "QueryPushdownBenchmark.headersWithStartTime |" -- \
    --base "$W/fire/base-r1.json" --head "$W/fire/head-r1.json" --calibration "$W/calibration.json" \
    --require QueryPushdownBenchmark.headersWithStartTime
mkdir -p "$W/emptydir"
run "refuse: a directory with no JSON files" 2 "no JMH result JSON files" -- --base "$W/emptydir" --head "$W/fire/head-r1.json"
run "refuse: a path that does not exist" 2 "no such file or directory" -- --base "$W/nope" --head "$W/fire/head-r1.json"
printf '{"version":1,"benchmarks":{"X.y":{"min_efect":0.05}}}' > "$W/bad-cal1.json"
run "refuse: calibration with an unknown field" 2 "unknown field(s) min_efect" -- \
    --base "$W/fire/base-r1.json" --head "$W/fire/head-r1.json" --calibration "$W/bad-cal1.json"
printf '{"version":1,"benchmarks":{"X.y":{"min_effect":"5%%"}}}' > "$W/bad-cal2.json"
run "refuse: calibration with a non-number" 2 "min_effect is not a non-negative number" -- \
    --base "$W/fire/base-r1.json" --head "$W/fire/head-r1.json" --calibration "$W/bad-cal2.json"
printf '{"version":1,"benchmarks":{"headersWithStartTime[chunks=32]":{"min_effect":0.05},"headersWithStartTime[generatedReader=true]":{"min_effect":0.1}}}' > "$W/bad-cal3.json"
run "refuse: two equally specific calibration entries that disagree" 2 "calibration is ambiguous" -- \
    --base "$W/fire/base-r1.json" --head "$W/fire/head-r1.json" --calibration "$W/bad-cal3.json"
printf 'not json' > "$W/bad-cal4.json"
run "refuse: calibration not JSON" 2 "malformed calibration file" -- \
    --base "$W/fire/base-r1.json" --head "$W/fire/head-r1.json" --calibration "$W/bad-cal4.json"

# ---------------------------------------------------------------- directories and determinism
mkdir -p "$W/dirs/base" "$W/dirs/head"
cp "$W/fire/base-r1.json" "$W/fire/base-r2.json" "$W/dirs/base/"
cp "$W/fire/head-r1.json" "$W/fire/head-r2.json" "$W/dirs/head/"
run "directories per side: pooled like files (6 forks each)" 0 "| 6 | 6 | slower |" -- \
    --base "$W/dirs/base" --head "$W/dirs/head" --calibration "$W/calibration.json"

# ---------------------------------------------------------------- empty results, directory contents
# JMH leaves a benchmark that throws out of the file and the Gradle task exits 0: an empty
# file is a run where everything failed, and must not turn into a table of "new" rows.
printf '[]\n' > "$W/empty.json"
run "an empty JMH result file on one side is refused" 2 "holds no JMH records" -- \
    --base "$W/empty.json" --head "$W/fire/head-r1.json"
run "an empty JMH result file among several is refused" 2 "holds no JMH records" -- \
    --base "$W/fire/base-r1.json" --base "$W/empty.json" --head "$W/fire/head-r1.json"
# A directory may hold an earlier --json report or a calibration file: skipped, named, not fatal.
mkdir -p "$W/mixed/base" "$W/mixed/head"
cp "$W/fire/base-r1.json" "$W/fire/base-r2.json" "$W/mixed/base/"
cp "$W/fire/head-r1.json" "$W/fire/head-r2.json" "$W/mixed/head/"
cp "$W/calibration.json" "$W/mixed/head/calibration.json"
python3 "$script" --base "$W/fire/base-r1.json" --head "$W/fire/head-r1.json" --json "$W/mixed/head/report.json" >/dev/null 2>&1
run "a directory's non-JMH JSON files are skipped with a note" 0 "| 6 | 6 | slower |" \
    "skipping $W/mixed/head/report.json" "skipping $W/mixed/head/calibration.json" -- \
    --base "$W/mixed/base" --head "$W/mixed/head" --calibration "$W/calibration.json" --json "$W/report.json"
check "skipped files are listed in report.json and not counted as read" \
    "sorted(i['path'].rpartition('/')[2] for i in r['inputs']['head'] if 'skipped' in i) == ['calibration.json', 'report.json']"
run "a non-JMH file named directly is still refused" 2 "expected a list, got dict" -- \
    --base "$W/fire/base-r1.json" --head "$W/calibration.json"

det() {
    python3 "$script" --base "$W/dirs/base" --head "$W/dirs/head" --base "$W/noisy/base.json" --head "$W/noisy/head.json" \
        --calibration "$W/calibration.json" --json "$W/det-$1.json" > "$W/det-$1.md" 2>/dev/null
}
det 1
det 2
if cmp -s "$W/det-1.md" "$W/det-2.md" && cmp -s "$W/det-1.json" "$W/det-2.json"; then
    printf 'ok   determinism: byte-identical markdown and report.json\n'
    pass=$((pass + 1))
else
    printf 'FAIL determinism: two runs differ\n'
    diff "$W/det-1.md" "$W/det-2.md" || true
    fail=$((fail + 1))
fi

echo "--- fire-test markdown:"
cat "$W/fire.md"
echo "perf_ab: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
