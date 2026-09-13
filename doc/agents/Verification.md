# Verifying a change

How to know a change works in this repository, and the case files that produced each rule.

Every rule here exists because something shipped — or nearly shipped — broken **while its tests were
green**. They are not general software advice; they are the specific ways this codebase fools you.

---

## R1. Type it into the built artifact

A user-facing command is not done until it has been typed into a built shell or a running server and
the output read. Not the unit test. Not the completer. The actual binary.

```bash
./gradlew :jfr-shell:shadowJar
printf 'open rec.jfr\nask --dry-run which threads used the most CPU?\nexit\n' \
  | java -jar jfr-shell/build/libs/jfr-shell-*-all.jar
```

> **Case file — `set llm.backend = ollama`.** This command appeared in five documents, in the help
> text, in `llm status`'s own advice, and in tab completion. It had never been run. The shell
> answered `Invalid variable name: llm.backend`, because `set` validates names against
> `[a-zA-Z_][a-zA-Z0-9_]*` and a setting is dotted. Every test passed, because every test called the
> completer or the config directly. Tab completion was *offering names the shell would then refuse*.

> **Case file — `ask` in the interactive shell.** Every `LlmCommandsTest` was green against a fake
> host while `ask` answered "No query evaluator available for this session" in the real shell.
> The fake host was never the thing that was broken.

**Corollary:** offering something in completion, documenting it, or printing a confirmation are not
evidence that it works. Confirmation messages lie — see R3.

## R2. Enumerate every path before you call it wired

When adding a capability, list every implementation of the interface and every dispatcher that could
reach it, then check each one. Write the list down; do not do it from memory.

In this repository the recurring multiplicities are:

