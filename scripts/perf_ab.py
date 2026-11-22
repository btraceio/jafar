#!/usr/bin/env python3
# perf_ab.py — advisory A/B report: a PR build's JMH results against its base build's.
#
# The perf-ab workflow (doc/plans/perf-ab.md) builds the merge base and the PR head in
# two worktrees on ONE runner and runs the same JMH benchmarks in interleaved rounds
# (A B B A), each invocation with `-rf json`. This script reads all of those result files,
# pools the forks of each benchmark per side, and prints one markdown table for
# $GITHUB_STEP_SUMMARY: base median, head median, ratio, interval, p, fork counts and a
# verdict per benchmark. Comparing on the same machine cancels the runner's speed, which
# night-to-night comparisons cannot (jmh_trend.py, header: 10% to 32% between runners).
#
# Timing is never a gate (doc/agents/Gates.md, principle 3), so this is an advisory
# report, not a gate, and the exit code says nothing about the numbers:
#
#   exit 0  the report was produced, whatever it says (a "slower" verdict still exits 0)
#   exit 2  the data is not what it claims to be (R3: refuse to pass on garbage):
#           unreadable/malformed JSON; a path with no JSON files; a record missing a field
#           the statistics use (benchmark, mode, primaryMetric.scoreUnit,
#           primaryMetric.rawData); rawData missing, empty, or holding an empty fork or a
#           non-positive score; two units for one benchmark (between base and head, or
#           within one side); a malformed calibration file; or a benchmark named with
#           --require that either side has no record of. That is a pipeline bug, not a
#           timing change.
#
# Usage:
#
#   perf_ab.py --base PATH [--base PATH ...] --head PATH [--head PATH ...]
#              [--calibration FILE] [--changed-class CLASS ...] [--require BENCHMARK ...]
#              [--json report.json]
#
#   PATH is a JMH JSON result file or a directory of them (*.json, or **/*.json when the
#   top level has none, as in jmh_trend.py). Every file of a side is pooled.
#   --changed-class names a benchmark class (fully qualified, or any dot-separated tail)
#   whose source the PR changed: its benchmarks are reported as "benchmark changed, not
#   compared", because each side ran its own version of the workload.
#   --require names a benchmark (fully qualified, or a dot-separated tail) that both sides
#   must have a record of: a benchmark that throws is left out of JMH's JSON while the
#   Gradle task exits 0, so without it a missing benchmark is invisible.
#   --json writes the same results machine-readable, with the inputs (path and sha256 of
#   every file) and the test method used per benchmark.
#
# What a row is. The key is benchmark + params (sorted) + mode. The unit of observation is
# the fork: JMH's primaryMetric.rawData holds one list of iteration scores per fork, and one
# sample is one fork's mean (iterations within a fork are not independent). Only rawData is
# read: `sample` mode results that carry only rawDataHistogram are refused.
#
# Orientation. avgt, ss and sample scores are times (lower is better); thrpt scores are
# rates (higher is better). Every comparison is made in the time orientation: a thrpt score
# s is treated as the time 1/s. So "ratio" is always head/base in time, > 1 is slower and
# < 1 is faster, in every mode. The medians are shown in the benchmark's own unit.
#
# Statistics, per key with both sides present (doc/plans/perf-ab.md, section 3.3):
#
#   effect    Hodges-Lehmann estimate of the shift in log(time): the median of all n*m
#             pairwise differences log(head_i) - log(base_j). ratio = exp(shift).
#   test      Two-sided Mann-Whitney U on the fork means, by midranks. Exact when
#             C(n+m, n) <= 20000 (6 + 6 forks: 924): every assignment of the pooled
#             midranks to the two sides is enumerated, and p is the share whose rank sum
#             is at least as far from its mean as the observed one. That is the exact
#             permutation test of the same statistic, so ties are handled exactly. Above
#             20000 the normal approximation with the tie-corrected variance and a 0.5
#             continuity correction is used, and the row says so ("≈" and a note).
#   interval  99% distribution-free confidence interval for the ratio, from the ordered
#             pairwise differences (Moses; Hollander & Wolfe, "Nonparametric Statistical
#             Methods", section 4.3): [D(k), D(nm+1-k)], with k the largest integer such
#             that P(U <= k-1) <= 0.005 under the null, from the exact no-ties distribution
#             of U. The coverage actually achieved (discrete, so >= 99%) is reported; with
#             too few forks no k exists and the interval is "n/a". With ties the coverage
#             is approximate. 99% matches the p < 0.01 threshold: without ties the
#             interval excludes 1 exactly when the test rejects.
#
# Verdict per key:
#
#   slower / faster           p < 0.01 AND |ratio - 1| >= the key's min_effect
#   no change detected        otherwise (read the interval: how large a change could hide)
#   uncalibrated              no min_effect is configured: numbers shown, no verdict
#   new / removed             the key is on one side only
#   benchmark changed, not compared   its class is named by --changed-class
#   + noisy (a flag)          either side's spread of fork means, (max - min) / median,
#                             exceeds the key's noise_bound
#
# Calibration file (--calibration; optional — min effects come from A/A runs, section 5 of
# the plan, and are never guessed, so there are no defaults):
#
#   {
#     "version": 1,
#     "benchmarks": {
#       "QueryPushdownBenchmark.headersWithStartTime": {"min_effect": 0.05, "noise_bound": 0.10},
#       "QueryPushdownBenchmark.decodeAllFilterInHandler[generatedReader=true]":
#           {"min_effect": 0.20, "noise_bound": 0.15}
#     }
#   }
#
#   Each selector is a benchmark name (fully qualified, or a dot-separated tail) optionally
#   narrowed to parameter values with [param=value,...]; it applies to every key that
#   matches. Both numbers are fractions (0.05 = 5%) and each is optional. When several
#   selectors match one key, the one naming the most parameters wins; two equally specific
#   ones with different values are refused (exit 2). Unknown fields are refused, so a typo
#   cannot silently leave a benchmark uncalibrated. Selectors that match nothing are listed
#   on stderr and in report.json.
#
# Untrusted text. Benchmark names and parameter values come from the PR's code. Every
# piece of text that goes into the markdown is reduced to [A-Za-z0-9 .,=/-]; any other
# character becomes an HTML numeric entity (so `|`, backticks, `<`, `>`, `@`, `[`, `_` and
# newlines cannot start a cell, a code span, a tag, a mention or a link), and control and
# format characters (including bidi overrides) are shown as visible U+XXXX text.
#
# Output is deterministic: the same inputs give byte-identical markdown and JSON (no
# timestamps, no randomness, sorted keys).

