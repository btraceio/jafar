#!/usr/bin/env python3
# perf_ab_calibrate.py — derive perf_ab.py's calibration from A/A runs.
#
# Step 5 of doc/plans/perf-ab.md. An A/A run is a perf-ab run whose base and head are the same
# commit (dispatch perf-ab.yml with base_ref == head_ref). Every difference it reports is noise,
# so a set of them says, per benchmark, how large a ratio and how wide a spread of fork means the
# runner produces when nothing changed. That is what perf_ab.py needs before it may call a
# benchmark slower or faster.
#
#   perf_ab_calibrate.py ARTIFACT_DIR [ARTIFACT_DIR ...] [--min-runs N] [--margin M]
#                        [--out FILE] [--json-summary FILE]
#
# Each ARTIFACT_DIR is one run's perf-ab artifact (or perf_ab_run.sh's --out directory): its
# runner.txt must name the same commit as base and head, or the directory is refused, since a
# real A/B difference would inflate the thresholds. report.json supplies the numbers.
#
# Per benchmark key (benchmark + params, as perf_ab.py matches calibration selectors):
#   min_effect  = margin x the largest |ratio - 1| seen in any run, rounded up to 0.005.
#                 perf_ab.py calls a change only when p < 0.01 AND |ratio - 1| >= min_effect,
#                 so no A/A run in the set would have been called slower or faster.
#   noise_bound = margin x the largest relative spread of fork means ((max - min) / median, as
#                 perf_ab.py computes it) on either side of any run, rounded up to 0.005.
#                 Above it perf_ab.py flags a side as noisy.
# The default margin is 1.5. With 10 runs the largest of 10 noise draws still understates the
# tail; the margin is the guard, and more runs make the thresholds less arbitrary.
#
# Output: the calibration JSON perf_ab.py reads (version 1), to stdout or --out, with one
# selector per key: "Class.method[param=value,...]". --json-summary writes the per-key
# observations (every run's ratio, p and spreads) beside it, so a reviewer can see what the
# thresholds rest on.
#
# Exit status: 0 with a calibration written; 2 when the inputs cannot calibrate: fewer than
# --min-runs A/A runs (default 10), a run that is not A/A, a malformed report, or a benchmark
# missing from some run (thresholds from fewer runs than the rest would be inconsistent).

import argparse
import json
import math
import os
import re
import sys

ROUND = 0.005


def die(message):
    print(f"perf_ab_calibrate: {message}", file=sys.stderr)
    sys.exit(2)


def round_up(value):
    return math.ceil(value / ROUND - 1e-9) * ROUND


def runner_commits(directory):
    path = os.path.join(directory, "runner.txt")
    try:
        text = open(path, encoding="utf-8").read()
    except OSError as exc:
        die(f"{directory}: no runner.txt ({exc}); is this a perf-ab artifact?")
    base = re.search(r"^base: ([0-9a-f]{40})$", text, re.M)
    head = re.search(r"^head: ([0-9a-f]{40})$", text, re.M)
    if not base or not head:
        die(f"{path}: no base/head commit lines")
    order = re.search(r"^order: (abba|baab)$", text, re.M)
    return base.group(1), head.group(1), order.group(1) if order else None


def selector(benchmark, params):
    # perf_ab.py matches a selector's name against the benchmark or any dot-separated tail of
    # it; the class and method keep the selector readable and unambiguous in this repository.
    short = ".".join(benchmark.split(".")[-2:])
    if params:
        return short + "[" + ",".join(f"{k}={params[k]}" for k in sorted(params)) + "]"
    return short


