# Gates — what blocks a PR, and how to run each one

Every gate this repository enforces on a PR, in one table: what it checks, the
command that runs it locally, the command that regenerates its baseline, its
escape hatch, the CI job that runs it, and what it costs. This is the single
source of truth (R7): if a gate is not in this table, it does not exist — a
gate that lands adds its row here in the same change.

The harness these gates belong to was designed under four principles. The
evidence behind each is in
[the safety-harness plan](../plans/generated-code-safety-harness.md):

1. **Ratchet, not absolute.** Every new gate is green on current main and fails
   only on *new* violations. Absolute gates would demand a big-bang cleanup and
   get switched off; ratchets let a low-expertise operator merge safely with
   zero judgment calls.
2. **Escape hatches are reasoned and visible.** Breaking an API, raising an
   allocation budget, adding an ArchUnit violation each require a PR-body
   statement checked by a sibling of `scripts/parity-check.sh`. The harness
   cannot demand taste; it can demand a written reason at the exact moment the
   invariant bends.
3. **Deterministic oracles only, measured not reasoned.** Gates assert on
   bytes and names — allocation budgets in measured bytes, failure sets by
   test name, format checks against golden dumps — never on wall time,
   sleep-based race checks, or "did not throw".
4. **Layered cost.** PR-blocking gates run in minutes on synthetic fixtures;
   expensive nets (fuzzing sessions, JMH trends, real multi-GB recordings) run
   only in scheduled workflows.

Every command in the "Run locally" and "Regenerate baseline" columns has been
executed (R4); the wall-time column records what those runs measured.

## The table

