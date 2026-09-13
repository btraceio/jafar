# Setting up the LLM commands

The Jafar shell can turn a question into a query. This page covers picking a provider, getting it
authenticated, and the failure modes worth knowing before you hit them.

If you only read one thing: run `llm status`. It lists every backend, says which one will be used
and why, and tells you what to do about the ones that are not ready.

## What you get

| Command | Does |
|---|---|
| `ask <question>` (or `? <question>`) | Runs several queries, reads each result, and concludes |
| `as-query <question>` | Turns the question into one query, **prints the query**, and runs it |
| `<command> --dry-run <question>` | Prints exactly what it would send, and sends nothing |
| `explain` | Explains the most recent result |
| `explain --dry-run` | Prints exactly what `explain` would send, and sends nothing |
| `llm status` | Backends, readiness, credential source, and the active settings |
| `llm cost` | Token usage for this process |

Both `jfr-shell` (JFR recordings) and the unified `jafar-shell` (recordings, heap dumps, pprof and
OTLP profiles) have these commands. They use whichever query language the current session needs,
so in `jafar-shell` it reaches HdumpPath and the samples grammar as well as JfrPath. `jafar-shell`
has no `set` command yet, so configure it there with the `JAFAR_LLM_*` environment variables.

The feature is optional. Without a backend module on the classpath, or without a credential, every
other shell command behaves exactly as before and the LLM commands print a clear message. Nothing
calls out to the network unless you run one of the commands above.

## Choosing a provider

Three backend ids ship in the box. `llm.backend` picks one; the default, `auto`, takes the first
that reports ready.

| `llm.backend` | Module | Talks to | Default model | Credential |
|---|---|---|---|---|
| `anthropic` | `llm-anthropic` | api.anthropic.com | `claude-opus-5` | API key **or** keyless OAuth profile |
| `openai` | `llm-openai` | api.openai.com | `gpt-4o-mini` | `OPENAI_API_KEY` |
| `ollama` | `llm-openai` | `http://localhost:11434/v1` | `qwen2.5-coder:7b` | none locally; an API key for Ollama Cloud |

Each backend supplies its own default model, so there is no cross-provider default to get wrong:
leave `llm.model` unset and you get something sensible for whichever backend you selected.

`openai` and `ollama` are the same code — an OpenAI **chat-completions** client over the JDK's HTTP
client, with no provider SDK — differing only in default endpoint, default model and whether a key
is required. Point `llm.base-url` somewhere else and the same backend reaches anything else that
speaks that protocol: vLLM, LM Studio, llama.cpp's server, Groq, Together, OpenRouter.

```
jfr> set llm.backend = openai
jfr> set llm.base-url = https://api.groq.com/openai/v1
jfr> set llm.api-key  = gsk_...
jfr> set llm.model    = llama-3.3-70b-versatile
```

### Which one to pick

