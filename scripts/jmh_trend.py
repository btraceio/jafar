#!/usr/bin/env python3
# jmh_trend.py — advisory JMH trend report: current run vs the previous run's results.
#
# The nightly workflow (.github/workflows/nightly-jmh.yml) runs the :parser-core:jmh and
# :pprof-parser:jmh tasks with `-rf json -rff build/jmh/<module>.json`, uploads those
# JSON files as the `jmh-results` artifact, and points this script at the freshly
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
#           record missing the fields the table is built from. Failing loudly here is
#           fine: that is a pipeline bug, not a timing change.
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
    args = parser.parse_args()

    current = load_records(args.current, "current")
    if current is None:
        die(f"no JMH result JSON files in --current {args.current}")

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


if __name__ == "__main__":
    sys.exit(main())
