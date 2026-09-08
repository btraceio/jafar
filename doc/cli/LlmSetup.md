# Setting up the LLM commands

The Jafar shell can turn a question into a query. This page covers picking a provider, getting it
authenticated, and the failure modes worth knowing before you hit them.

If you only read one thing: run `llm status`. It lists every backend, says which one will be used
and why, and tells you what to do about the ones that are not ready.

## What you get

| Command | Does |
|---|---|
| `ask <question>` | Turns the question into a query, **prints the query**, and runs it |
| `explain` | Explains the most recent result |
| `llm status` | Backends, readiness, credential source, and the active settings |
| `llm dry-run <question>` | Prints exactly what `ask` would send, and sends nothing |
| `llm cost` | Token usage for this process |

Both `jfr-shell` (JFR recordings) and the unified `jafar-shell` (recordings, heap dumps, pprof and
OTLP profiles) have these commands. `ask` uses whichever query language the current session needs,
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
GPU. Every `ask` sends the question and the recording's type list to a third party.

Nothing stops you moving between them mid-session: `set llm.backend = ollama` and the next `ask`
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

```bash
ant auth login          # opens a browser, stores a profile under ~/.config/anthropic/
jfr-shell recording.jfr # no environment variable needed
```

`ant` is the [Anthropic CLI](https://github.com/anthropics/anthropic-cli). After login it writes
`configs/<profile>.json` and `credentials/<profile>.json`, and the SDK picks them up
automatically — there is no static key anywhere, and tokens are short-lived and refreshed for you.

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

### OpenAI

```bash
export OPENAI_API_KEY=sk-...
jfr-shell recording.jfr
```

Or `set llm.api-key = sk-...` in the shell, which takes precedence over the environment.

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

`ask` prints `1 correction(s)` alongside the token usage when this happens, so the round trip is
visible rather than hidden. `llm.max-retries` controls it: default 1, `0` disables it, and it is
capped at 3 — beyond that a model is not going to converge and you are paying for it to fail.

If the retry does not rescue the query, `ask` prints the query and the parser's complaint and runs
nothing.

## Settings

All settable with `set`, and visible in `vars`:

| Setting | Default | Meaning |
|---|---|---|
| `llm.enabled` | `true` | Master switch |
| `llm.backend` | `auto` | `anthropic`, `openai`, `ollama`; `auto` takes the first that is ready |
| `llm.model` | the backend's own default | Model id |
| `llm.base-url` | the backend's own default | Endpoint, for the OpenAI-compatible backends |
| `llm.api-key` | unset | Bearer token; overrides the provider's environment variable |
| `llm.max-tokens` | `2048` | Output ceiling per request |
| `llm.max-rows` | `50` | Result rows shown to the model by `explain` |
| `llm.max-retries` | `1` | Correction attempts after a query fails to parse (0–3) |
| `llm.timeout` | `120` | Request timeout in seconds — raise it for a large local model |
| `llm.confirm` | `false` | When true, `ask` prints the query but does not run it |
| `llm.redact` | `true` | Redact sensitive fields before sending |
| `llm.redact-fields` | see below | Replace the redaction list; a leading `+` extends it |

```
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

`llm dry-run <question>` builds the identical request and prints it instead of sending it — same
prompt, same redaction, same bytes. Use it to see what would leave the machine before you let
anything leave the machine. It needs no credentials.

## Next

- [Asking questions](AskTutorial.md) — the tutorial, which doubles as a way to learn JfrPath
- [What leaves your machine](LlmPrivacy.md) — redaction, local models, and analysing recordings you
  did not make
- [When to use which](../mcp/WhenToUseWhich.md) — shell LLM vs MCP server vs the Claude Code plugin
