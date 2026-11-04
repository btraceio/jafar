---
name: pr-writing
description: Write commits, PR descriptions and the parity statement for this repository. Use when writing a commit message, a PR description, or a comment on a PR — including the "what I did not verify" section and the Java/Go parity statement the parity gate requires.
---

# Writing a PR

Read [AGENTS.md](../../../AGENTS.md) first. The conventions are short; the two load-bearing parts
are the verification statement and the parity statement, because a gate checks the second one.

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

## Scope

- Stay inside the ask. Extra flags, extra features, drive-by refactors: out, unless requested.
- When the task is done, stop and wait for input.