| Gate | Checks | Run locally | Regenerate baseline | Escape hatch | CI job | Measured PR wall time |
|---|---|---|---|---|---|---|
| Doc links (R4) | Every relative link in the docs resolves; a new miss fails | `scripts/check-doc-links.sh` | none — the frozen allowlist inside the script shrinks as legacy misses are fixed; adding an entry requires fixing the document instead | none | `guardrails` | <1 s (0.8 s measured) |
| Gate-script regression suite (R5) | The gates' own tests, including each gate's proof that it fires on bad input | `scripts/tests/run_all.sh` | none — the suite is the oracle; a gate with no fire test is not done | none | `guardrails` | 1 m 22 s local (9 test files, measured) |
| Parity (Java/Go untyped parsers) | A production change to one untyped parser is stated for the other side in the PR body | `scripts/parity-check.sh --base origin/main` (local mode; CI reads the PR body) | none | `Parity: not applicable — <reason>` in the PR body | `parity` | <1 s local (measured); the CI job adds only two `gh pr view` calls |
| Formatting (Spotless) | Every Java file is formatted; runs on every Gradle build | `./gradlew spotlessCheck` | `./gradlew spotlessApply` rewrites the files — no baseline to regenerate | none | inside every Gradle invocation in `tests` | 9.5 s warm, local (measured) |
| Named failing set (R6) | Test failures are compared by name, never by count — a constant count can hide a swap | `scripts/failing-set.sh \| diff <before-dump> -` (dump before the change, diff after) | none — the standing set is dumped per run; compare dumps with `diff` | none | `aggregate-test-report` | the script is sub-second; the cost is the test suite's, not its own |
| API compatibility (japicmp per plane) | Every gated surface in `gradle/api-surfaces.txt` (parser, shell, mcp, hdump, pprof, otlp, llm-spi) is compared against the released bytes of its plane's baseline; incompatible changes beyond the accepted ratchet need a statement | `scripts/api-baseline.sh && ./gradlew apiCompat && scripts/api-break-check.sh --reports build/reports/api-compat --base origin/main` | shrink `gradle/api-accepted-breaks.txt`: entries are deleted when a baseline tag moves past the break; adding one is growth and needs the statement | `API-Break: <non-empty reason>` in the PR body | `api-compat` (comparison, every trigger) + `api-break` (statement, pull_request only) | 12.6 s warm with cached baselines, full chain (measured); ~26 s cold — 12.5 s baseline materialization + 13 s `apiCompat` (measured); CI numbers land with the PR that adds the jobs |
| Architecture boundaries (ArchUnit freeze) | The frozen rules in `arch-tests`: nothing outside the parser plane depends on parser internals, nothing outside `hdump-parser` depends on hdump internals, `api` packages never depend on their module's implementation packages, parser modules and `shell-core` never depend on consumer modules, and parser implementation code declares no mutable static field | `./gradlew :arch-tests:test` | `./gradlew :arch-tests:test -ParchFreeze` — only to shrink the committed violation store, or to record a deliberate grandfathering that shows up in the store diff | none — a new violation must be fixed | `tests` (JDK 21 cell, inside `./gradlew build`) | 45.5 s local, warm daemon (`./gradlew :arch-tests:test --rerun`, measured); 35–46 s across runs |
| Allocation budgets | Every public parse entry point (untyped JFR, typed JFR, hdump, pprof, otlp) allocates, per fixed fixture, at most the bytes recorded in `gradle/allocation-budgets.properties` on its own JDK cell — chunk-pool decode threads included | `./gradlew :parser-core:allocationTest :parser-codegen:allocationTest :hdump-parser:test :pprof-parser:test :otlp-parser:test :parser-core:allocationTest8 :parser-codegen:allocationTest8` | edit `gradle/allocation-budgets.properties` (re-measure per the recipe in its comments); lowering a value is free, a raise needs the statement | `Alloc-Budget: <non-empty reason>` in the PR body | `tests` (both matrix cells) + `alloc-budget` (statement, pull_request only) | the four allocation tasks ~4 s each warm (measured; ~17 s combined); hdump/pprof/otlp add no task — their budget tests run inside the modules' existing `test` suites |
| Golden value dumps | Every corpus fixture — JFR: the committed two-chunk `tck-test.jfr` plus the two generated recordings; hdump, pprof, otlp: their deterministic builders' fixtures — is rendered to a sorted, value-complete canonical dump and compared against a committed digest plus per-type counts; the JFR digests cover events, constants and metadata | `./gradlew :parser-core:test --tests '*JfrGoldenDumpTest' --rerun`; `./gradlew :hdump-parser:cleanTest :pprof-parser:cleanTest :hdump-parser:test --tests '*Golden*' :pprof-parser:test --tests '*Golden*' --no-build-cache`; `./gradlew :otlp-parser:cleanTest :otlp-parser:test --tests '*Golden*' --no-build-cache` (the `cleanTest` + `--no-build-cache` pairs work around the same `--rerun` cache lie the allocation gate records) | `-Pjafar.golden.update --rerun` per fixture: `./gradlew :parser-core:test --tests '*JfrGoldenDumpTest' -Pjafar.golden.update --rerun`, `./gradlew :hdump-parser:test --tests '*HdumpGoldenDumpTest' -Pjafar.golden.update --rerun`, `./gradlew :pprof-parser:test --tests '*PprofGoldenDumpTest' -Pjafar.golden.update --rerun`, `./gradlew :otlp-parser:test --tests '*OtlpGoldenDumpTest' -Pjafar.golden.update --rerun` | none — an intended value change regenerates the golden and the diff *is* the review; a real recording that replaced a generated fixture is refused, never compared (the D1 skip below) | `tests` (JDK 21 cell, inside `./gradlew build`) | JFR suite 3.7–4.1 s warm (measured); hdump+pprof ~1.8 s combined, otlp ~1.8 s (measured) |
| Jazzer regression corpus | The committed inputs under `parser-core/src/test/resources/io/jafar/parser/fuzz/UntypedParserFuzzTestInputs/parseUntyped/` replay through the `@FuzzTest` target inside ordinary `:parser-core:test`; the cause-chain oracle fails on any `Error` anywhere in the chain and on any root cause that is neither `io.jafar.*` nor `IOException`, and bounds each input's runtime (the hang oracle) | `./gradlew :parser-core:test --tests 'io.jafar.parser.fuzz.*' --rerun` | none — the corpus is the committed seeds; a new input is a hand-minimized crash file from a fuzz session, committed like any other change | the known-escapes allowlist below — a pinned escape, never a PR-body statement | `tests` (JDK 21 cell, inside `:parser-core:test`) | ~6 s warm (measured) |
| Adversarial recordings | The six `adv-*` seeds regenerate byte-identically from the committed enum — drift fails naming the seed and the regen command — and each seed's parse delivers one pinned specific outcome: a named exception class plus message fragment, or an exact delivered-event count ("did not crash" is not an outcome) | `./gradlew :test-recordings:generateAdversarialRecordings :test-recordings:test` | `./gradlew :test-recordings:generateAdversarialRecordings` | none — drift is fixed, or the case is deliberately rebuilt and re-pinned in the diff | `tests` (JDK 21 cell: drift in `:test-recordings:test`, pinned outcomes in `:parser-core:test`) | ~2 s warm (measured) |
| Known-escapes allowlist | An input listed in `parser-core/src/test/resources/io/jafar/parser/fuzz/known-escapes.txt` may escape the oracle only with exactly its pinned delivered throwable; an entry whose input no longer escapes FAILS "stale known-escape <sha>: remove the entry" — the list is shrink-only | `./gradlew :parser-core:test --tests 'io.jafar.parser.fuzz.*' --rerun` (the oracle enforces it; `EscapeOracleTest` proves each rule permanently) | none — no test or tool ever writes the list; growth is a visible hand edit in the diff, always with a `# reason:` line | the list itself — there is deliberately no third PR-body statement (I1, D3) | `tests` (JDK 21 cell, inside `:parser-core:test`) | included in the corpus row's ~6 s |
| Processor-count matrix (concurrency) | The three shell determinism nets, the pool-mirror abort tests, the JFR golden dumps and the shared-`ParsingContext` stress test all hold at both pool extremes — `-XX:ActiveProcessorCount=1` and `=16`, i.e. `StreamingChunkParser` pool sizes 1 and 14 — and every covered class's JUnit XML carries the `availableProcessors=<n> …` audit line proving the flag reached that JVM (R9) | `./gradlew -Pjafar.activeProcessorCount=1 :shell-core:test --tests '*JFRSessionDeterminismTest' --rerun :jfr-shell:test --tests '*MultiChunkDeterminismTest' --tests '*ConstantPoolChunkLocalityTest' --rerun :parser-core:test --tests '*EventDecodeFailureTest' --tests '*JfrGoldenDumpTest' --rerun :parser-codegen:test --tests '*GeneratedEventDecodeFailureTest' --tests '*SharedParsingContextStressTest' --rerun` — then the same command with `=16` | none — the nets are the oracle | none — a race must be fixed | `concurrency-matrix` (both cells) | measured as separate invocations in the step-5 reports: nets 10 s at =1 / 6 s at =16; abort + goldens ~4 s / ~3 s; stress ~8 s / ~7.3 s warm; the single combined invocation lands with the first CI run (see the subsection's R8 note) |
| Java/Go differential oracle | The Java and the Go untyped parser dump every corpus fixture plus the shared `go-parser/jfr/testdata` node-pool recordings under the one canonical line format (`JfrCanonicalDump` on the Java side, `go-parser/internal/differential` on the Go side), and the dumps must be identical modulo the root-caused allowlist: headers validated first (fixture sha256, event count vs line count, one-sided errors), then event totals and per-type histograms exact, then a line merge whose first mismatch names the event type, the field path and both lines | `./gradlew :parser-core:differentialTest --rerun` (needs `go` on PATH — a missing `go` fails the test naming PATH and the exact attempted command, never skips) | none — the allowlist `parser-core/src/test/resources/differential/allowlist.txt` is hand-edited and shrink-only; fixing a divergence is forced to shrink it | none — each allowlist entry documents a root-caused divergence with its proof in its `# reason:` line; a new divergence is fixed or root-caused, never waved through | `differential` (runs when `parser-core/`, `parser-codegen/` or `go-parser/` changes, and on every non-PR trigger) | ~9 s warm locally over the 6 fixtures (measured); the task deliberately sits outside `check` because it needs the Go toolchain |