import argparse
import glob
import hashlib
import itertools
import json
import math
import os
import sys
import unicodedata

EXIT_OK = 0
EXIT_BAD_DATA = 2

ALPHA = 0.01
CONFIDENCE = 1.0 - ALPHA
EXACT_LIMIT = 20000

LOWER_IS_BETTER = ("avgt", "ss", "sample")
HIGHER_IS_BETTER = ("thrpt",)

CALIBRATION_FIELDS = ("min_effect", "noise_bound")


def die(message):
    """R3: exit 2, loudly, naming the problem — never a silent partial report."""
    print(f"perf_ab: {message}", file=sys.stderr)
    sys.exit(EXIT_BAD_DATA)


def is_number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


# ---------------------------------------------------------------------------- loading


def result_files(path, label):
    if os.path.isfile(path):
        return [path]
    if not os.path.isdir(path):
        die(f"--{label} {path}: no such file or directory")
    files = sorted(glob.glob(os.path.join(path, "*.json")))
    if not files:
        files = sorted(glob.glob(os.path.join(path, "**", "*.json"), recursive=True))
    if not files:
        die(f"--{label} {path}: no JMH result JSON files")
    return files


def sha256_of(path):
    digest = hashlib.sha256()
    with open(path, "rb") as fh:
        for block in iter(lambda: fh.read(65536), b""):
            digest.update(block)
    return digest.hexdigest()


