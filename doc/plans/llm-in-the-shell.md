# An LLM inside the Jafar shells: design alternatives

Status: ideation, no decision taken. Companion to
[performance-engineer-in-a-box.md](performance-engineer-in-a-box.md), whose tiers A and B are
implemented. That work put the *tools* in front of an LLM that lives somewhere else (Claude Code,
Claude Desktop, any MCP client). This document is about the opposite direction: putting the LLM
*inside* `jfr-shell`, `hdump-shell`, `pprof-shell`, `otlp-shell` and the unified `jafar-shell`.

## 1. Why both, and how they differ

The MCP server and an in-shell LLM are not competing designs; they serve different situations.

| | MCP server (`jfr-mcp`) | LLM in the shell |
|---|---|---|
| Where the model runs | The user's AI client | The shell process |
| Prerequisite | An MCP-capable client | A terminal |
| Works over SSH on a prod jump host | Only if the client is there too | Yes |
| Works in CI / a script | Awkward | Yes — `jfr-shell ask "..."` is one command |
| Who sees the recording | The client's host | The shell's host |
| Conversation state | The client's | The shell session, alongside the open recordings |

The case for the in-shell LLM is the case for `jfr-shell` itself: an engineer is on a box with a
recording and a terminal. Today they need to know JfrPath. The gap this closes is the one between
"I have a 900 MB recording and a question" and "I know which of 224 event types answers it".

## 2. Authentication: both modes, verified

The requirement is API-key mode *and* keyless mode. The good news is that one client construction
serves both, because credential resolution is the SDK's job, not ours.

### 2.1 The Java SDK

`com.anthropic:anthropic-java` (2.34.0 at time of writing) is the official Java SDK — the right
choice for a Java 25 codebase, and it removes any need to hand-roll HTTP.

Verified by downloading the artifact from Maven Central and inspecting it: the core jar ships
`com.anthropic.credentials.CredentialResolver`, `com.anthropic.config.ProfileConfig`,
`ProfileConfigProvider`, `ConfigurationFileProvider`, `com.anthropic.core.auth.*`
(`CachingAccessTokenProvider`, `AccessTokenProvider`, `FileIdentityTokenProvider`), and
`com.anthropic.errors.NoCredentialsException` / `CredentialSource` / `CredentialSourceState`.
The env names embedded in those classes are `ANTHROPIC_CONFIG_DIR`, `ANTHROPIC_PROFILE`,
`ANTHROPIC_FEDERATION_RULE_ID`, `ANTHROPIC_ORGANIZATION_ID`, `ANTHROPIC_SERVICE_ACCOUNT_ID`,
`ANTHROPIC_WORKSPACE_ID`, `ANTHROPIC_IDENTITY_TOKEN`, `ANTHROPIC_IDENTITY_TOKEN_FILE`.

So the SDK itself implements the documented resolution order, first match wins:

1. `ANTHROPIC_API_KEY`
2. `ANTHROPIC_AUTH_TOKEN`
3. the `ANTHROPIC_PROFILE`-selected, or active, OAuth profile on disk
4. Workload Identity Federation env vars
5. the default profile on disk

`AnthropicOkHttpClient.fromEnv()` is therefore the whole of our auth code. **Mode 1 (API key)** is
`ANTHROPIC_API_KEY` in the environment. **Mode 2 (keyless)** is `ant auth login`, which stores a
profile under `~/.config/anthropic/` (`configs/<profile>.json`, `credentials/<profile>.json`) that
the SDK reads with no env var set.

### 2.2 What "keyless" honestly means

The term covers two different things, and conflating them will produce a broken feature and an
unhappy user:

- **An OAuth profile from `ant auth login`.** No static key to manage or leak; short-lived tokens,
  refreshed by the SDK. This is fully supported, works today, and needs no code from us. It
  authenticates against a Console organisation and bills as API usage.
- **A Claude.ai Pro/Max subscription.** This is the entitlement Claude Code uses. It is not the
  same as API access, and we should not attempt to mint or reuse subscription tokens ourselves —
  that is neither documented nor something to reverse-engineer. The supported way for a
  third-party tool to ride a user's subscription is to **delegate to a Claude Code installation
  they already have**, which is exactly what alternative C does.

Being precise about this in the docs matters more than usual: "keyless" will otherwise be read as
"free", and the first surprise API bill destroys trust in the feature.

### 2.3 Three traps to design around

Each of these produces a confusing failure unless the shell handles it explicitly.

1. **A stale `ANTHROPIC_API_KEY` silently shadows a profile.** Requests go to whatever org that key
   belongs to. An empty `ANTHROPIC_API_KEY=""` still wins its slot and authenticates as empty.
