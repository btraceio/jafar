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
