---
name: pr-writing
description: Write commits, PR descriptions and the gate-checked PR-body statements for this repository. Use when writing a commit message, a PR description, or a comment on a PR — including the "what I did not verify" section and the statements the CI gates require (Java/Go parity, API-Break, Alloc-Budget, Work-Budget).
---

# Writing a PR

Read [AGENTS.md](../../../AGENTS.md) first. The conventions are short; the load-bearing parts
are the verification statement and the gate-checked statements — parity, API-Break,
Alloc-Budget and Work-Budget — because CI checks those.

## Commits

- Concise, imperative mood, based only on the actual diff. Reference the issue or PR when
  relevant: `Fix parsing of constant pool (#17)`.
- One logical change per commit. Rebase before push; never merge `main` into the branch.
- When a fix lands with a regression test, say in the message which assertions fail without the
  fix (R5) — that is what makes the test a net instead of a decoration.

## PR description

Three sections, in this order:

1. **Summary** — what changed and why, one bullet per concern. A reader who sees only this
   section must know what the PR does and what it deliberately does not do.
2. **Validation** — the commands that were run and their outcomes, from the real artifact (R1),
   the failing-without-fix evidence (R5) and the named failing-set comparison (R6). Name the
   exact commands; a green `./gradlew test` line proves almost nothing here.
3. **Not verified** — say plainly what was not checked and why (R8). An honest gap is useful; a
   silent one is a trap.

Attach sample `.jfr` snippets when the change is about parsing a specific recording shape.

## The parity statement

A change that touches the untyped parser on one side (`parser-core` Java or `go-parser/jfr` Go)
must state, in the PR body, what happens on the other side. This is a blocking CI check
(`scripts/parity-check.sh`, `parity` job in `pr-tests.yml`) that accepts one of:

- `Parity: ported in this PR` — when the diff touches both sides, or the counterpart change is in
  the same PR;
- `Parity: not applicable — <reason>` — e.g. an optimization that depends on run-time bytecode
  generation has no Go equivalent.

Name the direction (`Java → Go` / `Go → Java`); the obligation is symmetric. The standing
convention (PRs #135, #150) records the reasoning, not just the verdict. Divergences that are
kept go into `go-parser/README.md` under "Differences from the Java parser".

## The typed parity statement

A change to one of the two typed handler generators — build time, `jafar-processor` (including
its templates); runtime, `parser-codegen`'s `CodeGenerator`, `CodeGeneratorDeserializerFactory` or
`FieldMapping` — must state, in the PR body, what happens on the other one. This is a blocking CI
check (`scripts/typed-parity-check.sh`, `typed-parity` job in `pr-tests.yml`) that accepts one of:

- `Typed-Parity: ported in this PR` — the counterpart change is in the same PR;
- `Typed-Parity: not applicable — <reason>` — e.g. the change brings this side to what the other
  already does, or depends on metadata only one side has.

Parity covers values and the work done per value (pool resolution, field skipping, per-event
work). When the change touches per-value work, put the `BuildTimeHandlerBenchmark` numbers for both
`decoder` values, from one session, in the PR. This is a separate line from the Java/Go `Parity:`
statement; a PR can need both.

## The API-Break statement

A PR that breaks a gated API surface — an incompatible change to any public member of the
surfaces listed in `gradle/api-surfaces.txt`, beyond the accepted ratchet in
`gradle/api-accepted-breaks.txt` — must say so in the PR body:

    API-Break: <non-empty reason>

This is a blocking CI check (`scripts/api-break-check.sh`, the `api-break` job in
`pr-tests.yml`, run on pull requests only, like the parity statement). The statement is
matched with `^[[:space:]]*API-Break:[[:space:]]*[^[:space:]]`: the reason must be
non-empty — `API-Break:` followed by spaces only is rejected, and the check fails as if
no statement were present.

When it is needed: when
`scripts/api-break-check.sh --reports build/reports/api-compat --base origin/main`
exits 1 — or, without running anything, when the diff knowingly removes or changes the
signature of a public member of a gated package, or adds an abstract method to a gated
interface. Purely additive changes do not need it. The gate, its tag-derived baselines
and the shrink-only accepted-breaks ratchet are documented in
[doc/agents/Gates.md](../../../doc/agents/Gates.md).

## The Alloc-Budget statement

A PR that raises an allocation budget must say so in the PR body:

    Alloc-Budget: <non-empty reason>

This is a blocking CI check (`scripts/alloc-budget-check.sh`, the `alloc-budget` job in
`pr-tests.yml`, run on pull requests only, like the parity and API-Break statements). The
statement is matched with `^[[:space:]]*Alloc-Budget:[[:space:]]*[^[:space:]]`: the reason
must be non-empty — `Alloc-Budget:` followed by spaces only is rejected, and the check
fails as if no statement were present.

When it is needed: when `scripts/alloc-budget-check.sh --base origin/main` exits 1 — or,
without running anything, when the diff raises a value or a tolerance in
`gradle/allocation-budgets.properties`, or turns a budgeted key into `excluded: …`.
Lowering a value, adding a new key, or leaving the file unchanged needs no statement. The
gate, the allocation meter and the per-JDK keys are documented in
[doc/agents/Gates.md](../../../doc/agents/Gates.md).

## The Work-Budget statement

A PR that raises a work budget must say so in the PR body:

    Work-Budget: <non-empty reason>

This is a blocking CI check (`scripts/work-budget-check.sh`, the `work-budget` job in
`pr-tests.yml`, run on pull requests only, like the Alloc-Budget statement). The statement is
matched with `^[[:space:]]*Work-Budget:[[:space:]]*[^[:space:]]`: `Work-Budget:` followed by
spaces only is rejected.

When it is needed: when `scripts/work-budget-check.sh --base origin/main` exits 1 — or, without
running anything, when the diff raises a count in `gradle/work-budgets.properties`, or adds a key
with a nonzero count to an entry point that already has keys. Lowering a count, adding a new
entry point, or leaving the file unchanged needs no statement. The reason says what work the parse
now does and why: "the time range now reads every chunk's constant pools, because …", not "budget
updated". A raise is an algorithmic change, so say it in the PR description too. The counters,
the agent that counts them and how to regenerate the file are in
[doc/plans/work-budgets.md](../../../doc/plans/work-budgets.md).

## Scope

- Stay inside the ask. Extra flags, extra features, drive-by refactors: out, unless requested.
- When the task is done, stop and wait for input.