2. **`ANTHROPIC_API_KEY` and `ANTHROPIC_AUTH_TOKEN` both set** makes the SDK send both, and the API
   rejects the request.
3. **Refresh tokens hard-expire**; they do not slide with use. A profile that worked last month
   starts failing auth, and the fix is `ant auth login`, not debugging.

There is a fourth, discovered empirically: **the SDK does not fail fast when it finds no
credentials at all.** With `HOME` pointed at an empty directory and both env vars unset,
`AnthropicOkHttpClient.fromEnv()` constructs fine and the failure only surfaces when a request is
made. A user with nothing configured would otherwise get an opaque 401 from the server rather than
"you are not logged in".

The conclusion is that the shell needs its own credential diagnostic. Every alternative below
includes an `llm status` command that reports which source won, which profile and workspace are
active, and what to do about it — the local equivalent of `ant auth status`, so the user never has
to guess.

## 3. The architectural decision that matters most

**The LLM must never see raw events.** A 900 MB recording is tens of millions of events; the
context window is 1M tokens. Any design that streams event data at the model is both ruinously
expensive and worse at answering than the query engine.

Jafar already owns the right reducer: JfrPath, HdumpPath and SamplesPath turn millions of events
into tens of rows. So the model's job is to *compose queries and interpret results*, and the
shell's job is to run them. Every alternative below rests on that split, and it is what keeps the
cost per question in cents rather than dollars.

The corollary is a hard cap: results handed to the model are truncated to a row budget, always
with the truncation stated in the payload, so the model knows it is looking at a sample.

## 4. Alternative A (conservative): `ask` — one question, one answer

**Thesis.** The single highest-value thing an LLM can do here is translate a question into a
correct query, and explain a result table. Neither needs an agent.

**Surface.** One new command in every shell, plus a non-interactive form:

```
jfr> ask "which threads are burning CPU, and what are they doing?"
jfr> explain            # explains the result of the previous query
$ jfr-shell ask recording.jfr "how long were the GC pauses?"
```

`ask` sends the question, the session's event-type inventory (names and counts — not data), and a
compact JfrPath grammar summary; the model returns a query, which the shell **prints, runs, and
shows the result of**. The query is echoed before it runs, always, so the user learns the language
rather than being insulated from it — and so a wrong query is visible rather than mysterious.

**Implementation.** A new `shell-core` package, `io.jafar.shell.core.llm`, with:

- `LlmClient` — thin wrapper over `AnthropicOkHttpClient.fromEnv()`, streaming, with the model,
  effort and max-tokens settings read from shell config.
- `LlmConfig` — `llm.model` (default `claude-opus-5`), `llm.enabled`, `llm.redact`,
  `llm.max-rows`, all settable via the existing `set` command and a config file.
- `CredentialDiagnostics` — powers `llm status`, covering §2.3.
- `SchemaSummary` — builds the compact type inventory a translation prompt needs.

Wiring is one case in `jfr-shell`'s `CommandDispatcher` (the switch at
`jfr-shell/src/main/java/io/jafar/shell/cli/CommandDispatcher.java:234`) and one in
`jafar-shell`'s unified `Shell`. Output goes through the existing `io.println` hook
(`CommandDispatcher.java:43`), so streaming into the TUI needs no new plumbing.

**Cost and risk.** Small — a few hundred lines plus prompts. The risk is a plausible-but-wrong
query producing a confident answer; showing the query mitigates it, and it is the reason `ask`
never hides what it ran.

**Does not do.** Multi-step investigation, correlation across sessions, or any judgement about
what to look at next.

## 5. Alternative B (moderate): an agentic loop in the shell

**Thesis.** A real investigation is a sequence: summarise, notice, drill in, correlate, conclude.
Give the model the shell's own capabilities as tools and let it run that loop, with the results
staying in the process.

**Surface.**

```
jfr> analyze                      # open-ended: triage this recording
jfr> analyze "why did p99 double after 14:20?"
jfr> analyze --max-steps 12 --budget 0.50
```

