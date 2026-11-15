#!/usr/bin/env python3
# jmh_trend.py — advisory JMH trend report: current run vs the previous run's results.
#
# The nightly workflow (.github/workflows/nightly-jmh.yml) runs the :parser-core:jmh and
# :pprof-parser:jmh tasks, and QueryPushdownBenchmark from :parser-codegen:jmh, each with
# `-rf json -rff build/jmh/<name>.json`, uploads those JSON files as the `jmh-results` artifact, and points this script at the freshly
# produced directory plus (when it exists) the previous successful run's downloaded
# artifact. The script turns them into one markdown table for $GITHUB_STEP_SUMMARY:
# benchmark, params, score ± error, and the delta in percent against the previous run.
#
# Timing is never a gate (kb/dead-ends: a wall-clock budget measured 5x variance within
# one process; bytes are deterministic, time is not). So the exit code says nothing
# about the numbers:
#
#   exit 0  the table was produced, however large the regressions or improvements are
#           (a 50% regression still exits 0); also when there is no baseline to
#           compare against (first nightly run, or no previous artifact)
#   exit 2  the data is not what it claims to be (R3: refuse to pass on garbage):
#           unreadable/malformed JSON, a results directory with no JSON files, or a
#           record missing the fields the table is built from, or a benchmark named
#           with --require that the current run has no record of. Failing loudly here
#           is fine: that is a pipeline bug, not a timing change.
#
# --require exists because a missing benchmark is otherwise invisible. A benchmark
# method that throws (QueryPushdownBenchmark checks what it counted and throws on a
# mismatch) is left out of the result JSON, the Gradle task still exits 0, and with
# other result files present this script would print a shorter table and exit 0.
#
# --ratio adds a second table: one benchmark's score divided by another's, within the
# same run. Scores differ by 20-30% between two GitHub-hosted runners (measured: the
# same commit, run twice, moved every one of 20 benchmarks by 10% to 32% in the same
# direction), so a delta against the previous night mostly reports which machine the job
# landed on. A ratio of two benchmarks that ran on the same machine minutes apart moves
# less: across those two runs the 14 ratios the nightly reports moved by 0.1% to 14.9%,
# half of them by less than 4%, and in both directions. Less is not nothing, so a ratio's
# delta is still advisory; it is the first place to look, not a verdict.
#
#   --ratio 'LABEL=NUMERATOR/DENOMINATOR'
#
# NUMERATOR and DENOMINATOR name benchmarks the way --require does, optionally narrowed
# to some parameter values: Class.method[param=value,param=value]. Each numerator record
# is paired with the denominator record that agrees with it on every parameter both have;
# the row's params are the numerator's. Ratios are advisory like everything else, but a
# ratio that cannot be computed in the current run (a side with no record, no pairing, or
# two different units) is exit 2: the workflow asked for a number that is not there.
#
# Both module result files are merged per side, because a directory (current or
# previous) holds one JSON file per JMH run (parser-core.json, pprof-parser.json).

import argparse
import glob
import json
import os
import sys

EXIT_OK = 0
EXIT_BAD_DATA = 2

# A JMH record without these cannot be reported at all (as opposed to one merely
# missing scoreError or params, which the table renders with a dash). Since JMH
# 1.10 the numbers live in the nested "primaryMetric" object, not at the top
# level; the shape was read from a real -rf json run (R9), not guessed.
REQUIRED_FIELDS = ("benchmark", "primaryMetric")


def primary_metric(record, label, path):
    metric = record["primaryMetric"]
    if not isinstance(metric, dict):
        die(f"malformed JMH result JSON in {label} file {path}: 'primaryMetric' is not an object")
    return metric


def die(message):
    """R3: exit 2, loudly, naming the file — never a silent empty table."""
    print(f"jmh_trend: {message}", file=sys.stderr)
    sys.exit(EXIT_BAD_DATA)


