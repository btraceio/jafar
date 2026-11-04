---
name: verify-change
description: Run the repo's verification rules against a finished change before claiming it done — R1 (type it into the built artifact), R5 (prove the test fails without the fix), R6 (compare failure sets by name). Use when about to report a change as complete, before opening a PR, or when asked to verify work.
---

# Verify a change

Read [AGENTS.md](../../../AGENTS.md) first. The rules live in
[Verification.md](../../../doc/agents/Verification.md); this skill runs the ones that prove a
change actually works, in the order that catches the most per unit of effort. Every case file in
Verification.md shipped green, so a passing build is the floor, not the ceiling.

## R1 — type it into the built artifact

A green unit test, a completer entry and a doc line are not evidence a command runs. Type the
user-facing command into the real binary and read the output.

```bash
./gradlew :jfr-shell:shadowJar
printf 'open <recording.jfr>\n<the new command>\nexit\n' \
  | java -jar jfr-shell/build/libs/jfr-shell-*-all.jar
```

- The unit test, the completer and the fake host are not the artifact. If the change is in a
  shell **other than the one you tested**, run it there too — `jfr-shell` and `jafar-shell` are
  two dispatchers (R2), and `explain` was once fixed in one and left broken in the other.
- A confirmation message is not evidence: `set llm.max-rows = 20` printed "Set" and then had no
  effect. Read the value back or dump the payload (R9).
- For an MCP tool, drive it through the real transport (`McpStdioProcessHarness`), not the handler
  object; a seam is a path too.

## R5 — prove the test fails without the fix

A regression test that has never been seen to fail is an assumption. Revert the fix, run the new
test, watch it fail, restore — and say so in the commit message.

```bash
cp src/.../Fixed.java /tmp/new && git stash push -- src/.../Fixed.java
./gradlew :module:test --tests "...NewTest"     # must FAIL
git stash pop
```

If the expected text can be a prefix of a plausible wrong answer (`5432` vs `5432.0`), assert the
value itself, not `output.contains(...)`.

## R6 — compare failure sets by name

A count that stays the same can hide a swap. Record the named baseline before changing code:

```bash
scripts/failing-set.sh > /tmp/fail_base.txt
# ... run the suite ...
scripts/failing-set.sh | diff /tmp/fail_base.txt - && echo "zero new failures"
```

Report both numbers: total tests and the named failing set.

## Report

State what was verified and how, and what was **not** verified (R8). A PR that says "tests pass"
without naming the artifact it ran against has skipped R1; one that does not say which tests fail
without the fix has skipped R5.
