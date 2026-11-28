#!/usr/bin/env bash
# test_perf_ab_calibrate.sh — scripts/perf_ab_calibrate.py, which turns A/A runs into perf_ab.py's
# calibration (doc/plans/perf-ab.md, step 5).
#
# The thresholds must be margin x the worst A/A noise, rounded up, so that no run of the set
# would have been called slower or faster; the output must be a file perf_ab.py accepts; and
# inputs that would bias the thresholds must be refused (exit 2): a run that is not A/A, too
# few runs, a benchmark missing from some run, a verdict an A/A run cannot have.
set -euo pipefail

repo="$(cd "$(dirname "$0")/../.." && pwd)"
cal="$repo/scripts/perf_ab_calibrate.py"

pass=0
fail=0
W=$(mktemp -d)
trap 'rm -rf "$W" 2>/dev/null || true' EXIT

ok() { printf 'ok   %s\n' "$1"; pass=$((pass + 1)); }
bad() { printf 'FAIL %s\n' "$1"; [ -n "${2:-}" ] && printf '%s\n' "$2"; fail=$((fail + 1)); }

SHA=1111111111111111111111111111111111111111
OTHER=2222222222222222222222222222222222222222

# run <dir> <base sha> <head sha> <ratio h> <spread h> <ratio c> <spread c> [verdict]
# One artifact: runner.txt and a report.json with two benchmarks, h and c.
run() {
    mkdir -p "$1"
    printf 'base: %s\nhead: %s\nnproc: 4\n' "$2" "$3" > "$1/runner.txt"
    D="$1" RH="$4" SH="$5" RC="$6" SC="$7" V="${8:-uncalibrated}" python3 - <<'PY'
import json, os
e = os.environ
row = lambda name, ratio, spread: {
    "benchmark": f"io.jafar.parser.benchmark.QueryPushdownBenchmark.{name}",
    "params": {"chunks": "32"}, "mode": "avgt", "verdict": e["V"], "ratio": float(ratio),
    "test": {"p": 0.3}, "base": {"n": 6, "spread": float(spread)}, "head": {"n": 6, "spread": float(spread) / 2}}
json.dump({"alpha": 0.01, "results": [row("headersWithStartTime", e["RH"], e["SH"]),
                                      row("ceilingSkipEveryEvent", e["RC"], e["SC"])]},
          open(f"{e['D']}/report.json", "w"))
PY
}

# Ten A/A runs. Worst |ratio-1|: h 0.04 (ratio 0.96), c 0.021. Worst spread: h 0.09, c 0.05.
args=()
for i in $(seq 1 10); do
    rh=1.01 rc=0.995 sh=0.03 sc=0.02
    [ "$i" -eq 3 ] && rh=0.96
    [ "$i" -eq 7 ] && rc=1.021 && sh=0.09
    [ "$i" -eq 9 ] && sc=0.05
    run "$W/aa$i" "$SHA" "$SHA" "$rh" "$sh" "$rc" "$sc"
    args+=("$W/aa$i")
done

if python3 "$cal" "${args[@]}" --out "$W/cal.json" --json-summary "$W/summary.json" 2>"$W/err"; then
    ok "ten A/A runs calibrate"
else
    bad "ten A/A runs refused" "$(cat "$W/err")"
fi
check() { # check <description> <python expression over c (calibration) and s (summary)>
    if python3 -c "
import json, sys
c = json.load(open(sys.argv[1]))['benchmarks']; s = json.load(open(sys.argv[2]))['benchmarks']
sys.exit(0 if ($2) else 1)" "$W/cal.json" "$W/summary.json"; then ok "$1"; else bad "$1" "$(cat "$W/cal.json")"; fi
}
H='QueryPushdownBenchmark.headersWithStartTime[chunks=32]'
C='QueryPushdownBenchmark.ceilingSkipEveryEvent[chunks=32]'
check "min_effect = 1.5 x worst |ratio-1|, rounded up to 0.005 (0.04 -> 0.06)" "c['$H']['min_effect'] == 0.06"
check "min_effect rounds up (1.5 x 0.021 = 0.0315 -> 0.035)" "c['$C']['min_effect'] == 0.035"
check "noise_bound = 1.5 x worst spread of either side (0.09 -> 0.135)" "c['$H']['noise_bound'] == 0.135"
check "noise_bound for the other benchmark (0.05 -> 0.075)" "c['$C']['noise_bound'] == 0.075"
check "every A/A run stays below its min_effect" \
    "all(abs(o['ratio'] - 1) < c[k]['min_effect'] for k in s for o in s[k]['observations'])"