def result_files(directory):
    """JSON files in a results directory.

    The top-level glob matches what the workflow produces (build/jmh/*.json) and
    what `gh run download` unpacks (the artifact root). The recursive fallback
    tolerates an artifact that was uploaded from a higher directory and therefore
    unpacks as <dir>/build/jmh/*.json. A directory that holds no JSON at all is
    not tolerated: it is reported by the caller.
    """
    files = sorted(glob.glob(os.path.join(directory, "*.json")))
    if not files:
        files = sorted(glob.glob(os.path.join(directory, "**", "*.json"), recursive=True))
    return files


def load_records(directory, label):
    """Parse every JMH result file in a directory into a list of records.

    Returns None when there is nothing to compare against (no directory, no files,
    no records) — the caller turns that into "no baseline" for the previous side.
    For the current side None is a hard error.
    """
    if not os.path.isdir(directory):
        return None
    files = result_files(directory)
    if not files:
        return None
    records = []
    for path in files:
        try:
            with open(path, encoding="utf-8") as fh:
                data = json.load(fh)
        except (OSError, ValueError) as exc:
            die(f"malformed JMH result JSON in {label} file {path}: {exc}")
        if not isinstance(data, list):
            die(f"malformed JMH result JSON in {label} file {path}: expected a list, got {type(data).__name__}")
        for record in data:
            if not isinstance(record, dict):
                die(f"malformed JMH result JSON in {label} file {path}: record is not an object")
            for field in REQUIRED_FIELDS:
                if field not in record:
                    die(f"malformed JMH result JSON in {label} file {path}: record lacks {field!r}")
            if not isinstance(record["benchmark"], str):
                die(f"malformed JMH result JSON in {label} file {path}: 'benchmark' is not a string")
            metric = primary_metric(record, label, path)
            if not isinstance(metric.get("score"), (int, float)) or isinstance(metric.get("score"), bool):
                die(f"malformed JMH result JSON in {label} file {path}: 'primaryMetric.score' is not a number")
            params = record.get("params", {})
            if not isinstance(params, dict):
                die(f"malformed JMH result JSON in {label} file {path}: 'params' is not an object")
        records.extend(data)
    if not records:
        print(f"jmh_trend: {label} directory {directory} holds result files but no records", file=sys.stderr)
        return None
    return records


def record_key(record):
    """A benchmark run is identified by benchmark name plus params (JMH's own
    grouping): the same benchmark at two param values is two trend rows."""
    params = record.get("params", {})
    param_text = ",".join(f"{k}={params[k]}" for k in sorted(params))
    return f"{record['benchmark']}|{param_text}"


def params_text(record):
    params = record.get("params", {})
    return ",".join(f"{k}={params[k]}" for k in sorted(params)) or "-"


def score_text(record):
    metric = record["primaryMetric"]
    # With a single measurement iteration JMH reports the error as the string
    # "NaN"; anything that is not a number is rendered as no error at all.
    error = metric.get("scoreError")
    if not isinstance(error, (int, float)) or isinstance(error, bool):
        error = None
    text = f"{metric['score']:.4g}"
    if error is not None:
        text += f" ±{error:.2g}"
    return text


def matches_name(record, name):
    """A benchmark is named by its fully qualified name or any dot-separated tail of it."""
    return record["benchmark"] == name or record["benchmark"].endswith("." + name)


def split_outside_brackets(text, separator):
    """Split at the first separator that is not inside [...]: parameter values may hold
    one (a file path has slashes)."""
    depth = 0
    for i, ch in enumerate(text):
        if ch == "[":
            depth += 1
        elif ch == "]":
            depth -= 1
        elif ch == separator and depth == 0:
            return text[:i], text[i + 1 :]
    return None