## API evolution

**The surfaces and their baselines.** `gradle/api-surfaces.txt` is the single
list of gated API surfaces — one row per surface with its Gradle project,
release plane, baseline and the `pkg:`/`class:` entries that scope the
comparison. A surface's baseline is always *derived*, never hardcoded: from the
newest tag of its release plane, via
`scripts/derive-version.sh --plane <plane> --baseline-tag`. Published artifacts
(jafar-parser, jafar-shell, jfr-mcp) are downloaded straight from Maven Central
with `.sha1` verification; unpublished modules (hdump, pprof, otlp, the LLM SPI)
are built from a checkout of the baseline tag in a temp worktree. On a plane's
release tag, the old baseline is exactly the previous release.

**The released-bytes guard.** The comparison is against the *released* bytes,
never against something Gradle resolved. `settings.gradle` substitutes
`io.btrace:jafar-parser` with the composite build, so a baseline resolved
through Gradle would quietly compare current code with itself (R9). Every
`apiCompat*` task therefore logs the baseline's provenance — Maven coordinates
or `tag@commit`, with the sha256 of both sides — and fails on a substituted or
stale baseline jar whose bytes do not match its `BASELINE` stamp.

**The `ignoreMissingClasses` limit (R8).** The japicmp tasks run with
`ignoreMissingClasses=true`, so a breaking change to a *supertype* that lives in
a dependency outside the gated packages is not seen by this gate.

**Statement policy.** While every plane is 0.x, *every* incompatible change
needs an `API-Break: <reason>` statement in the PR body — RELEASING.md's "no
compatibility guarantees yet" does not create an exemption. The major-tag
exemption (a breaking change ships on a new major plane tag) applies only from
1.0 and is not automated: the gate runs on PRs, which cannot see tagging
decisions, so the exemption stays a documented reason inside the statement
rather than a code path.

## Architecture boundaries (ArchUnit freeze)

**The rules.** The `arch-tests` module
(`arch-tests/src/test/java/io/jafar/arch/`) runs five frozen ArchUnit rules,
in plain words:

- **(a)** only the parser plane (`parser-core`, `parser-codegen`) may depend
  on `io.jafar.parser.impl..` or `io.jafar.parser.internal_api..`;
- **(b)** only `hdump-parser` may depend on `io.jafar.hdump.impl..`,
  `io.jafar.hdump.internal..` or `io.jafar.hdump.index..`;
- **(c)** within `parser-core`, `hdump-parser`, `pprof-parser` and
  `otlp-parser`, classes in an `..api..` package never depend on that module's
  `impl`/`internal`/`internal_api`/`index` packages (`parser-codegen` is
  deliberately absent from this rule — generating classes *into*
  `io.jafar.parser.impl` is its purpose);
- **(d)** the parser modules and `shell-core` never depend on the consumer
  modules (shells, plugins, MCP, processors, tools) — the dependency direction
  is shells → shell-core → parser, never the reverse;
- **(e)** classes in the parser modules' non-api packages declare no field
  that is `static` and not `final` (synthetic fields are ignored).

**Membership comes from class dirs.** Every covered module's *compiled*
classes are imported from `<module>/build/classes/java/main`, and module
membership — defined once, in `arch-tests/build.gradle` — is what the rules
test, not package names: packages overlap across modules
(`io.jafar.parser.impl` exists in both parser-core and parser-codegen), so a
package-name rule would be ambiguous. `ModuleCoverageTest` keeps the import
non-vacuous: every covered module must import more than zero classes — the
Java-25 shell modules and the Java-8 parser modules alike — and every module
in `settings.gradle` must be either classified in a group or exempt. The
exemption convention: modules named `test-*` are test tooling
(`test-recordings`, and any later test-support module), `parser` is the
aggregate re-export with no sources of its own, and `arch-tests` covers itself.
An unclassified module fails with `classify <m> in arch-tests/build.gradle`.

**The freeze store.** Violations that exist today are grandfathered in a
committed text store, `arch-tests/src/test/resources/archunit_store/`.
No ordinary run can add to it: store creation and refreeze are off unless
`-ParchFreeze` is passed, so a new violation fails the build and must be
fixed. There is deliberately no PR-body statement for one (Q14): a deliberate
grandfathering is a hand edit to the store, visible in the diff.
`ArchRuleFireTest` proves each rule fires on a synthetic violator, naming the
rule, and proves in CI mode that the store is byte-identical after both a
failing and a passing check.

**R8 — what this gate does not see.**

- `demo/` is a separate Gradle build; its reach-in is not covered.
- parser-core's multi-release overlay class dirs (`java9`, `java13`, `java21`)
  are not imported: they override classes already counted from the main class
  dir, and importing both would register the same class names twice.
- `jafar-processor`'s two reach-ins live in code-generation templates shipped
  as resources (`templates/FactoryTemplate.java`, `HandlerTemplate.java`),
  which are never compiled, so no bytecode-level rule sees them.
- The store also freezes transitive bytecode dependencies (generic type
  arguments flowing through public signatures) that source-text reach-in
counts do not include; all are frozen, so the ratchet still holds.

## Allocation budgets

**What is budgeted.** `gradle/allocation-budgets.properties` records, per
public parse entry point, fixture and JDK major, the bytes one parse may
allocate: the untyped JFR parse (`parser-core.untyped.tck-test.jfr`, on the
committed `tck-test.jfr`), the typed JFR parse
(`parser-codegen.typed.tck-test.jfr`), and the hdump, pprof and otlp parse
entry points on their deterministic builders' minimal fixtures. The first
two run in dedicated Gradle tasks — `allocationTest` (JDK 21) and
`allocationTest8` (JDK 8) — outside the ordinary test suites; the latter
three parse single-threaded (verified: no `Executor`/`new Thread`/parallel
stream on their parse path; every run logs `poolThreads=0`), so they run in
the normal `test` task with a calling-thread meter and carry explicit
`jdk8=excluded: …` lines instead of a JDK 8 budget.

