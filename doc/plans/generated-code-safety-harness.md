# A safety harness for generated code

**Status:** Proposal, based on the `generated-code-safety-harness` investigation (2026-11-05) · **Tracking:** TBD after review

This is a plan document: it records what was proposed at the time it was written.
When a gate lands, it moves into CI and `doc/agents/`; this document stays as written.

---

## Summary

Most code changes in this repository are now written by agents and reviewed by humans
with varying expertise. The guardrails effort (#165–#179) made the *process* safe:
doc links are checked, the parity statement is required, failure sets are reported by
name, release gates have regression tests. Nothing yet checks the *code itself*
against the properties that keep jafar shippable: a stable public API, module
boundaries that hold, deterministic concurrent parsing, allocation discipline, and
correct values for every supported format.

This proposal adds those checks as deterministic gates. Every claim below about
current behavior was measured, not estimated; the investigation graph holds the
evidence and the raw numbers.

The design constraint, again, is the **silent wrong answer**: a breaking change to
`JafarParser` merges green today, and so does a listener that loses 30 events to a
data race that a single-threaded CI runner cannot reproduce.

## Four rules that shape every gate

1. **Ratchet, not absolute.** Every gate is green on current `main` and fails only on
   *new* violations. Baselines (ArchUnit violations, API compatibility, allocation
   budgets) are files in the repository, updated in the same PR that legitimately
   changes them. A gate that demanded a big-bang cleanup would be switched off.
2. **Escape hatches carry a written reason.** The parity gate generalizes: an API
   break requires `API-Break: <reason>` in the PR body, an allocation budget raise
   requires `Alloc-Budget: <reason>`. The harness cannot demand taste; it can demand
   that the moment an invariant bends, a human-readable reason is written down and
   stays in the PR forever.
3. **Deterministic oracles only.** Gates assert on bytes and payloads, never on
   wall-clock time or exit status. Measured on this machine: parsing `tck-test.jfr`
   allocates *exactly* 82,920 bytes on JDK 21 (and 83,264 on JDK 8) on every run —
   byte-identical — while the same parse takes 245 ms or 52 ms depending on JIT mood.
   Budget bytes; never budget time. This is R9 applied to CI.
4. **PR gates are minutes; expensive nets run nightly.** PR-blocking checks use small
   fixtures (the 2.3 MB real Datadog recording, generated recordings). Fuzzing
   sessions, JMH trends, and the 1.7 GB real recordings go to scheduled workflows.

## The gates

### 1. API evolution — japicmp on every release plane

Today only `shell-core` runs japicmp, against a hardcoded `jafar-shell:0.9.0` baseline
that will rot. The parser API — the product — has no gate at all.

- Apply japicmp to every published plane: `parser` (via the `io.btrace:jafar-parser`
  aggregate, which carries the API classes — verified on Maven Central), `shell`,
  `mcp`, plus the not-yet-published modules (`hdump-parser`, `pprof-parser`,
  `otlp-parser`, `shell-core`, the LLM SPI), which either get published or get a
  tag-checkout baseline built in CI.
- Baselines derive from the newest plane tag — the same logic `derive-version.sh`
  already uses. Never hardcoded.
- A breaking change fails the build unless the PR carries `API-Break: <reason>` or
  ships on a new major plane tag (the versioning in `gradle/version-from-tag.gradle`
  already anticipates this).

### 2. Architecture — ArchUnit freeze rules

The realistic agent mistakes are mechanical: importing `io.jafar.parser.impl` instead
of the public API, making a parser module depend on a shell, adding mutable static
state. Rules as plain JUnit tests, baselined with ArchUnit's freeze store so current
(main-free) violations are grandfathered:

- nothing outside `parser-core`/`hdump-parser` imports their `impl`/`internal`
  packages; `api` never depends on `impl` inside a module;
- shells → `shell-core` → parser SPI, never the other way; extension only through
  the ServiceLoader SPIs;
- no mutable non-final static fields in parser implementations.

DRY (copy-paste detection) is report-only; open-closed *elegance* is not
mechanically checkable and stays with review.

### 3. Concurrency — vary what the runner hides

Jafar's one documented concurrency hazard — chunk callbacks run on a pool, so
listener state corrupts silently on machines with more cores than the CI runner —
was reproduced live **twice** during the investigation: a one-line `int[] n++` counter
handler reported 67,436, 66,499, 65,954, 65,851 events for the *same file*, and a
dump probe quietly lost 30 lines to an unsynchronized `ArrayList`.

- Run the existing determinism tests at `-XX:ActiveProcessorCount=1` **and** `=16` —
  one matrix cell, no new test code, and it catches "works on the runner, races on
  the laptop."
- A shared-`ParsingContext` stress test: N concurrent parses of one recording must
  reproduce a single-threaded reference exactly.
- A review-time lint for the hazard pattern (a field touched in a
  `ChunkParserListener` callback must be per-chunk keyed — the rule
  `Architecture.md` already states).
- Targeted nightly jcstress runs on the shared caches, only when those files change.

Rejected: static race finders (noisy precisely where the design is deliberately
concurrent) and a runtime cross-chunk-mutation detector in product (unsound without
data-race semantics; wrong place — see the investigation's `a5-detector-decision`).

### 4. Allocation — byte budgets per entry point

Proven cheap and exact: `ThreadMXBean.getThreadAllocatedBytes` around a parse is
byte-deterministic after two warmup parses. So:

- One `AllocationBudgetTest` per public entry point per small fixture: parse twice
  for warmup, then assert the byte delta stays within a checked-in budget
  (`allocation-budgets.properties`, keyed per JDK).
- The budget file is the ratchet: raising a number requires updating the file in
  the same PR with an `Alloc-Budget: <reason>` statement.
- Nightly: run the existing JMH tasks, store results as artifacts, report trends.
  This also fixes `PERFORMANCE.md`, which points at a `:benchmarks:jmh` target that
  does not exist.

### 5. Format correctness — goldens, fuzzing, and oracles

Four nets, each catching a different class of failure:

- **Golden value dumps.** Parse each corpus fixture per format into a normalized,
  sorted, value-complete dump; the hash lives in the repository. A PR that changes
  any parsed value changes the hash and must show the golden diff. This is the net
  for the DataShapes class — the wrapped-string bug in `doc/agents/DataShapes.md`
  passed every test because none asserted on the payload.
- **Jazzer fuzz tests.** `@FuzzTest` methods in the JDK-21 test set: only
  `Jafar*`/`IOException` may escape. Corpus replay runs in every PR; coverage-guided
  sessions run nightly. Random mutation alone found zero faults in 200 trials — the
  parser absorbs garbage — so the corpus also gets **adversarial recordings**
  generated by `test-recordings` (corrupted string-table sizes, huge declared
  lengths, deep metadata nesting for the StackOverflowError vector).
- **Differential oracles.** jafar vs the JDK backend (the TCK already runs both);
  typed vs untyped across the whole corpus (the parity test exists for one
  recording); and new — **Java vs Go**: the same recording dumped by both untyped
  parsers, diffed. Prototype result: 99.47% of 67,657 lines byte-identical under five
  normalization rules; the residue is float formatting (compare IEEE 754 bit
  patterns) and one real divergence (`datadog.Endpoint.endpoint`: empty-key wrapper
  in Java, collapsed scalar in Go) to root-cause when the gate lands. This turns the
  parity *statement* into a parity *proof*.

### 6. The advisory layer — knowledgebase + review

What no gate can judge (elegance, API pleasantness, novel bug shapes) goes to
review, informed by memory: distill the recurring failure classes (DataShapes cases,
the chunk-callback hazard, the plane-coupling rules) into
`.knowledgebase/memories`, which sphinx review pulls into every reviewer's context
as prior-learnings. Advisory, never blocking — findings are proposals a human
confirms.

## Deliberately not built

SonarQube-class platforms (a fourth review surface duplicating three), wall-clock
budgets in CI (5× measured variance — flaky or inert), full jcstress per PR,
blocking PMD/Checkstyle/CPD, PIT as a blocking gate, static race finders as the
primary concurrency net. Reasoning per item is in the investigation's `dead-ends` node.

## Sequence

| Step | Gate | Effort |
| --- | --- | --- |
| 1 | japicmp across planes + `API-Break` statement gate | medium — release-plumbing heavy |
| 2 | ArchUnit freeze rules | small |
| 3 | Allocation budgets + `Alloc-Budget` gate | small |
| 4 | Golden dumps + Jazzer regression corpus | medium |
| 5 | ActiveProcessorCount matrix + shared-context stress | small |
| 6 | Java/Go dump differential | small — prototype exists |
| 7 | Nightly fuzz sessions + JMH trends | small, scheduled workflows |

Open items from the investigation, resolved during implementation: root-cause the
`endpoint` divergence (gate step 6 will keep it visible until settled); decide
whether the unpublished API modules publish standalone jars or get tag-checkout
baselines; per-JDK budget keys vs JDK-21-only gating.

## Non-goals

No generated-code *approval* flow — the harness judges properties, not intent. No
coverage targets as gates (PIT/Jacoco stay advisory). No new public API for testing
internals beyond what `Values` already offers.