def fork_means(record, where):
    metric = record["primaryMetric"]
    if "rawData" not in metric:
        hint = " (sample mode writes rawDataHistogram, which this script does not read)" if "rawDataHistogram" in metric else ""
        die(f"{where}: record lacks 'primaryMetric.rawData'{hint}")
    raw = metric["rawData"]
    if not isinstance(raw, list) or not raw:
        die(f"{where}: 'primaryMetric.rawData' is missing or empty")
    means = []
    for fork in raw:
        if not isinstance(fork, list) or not fork:
            die(f"{where}: 'primaryMetric.rawData' holds an empty fork")
        for score in fork:
            if not is_number(score):
                die(f"{where}: 'primaryMetric.rawData' holds a non-number {score!r}")
            if score <= 0:
                die(f"{where}: 'primaryMetric.rawData' holds a non-positive score {score!r}; its log is undefined")
        means.append(math.fsum(fork) / len(fork))
    return means


def record_key(record):
    params = record.get("params") or {}
    return (record["benchmark"], tuple((k, str(params[k])) for k in sorted(params)), record["mode"])


def load_side(paths, label):
    """Every record of one side, pooled by key: {key: {"unit", "forks": [means]}}."""
    pooled = {}
    inputs = []
    for path in paths:
        for file in result_files(path, label):
            try:
                with open(file, encoding="utf-8") as fh:
                    data = json.load(fh)
            except (OSError, ValueError) as exc:
                die(f"malformed JMH result JSON in --{label} file {file}: {exc}")
            if not isinstance(data, list):
                die(f"malformed JMH result JSON in --{label} file {file}: expected a list, got {type(data).__name__}")
            inputs.append({"path": file, "sha256": sha256_of(file), "records": len(data)})
            for index, record in enumerate(data):
                where = f"--{label} file {file}, record {index}"
                if not isinstance(record, dict):
                    die(f"{where}: record is not an object")
                for field in ("benchmark", "mode", "primaryMetric"):
                    if field not in record:
                        die(f"{where}: record lacks {field!r}")
                if not isinstance(record["benchmark"], str) or not record["benchmark"]:
                    die(f"{where}: 'benchmark' is not a non-empty string")
                mode = record["mode"]
                if mode not in LOWER_IS_BETTER + HIGHER_IS_BETTER:
                    die(f"{where}: unknown mode {mode!r}; expected one of {', '.join(LOWER_IS_BETTER + HIGHER_IS_BETTER)}")
                if not isinstance(record.get("params", {}), dict):
                    die(f"{where}: 'params' is not an object")
                metric = record["primaryMetric"]
                if not isinstance(metric, dict):
                    die(f"{where}: 'primaryMetric' is not an object")
                unit = metric.get("scoreUnit")
                if not isinstance(unit, str) or not unit:
                    die(f"{where}: record lacks 'primaryMetric.scoreUnit'")
                means = fork_means(record, where)
                key = record_key(record)
                entry = pooled.setdefault(key, {"unit": unit, "forks": []})
                if entry["unit"] != unit:
                    die(f"--{label}: {key_text(key)} is in {entry['unit']} in one file and {unit} in {file}")
                entry["forks"].extend(means)
    return pooled, inputs


def key_text(key):
    benchmark, params, mode = key
    text = benchmark
    if params:
        text += "[" + ",".join(f"{k}={v}" for k, v in params) + "]"
    return f"{text} ({mode})"


def matches_name(benchmark, name):
    return benchmark == name or benchmark.endswith("." + name)


# ------------------------------------------------------------------------ calibration


