# Performance A/B report for pull requests (`perf-ab`)

Status: proposal, 2026-11-22. Nothing here is built. Section 9 records the decisions taken on
2026-11-22, and Q5, decided on 2026-11-26; section 10 lists what is not verified.

Origin: idea 3 of the 2026-11 brainstorm ("agent-native profiler analyst"). Most of that idea has
shipped: the `Finding` contract, `jfr_compare`, the MCP prompts and the `jafar-perf` plugin
([performance-engineer-in-a-box.md](performance-engineer-in-a-box.md), tiers A and B). What is
missing is the CI piece that plan names in section 5 (`perf-regression-gate`, line 219) and
recommends as a standalone step in section 8 (line 305): CI benchmarks the PR and its base,
records both with JFR, and reports on the PR what got slower and where.

## 1. The constraint: this repository does not gate on time

The design starts from three rules already in the repository. Each one rules out the obvious
version of a "performance gate".

- **No wall-clock gates.** [Gates.md](../agents/Gates.md), principle 3: "Gates assert on bytes and
  names ... never on wall time". The safety-harness plan lists "wall-clock budgets in CI (5×
  measured variance — flaky or inert)" under "Deliberately not built"
  ([generated-code-safety-harness.md:153-154](generated-code-safety-harness.md)).
- **JMH stays out of PR CI.** Gates.md, principle 4: expensive nets such as "JMH trends ... run
  only in scheduled workflows". `nightly-jmh.yml` says the same in its header (lines 5-13) and
  never fails on a timing change.
- **Runner-to-runner noise is larger than most real changes.** `scripts/jmh_trend.py` (header,
  lines 29-35) records it: the same commit run twice on GitHub-hosted runners moved every one of
  20 benchmarks by 10% to 32% in the same direction. Ratios of two benchmarks from the same run
  moved by 0.1% to 14.9%.

So this is **not a gate in the Gates.md sense**: it never blocks a merge, it does not get a row in
the Gates table, and it does not run on every PR. It is an **advisory A/B report a maintainer asks
for**. That is why it is called `perf-ab` (workflow `perf-ab.yml`, label `perf-ab`) rather than
the plan's `perf-regression-gate` (decision Q1 in section 9).

What makes it worth building despite the noise: comparing base and PR **on the same runner, in
the same job, interleaved** cancels the runner's speed. Section 2 gives the evidence for that.

## 2. Evidence that a same-machine A/B can see real changes

Measured locally while validating btraceio/jafar#250 (4 processors, JDK 21,
`QueryPushdownBenchmark`, `generatedReader=true`, `targetType=bench.Sampled`). There were five
builds, run as 2 rounds of 3 forks, with the order reversed in round 2. That gives 6 forks per
build. The full table is in the #250 description.

| Benchmark | Base → PR (mean ms/op) | Change | Intervals |
|---|---|---|---|
| `headersWithStartTime` | 198 → 149 | −25% | do not overlap in either round |
| `countEventsFromHeaders` | 207 → 149 | −28% | do not overlap in either round |
| `typeFilterInParser` | 139 → 122 | −12% | overlap in round 1 |
| `decodeAllFilterInHandler` | 707 → 647 | inconclusive | one build varied 709 / 585 between rounds |

What this shows, and what it does not:

- Changes of about 25% were detected cleanly with 6 forks per side.
- A change of about 12% was borderline.
- The decoding benchmark's spread between rounds (up to 18% for one build) is larger than the
  change it was meant to show.

The detectable effect therefore depends on the benchmark and has to be measured per benchmark.
Section 5 makes that measurement the first deliverable.

Two more lessons from that run that the design adopts:

- **Compare against the merge base, not the base branch's tip.** While the run was being prepared,
  `origin/main` moved by unrelated commits (`1fe4cff0` → `7c80c846`). Comparing against the tip
  would have attributed their effect to the PR.
- **Reverse the order between rounds.** In round 1 the builds ran L0..L4, in round 2 L4..L0, so
  drift during the run (thermal, other load) does not favour one side.

The Go side has the same tooling already: `benchstat` over interleaved runs found
`CountEvents` −31.85% (p=0.000, n=10) and correctly reported no change for the control benchmark
(p=0.631).

## 3. Design

### 3.1 Trigger