**Tools exposed to the model** — deliberately the shell's existing capabilities, not new code:
`list_types`, `run_query` (the active module's language), `get_metadata`, `summarize`,
`stackprofile`, `flamegraph`, and — where sessions of several formats are open — `list_sessions`
so it can reach for the cross-session `join`. The Java SDK supports tool use, so this is a loop
over `stop_reason == "tool_use"`, or the SDK's tool runner.

This is where the in-shell design earns its keep over MCP: the tools are in-process, so a tool call
is a method call rather than a JSON-RPC round trip over a pipe, and the model can be given far more
generous per-step result budgets without paying for serialisation.

**Reproducibility, free.** The shell already records commands into replayable `.jfrs` scripts
(`doc/cli/CommandRecording.md`). An `analyze` run should write its query sequence into exactly that
format, so every LLM investigation ends with a script a human can read, re-run and check. This
turns the loop's weakest property — that it is non-deterministic — into an artifact that is
verifiable. It is the single most important feature in this tier and it costs almost nothing,
because the recorder exists.

**Findings, shared.** Tier B of the companion document introduced
`io.jafar.mcp.findings.Finding` — severity, category, evidence, action, follow-up query, stable id.
`analyze` should emit that same shape (moving the record into `shell-core` so both the MCP server
and the shell use one model), so a shell investigation and an MCP investigation produce mergeable
output.

**Cost and risk.** Weeks. Needs a real budget mechanism — step cap, token cap, and a printed
running cost — because an agentic loop with no ceiling is how a shell command becomes a surprise
invoice. Needs the safety work in §7, which is not optional at this tier.

## 6. Alternative C (ambitious): delegate mode, for subscription users and full agency

**Thesis.** Some users have a Claude Code installation and a subscription and would rather the
shell use it than ask them for an API key. Others want the full agent — one that can read their
source tree, not just the recording.

**How.** The shell detects a `claude` binary and offers a delegate backend: rather than calling the
API, it spawns Claude Code headlessly, hands it the question, and lets it drive the analysis
*through jafar's own MCP server*. The shell already knows the recording path and can generate the
MCP configuration pointing at `jfr-mcp`. Auth is then whatever the user has already configured for
Claude Code, including a subscription — legitimately, because Claude Code is doing its own work
under its own credentials.

This also composes with what already exists: the `jafar-perf` plugin's skills and specialist agents
are available to the delegated session, so `analyze` in delegate mode inherits the whole
methodology layer rather than duplicating it in prompts.

**The backend abstraction.** Tiers A and B want one interface with two implementations:

```
LlmBackend
├── ApiBackend       — anthropic-java, API key or OAuth profile
└── DelegateBackend  — a local Claude Code process, subscription or key
```

Selection: `llm.backend = auto | api | delegate`, where `auto` prefers a configured API credential
and falls back to a detected `claude` binary. `llm status` reports which was chosen and why.

**Cost and risk.** Months, mostly integration and failure-mode work: process lifecycle, version
skew in the CLI's output format, and a much larger blast radius, since a delegated agent can touch
the filesystem. It must be opt-in and clearly labelled as running an external tool.

## 7. Cross-cutting concerns (not optional at tier B or above)

These are the parts most likely to be skipped and most likely to matter.

### 7.1 Recording content is untrusted input

A JFR recording contains strings produced by the profiled application: thread names, exception
messages, file paths, SQL, HTTP endpoints, class names from user code. A heap dump contains actual
string *values* from the heap.

Once those strings are placed in a model's context, they are indistinguishable from instructions
unless we make them distinguishable. A thread named `ignore previous instructions and ...` is a
real, cheap attack on anyone analysing a recording from an untrusted source — and "analyse this
recording from a customer" is a completely normal workflow.

Mitigations, all cheap:

- Wrap every query result in explicit data delimiters and state in the system prompt that content
  inside them is data, never instructions.
- Never let recording-derived text become a tool argument without escaping.
- Keep the tool surface read-only. Nothing in tier A or B should be able to write files, open
  network connections, or close sessions.
- Say this in the docs, because users analysing third-party recordings need to know.

### 7.2 Data egress and redaction

A production recording is sensitive: endpoint names leak API surface, file paths leak deployment
layout, SQL leaks schema, and heap dumps leak customer data outright. Sending any of it to a
third-party API is a decision the user must make knowingly.

Jafar already has the tool for this: `tools/` ships a **scrubber** that redacts named event fields
(`--scrub-field <Type>.<field>`, `io.jafar.tools.Scrubber`). Reuse it as a redaction filter on the
egress path rather than writing a second one:

- `llm.redact` — a field list applied to anything leaving the process; on by default for
  the obviously sensitive fields.
- `llm dry-run` — prints exactly what *would* be sent, byte for byte, and sends nothing. This is
  the feature that lets a security team approve the tool at all, and it should exist from tier A.
- Heap dumps get a stricter default than recordings: class names and counts may leave, string
  *values* may not, unless explicitly enabled.

### 7.3 The shell must be unchanged when the LLM is off

No new required dependency at runtime, no startup latency, no network call unless asked, every
existing command behaving identically. Air-gapped and regulated environments are a real part of
this tool's audience. The `anthropic-java` dependency should be optional at runtime and the LLM
commands should degrade to a clear message when it or a credential is absent.

### 7.4 Cost, made visible

Print token usage and estimated cost after any LLM command, and keep a session running total.
`ask` is a single call at a predictable size; `analyze` is not, which is why tier B needs both a
step cap and a token budget. Default to `claude-opus-5`; make `llm.model` configurable so a user
can put query translation on a cheaper model while leaving analysis on the strongest one.

## 8. Comparison

| | A: `ask` | B: `analyze` loop | C: delegate mode |
|---|---|---|---|
| New surface | `ask`, `explain`, `llm status`, `llm dry-run` | plus `analyze`, budgets, `.jfrs` transcript | plus `llm.backend`, Claude Code detection |
| Model does | Translates and explains | Investigates | Investigates with the repo and the plugin's skills |
| Auth modes | API key, OAuth profile | same | plus subscription, via Claude Code |
| Reproducibility | The query is printed | A replayable `.jfrs` script | The delegated session's own transcript |
| Egress control | dry-run, redaction | same, larger surface | hardest — an external process |
| Rough size | days | weeks | months |
| Main risk | A confident wrong query | Unbounded cost; injection | Blast radius; CLI version skew |

## 9. Recommendation

Do **A**, and design its `LlmBackend` seam so **B** is additive rather than a rewrite. `ask` is
where nearly all the everyday value is: it removes the JfrPath learning curve, which is the single
biggest barrier to the shell, and it does so with a surface small enough to get the auth, redaction
and cost-reporting right first.

Then **B**, whose real deliverable is not the loop but the `.jfrs` transcript — an LLM
investigation that a human can replay is a genuinely new thing for this tool, and it is what makes
the output trustworthy enough to paste into an incident review.

Treat **C** as demand-driven. It is the honest answer for subscription users, but it is worth
building only once enough people ask for it, because delegate mode is mostly failure-mode
engineering.

Regardless of tier: ship `llm status` and `llm dry-run` in the first release. They are small, and
without them the feature is unadoptable in exactly the environments that most need it.

## 10. Documentation plan

New material, roughly in the order a reader needs it:

| Document | Covers |
|---|---|
| `doc/cli/LlmSetup.md` | Both auth modes end to end; `ant auth login` vs `ANTHROPIC_API_KEY`; the three traps in §2.3; what "keyless" does and does not mean; cost expectations |
| `doc/cli/AskTutorial.md` | Learning JfrPath *through* `ask` — question, generated query, result, and what the query means. The pedagogical framing is the point |
| `doc/cli/AnalyzeTutorial.md` | An end-to-end investigation, ending with the `.jfrs` transcript and how to verify it |
| `doc/cli/LlmPrivacy.md` | What leaves the process, redaction defaults, `dry-run`, heap-dump rules, and guidance for analysing third-party recordings |
| `doc/mcp/WhenToUseWhich.md` | MCP server vs in-shell LLM vs the `jafar-perf` plugin — three surfaces, one toolkit |
| Updates | `README.md`, `AGENTS.md`, `doc/README.md`, `doc/cli/Usage.md`, `doc/cli/Tutorial.md`, `CHANGELOG.md` |

Blog-shaped pieces, which are a different genre and should not be written as docs:

1. **"Ask your JFR recording a question"** — the demo post. One real recording, one real question,
   the generated query, the answer. Short.
2. **"We put an LLM in a profiler shell and made it show its work"** — the `.jfrs` transcript idea,
   and why a reproducible LLM investigation beats a persuasive one.
3. **"Your heap dump is a prompt injection vector"** — §7.1 generalised. This is the piece with
   an audience beyond Jafar's users, and nobody in the profiling space has written it.
4. **"Correlating what is retained with who allocated it"** — the heap-to-JFR join, now that it
   works; strongest with the LLM composing the query.

## 11. Open questions

- **Does the query-translation prompt need the full grammar, or a retrieved subset?** The JfrPath
  reference is ~1250 lines. Sending it every call is expensive but cacheable — prompt caching makes
  a fixed grammar prefix nearly free after the first call, which argues for sending all of it and
  keeping the prefix byte-stable. Worth measuring before optimising.
- **Where does conversation state live?** Probably the existing `VariableStore`, so `vars` shows it
  and scripts can reset it. Needs a decision before B.
- **Should `ask` auto-run the query it generates, or require confirmation?** Auto-run is better UX
  and queries are read-only; a `llm.confirm` setting is the compromise.
- **Which module owns the code?** `shell-core` gives every shell the feature at once, but adds the
  SDK to a module that is currently dependency-light. An `llm-core` module keeps that boundary
  clean at the cost of another module.