**The meter.** `AllocationMeter` (`test-support`) sums the calling thread's
allocation delta plus the *absolute* counters of every chunk-pool thread,
read on that thread from the `onChunkEnd` parser-listener callback while the
thread is still alive — pool threads die at parse end and report -1
afterwards. The JFR cells assert pool-thread freshness (no thread id repeats
between warm-up and measured parses): a reused thread's absolute counter
would double-count. Every run prints an `alloc <key> measured=… budget=…
tol=… vm=…` line (R3: where the value came from), and the JFR tests an
`aggregate=… callingThreadOnly=… pool=…` audit line — the split that shows
the pool threads, not the caller, carry the decode (46.3 MB of 46.4 MB on
jdk21), which is what makes a calling-thread-only meter blind to the
regressions this gate exists to catch.

**The pins.** The allocation tasks run with `-XX:ActiveProcessorCount=4`,
which pins `StreamingChunkParser`'s per-parse pool at `max(4-2,1) = 2`
threads — exactly the two chunks of `tck-test.jfr` — and with
`-XX:TieredStopAtLevel=1` (C1): under default tiered compilation, whether
C2's escape analysis has eliminated allocations in the hot decode loop by
the measured parse depends on compile-queue timing, wobbling the same
measurement by 0.6–6% between runs; under C1 it is byte-identical.

**Determinism, spread, tolerances.** Two warm-up parses are discarded (the
first pays ~14× the steady state in lazy init). The pool-thread share
measured byte-identical across consecutive runs and across all four locally
installed JDK 21 builds (Temurin, Zulu, Liberica, Graal — the measurement
tables and per-build comments are in the budget file). Each key's tolerance
is recorded there with its rationale: 2048 (untyped jdk21/jdk8, hdump, otlp),
4096 (pprof), 310,000 (typed — covering an occasional one-off lazy-init
outlier on a pool thread under Zulu, root cause not hunted, R8). A measured
value more than the tolerance *below* the budget fails with "re-tighten:
set <key>=<n>", so a loosened budget cannot linger.

**Raising a budget.** `scripts/alloc-budget-check.sh --base origin/main`
compares the budget file against the base ref: raising a value, raising a
tolerance, or turning a budgeted key into `excluded: …` requires
`Alloc-Budget: <non-empty reason>` in the PR body. Lowering a value, adding
a key, or leaving the file unchanged passes; deleting the file is refused
(it would un-gate every key). Re-measuring a key: the recipe — toolchain
forcing, `cleanTest` + `--no-build-cache`, per-task `--rerun`, auditing the
`vm=` line — is recorded in the budget file's comments. Gradle's build cache
restores another JVM's test result because the test JVM is not in the cache
key, so a green cached or `UP-TO-DATE` run is not evidence of a
measurement. (The `vm=` line of the audit output names the JVM that actually
ran — trust it, not the exit status.)

**R8 — what is not verified.** The jdk8 keys were measured on Zulu 8.0.482,
not Temurin: sdkman no longer offers a Temurin 8 build, and CI's JDK 8 cell
runs Temurin. CI's JDK 21 and JDK 8 cells also run Temurin patch levels with
no local copy. The tolerances carry headroom for exactly this (2048 where
the measured build-to-build spread is 16–64 bytes); if a CI cell reds on a
budget key, the first move is to re-measure on the CI image and adjust the
value — with an `Alloc-Budget:` statement if the change is a raise.

## Format correctness: golden dumps, the fuzz corpus, adversarial recordings

The step-4 nets pin *parsed values*, not just "did not throw" (R9): a
golden dump per fixture, a fuzz corpus with a cause-chain oracle, and
deterministically generated adversarial recordings. All three run inside
the ordinary `tests` job — no new CI time beyond the suites themselves.

### Golden value dumps

**What is rendered.** For JFR, `JfrCanonicalDump` (`test-support`) is the
one canonical line format: every event as `<type> k=v …` built from
`Values.resolvedDeep`, sorted dotted keys, arrays as `.0 .1 …`,
`byte[]` and string-constant wrappers as `b<len>:<hex>`, `\n`/`\r`
escaped, floats and doubles as `f:%016x` IEEE-754 bit patterns, no chunk
identity. hdump, pprof and otlp render their public `api` models into
sorted `<kind> k=v…` lines (classes/fields/instances/roots/strings;
samples with resolved locations, functions, strings, mappings, value
types; profiles, samples, locations, functions, the string table,
attributes).

**How a golden is stored.** One text file per fixture at
`<module>/src/test/golden/<fixture>.golden`: `fixture=`,
`fixture-sha256=`, `lines=`, `digest=` and sorted `count.<type>=` lines.
The digest is the sha256 over the sorted per-line sha256 values, so the
repo never stores a full dump and the comparison never holds one in
memory either. A changed dump line, a changed count or a changed line
total each fail, naming the fixture, the first differing golden line,
both digests and the regeneration command.

**Regeneration.** An intended value change regenerates the golden with
`-Pjafar.golden.update --rerun` (per-fixture commands in the table) and
the resulting golden diff is the review. The commands are the ones the
failure messages print, and were executed to produce the committed
goldens (R4).

**The D1 skip — real recordings are refused.** `./get_resources.sh` can
replace a generated recording with a real download (it deletes the
`.generated` marker). A golden test on an unmarked fixture is *aborted
(SKIPPED)* — never compared, never failed, never passed — with:

> `<path> is a real recording (no .generated marker): golden compare
> refused. Fix: delete it, then ./gradlew :test-recordings:generateTestRecordings`

so a fixture swap cannot silently change what the gate checks. The
committed `tck-test.jfr` is a real recording by design; its golden tests
do not run the guard on it.

**The fixture-sha rule.** The golden pins the fixture bytes' sha256
(pprof and otlp builders produce deterministic bytes, so theirs is
pinned; the hdump case is the exception below). A differing sha fails
with "fixture bytes changed (generator/builder output), not a parser
value change — regenerate: <cmd>": a builder change can never masquerade
as a parser value change, and a parser change can never be waved away
as a builder change.