def parse_selector(text):
    """'Class.method' or 'Class.method[param=value,...]' -> (name, {param: value}) or None."""
    text = text.strip()
    params = {}
    name = text
    if text.endswith("]") and "[" in text:
        name, _, inside = text[:-1].partition("[")
        for part in inside.split(","):
            key, eq, value = part.partition("=")
            if not eq or not key.strip():
                return None
            params[key.strip()] = value.strip()
    name = name.strip()
    if not name or "[" in name or "]" in name:
        return None
    return name, params


def load_calibration(path):
    try:
        with open(path, encoding="utf-8") as fh:
            data = json.load(fh)
    except (OSError, ValueError) as exc:
        die(f"malformed calibration file {path}: {exc}")
    if not isinstance(data, dict) or set(data) - {"version", "benchmarks"}:
        die(f"malformed calibration file {path}: expected an object with 'version' and 'benchmarks' only")
    if data.get("version") != 1:
        die(f"malformed calibration file {path}: 'version' must be 1")
    entries = data.get("benchmarks")
    if not isinstance(entries, dict):
        die(f"malformed calibration file {path}: 'benchmarks' is not an object")
    selectors = []
    for spec in sorted(entries):
        values = entries[spec]
        selector = parse_selector(spec)
        if selector is None:
            die(f"malformed calibration file {path}: {spec!r} is not Class.method or Class.method[param=value,...]")
        if not isinstance(values, dict):
            die(f"malformed calibration file {path}: {spec!r} does not map to an object")
        unknown = sorted(set(values) - set(CALIBRATION_FIELDS))
        if unknown:
            die(f"malformed calibration file {path}: {spec!r} has unknown field(s) {', '.join(unknown)}")
        for field in CALIBRATION_FIELDS:
            if field in values and (not is_number(values[field]) or values[field] < 0):
                die(f"malformed calibration file {path}: {spec!r} {field} is not a non-negative number")
        selectors.append((spec, selector, values))
    return selectors


def calibration_for(key, selectors, used):
    """The most specific matching selector's values; two equally specific ones that
    disagree are refused."""
    benchmark, params, _ = key
    params = dict(params)
    matching = [
        (len(wanted), spec, values)
        for spec, (name, wanted), values in selectors
        if matches_name(benchmark, name) and all(params.get(k) == v for k, v in wanted.items())
    ]
    if not matching:
        return {}, None
    best = max(m[0] for m in matching)
    top = [m for m in matching if m[0] == best]
    if any(m[2] != top[0][2] for m in top):
        die(f"calibration is ambiguous for {key_text(key)}: {', '.join(m[1] for m in top)} match equally")
    for m in matching:
        used.add(m[1])
    return top[0][2], top[0][1]


# ------------------------------------------------------------------------- statistics


def median(values):
    ordered = sorted(values)
    n = len(ordered)
    mid = n // 2
    return ordered[mid] if n % 2 else (ordered[mid - 1] + ordered[mid]) / 2.0


def spread(values):
    """(max - min) / median of the fork means: the noise measure the noise_bound applies to."""
    return (max(values) - min(values)) / median(values)


def to_time_log(score, mode):
    """log of the score in the time orientation: a rate s is the time 1/s."""
    return -math.log(score) if mode in HIGHER_IS_BETTER else math.log(score)


def doubled_midranks(values):
    """2 x the midrank of every value (integers, so the test's arithmetic is exact)."""
    order = sorted(range(len(values)), key=lambda i: values[i])
    ranks = [0] * len(values)
    i = 0
    while i < len(order):
        j = i
        while j + 1 < len(order) and values[order[j + 1]] == values[order[i]]:
            j += 1
        # positions i..j (0-based) hold ranks i+1..j+1; their midrank doubled is i+j+2
        for k in range(i, j + 1):
            ranks[order[k]] = i + j + 2
        i = j + 1
    return ranks


