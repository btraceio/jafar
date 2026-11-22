#!/usr/bin/env python3
# perf_ab_comment.py — the PR comment of a perf-ab run, rendered from its artifact.
#
# Step 4 of doc/plans/perf-ab.md. .github/workflows/perf-ab-comment.yml runs this from the
# default branch after a perf-ab run on a PR, over that run's downloaded `perf-ab` artifact.
#
#   perf_ab_comment.py --artifact DIR --run-url URL [--max-chars N]
#
# The artifact is untrusted. perf-ab.yml runs the PR's own code, so a PR controls every byte of
# it, including benchmark and frame names. This script therefore never copies the artifact's
# markdown (summary.md); it rebuilds the comment from the structured files only:
#   report.json          perf_ab.py's results
#   attribute.tsv        the benchmarks that were attributed, in order (line i -> attribution/i)
#   attribution/<i>.json `jfr-shell compare --json` for benchmark i
# Every value is type-checked: a number must be a number, a string a string. Every string that
# reaches the comment goes through perf_ab.esc, which keeps [A-Za-z0-9 .,=/-] and turns the rest
# into HTML entities, so no markdown, HTML, link, image or @mention survives. Numbers are
# formatted here. A file that does not have the expected shape is refused (exit 2): the comment
# job then posts nothing rather than half a report.
#
# The comment starts with MARKER, by which the workflow finds and edits its own earlier comment.
# It is capped at --max-chars (GitHub's limit is 65,536); past the cap, attribution rows are
# dropped first, then the comment says it was cut and points to the run.
#
# Exit status: 0 with the comment on stdout; 2 when the artifact is malformed.

import argparse
import json
import math
import os
import sys

sys.dont_write_bytecode = True  # importing perf_ab must not leave scripts/__pycache__ behind
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from perf_ab import esc  # noqa: E402  (same directory; the escaping rules live in one place)

MARKER = "<!-- perf-ab-report -->"
VERDICTS = {
    "slower", "faster", "no change detected", "uncalibrated",
    "new", "removed", "benchmark changed, not compared",
}
ATTRIBUTION_ROWS = 8


class Malformed(Exception):
    pass


def need(obj, key, kind, where, optional=False):
    if not isinstance(obj, dict):
        raise Malformed(f"{where}: not an object")
    if key not in obj or obj[key] is None:
        if optional:
            return None
        raise Malformed(f"{where}: missing {key!r}")
    value = obj[key]
    kinds = kind if isinstance(kind, tuple) else (kind,)
    if bool in kinds:
        ok = isinstance(value, bool)
    else:
        ok = isinstance(value, kinds) and not isinstance(value, bool)
    if not ok:
        raise Malformed(f"{where}: {key!r} is not {'/'.join(k.__name__ for k in kinds)}")
    if isinstance(value, float) and not math.isfinite(value):
        raise Malformed(f"{where}: {key!r} is not finite")
    return value


def num(value, spec):
    return "-" if value is None else format(value, spec)


def short(benchmark):
    return benchmark.rpartition(".")[2] or benchmark


def params_text(params, where):
    if params is None:
        return ""
    if not isinstance(params, dict):
        raise Malformed(f"{where}: 'params' is not an object")
    parts = []
    for key in sorted(params):
        if not isinstance(key, str) or not isinstance(params[key], str):
            raise Malformed(f"{where}: 'params' holds a non-string")
        parts.append(f"{key}={params[key]}")
    return ",".join(parts)


def load(path, what):
    try:
        with open(path, encoding="utf-8") as fh:
            return json.load(fh)
    except (OSError, ValueError) as exc:
        raise Malformed(f"{what} {path}: {exc}")


def verdict_rows(report):
    rows = []
    results = need(report, "results", list, "report.json")
    for i, row in enumerate(results):
        where = f"report.json result {i}"
        benchmark = need(row, "benchmark", str, where)
        verdict = need(row, "verdict", str, where)
        if verdict not in VERDICTS:
            raise Malformed(f"{where}: unknown verdict")
        params = params_text(row.get("params"), where)
        ratio = need(row, "ratio", (int, float), where, optional=True)
        interval = row.get("interval")
        low = high = None
        if interval is not None:
            low = need(interval, "low", (int, float), where + " interval", optional=True)
            high = need(interval, "high", (int, float), where + " interval", optional=True)
        test = row.get("test")
        p = need(test, "p", (int, float), where + " test", optional=True) if test is not None else None
        base = row.get("base") or {}
        head = row.get("head") or {}
        n_base = need(base, "n", int, where + " base", optional=True) if base else None
        n_head = need(head, "n", int, where + " head", optional=True) if head else None
        noisy = bool(row.get("noisy")) if isinstance(row.get("noisy"), bool) else False
        rows.append((benchmark, params, ratio, low, high, p, n_base, n_head, verdict, noisy))
    return rows