**Where the full dump goes.** On a digest mismatch the full sorted dump
is written to `<module>/build/golden-actual/<fixture>.dump`, with the
new golden beside it — for `tck-test.jfr` that is about 454 MB
(453,889,122 bytes, measured), which is why goldens are stored as
digests. A green run never materializes the dump.

**Determinism and cost.** Three consecutive runs produced identical
digests per fixture (measured). The JFR suite costs ~4 s warm, the
hdump+pprof and otlp suites ~2 s each (measured); the tck digest itself
computes in ~2 s at `-Xmx1g`.

### The Jazzer regression corpus

The `@FuzzTest` target `UntypedParserFuzzTest#parseUntyped` lives in
parser-core's JDK-21 `test` source set (Jazzer `jazzer-junit` 0.30.0,
Apache-2.0). In regression mode — ordinary
`./gradlew :parser-core:test --tests 'io.jafar.parser.fuzz.*' --rerun`,
no `JAZZER_FUZZ=1` and no separate driver — Jazzer replays every file committed
under
`parser-core/src/test/resources/io/jafar/parser/fuzz/UntypedParserFuzzTestInputs/parseUntyped/`
(plus a built-in `<empty input>` case). The committed seeds are the
adversarial recordings below; a new input is a hand-minimized crash file
from a fuzz session, committed like any other change.

**The cause-chain oracle (D4).** The verdict walks the *whole* cause
chain, not just the delivered throwable: any `Error` anywhere in the
chain fails, and the root cause must be an `io.jafar.*` exception or an
`IOException`. Every failure message names the input's sha256 and the
chain (`A <- B <- C`). `EscapeOracleTest` proves each rule permanently,
including the injected-IAE, SOE-in-chain, OOM, hang and stale-escape
fires.

**The hang bound.** Each input's parse runs on a single-thread executor
and is bounded by `jafar.fuzz.inputTimeoutMs` (default 10000 ms); a
timeout fails "hang" naming the input sha. A hang has no throwable, so
it can never be pinned in the known-escapes list.

**Quiet logs.** The parser's slf4j logging is quieted in the test JVM
(`org.slf4j.simpleLogger.log.io.jafar=error`) and the oracle silences
stderr around the parse (see R8 below for why, and for what that hides).

### Known escapes (I1)

`parser-core/src/test/resources/io/jafar/parser/fuzz/known-escapes.txt`
is a **shrink-only** allowlist, enforced the way the ArchUnit freeze
store is: no test or tool ever writes it, and growth is possible only
by a visible hand edit in the diff — always an entry line
(`<sha256-of-input> <delivered-throwable-FQCN>`) preceded by a
`# reason:` line. The "delivered" throwable is the outermost of the
chain the oracle saw.

- a listed input passes **only** when it escapes with exactly that
  delivered throwable — anything else fails;
- a listed input that no longer escapes at all fails "stale known-escape
  <sha>: remove the entry", so fixing a parser defect is forced to shrink
  the list;
- a hang can never be listed;
- an entry only ever corresponds to an input committed in the corpus dir
  (the outcome tests enforce that).

There is deliberately no third PR-body statement (D3): a crash or
escape found by fuzzing or the adversarial recordings is pinned here,
and the parser itself is not fixed in this run — a fix under ~20 lines
is reported to the coordinator and not applied. The three pinned entries
today are the adversarial cases `deep-metadata`, `huge-length` and
`string-table-size`, each delivering `java.io.IOException` around a
non-jafar root cause.

### Adversarial recordings

`test-recordings` generates six deterministic adversarial recordings
(`adv-*` seeds) into the fuzz corpus dir, so they replay in every
`:parser-core:test` run and their outcomes are additionally pinned by
`AdversarialOutcomeTest`. See
[test-recordings/README.md](../../test-recordings/README.md) for the
case table and the file-naming rule.

**R8 — what this step covers, and what it does not.**

- Constants and metadata *are* covered for JFR: the digests include
  golden-only `C ` (constant-pool) and `M ` (metadata) lines keyed by
  chunk ordinal — 10,089 such lines in the `tck-test.jfr` golden. The
  pools are readable at `onChunkEnd`, so nothing was left out.
- The hdump golden pins a **null** fixture sha: the HPROF generator
  writes `System.currentTimeMillis()` into the header, so the file's
  bytes differ on every build. The timestamp is excluded from the dump
  and commented in the test, but the fixture-sha rule cannot guard that
  golden against builder changes.
- `parser-core/src/test/resources/test-dd.jfr` is deliberately not in
  the corpus: it is a byte-for-byte copy of `test-jfr.jfr`, so a golden
  for it would be a permanently identical second file with no added
  coverage.
- The `huge-length` seed declares a string length of `1 << 24`, not the
  exact `2^31` an earlier build used. The 2^31 declared length narrows
  to a negative `int` and drives an uninterruptible per-unit loop that
  survives `shutdownNow()` — an abandoned executor burned ~17 CPU-hours
  on it before the seed was shrunk. The 2^31 variant is the real
  unbounded-work vector: recorded here, not fixed (D3).
- The oracle silences stderr around the parse: the deep-metadata case
  makes the parser log an exception per recursion level, and one green
  replay before that fix wrote a **506 MB JUnit XML** (4.8M stack-frame
  lines in system-err; 14.7 KB after). The verdict carries the chain;
  parse logs are noise. The side effect is accepted: parser WARN/ERROR
  logging on a fuzz input is invisible in the XML.
- The pprof and otlp renderers cover `mapping` lines, but none of the
  committed fixtures contains a mapping — that decode path is not
  pinned by a golden today.

## Differential oracles: Java vs Go untyped dumps

The golden dumps pin one parser's values against a committed digest; the
differential pins two parsers against each other. The Java untyped parser
and the Go untyped parser each render the same fixtures into the **one
canonical line format** and the two dumps must be identical modulo the
root-caused allowlist.

