# Agent configuration and deterministic guardrails for agentic development

**Status:** Proposal, decisions recorded below · **Tracking:** one GitHub issue, one sub-issue per step

This is a plan document: it records what was proposed and decided at the time it was
written. Working rules live in [AGENTS.md](../../AGENTS.md) and
[Verification.md](../agents/Verification.md); when a step of this plan lands, it changes
those files and this one stays as written.

---

## Summary

Jafar's agent configuration already has the right layer-1 shape: `AGENTS.md` is a short
entry point, and the detail — including the verification rules R1–R11, each with a case
file — lives in `doc/agents/`. What is missing are the other two layers: procedures run
by hand where a skill should load them at the moment of use, and rules that "must always
hold" that nothing enforces. This proposal separates the concerns:

| Layer | Holds | Nature |
| --- | --- | --- |
| `AGENTS.md` + `doc/agents/` | What the project is: the map, the R-rules, the case files | Advisory, always loaded — keep |
| Skills (`.agents/skills/`) | How to run a procedure, loaded when it is needed | Advisory, on demand — build |
| Scripts + hooks + CI | Rules that must always hold | Deterministic — build |

The design constraint that shapes everything below: the recurring bug class in this
repository is the **silent wrong answer** — every case file in
[Verification.md](../agents/Verification.md) shipped green. The guardrails must therefore
gate exactly the checks an agent is tempted to fake: exit status over payload (R9),
count over name (R6), "tests pass" over "the right tests pass" (R5), doc claim over doc
execution (R4).

## Why change anything

Evidence gathered from the current tree, not estimates:

1. **Advisory text cannot gate.** `AGENTS.md` says a pre-commit hook runs
   `spotlessApply` — no `.githooks` directory exists in the repository. Three documents
   reference it (`AGENTS.md` quick start, `doc/agents/Architecture.md` §Pre-commit
   Formatting, `CONTRIBUTING.md` §First-Time Setup), and two of them instruct
   `git config core.hooksPath .githooks`, which points at nothing and **replaces the
   user's own global hooks** with nothing. The rules were right; nothing enforced them,
   and the documents drifted from a hook that was apparently planned and never shipped.
2. **Mechanizable procedures run by hand.** R5's revert-run-restore recipe, R6's
   named-failure-set dump, and the documentation link checker are inline Python in
   `Verification.md`, executed only when an agent remembers to. CI already runs
   `spotlessCheck`, `japicmp` on `shell-core`, the TCK, `go vet` / `go test -race` on
   `go-parser` changes, and label-gated e2e — those gates work. The R-procedures are
   the ones with no gate at all.