- **The `perf-ab` label on a PR** (`pull_request: types: [labeled, synchronize]`, running only
  while the label is present). Only users with triage access or above can add labels, so cost and
  fork code stay under maintainer control.
- **`workflow_dispatch`** with `base_ref` and `head_ref` inputs, for comparing any two refs. Like
  every dispatch, this only works once the workflow is on the default branch.
- **Push to `perf/**`**, for testing the workflow itself from a branch that is not merged yet. This
  is the same pattern as `nightly/**` and `bench/**` (`nightly-jmh.yml` lines 10-14,
  `go-parser-bench.yml` lines 35-38).

The workflow never checks out PR code in a job that has secrets or a write token (section 3.5).

### 3.2 Measurement

One job, one runner:

1. **Two worktrees.** `base/` at `git merge-base <head> <base branch>` and `head/` at the PR head.
2. **The benchmark code must match.** If `parser-codegen/src/main/jmh` differs between the two, the
   report says "benchmark changed by this PR, comparison not valid" for the changed classes and
   compares only the others. Each side runs its own benchmark sources, so a changed benchmark
   would compare two different workloads.
3. **Interleaved rounds.** The JMH invocations run in the order A B B A. Each invocation is
   `./gradlew :parser-codegen:jmh -PjmhArgs="-f 3 -wi 3 -i 5 -rf json -rff ... <benchmarks>"` in that
   worktree. That gives 6 forks per side, the setting from section 2.
4. **Benchmarks.** v1 covers `QueryPushdownBenchmark` only:
   - It generates its own recording, so nothing has to be downloaded.
   - It checks every count against the recording's known shape and throws on a mismatch, so a
     broken build cannot report a fast time.
   - Its default parameter set is the one from section 2. A `benchmarks` input takes a JMH regex,
     and a `params` input takes `-p` options.
   - `parser-core` and `pprof-parser` benchmarks are a later step (section 7).
5. **No profiler during timing.** The timing forks run without `-prof`. Profiling perturbs the
   timing, so attribution gets its own forks (section 3.4).

### 3.3 Statistics and verdict

The unit is the fork. JMH's JSON has `primaryMetric.rawData`, one array of iteration scores per
fork. Each fork's mean is one sample, which gives n = 6 per side.

For each benchmark and parameter set:

- **Effect.** The Hodges-Lehmann estimate of the shift in `log(score)`, reported as a ratio
  (PR / base).
- **Test.** An exact two-sided Mann-Whitney U test on the fork means. With 6 + 6 samples there are
  924 orderings, so the exact distribution is enumerated in plain Python. That needs no SciPy and
  no new dependency; `jmh_trend.py` is stdlib-only today.
- **Verdict.** `slower` or `faster` only when p < 0.01 **and** |ratio − 1| ≥ the benchmark's minimum
  effect. Otherwise the verdict is `no change detected`, and the report shows the interval so a
  reader sees how large a change could have been missed.
- **Minimum effect.** Set per benchmark from the A/A calibration in section 5, never guessed. Until
  calibrated, the report prints every result as `uncalibrated`.

A `noisy` flag is added when the spread of one side's fork means exceeds a calibrated bound. This
is the `decodeAllFilterInHandler` case: the honest answer there is "this benchmark cannot resolve
a change of this size on this runner", not a verdict.

### 3.4 Attribution with JFR

For every benchmark whose verdict is `slower` or `faster`:

1. **Record.** One extra fork per side runs with JMH's built-in JFR profiler. JMH 1.37 (the
   version on the classpath) ships `JavaFlightRecorderProfiler`. `-prof jfr:help` lists the
   options `dir`, `configName` (default `profile`), `debugNonSafePoints` (default `true`) and
   `stackDepth`.
   - The profiled forks pass `-prof jfr:stackDepth=2048`. JMH turns that into
     `-XX:FlightRecorderOptions=stackdepth=2048` for the forked JVM: the class file contains
     both strings.
   - JFR keeps 64 frames per stack by default. When a stack is cut, its outer callers are
     missing, so their inclusive shares come out too low.
   - Measured on JDK 21.0.12 with a 3,000-deep recursion and `settings=profile`: the deepest
     recorded stack was 64 frames by default and 2048 with `stackdepth=2048`. `stackdepth=4096`
     also gave 2048, capped without a warning, so 2048 is the ceiling on this JDK.
   - Deeper stacks cost more per sample. That matters little here, because the profiled forks
     are separate from the timing forks (section 3.2).