def parse_selector(text, spec):
    """'Class.method' or 'Class.method[param=value,...]' -> (name, {param: value})."""
    text = text.strip()
    params = {}
    if text.endswith("]") and "[" in text:
        name, _, inside = text[:-1].partition("[")
        for part in inside.split(","):
            key, eq, value = part.partition("=")
            if not eq or not key.strip():
                die(f"--ratio {spec!r}: parameter {part!r} is not param=value")
            params[key.strip()] = value.strip()
    else:
        name = text
    if not name.strip() or "[" in name or "]" in name:
        die(f"--ratio {spec!r}: {text!r} is not a benchmark name")
    return name.strip(), params


def parse_ratio(spec):
    """'LABEL=NUMERATOR/DENOMINATOR' -> (label, numerator selector, denominator selector,
    the formula as written, for the legend)."""
    label, eq, rest = spec.partition("=")
    if not eq or not label.strip():
        die(f"--ratio {spec!r}: expected LABEL=NUMERATOR/DENOMINATOR")
    sides = split_outside_brackets(rest, "/")
    if sides is None:
        die(f"--ratio {spec!r}: expected LABEL=NUMERATOR/DENOMINATOR")
    numerator, denominator = parse_selector(sides[0], spec), parse_selector(sides[1], spec)
    return label.strip(), numerator, denominator, f"{sides[0].strip()} / {sides[1].strip()}"


def select(records, selector):
    name, wanted = selector
    return [
        r
        for r in records
        if matches_name(r, name)
        and all(str(r.get("params", {}).get(k)) == v for k, v in wanted.items())
    ]


def ratio_rows(records, numerator, denominator):
    """Pairs each numerator record with the one denominator record that agrees with it
    on every parameter both have.

    Returns ({row key: (params text, ratio or None)}, problem). A ratio is None when the
    denominator's score is 0. problem is a sentence when nothing can be computed, and
    None otherwise.
    """
    nums = select(records, numerator)
    dens = select(records, denominator)
    if not nums:
        return {}, f"no result for the numerator {numerator[0]}"
    if not dens:
        return {}, f"no result for the denominator {denominator[0]}"
    rows = {}
    for num in nums:
        num_params = num.get("params", {})
        paired = [
            den
            for den in dens
            if all(
                str(num_params[k]) == str(v) for k, v in den.get("params", {}).items() if k in num_params
            )
        ]
        if len(paired) != 1:
            return {}, (
                f"{num['benchmark']} ({params_text(num)}) pairs with {len(paired)} results of "
                f"{denominator[0]}, not 1: narrow a side with [param=value]"
            )
        den = paired[0]
        num_unit = num["primaryMetric"].get("scoreUnit")
        den_unit = den["primaryMetric"].get("scoreUnit")
        if num_unit != den_unit:
            return {}, f"{numerator[0]} is in {num_unit} and {denominator[0]} in {den_unit}"
        den_score = den["primaryMetric"]["score"]
        ratio = None if den_score == 0 else num["primaryMetric"]["score"] / den_score
        rows[params_text(num)] = ratio
    return rows, None


def ratio_text(ratio):
    return "n/a" if ratio is None else f"{ratio:.3g}"


def print_ratios(specs, current, previous, current_dir):
    """The ratio table. Exits 2 when a ratio cannot be computed in the current run."""
    parsed = [parse_ratio(spec) for spec in specs]
    tables = []
    for label, numerator, denominator, formula in parsed:
        rows, problem = ratio_rows(current, numerator, denominator)
        if problem:
            die(f"--ratio {label!r} cannot be computed from --current {current_dir}: {problem}")
        # The previous run may predate a benchmark: that is "new", not an error.
        prev_rows, _ = ratio_rows(previous, numerator, denominator) if previous else ({}, None)
        tables.append((label, formula, rows, prev_rows))

    print()
    print("## JMH ratios")
    print(
        "_Each row divides two results of the same run, so it moves less than a score does with "
        "the speed of the machine the run landed on._"
    )
    print()
    print("| Ratio | Params | This run | Previous | Δ vs previous |")
    print("|---|---|---|---|---|")
    for label, _, rows, prev_rows in tables:
        for params in sorted(rows):
            ratio = rows[params]
            prev = prev_rows.get(params)
            if params not in prev_rows:
                prev_text, delta = "-", "new"
            elif ratio is None or prev is None or prev == 0:
                prev_text, delta = ratio_text(prev), "n/a"
            else:
                prev_text, delta = ratio_text(prev), f"{(ratio - prev) / prev * 100.0:+.1f}%"
            print(f"| {label} | {params} | {ratio_text(ratio)} | {prev_text} | {delta} |")
    print()
    for label, formula, _, _ in tables:
        print(f"- {label} = {formula}")