def attribution_sections(artifact):
    tsv = os.path.join(artifact, "attribute.tsv")
    if not os.path.isfile(tsv):
        return []
    sections = []
    with open(tsv, encoding="utf-8") as fh:
        lines = [line.rstrip("\n") for line in fh if line.strip()]
    for index, line in enumerate(lines, start=1):
        benchmark, _, params = line.partition("\t")
        path = os.path.join(artifact, "attribution", f"{index}.json")
        if not os.path.isfile(path):
            continue  # the run stopped before this one: say nothing rather than guess
        data = load(path, "attribution")
        where = f"attribution {index}"
        frames = []
        for key, share in (("inclusiveFrames", "TotalPct"), ("frames", "SelfPct")):
            listed = data.get(key)
            if listed is None:
                continue
            if not isinstance(listed, list):
                raise Malformed(f"{where}: {key!r} is not a list")
            for j, frame in enumerate(listed):
                w = f"{where} {key}[{j}]"
                if not need(frame, "significant", bool, w):
                    continue
                frames.append((
                    "total" if share == "TotalPct" else "self",
                    need(frame, "method", str, w),
                    need(frame, "baseline" + share, (int, float), w),
                    need(frame, "candidate" + share, (int, float), w),
                    need(frame, "deltaPct", (int, float), w),
                ))
        notes = data.get("comparability") or []
        if not isinstance(notes, list) or not all(isinstance(n, str) for n in notes):
            raise Malformed(f"{where}: 'comparability' is not a list of strings")
        frames.sort(key=lambda f: -abs(f[4]))
        sections.append((benchmark, params.replace("-p ", ""), frames, notes))
    return sections


def render(artifact, run_url, max_chars):
    report = load(os.path.join(artifact, "report.json"), "report")
    rows = verdict_rows(report)
    sections = attribution_sections(artifact)

    def build(attribution_limit):
        out = [MARKER, "### Performance A/B (advisory)", ""]
        out.append(
            "Ratio = PR / base in time (> 1 is slower), Hodges-Lehmann estimate with a 99% "
            "interval; p from an exact Mann-Whitney test on the fork means. `slower`/`faster` "
            "needs p < 0.01 and a calibrated minimum effect; until a calibration exists every "
            "verdict reads `uncalibrated`. Timing never fails a check."
        )
        out += ["", "| Benchmark | Params | Ratio | 99% interval | p | Forks | Verdict |",
                "|---|---|---|---|---|---|---|"]
        for benchmark, params, ratio, low, high, p, nb, nh, verdict, noisy in rows:
            interval = "-" if low is None or high is None else f"{low:.3f} - {high:.3f}"
            forks = f"{num(nb, 'd')} / {num(nh, 'd')}"
            mark = " (noisy)" if noisy else ""
            out.append(
                f"| {esc(short(benchmark))} | {esc(params)} | {num(ratio, '.3f')} | {interval} "
                f"| {num(p, '.4f')} | {forks} | {esc(verdict)}{mark} |"
            )
        if sections:
            out += ["", "#### Where time moved (JFR, one profiled fork per side)", "",
                    "A share shift is attribution, not proof: only the table above says whether "
                    "a benchmark got slower."]
            for benchmark, params, frames, notes in sections:
                out += ["", f"**{esc(short(benchmark))}** {esc(params)}", ""]
                shown = frames[:attribution_limit]
                if shown:
                    out += ["| Frame | Share | Base | PR | Delta |", "|---|---|---|---|---|"]
                    for kind, method, base_pct, cand_pct, delta in shown:
                        out.append(
                            f"| {esc(method)} | {kind} | {base_pct:.2f}% | {cand_pct:.2f}% "
                            f"| {delta:+.2f} |"
                        )
                    if len(frames) > len(shown):
                        out.append(f"\n{len(frames) - len(shown)} more significant frame(s) in the artifact.")
                else:
                    out.append("No frame moved significantly.")
                for note in notes:
                    if note != "Recordings appear comparable.":
                        out.append(f"\n_{esc(note)}_")
        out += ["", f"Run, job summary and the `perf-ab` artifact (recordings, logs): {esc_url(run_url)}"]
        return "\n".join(out) + "\n"

    for limit in (ATTRIBUTION_ROWS, 3, 0):
        text = build(limit)
        if len(text) <= max_chars:
            return text
    cut = f"\n\n_The report was cut at {max_chars} characters: see the run for all of it._\n"
    return text[: max_chars - len(cut)] + cut


def esc_url(url):
    # The run URL comes from the workflow (github.server_url, repository, run id), not the
    # artifact; still, only a plain https URL is printed as-is.
    if isinstance(url, str) and url.startswith("https://") and all(
        c.isalnum() or c in ":/.-_" for c in url
    ):
        return url
    return esc(url)


def main():
    parser = argparse.ArgumentParser(description="Render the perf-ab PR comment from a run's artifact.")
    parser.add_argument("--artifact", required=True, help="the downloaded perf-ab artifact directory")
    parser.add_argument("--run-url", required=True, help="URL of the perf-ab run")
    parser.add_argument("--max-chars", type=int, default=60000)
    args = parser.parse_args()
    try:
        sys.stdout.write(render(args.artifact, args.run_url, args.max_chars))
    except Malformed as exc:
        print(f"perf_ab_comment: malformed artifact: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