2. **Compare** the two recordings with the `jfr_compare` logic:
   - Self-time share per leaf frame.
   - A frame is reported only if its share moved by at least 1 percentage point
     (`DEFAULT_MIN_DELTA_PCT`), it has at least 5 samples (`MIN_FRAME_SAMPLES`), and the
     two-proportion z-score is at least 3 (`MIN_SIGNIFICANCE_Z`).
   - A recording with fewer than 30 samples (`MIN_RECORDING_SAMPLES`) is too thin to judge.
   - All four constants were in `JfrCompareTools.java:54-70`. Since step 1 they are in
     `shell-core/.../compare/JfrCompare.java`.
3. **Report the frames as attribution, not proof.** A share shift says where time moved, not
   whether the benchmark got slower. Only the JMH verdict decides that. Two known blind spots:
   - A uniform slowdown (every frame slower by the same factor) moves no share. The JMH verdict
     still catches it.
   - Self time alone misses a caller whose own code is unchanged but whose callees got slower.
     Inclusive (total-time) shares cover that case. Step 1 added them, and `jfr-shell compare`
     reports them.
   - Frames of JVM-generated classes (for example `LambdaForm$MH/0x00007f2e…`) carry an address
     that differs per JVM. The same frame therefore gets a different name in each fork's
     recording and shows up as a large move. Their names must be normalised before comparing.
     This is not done yet; see step 3.

**`jfr_compare` cannot run headless today.** It exists only as an MCP tool:
- `handleJfrCompare` takes MCP session ids.
- `profile()` uses jfr-mcp's `QueryEvaluator`, `QueryParser` and two helpers of
  `JfrAnalysisTools`: `detectExecutionEventType` and `extractFrames` (`JfrCompareTools.java:224-268`).

The proposed fix is to extract the comparison into `shell-core` (for example
`io.jafar.shell.core.compare`):
- **Input:** two opened sessions.
- **Output:** the comparison result plus the `Finding` list.
- `jfr_compare` delegates to it.
- A new non-interactive `jfr-shell compare <baseline.jfr> <candidate.jfr> --json` exposes it,
  beside the existing `show`, `metadata` and `chunks` subcommands (`jfr-shell/.../Main.java:348`
  onward).

This is also the first step of brainstorm idea 2: a compare core that pprof and OTLP can feed
later. The ArchUnit rule "parser modules and `shell-core` never depend on consumer modules" allows
the move, because the code goes from the consumer (`jfr-mcp`) down into `shell-core`.

### 3.5 Report and where it is posted

- **Step summary.** A markdown table, one row per benchmark and parameter set: base, PR, ratio,
  interval, p, verdict. Under it, the attributed frames of each changed benchmark, each with the
  `jfr-shell show` query that reproduces it.
- **Artifact `perf-ab`.**
  - The four JMH JSON files.
  - The two `.jfr` files per changed benchmark.
  - `report.json`, holding scores, verdicts and the `Finding` list in the existing `Finding` shape
    (`shell-core/.../findings/Finding.java`).
- **PR comment.** One comment, edited in place under a marker. This is how the test summary
  comment already works (`<!-- test-summary-marker -->`).
  - The measuring job has only `contents: read`.
  - A separate job posts the comment. It runs on `workflow_run` (or `pull_request_target`, like
    `pr-results-comment.yml`), downloads the artifact, and never checks out PR code.
  - It treats the artifact as untrusted data: a PR controls what its benchmark prints, so
    benchmark names and frame names are escaped before they go into markdown.

### 3.6 Step 3 as implemented (2026-11-22)

- `.github/workflows/perf-ab.yml` is a thin wrapper around `scripts/perf_ab_run.sh`, which runs
  the same way locally. Inputs reach the script through the environment only.
- The JFR profiler writes each fork's recording to
  `<dir>/<benchmark>-<Mode>-<param>-<value>/profile.jfr`; the driver gives every profiled
  benchmark its own `dir`, so each holds exactly one recording. The forked JVM's arguments include
  `-XX:FlightRecorderOptions=stackdepth=2048` (read from `jdk.JVMInformation` in a recording).
- A `-p` option a benchmark does not declare is ignored by JMH, so one `--params` string serves
  every benchmark: `headersWithStartTime` declares only `chunks`.
