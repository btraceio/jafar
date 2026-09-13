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
printf 'open rec.jfr\nas-query --dry-run which threads used the most CPU?\nexit\n' \
  | java -jar jfr-shell/build/libs/jfr-shell-*-all.jar
```

> **Case file — `set llm.backend = ollama`.** This command appeared in five documents, in the help
> text, in `llm status`'s own advice, and in tab completion. It had never been run. The shell
> answered `Invalid variable name: llm.backend`, because `set` validates names against
> `[a-zA-Z_][a-zA-Z0-9_]*` and a setting is dotted. Every test passed, because every test called the
> completer or the config directly. Tab completion was *offering names the shell would then refuse*.

> **Case file — `as-query` in the interactive shell.** Every `LlmCommandsTest` was green against a fake
> host while it answered "No query evaluator available for this session" in the real shell.
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

> **Case file — a seam is a path too.** Extracting the analyses out of `jfr-mcp`, `JfrAnalyses`
> built its own `new JfrPathEvaluator()` instead of taking the injected one. It looks equivalent and
> is not: `ConsumeEdgeCasesTest` constructs the server with an evaluator that yields nothing, and an
> analysis holding its own real one ignored the double and read the recording. Injection points do
> not appear in a diff as changes — they appear as code that looks the same.

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

> **Case file — the answer was in hand and thrown away.** Both LLM backends read `finish_reason`
> into `LlmResponse.stopReason` and *nothing consumed it*. A reply truncated mid-thought reported
> only "No query could be extracted from the model's reply", with the token count that would have
> explained it printed on the next line. A field you capture and never read is a fallback in
> disguise.

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

## R9. Inspect the payload, not the exit status

A command that succeeds has not told you it did the right thing. Read what actually went out or came
back: the bytes on the wire, the rows the model received, the JSON the tool returned.

Every bug in [DataShapes.md](DataShapes.md) survived a green test run, and each was caught the same
way — by looking at a value rather than at control flow.

> **Case file — the redaction that looked like it was working.** Driving `ask` against a stub
> and reading what the stub received showed:
>
> ```
> count   key
> 8519    {string=<redacted>}
> ```
>
> Class names were being redacted because the parser's wrapper has an inner key named `string`. The
> command succeeded, the rows arrived, the redaction ran. Only the payload showed it was wrong — and
> the same read showed a `Finding`'s own description being redacted too, which is Jafar's prose, not
> recording content.

> **Case file — the empty field list.** The field-metadata feature "worked": the model got labels
> and descriptions. Dumping what the stub received showed every `fields:` line missing, because the
> code read the display list rather than the structured one.

When a model is the consumer, this is the only way: it will use whatever it is given and produce a
fluent answer either way. A plausible answer drawn from redacted data is indistinguishable from a
good one unless you looked.

## R10. Before a refactor, establish the net — and prove it fails

Find out what actually covers the code you are about to move, in *this* environment. Not what exists
in the repository; what runs.

> **Case file — nineteen hundred lines with nothing watching.** `jfr_use`, `jfr_tsa` and
> `jfr_diagnose` are exercised only by `McpJfrTransportTest`, which cannot run without the binary
> recordings `get_resources.sh` downloads and is one of this environment's standing failures, and by
> `McpEndToEndTest`, a separate task. Moving them on a green `./gradlew :jfr-mcp:test` would have
> been a guess dressed as a refactor. `JfrAnalysesCharacterizationTest` was written first, against a
> synthetic recording so no download is needed, and pins the keys callers bind to rather than
> numbers that depend on the recording.

Then prove the net closes: change the thing it is supposed to notice and watch it fail. Renaming
`capabilityGaps` to `capability_gaps` failed exactly one test and no others. A net that has never
failed is an assumption, and R5 applies to safety nets as much as to fixes.

Hold behaviour fixed while moving code, because the net is only a net if the answers are identical.
Two things that are invisible in a diff and change the answer:

- **A type that crosses a boundary.** `SessionInfo.id()` is an `int` and the MCP result has always
  carried a number; declaring the new record's field `String` would have changed the JSON without
  failing anything that runs here.
- **An injected dependency replaced by a constructed one.** See the seam case under R2.

A refactor that removes a JSON round trip, a duplicated helper or a copied constant is worth doing
on its own — `diagnose` serialised five sub-analyses to JSON and parsed them back — but do it as a
step you can point at, not mixed into the move.

---

## Keeping this file honest

**This file is part of the work, not a record of it.** Every rule here was paid for once; the point
is not to pay again. That only holds if it grows when something new is learned and stays trustworthy
when something changes.

Add a rule when a bug **cost more than one attempt to find**, or when you were **confidently wrong
about a cost or a risk** — those are the two shapes that repeat. A one-line fix you spotted
immediately is not a lesson.

Every rule needs a **case file**: what actually happened, with the real error text, the real numbers,
the real command. A rule without one degrades into advice, and advice is ignored. If you cannot
write the case file, you have not understood the bug well enough to generalise from it yet.

Keep the case files even after the bug is fixed — they are the evidence for the rule, not a bug
list. But correct them when they become untrue: if `get_resources.sh` starts working here, R6 and
R10 change shape, and a stale case file is worse than none because it is quotable.

When a rule earns its place, add the one-line summary to the table in
[AGENTS.md](../../AGENTS.md#read-this-first) as well — that table is what gets read; this file is
what gets read second.

## Keeping the rest of it honest

The same obligation runs through `doc/agents/`:

- **A new area gets a document, and a row in the map** in [AGENTS.md](../../AGENTS.md#where-things-are)
  and in [doc/README.md](../README.md). A document nothing links to is a document nobody opens.
- **Prefer a section link to a line number.** `AGENTS.md:364-372` pointed at nothing within a day of
  the file being reorganised; `Mcp.md#mcp-server-jfr-mcp` survives a move.