**The one format (R7).** Every event is one line `<type> k=v …` built from
`Values.resolvedDeep` (Java) / `jfr.ResolveDeepMap` (Go) — deep
constant-pool resolution, never the collapsed variant — with sorted dotted
keys, arrays as `.0 .1 …`, `byte[]` and string-constant wrappers as
`b<len>:<hex>`, `\n` and `\r` escaped, floats and doubles as `f:%016x`
IEEE-754 bit patterns (bit equality, not decimal — the two parsers' float
formatting never agrees, their bits do), `null` printed as `null`, and no
chunk identity anywhere. Each dump starts with
`# jafar-dump v1 side=<java|go> fixture-sha256=<hex> events=<n>`; a refused
recording yields a header `… error=<msg>` and nothing else, so an empty or
stale dump can never pass as agreement.

**What runs.** `:parser-core:differentialTest` — deliberately outside
`check` and excluded from the ordinary `test` task, because it needs the Go
toolchain — runs `JavaGoDifferentialTest` over the JFR corpus
(`tck-test.jfr` plus the two generated recordings) and the shared
`go-parser/jfr/testdata` node-pool fixtures. The Java side dumps in-JVM
through `JfrCanonicalDump`; the Go side is
`go run ./internal/differential/cmd/jfrdump` inside `go-parser`, its stderr
relayed into the test log. A missing `go` on PATH fails the test naming
PATH and the exact attempted command (R3) — it never skips.

**The compare.** `DumpComparator` validates the headers first (kb/probe-pitfalls
1–2: a stale or empty dump, a swapped side, a header/body count disagreement
or a one-sided `error=` each fails with its cause before any line is
compared — never "all lines differ"), then requires the event totals and
per-type histograms to match exactly (`tck-test.jfr`: 67,657 events on both
sides, identical histograms), then line-merges the two sorted dumps; the
first mismatch fails naming the event type, the field path (with both value
renderings) and both full lines.

**The allowlist.**
`parser-core/src/test/resources/differential/allowlist.txt` is a
shrink-only, hand-edited list, enforced the way the fuzz known-escapes list
and the ArchUnit freeze store are: no test or tool writes it, and growth is
a visible hand edit in the diff. Two entry kinds: `equiv <type> <fieldPath>
<rule>` normalizes both sides' rendering *before* comparing — the values
themselves stay compared, so an equiv can never hide a value divergence —
and documents a representational difference; `diverge <type> <fieldPath>
side=<java|go>` excludes the field on both sides and names the side at fault
— a real cross-parser divergence. Every entry carries its proof in its
`# reason:` line. A stale entry fails — an equiv whose rule normalized
nothing on any fixture carrying its type, a diverge whose field no longer
differs — so fixing a divergence is forced to shrink the list. The list is
corpus-level while the compare runs fixture by fixture, so an entry whose
event type appears in no corpus fixture is silent: a typo'd type name is a
review catch, not a gate catch.

**The endpoint outcome (root-caused).** The one landed entry is
`equiv datadog.Endpoint endpoint unwrap-empty-key`. On the 18
`datadog.Endpoint` events of `tck-test.jfr`, Java renders
`endpoint.=GET /actuator/health` and Go `endpoint=GET /actuator/health`:
identical values — confirmed by `jfr print` and the JDK consumer API, both
of which deliver the flat scalar string — differing only in the wrapper.
The field is a pooled `java.lang.String` (a primitive class with no metadata
fields), so the Java untyped map path must key its value under the synthetic
empty field name, while Go's `java.lang.String` pool special case returns
the scalar. The rule renames the empty key; the value stays compared. The
full proof, with the code lines on both sides, is the entry's comment block
in the allowlist itself.

**The one corpus exclusion.** `node-pool-dangling-name.jfr` is excluded by
exact name in `JavaGoDifferentialTest`: its undecodable pool entry makes
the Java parse refuse (`error=` on the Java side) while Go delivers all
events rendering the entry as `null` — a one-sided whole-recording refusal
the per-field allowlist grammar cannot key, and which must keep failing
loudly: tolerating a one-sided `error=` would gut the header validation that
catches stale and failed dumps. The divergence itself is documented under
[Differences from the Java parser](../../go-parser/README.md) in the Go
README and pinned by both parsers' own suites
(`UnindexedPoolReferenceTest.DANGLING_NAME` in parser-core,
`TestUndecodableEntryFailsOnResolutionTheSameWayEachTime` in go-parser).

**The parity relation.** Two different contracts, both alive.
`scripts/parity-check.sh` gates *change scope*: a production change to one
untyped parser must be considered for the other, with a `Parity:` statement
when it is not ported. The differential gates *behavior*: whatever each
side ships must parse the same bytes into the same values. The Go harness
deliberately lives in `go-parser/internal/differential/`, outside
`go-parser/jfr/`, so its existence is no one-sided production change and the
parity gate stays clean (AC-6.6).