**A local model** (`ollama`) is the only option where the question and the type list never leave the
machine. That matters when the recording came from a customer. It is also free and works offline.
The cost is accuracy: a 7B model gets the query language wrong more often, which is exactly why the
shell validates the query locally and asks for a correction — see [Wrong queries](#wrong-queries).

**A hosted frontier model** (`anthropic`, `openai`) gets the query right more often and needs no
GPU. Every question sends the question and the recording's type list to a third party.

Nothing stops you moving between them mid-session: `set llm.backend = ollama` and the next question
goes local.

## Authenticating

### Anthropic — two modes

The shell uses the official Anthropic Java SDK, which resolves credentials itself. Both modes are
the same code path and neither needs configuration in Jafar. Resolution order, first match wins:

1. `ANTHROPIC_API_KEY`
2. `ANTHROPIC_AUTH_TOKEN`
3. the OAuth profile selected by `ANTHROPIC_PROFILE`, or the active one
4. Workload Identity Federation environment variables
5. the default profile on disk

**Mode 1 — API key**

```bash
export ANTHROPIC_API_KEY=sk-ant-...
jfr-shell recording.jfr
```

Simple, and the right choice for CI or a container. The cost is that you now have a long-lived
secret to store and rotate.

**Mode 2 — keyless, with an OAuth profile**

Entirely optional: it is a second way to authenticate, not a requirement. If installing it is
awkward, use Mode 1 and skip this section.

It needs the [Anthropic CLI](https://github.com/anthropics/anthropic-cli), which is **not installed
by default** — `ant: command not found` means you have not installed it yet:

```bash
brew install anthropics/tap/ant                                  # macOS
go install github.com/anthropics/anthropic-cli/cmd/ant@latest    # Go 1.25+, any platform
```

The Go route installs into `$(go env GOPATH)/bin`, which has to be on your `PATH`.

> **Two traps, both of which look like the install failed when it did not.**
>
> The tap owner is `anthropics`, **plural**. `brew install anthropic/tap/ant` fails with
> *"Repository not found"* on `github.com/anthropic/homebrew-tap` — the missing `s` is the whole
> problem.
>
> And do not fall back to plain `brew install ant`. That is **Apache Ant**, the Java build tool,
> which has owned the name for two decades; it installs cleanly, and then `ant auth login` makes
> no sense to it. `ant -version` printing *"Apache Ant"* means you have the wrong one — put the
> Anthropic CLI earlier on your `PATH`, or invoke it by its full path.

There is **no macOS release tarball** — as of v1.32.0 the published binaries cover Linux and
Windows, so on macOS it is Homebrew or `go install`. On an Intel Mac, Homebrew now warns that
x86_64 is unsupported and may build from source; `go install` avoids that entirely.

Then:

```bash
ant auth login          # opens a browser, stores a profile under ~/.config/anthropic/
jfr-shell recording.jfr # no environment variable needed
```

After login it writes `configs/<profile>.json` and `credentials/<profile>.json`, and the SDK picks
them up automatically — there is no static key anywhere, and tokens are short-lived and refreshed
for you.

On a machine with no browser, `ant auth login --no-browser` prints a URL and takes the code back on
the terminal.

**What "keyless" does not mean.** It does not mean free. An OAuth profile authenticates against a
Console organisation and bills as ordinary API usage, exactly like an API key does. The difference
is credential management, not cost.

A **Claude Pro or Max subscription is a different entitlement** from API access. It is what Claude
Code uses, and it is not something this shell can use directly. If that is what you have, the
supported route is to let Claude Code do the analysis through Jafar's MCP server — see
[When to use which](../mcp/WhenToUseWhich.md). A delegate backend that automates this is designed
but not built; see [the handoff document](../plans/llm-in-the-shell-handoff.md).

### A settings file, rather than the environment

**For a long-lived key this is the better option, and it is what `llm status` points you at.** An
environment variable is inherited by every process the shell starts, shows up in crash dumps and CI
logs, and lands in your shell history if you export it inline. A file only you can read has none of
those properties, and it survives opening a new terminal.

```bash
mkdir -p ~/.config/jafar
cat > ~/.config/jafar/llm.properties <<'EOF'
llm.backend=openai
llm.api-key=sk-...
EOF
chmod 600 ~/.config/jafar/llm.properties
```

Keys are the same names `set` uses, so anything in the settings table below can go in the file.
`llm status` prints the path, warns if the file is readable by anyone else, and — the part that
matters when something misbehaves — says which layer each setting actually came from:

```
Settings file
-------------
  /home/you/.config/jafar/llm.properties
  llm.api-key    from the settings file
  llm.backend    from JAFAR_LLM_BACKEND (overrides the settings file)
```

Resolution order, first match wins: a `set` command in the shell, then an environment variable,
then the settings file, then the default. The environment sits above the file deliberately, so CI
can override without editing anything — but it means a stale variable silently shadows your file,
which is exactly what that `from ...` line exists to show you.

Other locations: `$JAFAR_LLM_CONFIG` points at a specific file, and
`$XDG_CONFIG_HOME/jafar/llm.properties` is honoured if you set `XDG_CONFIG_HOME`.

### OpenAI

```bash
export OPENAI_API_KEY=sk-...
jfr-shell recording.jfr
```

Better, per the section above: put `llm.api-key` in `~/.config/jafar/llm.properties`. Or
`set llm.api-key = sk-...` in the shell for a single session.

### Ollama — local

```bash
ollama serve                    # usually already running
ollama pull qwen2.5-coder:7b    # or any model you prefer
jfr-shell recording.jfr
```

No credential. Because the endpoint is on loopback, `llm status` probes it with
`GET <base-url>/models` before you spend a turn on it, so a stopped daemon is a clear message rather
than a timeout at request time:

```
jfr> llm status
  ollama       Ollama (local or cloud)            NOT READY
               Cannot reach http://localhost:11434/v1 (ConnectException).
               default model: qwen2.5-coder:7b
               -> Local Ollama needs no key: run `ollama serve` and `ollama pull <model>`. For Ollama Cloud set OLLAMA_API_KEY and point llm.base-url at the cloud endpoint.
```

A remote endpoint is not probed — that would cost a round trip on every `llm status`. A model the
daemon has not pulled comes back at request time as an HTTP 404 naming the model, with `ollama pull`
as the remedy.

### Ollama Cloud

Same backend, a remote base URL and a key:

```
jfr> set llm.backend  = ollama
jfr> set llm.base-url = <the cloud endpoint from Ollama's documentation>
jfr> set llm.api-key  = <your key>          # or export OLLAMA_API_KEY
jfr> set llm.model    = <a model the cloud offers>
```

The cloud endpoint is deliberately not baked into Jafar — it is the part most likely to change, and
a stale hardcoded URL is worse than no default. Note also that this is a hosted endpoint: the
privacy argument for local Ollama does not apply to it.

## Three Anthropic traps

These are the failures people actually hit. The shell detects all three locally and tells you the
fix, rather than letting them surface as an opaque error from the server.

**A stale `ANTHROPIC_API_KEY` silently shadows your profile.** It sits above profiles in the
resolution order, so requests go to whatever organisation that key belongs to — not the one you
logged into. If `llm status` shows a key you did not expect, that is why.

**An empty key still wins.** `ANTHROPIC_API_KEY=""` is not the same as unset: it occupies its slot
in the order and authenticates as an empty key. Truly `unset` it.

```
$ ANTHROPIC_API_KEY= jfr-shell
jfr> llm status
  anthropic    Anthropic API (anthropic-java)     NOT READY
               ANTHROPIC_API_KEY is set but empty. It still takes precedence over an OAuth
               profile and authenticates as an empty key.
               -> Truly unset it: unset ANTHROPIC_API_KEY
```

**Refresh tokens expire outright.** They do not slide with use, so a profile that worked last month
can stop working. The fix is `ant auth login` again, not debugging.

There is also a fourth thing worth knowing: the SDK does **not** fail fast when it finds no
credentials — it sends the request unauthenticated and you get a 401 back. That is precisely why
`llm status` exists, and why the shell checks readiness before every request.

## Wrong queries

A model — a small local one especially — will sometimes answer with something that is not valid in
the query language. The shell does not run it and does not make you deal with it:

1. the candidate query is parsed with **the same parser that would execute it**;
2. if it does not parse, the parser's own error is sent back with the invalid query and a request
   to correct it;
3. the corrected query is validated again, and only then run.

The shell prints `1 correction(s)` alongside the token usage when this happens, so the round trip is
visible rather than hidden. `llm.max-retries` controls it: default 1, `0` disables it, and it is
capped at 3 — beyond that a model is not going to converge and you are paying for it to fail.

If the retry does not rescue the query, `as-query` prints the query and the parser's complaint and runs
nothing.

## What the model knows about your recording

Both commands send the list of event types in the recording together with the recording's own
documentation for them — JFR annotates its event classes, so the model sees:

```
jdk.ExecutionSample — Java Execution Sample
    Snapshot of a thread executing Java code. Threads that are not executing Java code,
    including those waiting or executing native code, are not included.
```

That is what lets it pick `jdk.ExecutionSample` for a CPU question rather than something whose name
merely shares a word — and the description tells it what the type does *not* cover.

**Fields are fetched on demand, not sent up front.** JFR is self-describing: an event's fields are
whatever *your* recording declares, so they cannot be guessed from the type name, and a custom event
has fields nothing was ever trained on. Sending every type's fields would cost around 9,800 tokens a
question, almost all of it about types the question never touches. So the model names the types it
needs and gets their fields — with the types those fields lead to, so a path can be followed:

```
  jdk.ExecutionSample — Java Execution Sample
      fields: sampledThread: java.lang.Thread, stackTrace: jdk.types.StackTrace, ...
  java.lang.Thread
      fields: group: ..., javaName: java.lang.String, javaThreadId: long, ...
```

That makes `sampledThread/javaName` something the model reads rather than invents. It costs one
extra round trip, and about 1,200 characters instead of 24,000.

**Types with no events are separated out.** JFR metadata declares every type the JVM registered,
whether or not it emitted anything — so a recording made with an agent that ships its own sampler
lists an empty `jdk.ExecutionSample` next to a vendor type holding thousands of events, and a model
told only the names picks the one it recognises. The shell counts the events once, lists the types that
have data with their counts, and collapses the rest into one line the model is told not to query.

That count is a pass over the recording, done once and then cached under
`$XDG_CACHE_HOME/jafar/event-counts` (else `~/.cache/jafar/`), keyed by the file's path, size and
modification time, so later sessions reuse it and a replaced file does not answer from a stale
count. It is the same pass the query answering your question makes anyway. Set
`llm.count-events = false` to skip it on a recording large enough that one extra pass is not worth
the accuracy.

No event data is sent. `as-query --dry-run` shows the first round in full.

## `ask` — more than one query

`as-query` is one question, one query. That answers "how many execution samples are there"; almost
no real performance question is of that shape. `ask` runs several: it looks, reads the result,
decides what to look at next, and concludes. `?` is short for it, with or without a space after it,
and `analyze` and `investigate` are word aliases.

```
jfr> ask why is this workload slow
> events/jdk.ExecutionSample | groupBy(sampledThread/javaName) | top(3, by=count)
  3 rows
| count | key       |
+-------+-----------+
| 8412  | main      |
| 210   | worker-1  |
| 97    | scheduler |

> events/jdk.ObjectAllocationSample | groupBy(objectClass/name) | top(3, by=count)
  3 rows
| count | key                |
+-------+--------------------+
| 5109  | byte[]             |
| 812   | java.lang.String   |
| 344   | java.util.HashMap  |

Execution samples concentrate on the main thread, and allocation samples are dominated by
byte[]. The workload is allocation-heavy on a single thread, so the next step is to look at
the allocation call sites rather than adding parallelism.

Transcript: ~/.jafar/investigations/ask-20260913-202249.jfrs
```

Every query is printed as it runs, with the rows it returned underneath — the investigation is not
hidden behind its conclusion, and those are the same rows the model was given, capped at
`llm.max-rows`. The sequence is also written to a **re-runnable `.jfrs` script**. That is the part
worth caring about: the conclusion came from a model and is not reproducible, but the evidence is a
file you can open, run, and disagree with. `explain` afterwards describes the last result the
investigation looked at.

**It can run the analyses, not just queries.** `ANALYSIS: diagnose` (also `use`, `tsa`, `summary`,
`hotmethods`, `exceptions`) runs the same implementation the MCP server exposes as `jfr_diagnose` —
one copy, since these moved into `shell-core` — so the model gets the thresholds, the USE and TSA
passes, and the `capabilityGaps` rather than trying to rebuild that judgement out of queries:

```
jfr> ask why is this workload slow
* diagnose
  done

> events/jdk.ObjectAllocationSample | groupBy(objectClass/name) | top(3, by=count)
  3 rows

The diagnosis flagged high GC pressure (609 collections, 20.2 ms average pause) and the
allocation breakdown is dominated by byte[]. Look at the allocation call sites.
```

It is bounded on two axes, because an unbounded loop against a paid API loses money quietly:
`llm.max-steps` (default 6) caps the moves and `llm.max-total-tokens` (default 200000) caps the
spend. The model is told how many steps remain, so it concludes rather than being cut off. Result
rows are redacted and truncated on every step exactly as `explain` does — this path sends far more
recording data than `as-query`, so it matters more here, not less.

`ask --dry-run` shows the first request; later steps depend on what earlier ones return, so they
cannot be shown in advance.

`llm.confirm` turns `ask` off rather than changing it. The setting means "show me a query before
it runs", and an investigation chooses each query from the result of the last one, so there is no
query to show in advance. With it on, `ask` says so and sends nothing; use `as-query` for a single
query you approve, or `ask --dry-run` to read the opening request.

## Settings

All settable three ways — `set` in the shell, a `JAFAR_LLM_*` environment variable, or a line in
`~/.config/jafar/llm.properties` — and visible in `vars`.

A setting's value is taken as **literal text**, unlike an ordinary `set`, whose right-hand side is
an expression. So `set llm.base-url = http://localhost:11434/v1` needs no quotes, and
`set llm.max-rows = 20` stores the integer rather than coercing it. Quotes are stripped if you use
them. A name that is not a setting but starts with `llm.` is reported as a typo, with the real
names listed, rather than silently becoming a variable.

| Setting | Default | Meaning |
|---|---|---|
| `llm.enabled` | `true` | Master switch |
| `llm.backend` | `auto` | `anthropic`, `openai`, `ollama`; `auto` takes the first that is ready |
| `llm.model` | the backend's own default | Model id |
| `llm.base-url` | the backend's own default | Endpoint, for the OpenAI-compatible backends |
| `llm.api-key` | unset | Bearer token; overrides the provider's environment variable |
| `llm.max-tokens` | `2048`, auto-raised | Output ceiling per request — see below |
| `llm.max-rows` | `50` | Result rows shown to the model by `explain` |
| `llm.max-retries` | `1` | Correction attempts after a query fails to parse (0–3) |
| `llm.timeout` | `120` | Request timeout in seconds — raise it for a large local model |
| `llm.confirm` | `false` | When true, `as-query` prints the query but does not run it, and `ask` refuses |
| `llm.redact` | `true` | Redact sensitive fields before sending |
| `llm.redact-fields` | see below | Replace the redaction list; a leading `+` extends it |
| `llm.count-events` | `true` | Count events per type so empty types can be excluded; one pass, cached |
| `llm.max-steps` | `6` | Moves one `ask` may make (1–20) |
| `llm.max-total-tokens` | `200000` | Token ceiling for a whole `ask` run; `0` = no cap |
| `llm.max-analysis-chars` | `6000` | Characters of one analysis result shown to the model |

**`llm.max-tokens` raises itself for a reasoning model.** The default is small because that is all
an answer needs — a query and one line — and because the ceiling is what caps the bill when a model
loops. A reasoning model spends that same budget *thinking* before it writes anything, hits the
ceiling mid-thought, and returns no query at all. So when a reply says it stopped on its token
limit without producing a query, the shell raises the ceiling to 16384, says so, and asks again:

```
jfr> as-query which method is using most CPU
# This model reasons before answering; raised llm.max-tokens to 16384 for this session.
```

It is remembered for that model for the rest of the session, so only the first question pays for
the short attempt. Setting `llm.max-tokens` yourself to something larger disables the raise — your
number is never lowered. The trigger is the reply's own stop reason, not a list of model names,
which would be stale within a month and says nothing about a local model someone renamed.

```
jfr> set llm.backed = ollama
Unknown setting: llm.backed
Settings are: llm.enabled, llm.backend, llm.model, ...

jfr> set llm.backend = ollama
jfr> set llm.model = qwen2.5-coder:14b
jfr> set llm.redact-fields = +sessionId,userId
jfr> set llm.confirm = true
```

Each is also readable from an environment variable (`JAFAR_LLM_BACKEND`, `JAFAR_LLM_MODEL`,
`JAFAR_LLM_BASE_URL`, `JAFAR_LLM_MAX_ROWS`, and so on), which is the easier route in CI and the only
route in `jafar-shell` until it grows a `set` command.

## Cost

For the hosted providers the default model is the strongest tier that is sensible for the provider,
deliberately: a wrong query wastes your turn and teaches you the wrong syntax, which costs more than
the token difference. If you want translation on something cheaper,
`set llm.model = claude-haiku-4-5`. With `ollama` the cost is zero and the question is latency.

Two things keep the cost small by construction:

- **The model never sees raw events.** It composes a query; the shell runs it. A 900 MB recording
  costs the same as a 2 MB one, because the recording never goes anywhere.
- **The language reference is cached.** It is the bulk of each request and is byte-identical every
  time, so after the first call it is a cache read. `llm cost` shows the cached-token count; if it
  stays at zero across several calls with a provider that supports caching, something is varying
  the prefix and worth reporting as a bug.

Every LLM command prints its token usage when it finishes — including when the query it produced
then failed to run, because the request was paid for either way.

## Verifying without spending anything

`as-query --dry-run <question>` builds the identical request and prints it instead of sending it — same
prompt, same redaction, same bytes. `explain --dry-run` does the same for the explain request. Use it to see what would leave the machine before you let
anything leave the machine. It needs no credentials.

## Next

- [Asking questions](AskTutorial.md) — the tutorial, which doubles as a way to learn JfrPath
- [What leaves your machine](LlmPrivacy.md) — redaction, local models, and analysing recordings you
  did not make
- [When to use which](../mcp/WhenToUseWhich.md) — shell LLM vs MCP server vs the Claude Code plugin