def observations(directory):
    path = os.path.join(directory, "report.json")
    try:
        report = json.load(open(path, encoding="utf-8"))
    except (OSError, ValueError) as exc:
        die(f"{path}: {exc}")
    rows = report.get("results") if isinstance(report, dict) else None
    if not isinstance(rows, list) or not rows:
        die(f"{path}: no results")
    out = {}
    for i, row in enumerate(rows):
        where = f"{path} result {i}"
        if not isinstance(row, dict):
            die(f"{where}: not an object")
        benchmark, params = row.get("benchmark"), row.get("params") or {}
        if not isinstance(benchmark, str) or not isinstance(params, dict):
            die(f"{where}: no benchmark/params")
        if row.get("verdict") in ("new", "removed", "benchmark changed, not compared"):
            die(f"{where}: verdict {row.get('verdict')!r} cannot occur in an A/A run")
        ratio = row.get("ratio")
        spreads = [(row.get(side) or {}).get("spread") for side in ("base", "head")]
        p = (row.get("test") or {}).get("p")
        if not isinstance(ratio, (int, float)) or ratio <= 0 or not all(
            isinstance(s, (int, float)) and s >= 0 for s in spreads
        ):
            die(f"{where}: no ratio or spreads")
        key = (selector(benchmark, params), row.get("mode"))
        out[key] = {"ratio": ratio, "p": p, "spread_base": spreads[0], "spread_head": spreads[1]}
    return out


def main():
    parser = argparse.ArgumentParser(description="Derive perf_ab.py's calibration from A/A runs.")
    parser.add_argument("artifacts", nargs="+", help="perf-ab artifact directories of A/A runs")
    parser.add_argument("--min-runs", type=int, default=10)
    parser.add_argument("--margin", type=float, default=1.5)
    parser.add_argument("--out", help="write the calibration here (default: stdout)")
    parser.add_argument("--json-summary", help="write the per-key observations here")
    args = parser.parse_args()

    if args.margin < 1.0:
        die("--margin below 1 would make some A/A run in the set a false positive")
    runs = []
    for directory in args.artifacts:
        base, head, order = runner_commits(directory)
        if base != head:
            die(f"{directory}: base {base[:12]} != head {head[:12]}: not an A/A run")
        obs = observations(directory)
        for value in obs.values():
            value["order"] = order
        runs.append((directory, obs))
    if len(runs) < args.min_runs:
        die(f"{len(runs)} A/A run(s); at least {args.min_runs} are needed (--min-runs)")

    keys = set(runs[0][1])
    for directory, obs in runs[1:]:
        if set(obs) != keys:
            missing = sorted(map(str, keys ^ set(obs)))
            die(f"{directory}: a different set of benchmarks than {runs[0][0]}: {', '.join(missing)}")
    modes = {}
    for sel, mode in keys:
        modes.setdefault(sel, set()).add(mode)
    if any(len(m) > 1 for m in modes.values()):
        die("a benchmark ran in several modes; perf_ab.py calibration selectors do not name a mode")

    benchmarks, summary = {}, {}
    for sel, mode in sorted(keys):
        seen = [obs[(sel, mode)] for _, obs in runs]
        worst_effect = max(abs(o["ratio"] - 1.0) for o in seen)
        worst_spread = max(max(o["spread_base"], o["spread_head"]) for o in seen)
        benchmarks[sel] = {
            "min_effect": round(round_up(args.margin * worst_effect), 3),
            "noise_bound": round(round_up(args.margin * worst_spread), 3),
        }
        summary[sel] = {
            "mode": mode,
            "runs": len(seen),
            "max_abs_ratio_minus_1": worst_effect,
            "max_spread": worst_spread,
            "p_below_0.01": sum(1 for o in seen if isinstance(o["p"], (int, float)) and o["p"] < 0.01),
            "observations": seen,
        }

    calibration = {"version": 1, "benchmarks": benchmarks}
    text = json.dumps(calibration, indent=2, sort_keys=True) + "\n"
    if args.out:
        open(args.out, "w", encoding="utf-8").write(text)
    else:
        sys.stdout.write(text)
    if args.json_summary:
        meta = {"runs": [d for d, _ in runs], "margin": args.margin, "round_up_to": ROUND}
        json.dump({"meta": meta, "benchmarks": summary}, open(args.json_summary, "w", encoding="utf-8"),
                  indent=2, sort_keys=True)
    for sel, values in benchmarks.items():
        s = summary[sel]
        print(
            f"perf_ab_calibrate: {sel}: min_effect {values['min_effect']} (worst |ratio-1| "
            f"{s['max_abs_ratio_minus_1']:.4f}), noise_bound {values['noise_bound']} "
            f"(worst spread {s['max_spread']:.4f}), p<0.01 in {s['p_below_0.01']}/{s['runs']} runs",
            file=sys.stderr,
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