- **When you change a behaviour a document describes, change the document in the same commit.** Not
  the next one. The rule in `## Rules` about updating user docs applies to these too.
- **A design document records what was proposed at the time**, so leave `doc/plans/` as written and
  correct the record elsewhere. Do not retrofit a plan to match what shipped.

Checking the links costs nothing, so there is no excuse for a dead one you introduced:

```bash
python3 - <<'EOF'
import re, pathlib
for p in list(pathlib.Path("doc").rglob("*.md")) + [pathlib.Path("AGENTS.md")]:
    body, fenced = [], False
    for line in p.read_text().split("\n"):
        if line.lstrip().startswith("```"):
            fenced = not fenced           # a regex in a code block is not a link
        elif not fenced:
            body.append(line)
    for m in re.finditer(r"\]\((?!https?://)([^)#]+)(#[^)]*)?\)", "\n".join(body)):
        if not (p.parent / m.group(1)).resolve().exists():
            print("MISSING", p, "->", m.group(1))
EOF
```

It currently reports 23 misses, none of them under `doc/agents/`: three are the deliberate
`filename.md` placeholders in [doc/README.md](../README.md), four are footnote-style `[1]`–`[4]`
references in `doc/design/jfr2pprof.md` that are not links at all, and the remaining sixteen are
older pages pointing at files that were renamed or never existed — `jfrpath.md`,
`../jfr-shell/README.md`, `unTypedAPITutorial.md`. They predate this page and are left alone here
rather than swept up in an unrelated change. The bar is that **your** change adds none, which the
same command tells you in a second.

---

## Test fixtures

The recordings in this tree are stripped and several do not parse
(`IllegalArgumentException: newPosition > limit`). To verify behaviour end to end, record a real one:

```bash
java -XX:StartFlightRecording=duration=10s,filename=/tmp/spin.jfr,settings=profile Spin.java
```

`./get_resources.sh` downloads the full set from Dropbox where the network allows it.