3. **The parity rule is prose.** PRs #135 and #150 record careful parity statements by
   hand ("Parity: go-parser already skips an undecodable event…", "Go parity:
   `skipString` in `go-parser/jfr/strings.go` had the identical…"). The convention
   exists and is honored by whoever remembers it. Nothing requires the statement.
4. **Skill reach is one harness.** The only skill in the repository,
   `.claude/skills/babysit-pr`, is invisible to every harness but Claude Code.
5. **The release pipeline's gates are new.** Tag-derived versioning, the per-plane fat
   jars and the publish-nothing guard (#164) landed in the last few commits. Before
   them, snapshot pushes without tags produced a burst of "Maven Central sync failed
   for untagged-*" bot issues. Nothing regression-tests the gates themselves.

## What already works — do not rebuild it

- `AGENTS.md` at 159 lines with the R-table and the module map; `Verification.md` with
  a case file per rule and "Keeping this file honest" defining what earns a rule. This
  is the invariants layer, already in the right shape.
- The CI gates listed above.
- The MCP guidance-contract tests and `McpAgentWorkflowEndToEndTest` (#121): the
  agent-facing surface of `jfr-mcp` is already pinned deterministically. This is the
  seed of the dogfood evals in §4.
- Generated test recordings (#141): the suite runs in CI without downloads, which
  makes R6's named-failure comparison mechanically possible in CI, not only locally.

## Proposal

### 1. Mechanize the checkable rules

| Rule | Current state | Enforcement |
| --- | --- | --- |
| `spotlessApply` before commit | Docs claim a `.githooks/pre-commit` that does not exist; CI runs `spotlessCheck` | **Delete the hook claims** from the three documents and let CI's `spotlessCheck` be the single gate. Never instruct `core.hooksPath`: it silently disables the user's global hooks. If local pre-commit enforcement is still wanted later, ship a setup script that installs the hook into `.git/hooks`, which the global hooks chain to. |
| R6 — failure sets by name | Inline Python, run by hand | Promote to `scripts/failing-set.sh`. With generated recordings, CI can compare the base-branch and PR failure sets by name; a constant count no longer hides a swap. |
| R4 — documentation is code | Link checker inline, with a known-stale backlog ("23 misses") | Promote to `scripts/check-doc-links.sh` + CI job. Two tiers: `doc/agents/` and top-level documents at zero misses; the legacy misses frozen as an allowlist in the script so the number can only shrink. |
| CLAUDE.md stays the redirect stub | Bootstrap comment in `AGENTS.md` | CI `diff` against the canonical stub text. |
| Java/Go untyped-parser parity | Prose; statements recorded by hand (#135, #150) | **Blocking CI check** — design below. |
| `doc/plans/` is a record, not living documentation | Prose in Verification.md | CI flag on PRs that *modify* a `doc/plans/` file predating the last release tag. |
| `.investigations/`, `.sphinx/` are session-local | Committed to the repository | **Remove from the repository and gitignore.** They are tool-generated, harness-specific session state and must stay private. See Decisions. |

**The parity check, blocking:**

- `scripts/parity-check.sh` examines the PR diff. If it touches
  `parser-core/src/main/java/io/jafar/parser/impl/`, `parser-core/src/main/java/io/jafar/parser/internal_api/`,
  or `go-parser/jfr/` (production code, not tests or fixtures), the check passes only when:
  - the diff also touches the counterpart path set, **or**
  - the PR body carries a parity statement — `Parity: ported in this PR` or
    `Parity: not applicable — <reason>` — matching the convention #135 and #150 already
    use.
- Wired as a separate required status check in `pr-tests.yml`, so the verdict is
  visible without reading a test log.
- Carve-outs: test-only changes, `parser-codegen` output (runtime bytecode generation
  has no Go counterpart — the standing example in `AGENTS.md`), and the statement
  format must name the direction (Java → Go or Go → Java), because the obligation is
  symmetric.
- R5 applies to the gate itself: hermetic tests that it fails on a one-sided diff with
  no statement, passes with a statement, passes on a two-sided diff, and ignores
  test-only changes.

### 2. Move procedures into skills

Skills live in `.agents/skills/` (read natively by Pi) with `.claude/skills` a symlink
(read by Claude Code) — the layout the java-profiler repository uses for the same
two-harness requirement. `babysit-pr` moves there first.

| Skill | Takes over | Triggers |
| --- | --- | --- |
| `babysit-pr` (exists; port the reach) | The PR-stewardship loop; already encodes R1, R5, R6, R9 | CI red on a PR you own |
| `verify-change` | R1 (typed into the built artifact), R5 (prove the test fails: revert-run-restore), R6 (baseline and compare failure sets by name) | Before claiming any change done |
| `pr-writing` | Commit/PR conventions, the R8 "what I did not verify" section, the parity statement | Writing a PR description or commit message |
| `doc-verify` | R4: run every command in a clean environment, link check, verify the claim not just the command | Editing `doc/` or any `*.md` |
| `parity-check` | Enumerate what changed in the touched parser, decide port / not-applicable, record it | Any change to either untyped parser |
| `release-steward` | Tag-derived versioning, per-plane fat jars, snapshot-on-push, JBang catalog sync, and watching the Maven-Central-sync bot for the "untagged-*" issue class | Cutting a release; babysitting a publish |

### 3. Keep the invariants layer

`AGENTS.md` stays the entry point; `Verification.md` stays the case-file ledger and keeps
earning rules exactly as "Keeping this file honest" prescribes. The only edits this
proposal makes to it: remove the false pre-commit-hook claim, and promote the R5 / R6 /
link-check procedures into their scripts, leaving a one-line pointer in each rule. The
mechanism that produced R1–R11 is not replaced by the gates — the gates exist so that
the *next* case file is harder to write.

### 4. Close the gaps

- **Guardrail regression suite.** Every script and check from §1 gets hermetic tests
  (the parity-check tests above are the model): prove each gate fires on bad input and
  passes good input before it starts gating anyone. A gate that has never failed is an
  assumption (R5, and R10's closing line, apply to safety nets as much as to fixes).
- **Dogfood evals.** Extend the guidance-contract tests into scripted agent-task
  evals — representative tasks ("find the top allocating method", "compare these two
  recordings") run against `jfr-mcp` itself whenever guidance, prompts, or tool
  descriptions change. Jafar can regression-test its own agent experience end to end;
  almost no repository can.
- **Verification path per plane.** `doc/agents/Build.md` gains one short table per
  plane (parser / shells / MCP / Go): the command, what healthy output looks like, and
  the one assertion that catches the silent-wrong-answer class for that plane.
- **Measurement.** Leading: share of PRs carrying the R8 statement and the parity
  statement, both scriptable from PR bodies. Lagging: fix-after-merge PRs and reopened
  issues. Record the baseline before any gate lands, so the gates' effect is a number
  and not a feeling.

## Decisions

1. **Harness scope: Claude Code and Pi.** Skills in `.agents/skills/` with a
   `.claude/skills` symlink; no other harness is supported or verified.
2. **The parity check is blocking**, as specified in §1.
3. **`.investigations/` and `.sphinx/` are private session state, not repository
   content.** They are removed from the repository and gitignored. Both directories
   have already been published in the repository's history (`.investigations/` since
   #121); that exposed history is **accepted as-is** — no rewrite, which would replay
   every commit SHA of a public repository. The obligation is that the directories
   stop growing: after removal, no commit extends them again, and the `.gitignore`
   entry makes any accidental re-add visible in a diff.

## How the work proceeds

- One GitHub tracking issue; each step is a sub-issue small enough for one PR.
- Every step that adds a gate proves the gate fails when it should (R5) in the same PR
  that introduces it.
- Each step follows the repo's own bar: verified per `Verification.md`, and the PR
  states what was verified and how (R8).

## Candidate first steps

1. **The `.githooks` lie** — smallest PR: remove the claims from the three documents.
   Immediate value; exercises doc-verify and the link checker.
2. `scripts/failing-set.sh` and `scripts/check-doc-links.sh`, promoted and wired into CI.
3. Skill layout: `.agents/skills/` + symlink, `babysit-pr` moved, `verify-change` and
   `pr-writing` written.
4. Blocking parity check with its hermetic tests.
5. Guardrail regression suite + dogfood evals.
6. `release-steward` skill and release-pipeline regression tests — the youngest,
   least-covered pipeline.

## Remaining open questions

- Who merges agent-configuration changes, and at what bar — same as code (R5 on the
  gates themselves), or lighter? In a single-maintainer repository this is a formality
  today; it becomes real the first time a contributor PR touches a gate.