def mann_whitney(base, head):
    """Two-sided Mann-Whitney test on the head sample's midrank sum. Returns a dict with
    the method ("exact" or "normal"), p and, when exact, the count behind it."""
    n, m = len(base), len(head)
    total_n = n + m
    pooled = list(base) + list(head)
    ranks2 = doubled_midranks(pooled)
    observed = sum(ranks2[n:])
    # 2 x the expected rank sum of m values among total_n: m (total_n + 1)
    expected = m * (total_n + 1)
    distance = abs(observed - expected)
    u_head = (observed - m * (m + 1)) / 2.0  # U of head (doubled sums halved)
    assignments = math.comb(total_n, m)
    if assignments <= EXACT_LIMIT:
        count = 0
        for chosen in itertools.combinations(ranks2, m):
            if abs(sum(chosen) - expected) >= distance:
                count += 1
        return {
            "method": "exact",
            "p": count / assignments,
            "p_count": count,
            "p_total": assignments,
            "u_head": u_head,
        }
    tie_sum = 0
    for value in set(pooled):
        t = pooled.count(value)
        tie_sum += t ** 3 - t
    variance = n * m / 12.0 * ((total_n + 1) - tie_sum / (total_n * (total_n - 1)))
    if variance <= 0:
        p = 1.0
    else:
        z = max(0.0, abs(u_head - n * m / 2.0) - 0.5) / math.sqrt(variance)
        p = min(1.0, math.erfc(z / math.sqrt(2.0)))
    return {"method": "normal", "p": p, "u_head": u_head}


def u_null_counts(n, m):
    """Exact null distribution of U (no ties): counts[u] = number of the C(n+m, n)
    orderings with U = u. f(n, m, u) = f(n-1, m, u-m) + f(n, m-1, u)."""
    # table[j] holds the counts for (i, j) while i grows
    prev = [[1] for _ in range(m + 1)]  # i = 0: U is 0 for every j
    for i in range(1, n + 1):
        cur = [[1]]  # j = 0
        for j in range(1, m + 1):
            size = i * j + 1
            row = [0] * size
            a = prev[j]  # (i-1, j): shifted by j
            for u, c in enumerate(a):
                row[u + j] += c
            b = cur[j - 1]  # (i, j-1)
            for u, c in enumerate(b):
                row[u] += c
            cur.append(row)
        prev = cur
    return prev[m]


def hodges_lehmann(base_t, head_t):
    """Shift estimate and the 99% interval from the ordered pairwise differences."""
    diffs = sorted(h - b for h in head_t for b in base_t)
    shift = median(diffs)
    n, m = len(base_t), len(head_t)
    counts = u_null_counts(n, m)
    total = math.comb(n + m, n)
    cumulative = 0
    k = 0
    # largest k with P(U <= k - 1) <= alpha / 2
    for u, c in enumerate(counts):
        if (cumulative + c) / total > ALPHA / 2:
            break
        cumulative += c
        k = u + 1
    if k < 1:
        return shift, None
    low, high = diffs[k - 1], diffs[len(diffs) - k]
    return shift, {"low": math.exp(low), "high": math.exp(high), "coverage": 1 - 2 * cumulative / total, "k": k}


# -------------------------------------------------------------------------- reporting


def esc(text):
    """Untrusted text to markdown/HTML-inert text (see the header)."""
    out = []
    for ch in str(text):
        if ch.isascii() and (ch.isalnum() or ch in " .,=/-"):
            out.append(ch)
        elif unicodedata.category(ch).startswith("C"):
            out.append(f"U+{ord(ch):04X}")
        else:
            out.append(f"&#{ord(ch)};")
    return "".join(out)


def fmt(value, spec):
    return "-" if value is None else format(value, spec)


