# Performance A/B report for pull requests (`perf-ab`)

Status: proposal, 2026-11-22. Nothing here is built. Section 9 records the decisions taken on
2026-11-22 and the one still open; section 10 lists what is not verified.

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

## 6. Cost estimate (to be replaced by measurements)

Locally, one invocation of the default benchmarks took 9 min 23 s (JMH's "Total time", 6
benchmarks × 3 forks × 8 iterations). The A/B needs 4 such invocations, so about 38 minutes, plus
two Gradle builds and, per changed benchmark, 2 profiled forks. That makes a local estimate of
**40-50 minutes per labelled PR**. The GitHub runner's time is unknown until step 1 of section 5;
`nightly-jmh.yml` allows 90 minutes for its three suites.

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

Still open:

- **Q5. Work counters.** Add deterministic counters as a separate ratchet gate (section 8)?
  Proposed: a separate design doc if wanted.

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
