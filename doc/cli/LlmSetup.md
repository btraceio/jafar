# Setting up the LLM commands

The Jafar shell can turn a question into a query. This page covers getting that working, in both
authentication modes, and the failure modes worth knowing before you hit them.

If you only read one thing: run `llm status`. It tells you which credential the shell will use and
what to do if that is not what you expected.

## What you get

| Command | Does |
|---|---|
| `ask <question>` | Turns the question into a query, **prints the query**, and runs it |
| `explain` | Explains the most recent result |
| `llm status` | Which credential source and settings are active |
| `llm dry-run <question>` | Prints exactly what `ask` would send, and sends nothing |
| `llm cost` | Token usage for this process |

Both `jfr-shell` (JFR recordings) and the unified `jafar-shell` (recordings, heap dumps, pprof and
OTLP profiles) have these commands. `ask` uses whichever query language the current session needs,
so in `jafar-shell` it reaches HdumpPath and the samples grammar as well as JfrPath. `jafar-shell`
has no `set` command yet, so configure it there with the `JAFAR_LLM_*` environment variables.

The feature is optional. Without the `llm-core` module on the classpath, or without a credential,
every other shell command behaves exactly as before and the LLM commands print a clear message.
Nothing calls out to the network unless you run one of the commands above.

## Two ways to authenticate

The shell uses the official Anthropic Java SDK, which resolves credentials itself. That means both
modes are the same code path and neither needs configuration in Jafar.

Resolution order, first match wins:

1. `ANTHROPIC_API_KEY`
2. `ANTHROPIC_AUTH_TOKEN`
3. the OAuth profile selected by `ANTHROPIC_PROFILE`, or the active one
4. Workload Identity Federation environment variables
5. the default profile on disk

### Mode 1 — API key

```bash
export ANTHROPIC_API_KEY=sk-ant-...
jfr-shell recording.jfr
```

Simple, and the right choice for CI or a container. The cost is that you now have a long-lived
secret to store and rotate.

### Mode 2 — keyless, with an OAuth profile

```bash
ant auth login          # opens a browser, stores a profile under ~/.config/anthropic/
jfr-shell recording.jfr # no environment variable needed
```

`ant` is the [Anthropic CLI](https://github.com/anthropics/anthropic-cli). After login it writes
`configs/<profile>.json` and `credentials/<profile>.json`, and the SDK picks them up
automatically — there is no static key anywhere, and tokens are short-lived and refreshed for you.

On a machine with no browser, `ant auth login --no-browser` prints a URL and takes the code back on
the terminal.

### What "keyless" does not mean

**It does not mean free.** An OAuth profile authenticates against a Console organisation and bills
as ordinary API usage, exactly like an API key does. The difference is credential management, not
cost.

A **Claude Pro or Max subscription is a different entitlement** from API access. It is what Claude
Code uses, and it is not something this shell can use directly. If that is what you have, the
supported route is to let Claude Code do the analysis through Jafar's MCP server — see
[When to use which](../mcp/WhenToUseWhich.md). A delegate backend that automates this is designed
but not built; see [the handoff document](../plans/llm-in-the-shell-handoff.md).

## Three traps

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

## Settings

All settable with `set`, and visible in `vars`:

| Setting | Default | Meaning |
|---|---|---|
| `llm.enabled` | `true` | Master switch |
| `llm.model` | `claude-opus-5` | Model id |
| `llm.backend` | `auto` | Backend id; `auto` takes the first discovered |
| `llm.max-tokens` | `2048` | Output ceiling per request |
| `llm.max-rows` | `50` | Result rows shown to the model by `explain` |
| `llm.confirm` | `false` | When true, `ask` prints the query but does not run it |
| `llm.redact` | `true` | Redact sensitive fields before sending |
| `llm.redact-fields` | see below | Replace the redaction list; a leading `+` extends it |

```
jfr> set llm.model = claude-haiku-4-5
jfr> set llm.redact-fields = +sessionId,userId
jfr> set llm.confirm = true
```

Each is also readable from an environment variable (`JAFAR_LLM_MODEL`, `JAFAR_LLM_MAX_ROWS`, and so
on), which is the easier route in CI.

## Cost

The default model is the strongest tier, deliberately: a wrong query wastes your turn and teaches
you the wrong syntax, which costs more than the token difference. If you want translation on
something cheaper, `set llm.model = claude-haiku-4-5`.

Two things keep the cost small by construction:

- **The model never sees raw events.** It composes a query; the shell runs it. A 900 MB recording
  costs the same as a 2 MB one, because the recording never goes anywhere.
- **The language reference is cached.** It is the bulk of each request and is byte-identical every
  time, so after the first call it is a cache read. `llm cost` shows the cached-token count; if it
  stays at zero across several calls, something is varying the prefix and worth reporting as a bug.

Every LLM command prints its token usage when it finishes.

## Verifying without spending anything

`llm dry-run <question>` builds the identical request and prints it instead of sending it — same
prompt, same redaction, same bytes. Use it to see what would leave the machine before you let
anything leave the machine. It needs no credentials.

## Next

- [Asking questions](AskTutorial.md) — the tutorial, which doubles as a way to learn JfrPath
- [What leaves your machine](LlmPrivacy.md) — redaction, and analysing recordings you did not make
- [When to use which](../mcp/WhenToUseWhich.md) — shell LLM vs MCP server vs the Claude Code plugin