check "the summary keeps every run's observations" "all(s[k]['runs'] == 10 and len(s[k]['observations']) == 10 for k in s)"
grep -qF 'perf_ab_calibrate: QueryPushdownBenchmark.headersWithStartTime[chunks=32]: min_effect 0.06' "$W/err" \
    && ok "a per-benchmark line on stderr" || bad "stderr summary" "$(cat "$W/err")"

# perf_ab.py reads the output: a fork-level fixture with a 20% slowdown is called slower.
mkdir -p "$W/ab"
python3 - "$W/ab" <<'PY'
import json, sys
d = sys.argv[1]
rec = lambda forks: [{"benchmark": "io.jafar.parser.benchmark.QueryPushdownBenchmark.headersWithStartTime",
    "mode": "avgt", "params": {"chunks": "32"},
    "primaryMetric": {"score": 1, "scoreUnit": "ms/op", "rawData": [[f, f, f] for f in forks]}}]
json.dump(rec([100, 101, 99, 100.5, 99.5, 100.2]), open(f"{d}/base.json", "w"))
json.dump(rec([120, 121.2, 118.8, 120.6, 119.4, 120.2]), open(f"{d}/head.json", "w"))
PY
out=$(python3 "$repo/scripts/perf_ab.py" --base "$W/ab/base.json" --head "$W/ab/head.json" --calibration "$W/cal.json" 2>&1) \
    && grep -qF '| slower |' <<<"$out" && ok "perf_ab.py accepts the calibration and calls a 20% slowdown slower" \
    || bad "perf_ab.py with the calibration" "$out"

# Refusals: exit 2 with a message naming the problem.
refuse() { # refuse <description> <expected message> <args...>
    local desc="$1" want="$2" rc=0 msg
    shift 2
    msg=$(python3 "$cal" "$@" 2>&1 >/dev/null) || rc=$?
    if [ "$rc" -eq 2 ] && grep -qF -- "$want" <<<"$msg"; then ok "refused: $desc"; else bad "not refused (exit $rc): $desc" "$msg"; fi
}
refuse "nine runs (fewer than 10)" "9 A/A run(s); at least 10" "${args[@]:0:9}"
run "$W/ab1" "$SHA" "$OTHER" 1.2 0.03 1.0 0.02
refuse "a run that is not A/A" "not an A/A run" "${args[@]:0:9}" "$W/ab1"
run "$W/new1" "$SHA" "$SHA" 1.0 0.03 1.0 0.02 new
refuse "a verdict an A/A run cannot have" "cannot occur in an A/A run" "${args[@]:0:9}" "$W/new1"
mkdir -p "$W/short1"
printf 'base: %s\nhead: %s\n' "$SHA" "$SHA" > "$W/short1/runner.txt"
python3 -c "
import json, sys
r = json.load(open(sys.argv[1])); r['results'] = r['results'][:1]; json.dump(r, open(sys.argv[2], 'w'))" \
    "$W/aa1/report.json" "$W/short1/report.json"
refuse "a benchmark missing from one run" "a different set of benchmarks" "${args[@]:0:9}" "$W/short1"
mkdir -p "$W/norunner"
cp "$W/aa1/report.json" "$W/norunner/"
refuse "no runner.txt" "no runner.txt" "${args[@]:0:9}" "$W/norunner"
refuse "a margin below 1" "--margin below 1" "${args[@]}" --margin 0.9

echo "perf_ab_calibrate: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