**Cost and gating.** ~9 s warm locally over the 6 fixtures (measured in the
step-6 task reports; the Gradle side is dominated by compile/up-to-date
checks). The CI job is path-gated — it runs when `parser-core/`,
`parser-codegen/` or `go-parser/` changes, and on every non-PR trigger —
so a docs-only PR does not pay for it, and on failure it uploads
`parser-core/build/differential/` (both sides' dumps) for review. It cannot
live in `go-parser.yml`: that workflow is filtered to `go-parser/**` and
would never see a Java-side change (AC-6.5).

## Concurrency: the processor-count matrix

**The hazard.** JFR chunk callbacks (`ChunkParserListener`, per-event
handlers) run concurrently on `StreamingChunkParser`'s per-parse pool of
`max(nproc-2,1)` threads. Listener state that looks single-threaded — one
plain instance field, one shared `ArrayList` — corrupts silently, and a
runner with one or two cores cannot reproduce the interleaving at all. The
Go parser already runs `go test -race` in CI; Java has no in-process
equivalent, so the matrix is the Java answer to the same threat: run the
existing determinism nets at both pool extremes instead of writing new
races-in-a-bottle.

**The matrix.** `-Pjafar.activeProcessorCount=N` (root `build.gradle`)
injects `-XX:ActiveProcessorCount=N` plus `jafar.expectedProcessors=N` into
every Test JVM — except `allocationTest*`, which keeps its own
`-XX:ActiveProcessorCount=4` pin (D9: pool = 2 = the two chunks of
`tck-test.jfr`). Every covered class calls test-support's
`ProcessorCount.assertExpected()` from `@BeforeAll`: it asserts the expected
value arrived and always prints
`availableProcessors=<n> expected=<N|unset> pool=<max(n-2,1)>` to real
stdout, which the JUnit XML records in its system-out. That audit line is
the R9 proof that the flag reached the forked JVM — the CI job greps every
covered class's XML for it, fails naming any class whose line is missing,
and the trailing space in the needle pins `=1` against `=16`.

**What runs at both counts.** The three shell determinism nets
(`MultiChunkDeterminismTest`, `ConstantPoolChunkLocalityTest`,
`JFRSessionDeterminismTest`), the pool-mirror abort tests
(`EventDecodeFailureTest`, `GeneratedEventDecodeFailureTest`), the JFR
golden dumps — their digests are identical at `=1` and `=16` — and the
shared-`ParsingContext` stress test.

**The stress test.** `SharedParsingContextStressTest` (parser-codegen) runs,
through one shared `ParsingContext`, N=8 concurrent parses of `tck-test.jfr`
(half typed, half untyped) released together by a `CyclicBarrier`, R=3
rounds. Each parse's per-chunk event sequences must `equals` a
fresh-context reference exactly — bucketed by chunk ordinal, written only by
the chunk's own pool thread — with no sleeps and no timing assertions;
`@Timeout(10, MINUTES)` is a hang guard only.

**The recorded fire cases (R5).** Both recorded races are probabilistic; each
was re-run until it failed, then reverted (sha-verified against the
pre-mutation file):

- *The shared per-chunk list (AC-5.2).* Feeding `JafarSources`'
  `loadAllClasses` `PerChunk` collection one shared unsynchronized
  `ArrayList` fails at `=16`:
  `MultiChunkDeterminismTest.allClassesAreStable` — "run 27 of 200 differs
  from run 1: element 9: {id=178, name=java.lang.Class, …} vs {id=7,
  name=jdk.ResidentSetSize, …}" (2 failures in 6 mutation runs). **At `=1`
  the same mutation stayed green** — recorded, and the reason the matrix
  needs both cells: a single-core runner is blind to this class of race.
- *The shared deserializer cache (AC-5.3).* Making
  `UntypedDeserializerCache`'s delegate a plain `HashMap` fails the stress
  test at the default 16-core host (pool = 14) with
  `IOException: … java.util.ConcurrentModificationException` from
  `EventStreamGenerated.shared` on a chunk pool thread (2 failures in 5
  mutation runs). The `=1` outcome of that specific mutation was not
  separately recorded (R8): with pool = 1 each parse's chunks are
  serialized, so the same blindness as the first case is expected.

**Times.** Measured warm, as separate invocations, in the step-5 task
reports: the determinism nets 10 s at `=1` / 6 s at `=16`; the abort + golden
suites ~4 s / ~3 s; the stress test ~8 s / ~7.3 s (5.5 s test method). The
single combined invocation the `concurrency-matrix` job runs has not been
measured locally — T31 is a Gradle-free task in a wave whose Gradle slot
belonged to another task — so its wall time lands with the first CI run,
and the coordinator re-runs the exact command in Verification (R4).

**The advisory half.** The review-time hazard lint (AC-5.4, Q12) is written
down in
[the verify-change skill](../../.agents/skills/verify-change/SKILL.md):
a field touched in a `ChunkParserListener` callback must be keyed by
`getChunkIndex()` or be thread-confined. It is deliberately not a CI check —
no static race checker owns this codebase's deliberately concurrent
design; the matrix is the gate, the lint is the review judgment.

## Nightly (advisory)

The scheduled workflows are nets, not gates (principle 4): they run overnight
and on demand, their findings are reports for humans, and nothing they find
blocks a PR. A workflow in this section therefore has no table row — the
table is the PR-blocking contract, and a scheduled run cannot see a PR.

### JMH trend — [`nightly-jmh.yml`](../../.github/workflows/nightly-jmh.yml)

**Triggers.** Nightly at 03:41 UTC (off the hour, out of the scheduling
stampede), `workflow_dispatch` with an input `jmh_args` (default
`-wi 2 -i 5 -f 1`; a dispatch run can pass a shorter or longer sweep), and
every push to a branch named `nightly/**`. That last trigger is how a
workflow is exercised before it reaches the default branch:
`workflow_dispatch` only works for workflows already on the default branch —
the same pattern as `go-parser-bench.yml`'s `bench/**`.

**What runs.** The repository's two JMH task sets, with their JSON output
pinned by `-rff`:

```bash
mkdir -p build/jmh
./gradlew --no-daemon :parser-core:jmh -PjmhArgs="-rf json -rff build/jmh/parser-core.json <JMH_ARGS>"
./gradlew --no-daemon :pprof-parser:jmh -PjmhArgs="-rf json -rff build/jmh/pprof-parser.json <JMH_ARGS>"
```

The tasks are JavaExec with the repo root as their working directory, so the
benchmark `@Param` fixture paths resolve and the results land in
`build/jmh/<module>.json` — and JMH does not create the result file's parent
directory, hence the `mkdir`. Locally the same commands were executed with
the short sweep `-wi 0 -i 1 -f 1 -r 1s` (they are the manual commands in
[PERFORMANCE.md](../../PERFORMANCE.md)): the pprof-parser run produced real
records, and the parser-core run produced an **empty result JSON** — its
benchmark class was missing its `@Setup`, every iteration NPE'd and JMH
swallowed the failures (the Gradle task still exited 0). That is exactly the
lie the trend script's unusable-data guard exists for; the benchmark was
fixed afterwards (5473/21765 ops/s on the fixed class), so the empty-result
guard is today exercised by the trend script's own tests rather than by a
live benchmark.

**Artifacts.** `build/jmh/*.json` is uploaded as `jmh-results` (90-day
retention, `if: always()`). The next run fetches the previous *successful*
run's `jmh-results` with the runner's own `gh` (`gh run list --workflow
nightly-jmh.yml --status success -L1`, then `gh run download`, with
`GH_TOKEN: github.token`), and pipes
`python3 scripts/jmh_trend.py --current build/jmh [--previous …]` into
`$GITHUB_STEP_SUMMARY`: benchmark, params, score ± error, unit and Δ% per
benchmark, keyed on benchmark+params (JMH's own grouping). A missing or
expired previous artifact is not an error — the report says "no baseline".

**Advisory by design.** The trend script exits 0 on *any* timing change — a
50% regression is still 0 — because a wall-clock gate would be flaky or
inert. Exit 2 is reserved for unusable result data (unparseable JSON, a
record lacking `benchmark` or `primaryMetric`, a non-numeric score, a
current run with no result JSON at all): that is a pipeline bug, not a
performance statement, and only that fails the workflow.
`scripts/tests/test_jmh_trend.sh` (part of `run_all.sh`) proves each rule on
the real JMH payload shape — the numbers nest under `primaryMetric`, and
`scoreError` can arrive as the string `"NaN"`.

**R8 — what is not verified before merge.** The `schedule` and
`workflow_dispatch` triggers, the GitHub-side `gh run download` and
artifact plumbing, and the run-summary rendering execute only on GitHub
runners, after the workflow reaches the default branch. What was verified
pre-merge: the JMH commands locally with the short sweep (real records for
pprof-parser; the parser-core empty-array finding and fix above), the trend
script over the real JSON — including a doctored previous run for the Δ%
path — the hermetic test suite, and the YAML parse. The first scheduled run
is the first real execution.

### Jazzer fuzz — [`nightly-fuzz.yml`](../../.github/workflows/nightly-fuzz.yml)

**Triggers.** Nightly at 02:17 UTC (off the hour, out of the scheduling
stampede), `workflow_dispatch` with an input `max_duration` (default `5m`;
a dispatch run can pass a shorter bound for a quick check), and every push
to a branch named `nightly/**` — the same pre-merge mechanism as the JMH
workflow above.

**What runs.** One coverage-guided Jazzer session (`JAZZER_FUZZ=1`) per
step-4 fuzz target — currently
`io.jafar.parser.fuzz.UntypedParserFuzzTest.parseUntyped` — because Jazzer
fuzzes exactly one `@FuzzTest` per run (its `AgentConfigurator` refuses a
second fuzz test in the same JVM), so the targets are looped outside
Gradle:

```bash
JAZZER_FUZZ=1 ./gradlew --no-daemon :parser-core:test \
  --tests 'io.jafar.parser.fuzz.UntypedParserFuzzTest.parseUntyped' \
  -Pjafar.fuzz.maxDuration=<DURATION> --rerun
```

The workflow then runs `:parser-core:differentialTest` (the nightly always
carries the Java/Go differential, spec Q15) after `setup-go` 1.24, since
that task needs Go on PATH and plain `build` cannot assume it. A target
whose oracle catches an escape exits nonzero; the loop records that and
still runs the remaining targets, then fails the step — the differential
and the artifact uploads run either way (`if: always()`).

**The session bound.** `@FuzzTest(maxDuration)` defaults to `"5m"` in
jazzer-junit 0.30.0 and is only a fallback: Jazzer resolves the bound from
the `jazzer.max.duration` system property (or `JAZZER_MAX_DURATION` env)
first, and applies the annotation value only when that option is still at
its default. `parser-core/build.gradle` therefore wires
`-Pjafar.fuzz.maxDuration` (default `5m`) to that system property, and the
annotation stays bare. `UntypedParserFuzzTest.sessionBoundIsWiredToTheTestJvm`
is the wiring's permanent fire proof: it fails if the build stops setting
the property, and also refuses the two values that would silently *unbound*
a session — an empty string (Jazzer: unlimited) and a zero duration
(libFuzzer's `-max_total_time=0` means no limit). Verified against the
0.30.0 jars: the override flows through `Opt.maxDuration` to libFuzzer's
`-max_total_time=<seconds>`.

**Artifacts, and why nothing is committed (AC-7.1, Q11).** Crash inputs
land where Jazzer's `artifact_prefix` points — the **committed corpus
directory** under
`parser-core/src/test/resources/io/jafar/parser/fuzz/UntypedParserFuzzTestInputs/`
(so a committed crash would be replayed as a regression test on the next
build) — and the coverage-guided corpus grows under
`parser-core/.cifuzz-corpus/`. The workflow uploads both as artifacts
(`jazzer-crash-inputs`, `jazzer-generated-corpus`; 90-day retention,
`if: always()`, `if-no-files-found: warn` — no crashes is the healthy
case) and commits **nothing**. A human takes it from there: minimize the
crash input, add it to the committed corpus by a visible hand edit, and pin
the escape in `known-escapes.txt` exactly as the oracle's failure message
names it (`<sha256> <delivered-throwable>` with a `# reason:` comment) —
never a parser fix inside that change (D3).

**It finds real things.** The first local session
(`-Pjafar.fuzz.maxDuration=30s`) found a D4 escape in 19 runs (the exec/s
lines of its libFuzzer output are in the T38 report): an input whose parse
escapes as `IOException` wrapping a `java.nio.BufferUnderflowException`
root, input sha256
`b231c025371f1a865e3b5cb0a231bb500a4fc147fc2f173c8d13b887fb91c376`, DEDUP
`b97439600fb31b5d`, crash file preserved with the T38 task report for that
human minimization (and deleted from the tree). Until it is pinned, a fuzz
session can hit it at any time and the nightly fuzz step goes red — that
is the net reporting a real finding, not a flake.

**R8 — what is not verified before merge.** The `schedule` and
`workflow_dispatch` triggers, the GitHub-side artifact upload, and the
runner's Go/JDK setup execute only on GitHub runners, after the workflow
reaches the default branch. What was verified pre-merge: the exact fuzz
command locally at `30s` (real coverage-guided output; the escape above is
its finding), the crash and corpus file locations on disk, the wiring fire
proof, the YAML parse, and `:parser-core:differentialTest` green. The
first scheduled run is the first real execution on a runner.