- **Changed benchmarks are judged per class.** Any change to a benchmark class's source marks all
  of its methods "benchmark changed, not compared". This is conservative: btraceio/jafar#250 added
  one method to `QueryPushdownBenchmark`, which disables the comparison of the class's other
  methods for that PR, although their workloads did not change.
- **Attribution before calibration.** Until a calibration exists no verdict is `slower` or
  `faster`, so the driver also attributes an `uncalibrated` benchmark with p < 0.01. With 6 forks
  per side that p is reachable (2/924 at complete separation).
- Frame names of JVM-generated classes are normalised in the compare core (section 3.4), so
  `jfr_compare` gains it too.
- `jfr-shell` for attribution is built from the driver's own checkout, not from the head worktree:
  `compare` exists only from btraceio/jafar#252 on, and a head or a dispatched ref may predate it.
  On a PR the two are the same commit.
- **End-to-end run, local (4 processors, JDK 21).**
  - **Inputs:** `--exact-base` with a local commit of `1fe4cff0` plus #250's benchmark sources
    against #250's pre-squash head `974ba69c`, so only #250's production code differed.
    Two benchmarks, short settings (`-f 3 -wi 1 -i 2 -w 2s -r 2s`).
  - **Two runs, same verdicts:**

    | Benchmark | Run 1 | Run 2 |
    |---|---|---|
    | `headersWithStartTime` | 0.754 (p 0.0022) | 0.817 (p 0.0022) |
    | `ceilingSkipEveryEvent` | 0.902 (p 0.0087) | 0.910 (p 0.0152) |

    Run 2 therefore attributed only `headersWithStartTime`.
  - **Attribution named #250's change:**
    - `RecordingStream.readVarint` inclusive 38.86% → 25.23%;
    - `Buffer.nextGetIndex` (the out-of-line `get()` calls of the unrolled decoder) 21.49% → 6.22%;
    - `peekStartTime` 15.18% → 8.83%.
  - **The short settings are noisy:** the same pair moved `headersWithStartTime` from 0.754 to
    0.817 between the two runs. Calibration has to use the workflow's default settings.
- **A pair that changes the benchmark class is not compared.** #250 against its own base
  reported `QueryPushdownBenchmark` as "benchmark changed, not compared": #250 added
  `headersWithStartTime`, which the base lacks (n base = 0). The workflow ran, and no attribution
  followed.

### 3.7 Step 4 as implemented (2026-11-22)

- `.github/workflows/perf-ab-comment.yml` runs on `workflow_run` of `Performance A/B (perf-ab)`
  for PR runs that were not cancelled or skipped. It has `pull-requests: write`, so it runs only
  default-branch code. Its checkout is the default branch with `persist-credentials: false`, and
  it reads the run's `perf-ab` artifact as data.