def delta_text(current, previous):
    """The delta is advisory information, never a verdict — so is a missing or
    zero baseline score."""
    if previous is None:
        return "new"
    prev = previous["primaryMetric"]["score"]
    if prev == 0:
        return "n/a"
    delta = (current["primaryMetric"]["score"] - prev) / prev * 100.0
    return f"{delta:+.1f}%"


def main():
    parser = argparse.ArgumentParser(
        description="Print a markdown JMH trend table (advisory: timing is never a gate)."
    )
    parser.add_argument("--current", required=True, help="directory with the current run's JMH result JSON files")
    parser.add_argument("--previous", default=None, help="directory with the previous run's JMH result JSON files")
    parser.add_argument(
        "--require",
        action="append",
        default=[],
        metavar="BENCHMARK",
        help="a benchmark the current run must have a record of: its fully qualified name, or any "
        "dot-separated tail of it (Class.method). Repeatable. A missing one is exit 2.",
    )
    parser.add_argument(
        "--ratio",
        action="append",
        default=[],
        metavar="LABEL=NUM/DEN",
        help="add a row group to the ratio table: the score of benchmark NUM divided by that of DEN "
        "in the same run, paired on the parameters both have. A side may be narrowed with "
        "[param=value,...]. Repeatable. A ratio the current run cannot provide is exit 2.",
    )
    args = parser.parse_args()
    # Checked before any data is read: a mistyped spec is the caller's bug, whatever the data says.
    for spec in args.ratio:
        parse_ratio(spec)

    current = load_records(args.current, "current")
    if current is None:
        die(f"no JMH result JSON files in --current {args.current}")

    absent = [
        name
        for name in args.require
        if not any(matches_name(r, name) for r in current)
    ]
    if absent:
        die(
            f"--current {args.current} has no result for required benchmark(s): {', '.join(absent)} "
            "— a benchmark that throws is left out of the JSON while its Gradle task exits 0; "
            "read that run's log"
        )

    previous = load_records(args.previous, "previous") if args.previous else None
    prev_by_key = {}
    if previous is not None:
        for record in previous:
            prev_by_key[record_key(record)] = record

    print("## JMH trend")
    if previous is None:
        provenance = f"current: {args.current} ({len(current)} results) — no baseline to compare against"
        if args.previous:
            provenance += f" (nothing usable in {args.previous})"
        print(f"_{provenance}_")
    else:
        print(
            f"_current: {args.current} ({len(current)} results), "
            f"previous: {args.previous} ({len(previous)} results)_"
        )
    print()
    print("| Benchmark | Params | Score | Unit | Δ vs previous |")
    print("|---|---|---|---|---|")
    seen_keys = set()
    for record in sorted(current, key=record_key):
        key = record_key(record)
        seen_keys.add(key)
        print(
            f"| {record['benchmark']} | {params_text(record)} | {score_text(record)} "
            f"| {record['primaryMetric'].get('scoreUnit', '')} | {delta_text(record, prev_by_key.get(key))} |"
        )

    missing = sorted(set(prev_by_key) - seen_keys)
    if missing:
        print()
        print("Also in the previous run but not this one:")
        for key in missing:
            benchmark, _, param_text = key.partition("|")
            print(f"- {benchmark} ({param_text or 'no params'})")

    if args.ratio:
        print_ratios(args.ratio, current, previous, args.current)


if __name__ == "__main__":
    sys.exit(main())