| Axis | Instances |
|---|---|
| Shells | `jfr-shell` (`CommandDispatcher`) and `jafar-shell` (`unified/Shell`) |
| JfrPath execution | via `JfrSelector` when supplied; via `JfrPathEvaluator` directly when not |
| LLM backends | `llm-anthropic`, and `llm-openai`'s `openai` + `ollama` profiles |
| Untyped parsers | Java (`parser-core`) and Go (`go-parser`) — see the [parity rule](../../AGENTS.md#rules) |
| Query evaluators | JFR, Hdump, pprof, OTLP |

> **Case file — two query paths.** `CommandDispatcher` runs JfrPath two ways. The LLM host adapter
> knew only the `JfrSelector` one; the interactive shell builds the dispatcher the other way. Fixed
> by `LlmHostAdapterTest`, which drives the adapter rather than a fake.

> **Case file — one backend family.** `llm.api-key` was read by `OpenAiCompatibleBackend` and
> ignored by `AnthropicBackend`, which asked `AnthropicOkHttpClient.fromEnv()` and inspected only
> the environment. The settings file worked for two of three backends. Found by testing the advice
> in the README, not by a test.

> **Case file — one shell.** `explain` was fixed in `jfr-shell` and left broken in `jafar-shell` in
> the same change, because the second dispatcher was not on the list.

## R3. A fallback that hides a misconfiguration is a bug

A `catch` that substitutes a default, a literal that stands in for a real value, a lookup that
returns `null` and is quietly tolerated — each turns a loud failure into a wrong answer. Either
report what happened, or make the failure impossible.

Prefer reporting **where a value came from**. `llm status` does this per setting
(`LlmConfig.sourceOf`) precisely because a stale environment variable shadowing a settings file
looks identical to the file not being read at all.

> **Case file — `20.0`.** `set llm.max-rows = 20` coerced the value to a double. `LlmConfig` then
> did `Integer.parseInt("20.0")`, caught `NumberFormatException`, and returned the default. The
> shell printed `Set llm.max-rows = 20.0` and `llm status` went on reporting `50`. Two confident,
> mutually contradictory messages and no error anywhere.

> **Case file — sixteen releases of a lie.** `McpServerFactory.SERVER_VERSION` was the literal
> `"0.10.0"`. Every release from 0.10.0 through 0.26.2 told MCP clients it was 0.10.0. Now read from
> the jar manifest's `Implementation-Version`, which the shadow-jar build stamps, so it cannot drift.

## R4. Documentation is code — run it

Commands in a README or tutorial are executed by people. Run them, in a clean environment, before
committing them.

> **Case file — the `ant` install.** The docs sent someone to `brew install anthropic/tap/ant`. The
> tap owner is `anthropics`, plural, so Homebrew reported "Repository not found". The natural
> fallback, `brew install ant`, installs **Apache Ant**, an unrelated Java build tool that installs
> cleanly and then has no idea what `auth login` means.

> **Case file — the snippet that could not run.** A README block wrote a key to
> `~/.config/jafar/llm.properties` without `mkdir -p`. Testing it in a scratch `HOME` caught it —
> and testing the *advice* it gave uncovered R2's Anthropic key bug.

When you verify a doc command, verify the claim too: asset names were checked with `curl -o /dev/null
-w "%{http_code}"` rather than assumed, which is how "there is no macOS release tarball" became a
fact worth writing down.

## R5. Prove the test fails without the fix

A regression test that has never been seen to fail is an assumption. Revert the fix, run the test,
watch it fail, restore.

```bash
cp src/.../Fixed.java /tmp/new && git stash push -- src/.../Fixed.java
./gradlew :module:test --tests "...NewTest"     # must FAIL
git stash pop
```

Record the result in the commit message. `SetLlmSettingTest`: **6 of 7 fail** against the previous
dispatcher; the seventh is the guard that ordinary variables still behave, and passes both ways,
which is exactly what it is for.

## R6. Compare failure sets by name, never by count

Parts of this suite fail in any environment that cannot fetch the binary recordings
(`./get_resources.sh`, Dropbox). Those failures are `NoSuchFileException`, not logic errors — and a
count that stays at 126 can still hide a swap.

```bash
./gradlew :jfr-shell:test --rerun-tasks
python3 - <<'PY' > /tmp/fail_now.txt
import glob, xml.etree.ElementTree as ET
names = []
for f in glob.glob("jfr-shell/build/test-results/test/*.xml"):
    for tc in ET.parse(f).getroot().iter('testcase'):
        if tc.find('failure') is not None or tc.find('error') is not None:
            names.append(f"{tc.get('classname')}.{tc.get('name')}")
print("\n".join(sorted(names)))
PY
diff /tmp/fail_base.txt /tmp/fail_now.txt && echo "zero new failures"
```

Record the baseline **before** you start changing code. Report both numbers — total tests and the
named failing set — so a reader can tell growth from regression.

## R7. One source of truth for any list two places must agree on

If a list is duplicated, the copies will disagree, and the disagreement will be invisible until a
user hits it.

> **Case file — the twelve settings.** `ShellCompleter` held a private `LLM_SETTINGS` table; `set`
> validated against a regex that matched none of them. They are now
> `io.jafar.shell.core.llm.LlmSettings` in `shell-core`, read by the completer, the `set`
> validation, and the error message that lists valid names.

Where a shared constant is impractical, write a test that reads the other source and fails on drift —
`ShellCompleterLlmTest` parses `LlmConfig.java` for `llm.*` keys and fails if completion does not
offer one.

## R8. Say plainly what you did not verify

An honest gap is useful; a silent one is a trap. `doc/plans/llm-in-the-shell-handoff.md` §6 and the
"What is not verified" section of the LLM PR exist for this.

Two standing gaps in this area:

- **No hosted LLM provider has ever been called from this repository.** Tests must not spend
  someone else's money. `LlmCommandsTest` pins `llm.backend` to a non-existent id so a machine with
  `ANTHROPIC_API_KEY` set cannot make a live billable call during the suite.
- **Flake is a diagnosis of last resort.** A CI failure that did not reproduce in 13 local runs was
  not declared flaky; instead `assertSuccess` was made to include the response in every message and
  12 unasserted setup calls were asserted, so the next occurrence names its own cause.

---

## Test fixtures

The recordings in this tree are stripped and several do not parse
(`IllegalArgumentException: newPosition > limit`). To verify behaviour end to end, record a real one:

```bash
java -XX:StartFlightRecording=duration=10s,filename=/tmp/spin.jfr,settings=profile Spin.java
```

`./get_resources.sh` downloads the full set from Dropbox where the network allows it.