- **The comment is rebuilt, not copied.** `scripts/perf_ab_comment.py` renders it from
  `report.json`, `attribute.tsv` and `attribution/<i>.json`:
  - every value is type-checked, and a file of the wrong shape is refused, so nothing is posted;
  - every name goes through `perf_ab.esc`;
  - the comment is capped at 60,000 characters (GitHub's limit is 65,536), dropping attribution
    rows first.

  `summary.md`, which the PR's own code wrote, is never posted.
- **The PR comes from the run, not the artifact.** `scripts/perf_ab_post.py` takes it from
  `workflow_run.pull_requests`; that list is empty for a PR from a fork, and then it uses the open
  PR whose head is the run's head commit. None or several matching PRs post nothing. It edits the
  newest comment by `github-actions[bot]` that starts with `<!-- perf-ab-report -->`, or creates one.
- **A run without a readable report** (a failed run, a malformed artifact) gets a short comment
  with its conclusion and link, built without reading the artifact.
- **Tested locally:** `scripts/tests/test_perf_ab_comment.sh`, 27 checks.
  - The renderer escapes a name built from `|`, a backtick, `<img>`, a markdown link,
    `@everyone`, `**` and `_`; with escaping removed, all six leak checks fail.
  - It refuses seven malformed shapes.
  - The poster runs against a local fake of the GitHub API. It finds the PR from the metadata and,
    for a fork, by head commit, and it edits only its own marked comment. It posts nothing for zero
    or two PRs, for a head that is not a commit id, or for a comment without the marker.
- **Not run on GitHub:** `workflow_run` fires only for workflows on the default branch, so the
  comment job cannot run before both workflows are merged.
- **javac numbers, from the first real comment rendered locally.** The attribution of #250
  showed `UntypedJafarParserImpl.access$400` at 53.42% → 0% and `access$300` at 0% → 57.57%: the
  same accessor, renumbered by javac between the two builds. `parser-core` targets Java 8, which
  predates nestmates, so it still has synthetic accessors. The compare core now drops the number
  in `access$<n>` and `lambda$<method>$<n>`, as it does for hidden-class addresses.

### 3.8 Step 5: calibration tooling and procedure (2026-11-22)

Calibration needs runner data, which needs dispatch, which needs `perf-ab.yml` on the default
branch. This step therefore ships the tool and the procedure. The calibration file itself is
committed after the A/A runs on the runner.

**The tool.** `scripts/perf_ab_calibrate.py` reads perf-ab artifacts of A/A runs, where base and
head are the same commit. For each benchmark key (class, method, params) it sets:
- `min_effect`: 1.5 × the largest |ratio − 1| in any run, rounded up to 0.005. No run of the set
  would have been called `slower` or `faster`.
- `noise_bound`: 1.5 × the largest spread of fork means on either side, rounded up to 0.005.

It refuses (exit 2):
- fewer than 10 runs;
- a run whose `runner.txt` names different base and head commits;
- a verdict an A/A run cannot have;
- a benchmark missing from some run;
- a benchmark seen in several modes;
- a margin below 1.

Tests: `scripts/tests/test_perf_ab_calibrate.sh` has 15 checks, including that `perf_ab.py`
accepts the output and calls a 20% slowdown `slower`. With the margin removed, four checks fail.

**The procedure, once merged:**
1. Dispatch `perf-ab.yml` at least 10 times with `base_ref` = `head_ref` = the same commit of
   `main`, leaving `merge_base` unset (exact base), and default benchmarks and JMH options.
   Calibrating with other JMH options would calibrate other noise. Separate dispatches land on
   separate runner machines, which is the variation a later PR run will meet (the first three
   runs on runners had three CPU models). Each dispatched run has its own concurrency group, so
   all 10 can be dispatched at once and run side by side; before that change, every dispatch on
   `main` shared the group of `github.ref` and cancelled the one before.
2. Download each run's artifact: `gh run download <run-id> -n perf-ab -D aa/<run-id>`.
3. Run `scripts/perf_ab_calibrate.py aa/* --out scripts/perf-ab-calibration.json --json-summary
   calibration-summary.json`.
4. Commit `scripts/perf-ab-calibration.json`, and put the summary (every run's ratio, p and
   spreads) in the PR. `perf_ab_run.sh` uses the file when it exists, so verdicts stop reading
   `uncalibrated` from then on.
5. Repeat when the benchmark, the JMH options or the runner image change. A calibration describes
   the noise of one setup.

**Round order alternates.** The tool was first run end to end, with `--min-runs 3`, on three
local A/A runs: this machine, short settings, two benchmarks. Results:
- no run reached p < 0.01, and the worst |ratio − 1| was 4.25%, which gives minimum effects of
  6–6.5%;
- `headersWithStartTime` came out below 1 in all three runs (0.961, 0.966, 0.973);
- the per-round medians showed the cause: the fourth round, which is base in A B B A, was the
  slowest every time (155.7, 154.3 and 158.4 ms against about 146–153 for the others).

A B B A cancels a linear drift but not a penalty on one position, and with a fixed order that
penalty always lands on base. `perf_ab_run.sh` now alternates A B B A and B A A B between runs
(by the GitHub run id's parity, random locally, or `--order`) and records the order in
`runner.txt`. The calibration summary carries it per run, so a set of A/A runs sees a position
effect in both directions. Three runs do not establish the effect; what matters is that it can no
longer bias one side. These local numbers are not the calibration: they come from another machine
and from short JMH settings.

**The first A/A run on the runner.** That was run 45037111140. Its head, `1da927a2`, differs
from its base, `12b5c791`, only in scripts, workflows and the compare core, none of which
QueryPushdownBenchmark runs.
- **All six benchmarks** came out between 0.972 and 1.010, with fork-mean spreads of 1.8–8.1% per
  side.
- **One false positive by p alone.** `countEventsFromHeaders` came out at 0.980 with p = 0.0087,
  below 0.01 with nothing changed. With a calibrated minimum effect above 2%, it stays "no change
  detected", which is why `perf_ab.py` requires both conditions. The fork means are not fully
  independent: forks of one round share the machine's state at that time, so p is optimistic and
  the minimum effect is the real guard.
- **No consistent position effect,** unlike locally. The fourth round, base, was the slowest only
  for `decodeAllFilterInHandler` (356.86 in round 3, head, was slower still).
- **Attribution noise.** The p < 0.01 above triggered attribution, so the run also measured the
  attribution's noise between two JVMs. At `jfr_compare`'s 1-point floor, 13 frame rows came out
  "significant", up to 2.84 points (metadata parsing, +2.8). With 3 points there were none.
  `perf_ab_run.sh` therefore passes `--min-delta-pct 3` to attribution, adjustable with
  `--attribution-min-delta`.
- **Not a calibration:** this is one run, short of the 10 that `perf_ab_calibrate.py` requires.

**The second A/A run on the runner.** That was
[run 45040909061](https://github.com/btraceio/jafar/actions/runs/45040909061), head `baced18e`,
also without benchmark-relevant changes. It is the first run with the alternating order, which
came out B A A B.
- **Ratios:** all six came out between 0.989 and 1.019, the smallest p was 0.132, and no
  attribution ran.
- **Another machine:** it ran on an `AMD EPYC 9V74`, where the first ran on an
  `Intel(R) Xeon(R) 6975P-C`.
  - Absolute times differed by up to 19% between the two machines: `countByDecoding` round
    medians were about 400 ms on the Xeon and 471–485 ms on the EPYC.
  - Each run's A/A ratios stayed within 2.8%. The machine's speed cancels out of a same-job ratio,
    which is the premise of section 2.
- **Worst |ratio − 1| over both runs:**

  | Benchmark | Worst \|ratio − 1\| |
  |---|---|
  | `typeFilterInParser` | 0.028 |
  | `countEventsFromHeaders` | 0.020 |
  | `ceilingSkipEveryEvent` | 0.019 |
  | `headersWithStartTime` | 0.017 |
  | `decodeAllFilterInHandler` | 0.011 |
  | `countByDecoding` | 0.005 |

  If 10 runs look alike, minimum effects of about 1.5–4.5% follow. #250's measured effects
  (−18 to −25% on `headersWithStartTime`) are far above that.

**The calibration (2026-11-25).** Ten A/A runs were dispatched at once on `main` at `f1d80dfa`,
the #254 merge. All ten ran side by side without cancelling each other, which confirmed #254 on
GitHub, and all succeeded: runs 45705755402, 45705789463, 45705794627, 45705801185, 45705807938,
45705815616, 45705822167, 45705825903, 45705829049 and 45705833399.
- **Machines:** EPYC 7763 ×5, EPYC 9V45 ×3, EPYC 9V74 ×2, all AMD. No Intel runner came up this
  time; the first PR run had a Xeon 6975P-C.
- **Orders:** A B B A ×3, B A A B ×7.
- **No false positives:** none of the 60 tests (6 benchmarks × 10 runs) reached p < 0.01, so no
  run attributed.
- **`scripts/perf_ab_calibrate.py`** produced `scripts/perf-ab-calibration.json`:

  | Benchmark | Worst \|ratio − 1\| | `min_effect` | Worst spread | `noise_bound` |
  |---|---|---|---|---|
  | `ceilingSkipEveryEvent` | 0.0234 | 0.040 | 0.0894 | 0.135 |
  | `countByDecoding` | 0.0312 | 0.050 | 0.2230 | 0.335 |
  | `countEventsFromHeaders` | 0.0293 | 0.045 | 0.2033 | 0.305 |
  | `decodeAllFilterInHandler` | 0.0287 | 0.045 | 0.1985 | 0.300 |
  | `headersWithStartTime` | 0.0320 | 0.050 | 0.1377 | 0.210 |
  | `typeFilterInParser` | 0.0318 | 0.050 | 0.0815 | 0.125 |

- **Checked against earlier data:**
  - With this file, the first runner A/A run reads "no change detected" in every row, including
    `countEventsFromHeaders` at 0.980 with p = 0.0087.
  - #250's local A/B (`headersWithStartTime` 0.817, p 0.0022) reads `faster`. It is also flagged
    noisy, because the local short-settings run spreads more than the runner's bound.
- **`perf_ab_run.sh` picks the file up by default,** so PR runs give `slower`/`faster` verdicts
  from now on.
- **Recalibrate** when `QueryPushdownBenchmark`, the default JMH options or the runner image
  change.

**What a calibration does not promise.** The thresholds are the worst of about 10 noise draws,
times 1.5. A later A/A run can still exceed them, so the false-positive rate is low but not
measured. A benchmark whose noise is close to the effects that matter (section 2:
`decodeAllFilterInHandler`) gets a minimum effect too large to be useful. The summary shows this,
and the right answer then is a longer benchmark, not a smaller threshold.

## 4. What stays the same

- `nightly-jmh.yml` keeps its trend role: the same commit night to night, absolute scores and
  ratios. The A/B report answers a different question, this PR against its base, and does not
  replace the nightly trend.
- No Gates.md row, no PR-body statement, no required check.
- `jfr_compare`'s behaviour over MCP is unchanged by the extraction (step 1). Its response-shape
  tests stay green. Step 3 changes one thing it reports: frame names of JVM-generated classes lose
  their per-run address and lambda number (section 3.6).

## 5. Validating the report itself

The report claims "slower" and "faster", so its own false-positive rate and sensitivity must be
measured before anyone reads a verdict. This is the analogue of R5, a gate's proof that it fires.

1. **Runner facts.** Log `nproc`, memory and CPU model in every run. GitHub documents
   `ubuntu-latest` for public repositories as 4 vCPUs and 16 GB
   (<https://docs.github.com/en/actions/using-github-hosted-runners/about-github-hosted-runners>),
   but the jobs record what they actually got.
2. **A/A calibration.** At least 10 dispatches with `base_ref == head_ref`.
   - For every benchmark, record the distribution of the estimated ratio and of p.
   - Each benchmark's minimum effect is set above the largest |ratio − 1| seen in A/A, with
     margin.
   - Any `slower` or `faster` verdict in A/A, before minimum effects exist, is a false positive and
     is counted.
3. **Known-effect runs.**
   - btraceio/jafar#250's base (`1fe4cff0`) against its merge (`52df61bb`). Expected:
     `headersWithStartTime` and `countEventsFromHeaders` faster. `ceilingSkipEveryEvent` was
     measured as unchanged in section 2, so it is expected as no change detected.
   - The same pair reversed, which must report them slower. This is the injected regression.
   - The attribution of the reversed run should name `RecordingStreamReader.readVarintSeq` or its
     callers among the moved frames. If it does not, the leaf-only limitation (section 3.4) is
     hurting, and inclusive shares move up in priority.
4. **Cost.** Record the job's wall time per run.

## 6. Cost, measured on a GitHub runner

The first run on GitHub was
[run 45037111140](https://github.com/btraceio/jafar/actions/runs/45037111140), the `perf-ab` label
on btraceio/jafar#253:
- **Total: 38 minutes.** The `Compare` step took 37 min 23 s; the four rounds took 9:05, 8:56,
  8:53 and 8:52.
- **Attribution added 1 min 37 s** for one benchmark: building `jfr-shell` took 20 s, and the two
  profiled forks the rest.
- **The runner had 4 vCPUs** (`Intel(R) Xeon(R) 6975P-C`) and 16 GB, as GitHub documents for
  public repositories.

Locally, one round of the default benchmarks took 9 min 23 s, so the earlier 40–50 minute estimate
held. The 150-minute timeout leaves room for several attributed benchmarks.

## 7. Steps

| Step | Deliverable | Depends on |
|---|---|---|
| 1 | Extract the compare core into `shell-core`; `jfr_compare` delegates; `jfr-shell compare --json`; inclusive shares added | — |
| 2 | `scripts/perf_ab.py`: reads both sides' JMH JSON, computes fork-level statistics, verdicts, markdown and `report.json`; self-tests with fixed JSON fixtures, including a fire test | — |
| 3 | `.github/workflows/perf-ab.yml`: worktrees, interleaved rounds, profiled forks (`-prof jfr:stackDepth=2048`), artifact, step summary; triggers from 3.1. In the compare core: normalise the names of JVM-generated classes (`/0x…` suffix) | 1, 2 |
| 4 | Comment job (`workflow_run`), no PR checkout, escaped content | 3 |
| 5 | Calibration (section 5), minimum effects committed beside the script | 3 |
| 6 | Docs: a "Performance A/B" section in `doc/agents/Build.md`; `AGENTS.md` pointer | 5 |
| 7 | Later: `parser-core` and `pprof-parser` benchmarks; Go benchmarks via `benchstat` with the same A/B layout | 5 |

Steps 1 and 2 are independent and can go in parallel. Step 1 is the only production-code change.
It touches `jfr-mcp` and `shell-core`, so it needs the API-compatibility check for the shell
surface. It does not touch the untyped parser, so the Java/Go parity statement is "not applicable".

## 8. Alternatives considered

- **A threshold on the nightly trend.** Rejected: night-to-night deltas mostly measure which runner
  the job landed on (10-32% on the same commit, `jmh_trend.py:29-35`).
- **A blocking check with a fixed budget per benchmark.** Rejected by Gates.md principle 3 and the
  measured 5× variance.
- **Deterministic work counters instead of time** (events decoded, bytes read, varints read per
  benchmark), asserted like allocation budgets. This fits principle 3 and could become a real
  ratchet gate. It catches algorithmic regressions (decoding events the filter should have
  skipped) but not constant-factor ones (the varint inlining in #250 changed no counter). It is
  complementary, not a replacement, and is listed as decision Q5.
- **Self-hosted or larger runners for less noise.** This would lower the noise floor, but costs
  money and adds a maintenance burden. The same-runner A/B already cancels the runner's speed, so
  it is not needed for v1.

## 9. Decisions

Taken on 2026-11-22:

- **Q1. Name: `perf-ab`.** Workflow `perf-ab.yml`, label `perf-ab`. "Gate" would contradict
  Gates.md, where a gate blocks merges.
- **Q2. Trigger: the label only** (plus `workflow_dispatch` and `perf/**` pushes for the workflow's
  own testing). No automatic runs on parser changes: each would add 40-50 runner minutes to the PR,
  and Gates.md principle 4 keeps JMH out of default PR CI.
- **Q3. Scope of v1: `QueryPushdownBenchmark` only.** The `parser-core` and `pprof-parser` suites
  follow (step 7) once calibration shows what the runner can resolve.
- **Q4. No agent layer.** The report stays deterministic: no model call, no API key, no cost per
  run beyond runner minutes. An agent can still read the published artifact (`report.json` and the
  recordings) through `jfr-mcp` on a developer's machine, outside CI.

- **Q5. Work counters: yes, as a separate ratchet gate** (decided 2026-11-26). Deterministic
  counts of the work a parse does — events decoded, varints read, chunks read and more — counted by
  a test-only agent and budgeted exactly in `gradle/work-budgets.properties`, with a `Work-Budget:`
  statement for a raise. Design and evidence: [work-budgets.md](work-budgets.md).

## 10. What is not verified

- No part of this design has run on a GitHub runner. Every runner number (time, noise, minimum
  effect) is unknown until section 5.
- The section 2 evidence comes from one machine, one JDK (21) and one benchmark class.
- The JFR profiler's output layout (where JMH writes each fork's `.jfr` under `dir`) was not
  checked. Only the option list was read from `-prof jfr:help`.
- Whether JFR's stack traces keep inlined frames, so that attribution survives the JIT inlining a
  method like `readVarintSeq`, was not checked on a recording. Section 5, step 3, tests it.
- The extraction in step 1 was scoped by reading `JfrCompareTools` (588 lines) only. How much of
  `JfrAnalysisTools.extractFrames` and `detectExecutionEventType` must move with it was not
  measured.
- Hodges-Lehmann with an exact Mann-Whitney test at n = 6 + 6 is a proposal. Its smallest
  achievable two-sided p is 2/924 ≈ 0.0022, so p < 0.01 is reachable, but its power at the effect
  sizes of section 2 was not computed.
- Side note on the existing nightly: its last two scheduled runs (2026-11-20 and 2026-11-21, run
  44900314068) failed in under 10 seconds. They ran `main` at `821e2b6c` (#230), the last commit
  before 2026-11-21 17:29 UTC. That version's "Install JDK 8 for Gradle toolchains" step
  (`actions/setup-java@v4`, temurin 8) failed with "gzip: stdin: not in gzip format". Current
  `main` has no such step; the JDKs come from `.github/actions/liberica-jdks`. The next scheduled
  run on current `main` has not happened yet, so the fix is not confirmed.