def compare(key, base, head, args, selectors, used):
    benchmark, params, mode = key
    row = {
        "benchmark": benchmark,
        "params": dict(params),
        "mode": mode,
        "orientation": "higher score is better" if mode in HIGHER_IS_BETTER else "lower score is better",
        "unit": (base or head)["unit"],
        "base": None,
        "head": None,
        "ratio": None,
        "interval": None,
        "test": None,
        "min_effect": None,
        "noise_bound": None,
        "calibration_selector": None,
        "noisy": False,
        "verdict": None,
    }
    for side, entry in (("base", base), ("head", head)):
        if entry is not None:
            row[side] = {
                "n": len(entry["forks"]),
                "median": median(entry["forks"]),
                "spread": spread(entry["forks"]),
                "fork_means": entry["forks"],
            }
    if base is not None and head is not None and base["unit"] != head["unit"]:
        die(f"{key_text(key)} is in {base['unit']} on --base and {head['unit']} on --head")

    calibration, selector = calibration_for(key, selectors, used)
    row["min_effect"] = calibration.get("min_effect")
    row["noise_bound"] = calibration.get("noise_bound")
    row["calibration_selector"] = selector

    changed_class = benchmark.rpartition(".")[0]
    if any(matches_name(changed_class, c) for c in args.changed_class):
        row["verdict"] = "benchmark changed, not compared"
        return row
    if base is None:
        row["verdict"] = "new"
        return row
    if head is None:
        row["verdict"] = "removed"
        return row

    base_t = [to_time_log(s, mode) for s in base["forks"]]
    head_t = [to_time_log(s, mode) for s in head["forks"]]
    shift, interval = hodges_lehmann(base_t, head_t)
    row["ratio"] = math.exp(shift)
    if interval is not None:
        row["interval"] = {
            "low": interval["low"],
            "high": interval["high"],
            "confidence": CONFIDENCE,
            "coverage_achieved": interval["coverage"],
            "order_statistic_k": interval["k"],
            "method": "distribution-free (ordered pairwise differences)",
        }
    row["test"] = mann_whitney(base_t, head_t)

    if row["noise_bound"] is not None:
        row["noisy"] = row["base"]["spread"] > row["noise_bound"] or row["head"]["spread"] > row["noise_bound"]
    if row["min_effect"] is None:
        row["verdict"] = "uncalibrated"
    elif row["test"]["p"] < ALPHA and abs(row["ratio"] - 1.0) >= row["min_effect"]:
        row["verdict"] = "slower" if row["ratio"] > 1.0 else "faster"
    else:
        row["verdict"] = "no change detected"
    return row


def markdown(rows, args, base_inputs, head_inputs):
    lines = []
    lines.append("## Performance A/B (advisory)")
    lines.append(
        f"_base: {len(base_inputs)} file(s), head: {len(head_inputs)} file(s). One sample is one fork's mean. "
        f"Ratio = head/base in time (a thrpt score s counts as time 1/s), so > 1 is slower in every mode; "
        f"Hodges-Lehmann estimate with a {CONFIDENCE:.0%} distribution-free interval; two-sided Mann-Whitney p. "
        f"slower/faster needs p < {ALPHA} and |ratio - 1| >= the benchmark's calibrated minimum effect. "
        f"Never a gate: timing does not fail this job._"
    )
    lines.append("")
    lines.append(
        "| Benchmark | Params | Mode | Base median | Head median | Unit | Ratio | "
        f"{CONFIDENCE:.0%} interval | p | n base | n head | Verdict |"
    )
    lines.append("|---|---|---|---|---|---|---|---|---|---|---|---|")
    normal_used = False
    for row in rows:
        params = ",".join(f"{k}={v}" for k, v in row["params"].items()) or "-"
        base, head = row["base"], row["head"]
        interval = row["interval"]
        interval_text = (
            f"{interval['low']:.3f} - {interval['high']:.3f}" if interval else ("n/a" if row["test"] else "-")
        )
        test = row["test"]
        if test is None:
            p_text = "-"
        elif test["method"] == "exact":
            p_text = f"{test['p']:.4f}"
        else:
            normal_used = True
            p_text = f"≈{test['p']:.4f}"
        verdict = row["verdict"] + (", noisy" if row["noisy"] else "")
        cells = [
            esc(row["benchmark"]),
            esc(params),
            esc(row["mode"]),
            fmt(base and base["median"], ".4g"),
            fmt(head and head["median"], ".4g"),
            esc(row["unit"]),
            fmt(row["ratio"], ".3f"),
            interval_text,
            p_text,
            str(base["n"]) if base else "0",
            str(head["n"]) if head else "0",
            esc(verdict),
        ]
        lines.append("| " + " | ".join(cells) + " |")
    lines.append("")
    if normal_used:
        lines.append(
            f"≈: more than {EXACT_LIMIT} assignments, so p is the normal approximation "
            "(tie-corrected variance, continuity correction), not the exact test."
        )
    if any(r["verdict"] == "uncalibrated" for r in rows):
        lines.append(
            "uncalibrated: no minimum effect is configured for this benchmark (A/A calibration), "
            "so no slower/faster verdict is given."
        )
    if any(r["noisy"] for r in rows):
        lines.append(
            "noisy: one side's fork means spread, (max - min) / median, exceeds the calibrated bound; "
            "this benchmark may not resolve a change of this size on this runner."
        )
    if any(r["verdict"] == "benchmark changed, not compared" for r in rows):
        lines.append(
            "benchmark changed: the PR changed this benchmark's source, so each side ran a different workload."
        )
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(
        description="Advisory A/B report over two builds' JMH JSON results (timing is never a gate)."
    )
    parser.add_argument("--base", action="append", required=True, metavar="PATH",
                        help="base build JMH JSON file or directory; repeatable, all pooled")
    parser.add_argument("--head", action="append", required=True, metavar="PATH",
                        help="head (PR) build JMH JSON file or directory; repeatable, all pooled")
    parser.add_argument("--calibration", metavar="FILE", help="JSON file with min_effect/noise_bound per benchmark")
    parser.add_argument("--changed-class", action="append", default=[], metavar="CLASS",
                        help="benchmark class whose source the PR changed; repeatable")
    parser.add_argument("--require", action="append", default=[], metavar="BENCHMARK",
                        help="benchmark both sides must have a record of; repeatable; absent is exit 2")
    parser.add_argument("--json", metavar="FILE", help="also write the results as JSON to FILE")
    args = parser.parse_args()

    selectors = load_calibration(args.calibration) if args.calibration else []
    base, base_inputs = load_side(args.base, "base")
    head, head_inputs = load_side(args.head, "head")

    for name in args.require:
        for label, side in (("base", base), ("head", head)):
            if not any(matches_name(key[0], name) for key in side):
                die(
                    f"--{label} has no result for required benchmark {name} — a benchmark that throws is "
                    "left out of the JSON while its Gradle task exits 0; read that run's log"
                )

    used = set()
    rows = [
        compare(key, base.get(key), head.get(key), args, selectors, used)
        for key in sorted(set(base) | set(head))
    ]
    unused = [spec for spec, _, _ in selectors if spec not in used]
    for spec in unused:
        print(f"perf_ab: calibration entry {spec!r} matches no benchmark", file=sys.stderr)

    sys.stdout.write(markdown(rows, args, base_inputs, head_inputs))

    if args.json:
        report = {
            "tool": "perf_ab",
            "schema": 1,
            "alpha": ALPHA,
            "confidence": CONFIDENCE,
            "exact_limit": EXACT_LIMIT,
            "inputs": {"base": base_inputs, "head": head_inputs},
            "calibration": {"file": args.calibration, "unused_selectors": unused},
            "changed_classes": args.changed_class,
            "required": args.require,
            "results": rows,
        }
        with open(args.json, "w", encoding="utf-8") as fh:
            json.dump(report, fh, indent=2, sort_keys=True, ensure_ascii=True)
            fh.write("\n")
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main())
