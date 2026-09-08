# LLM in the shell: what is built, and where B plugs in

Companion to [llm-in-the-shell.md](llm-in-the-shell.md), which laid out alternatives A, B and C.
**Alternative A is implemented.** This document records what exists, the decisions behind it, the
seams deliberately left for B, and what is explicitly not done — so the next person (or the next
session) can start from the seams rather than from the design.

## 1. What shipped

| Piece | Where |
|---|---|
| Backend SPI, config, redaction, prompts, parsing, orchestration, validation loop | `shell-core/src/main/java/io/jafar/shell/core/llm/` |
| Anthropic backend and credential diagnostics | `llm-anthropic/src/main/java/io/jafar/shell/llm/` |
| OpenAI-compatible backends (`openai`, `ollama`) | `llm-openai/src/main/java/io/jafar/shell/llm/openai/` |
| `ask`, `explain`, `llm` commands | `jfr-shell/src/main/java/io/jafar/shell/cli/LlmCommands.java` |
| Wiring — `jfr-shell` (JFR only) | `CommandDispatcher.java` — cases at the top of the switch, `llmCommands()` host adapter |
| Wiring — `jafar-shell` (all four formats) | `unified/Shell.java` — branches in the command chain, `llmCommands()` host adapter |
| Docs | `doc/cli/LlmSetup.md`, `AskTutorial.md`, `LlmPrivacy.md`, `doc/mcp/WhenToUseWhich.md` |

Commands: `ask <question>`, `explain`, `llm status`, `llm dry-run <question>`, `llm cost`.

## 2. The five decisions worth not re-litigating

**The model composes queries; it never sees raw events.** This is what makes the feature cheap and
correct at the same time. A 900 MB recording costs the same as a 2 MB one. Any future work that
starts feeding event data to the model should be treated as a redesign, not an increment.

**LLM support is optional at every level.** The SPI is in `shell-core` (no new dependencies);
provider code is only in `llm-anthropic` and `llm-openai`, which both shells take as `runtimeOnly`
and discover with `ServiceLoader`. Delete those two lines and every provider dependency is gone, the
commands degrade to a message, and nothing else changes. Air-gapped users are a real part of this
tool's audience — and `ollama` on loopback is the other answer for them.

**No provider is privileged.** `llm.backend` selects by id, `auto` takes the first ready one, and
each backend supplies its own `defaultModel()` — which is why `LlmConfig` has no cross-provider
model default. `OpenAiCompatibleBackend` is a `Profile` plus wire code, so adding vLLM or Groq as a
named id is a new `Profile`, not new transport.

**For Anthropic, both auth modes are the SDK's job.** `AnthropicOkHttpClient.fromEnv()` resolves API
key, OAuth profile and WIF. Jafar contributes no auth code — only the *diagnostics*, because the SDK
does not fail fast when credentials are missing. The OpenAI-compatible backends take a bearer token
from `llm.api-key` or the profile's env vars, and omit the `Authorization` header entirely when
there is none: an empty bearer breaks several local servers.

**A candidate query is validated locally before it runs.** `Host.validateQuery` parses it with the
same parser that would execute it; on rejection `LlmService` feeds the parser's own error back and
asks for a correction, bounded by `llm.max-retries`. Without this the feature does not work on a
small local model, which is most of the reason `ollama` is worth having.

**The query is always printed before it runs.** Non-negotiable: it is how a wrong guess becomes
visible and how users learn JfrPath. Do not add a "quiet" mode that hides it.

**Recording content is fenced as untrusted data.** Thread names and heap strings are
attacker-controllable when the recording came from someone else. `PromptBuilder.DATA_OPEN` /
`DATA_CLOSE` and the system-prompt clause are the mitigation; the read-only tool surface is the
backstop.

## 3. The seams B plugs into

B is the agentic `analyze` loop. Each of these exists so that B is additive rather than a rewrite.

### 3.1 `LlmBackend` — add a method, not a module

`shell-core/.../llm/LlmBackend.java` has `complete(LlmRequest, LlmConfig)`. B adds a tool-using
call alongside it — most likely `completeWithTools(LlmRequest, List<ToolSpec>, LlmConfig)` returning
either text or a tool-use request. `AnthropicBackend` implements it with the Java SDK's tool
support (`Tool.builder()`, `stop_reason == "tool_use"`; the SDK's tool runner needs
`.addBeta("structured-outputs-2025-11-13")`).

Because discovery is `ServiceLoader`-based and selection goes through `llm.backend`, **alternative
C's delegate backend is a new module (or a class in an existing one) and one line in a services
file** — no changes to the command layer, the config, or the redaction path. `llm-openai` is the
worked example: it was added without touching `LlmCommands`, `LlmService`, `Redactor` or either
shell's adapter.

### 3.2 `LlmService` — the orchestration point

`LlmService` already owns prompt construction, redaction, truncation, backend readiness and usage
accumulation. B's `analyze` belongs here as a third entry point next to `ask` and `explain`, and
should reuse:

- `Redactor` on every tool result before it goes back to the model — the loop sends far more result
  data than `ask` does, so this matters more in B, not less.
- `sessionUsage` for the budget. B needs a **token budget and a step cap**; the accumulator is
  there, the enforcement is not.

### 3.3 Tools should be the shell's existing capabilities

`LlmCommands.Host` is already the right shape for this: `runQuery`, `availableTypes`,
`currentModuleId`. B's tool surface is that interface plus a few more methods (`summarize`,
`listSessions` for the cross-session join). Keeping tools behind `Host` preserves the property that
the whole feature is testable against a fake host with no network.

**Keep the tool surface read-only.** It is the backstop for §2's injection mitigation.

### 3.4 The `.jfrs` transcript is B's real deliverable

The design document argues this and it is worth repeating: the shell already records commands to
replayable `.jfrs` scripts (`doc/cli/CommandRecording.md`, `CommandRecorder`). An `analyze` run
should write its query sequence there, so an LLM investigation ends as a script a human can read
and re-run. That turns the loop's weakest property — non-determinism — into a verifiable artifact.
`LlmCommands.noteResult` already tracks the last query and rows; extending it to append to a
recorder is small.

### 3.5 Findings should be the shared shape

`jfr_diagnose`, `jfr_use`, `jfr_tsa`, `jfr_compare` and `hdump_report` all emit
`io.jafar.mcp.findings.Finding` (stable id, severity, category, evidence, action, follow-up query).
B's `analyze` should emit the same thing, which means **moving that record from `jfr-mcp` to
`shell-core`** so the shell and the MCP server share one model. That move is mechanical — the type
has no MCP dependencies — and it is the point at which a shell investigation and an MCP
investigation become mergeable.

## 4. Deliberately not done

| Not done | Why |
|---|---|
| Streaming output | The `IO` hook (`CommandDispatcher.IO.println`) supports it, but an `ask` reply is a query and one sentence — streaming it adds machinery for no perceptible gain. B, whose replies are long, is where it earns its place. |
| A `set` command in `jafar-shell` | The unified shell is wired for `ask` (it is the only entry point that opens all four formats), but it still has no `set`/`vars`, so `llm.*` settings there resolve from its global `VariableStore` — which nothing populates — and then from `JAFAR_LLM_*` environment variables. Giving that shell a `set` command is gap G8 in `performance-engineer-in-a-box.md`; the LLM host adapter already reads the store, so it starts working the day `set` lands. |
| Multi-turn conversation | `ask` is one shot. Conversation state belongs in `VariableStore` so `vars` shows it and scripts can reset it, but it is only worth building with B's loop. |
| Cost in currency | Usage is reported in tokens. Converting to money means shipping a price table that goes stale; the token counts are exact and the pricing is one lookup away. |
| Live API test | No test in this repository calls a hosted provider. See §6. |
| Streaming / tool use on the OpenAI backends | `stream:false` and no `tools` array. B needs tool use; the OpenAI protocol has it, and it goes next to `complete` per §3.1. |
| A named `Profile` per provider | vLLM, LM Studio, Groq, Together and OpenRouter all work today via `llm.backend = openai` plus `llm.base-url`. Named ids are three lines each and worth adding when someone actually asks. |
| Ollama's native `/api/*` endpoints | The OpenAI-compatible surface is enough and keeps one code path. Native mode would buy `keep_alive` and model-pull control. |

## 5. Where to look first

```
shell-core/src/main/java/io/jafar/shell/core/llm/
  LlmBackend.java        SPI + ServiceLoader discovery + selection   <- B adds a method here
  LlmService.java        orchestration, redaction, usage             <- B adds analyze() here
  LlmConfig.java         settings, defaults, env fallback
  Redactor.java          egress redaction, nested-aware
  PromptBuilder.java     prompts, data fencing, TSV rendering
  LanguageReference.java cached grammar prefixes (byte-stable!)
  QueryProposal.java     forgiving parse of the model's reply
  LlmRequest/Response    transport-neutral request and usage records

llm-anthropic/src/main/java/io/jafar/shell/llm/
  AnthropicBackend.java       the SDK call, prompt caching, error->remedy mapping
  CredentialDiagnostics.java  which credential wins, and the shadowing traps

llm-openai/src/main/java/io/jafar/shell/llm/openai/
  OpenAiCompatibleBackend.java  chat-completions over the JDK HttpClient; Profile; loopback probe
  OpenAiBackend.java            profile: api.openai.com, gpt-4o-mini, key required
  OllamaBackend.java            profile: localhost:11434, qwen2.5-coder:7b, keyless

jfr-shell/src/main/java/io/jafar/shell/cli/
  LlmCommands.java       command behaviour, Host interface           <- B's tools extend Host
  CommandDispatcher.java switch cases + the Host adapter
```

Two invariants to preserve:

1. **`LanguageReference` strings must stay byte-stable between calls.** They are the cached prompt
   prefix. A timestamp or session id in there silently costs full price on every request. The test
   `LlmServiceTest.theSystemPrefixIsByteStableAcrossCalls` guards this.
2. **Unit tests must never reach a real backend.** `llm-anthropic` and `llm-openai` are both on
   `jfr-shell`'s test runtime classpath, so their backends *are* discoverable in tests.
   `LlmCommandsTest` pins `llm.backend` to a non-existent id for exactly this reason — without it,
   running the suite on a machine with `ANTHROPIC_API_KEY` set would issue live, billable calls.
   Keep that pin. `llm-openai`'s own tests bind a `com.sun.net.httpserver.HttpServer` to loopback,
   which is a real socket and a real request but no provider account.
3. **The LLM host adapter must know both of `CommandDispatcher`'s query paths.** With a
   `JfrSelector` it delegates; without one — which is how the interactive `io.jafar.shell.Shell`
   builds it — it parses and evaluates JfrPath directly. An adapter that knows only the selector
   leaves `ask` broken in the shell people actually type into while every fake-host unit test stays
   green. `LlmHostAdapterTest` guards it.

## 6. Verification status — read this before trusting anything

**Tested, and passing:**

- Unit tests across `shell-core`, `llm-openai` and `jfr-shell`: redaction (including nesting and
  non-mutation), config precedence and defaults, reply parsing in six shapes, prompt construction,
  prefix stability, data fencing, truncation declaration, dry-run/actual equivalence, the
  validate-and-correct loop, and every command's degraded path.
- `llm-openai` is tested against a real `com.sun.net.httpserver.HttpServer` on loopback rather than
  a mocked client, because what is most likely to be wrong there is on the wire: the JSON shape, the
  headers, the absence of an `Authorization` header when there is no key, usage accounting with
  `prompt_tokens_details.cached_tokens`, and how an error body becomes a remedy.
- **The full path, in both built shells, against a real recording and a real HTTP server.** A stub
  OpenAI-compatible server was scripted to answer first with a query the JfrPath parser rejects and
  then with a valid one. `jfr-shell` and `jafar-shell` each produced:

  ```
  events/jdk.ExecutionSample | count()
  | count |
  +-------+
  | 1142  |
  [llm: 200 in, 48 out, 2200 cached, 1 correction(s)]
  ```

  matching the same query typed by hand — so the correction loop, the query execution, the
  rendering and the usage accounting all work outside the test harness.
- End-to-end without credentials: `llm status`, `llm dry-run`, and `ask`, plus both Anthropic
  credential traps (empty key; key and token together) — each produced the intended local
  diagnostic and remedy.
- ServiceLoader discovery of all three backends from a built shell's classpath.

**Not tested:** any hosted provider. No credentials were available and spending someone's money
from a test is not acceptable, so neither `AnthropicBackend.complete` nor a call to
`api.openai.com` has ever executed. What that leaves unverified, concretely:

- for Anthropic, that the request shape is accepted (model id, `systemOfTextBlockParams` with
  `cacheControl`, `maxTokens`), and that the cached prefix produces a non-zero
  `cache_read_input_tokens` on the second call;
- that `remedyFor` matches each provider's real error messages for 401/403/429/404. Both backends
  match on substrings, which is the fragile part; the OpenAI one at least matches on the HTTP
  status first;
- that a real model's reply parses. `QueryProposal` is tested against six hand-written shapes and
  the stub's output, not against a real model.

The OpenAI-compatible path is the cheapest to close: `ollama serve`, `ollama pull qwen2.5-coder:7b`,
`set llm.backend = ollama`, `ask`. That costs nothing and exercises real model output through the
real wire format.

**The first thing to do with a hosted credential** is run `llm dry-run`, then `ask`, then
`llm cost`, and check that the cached-token count is non-zero on the second `ask`. That exercises
the rest in under a minute.

## 7. Suggested order for B

1. Move `Finding` to `shell-core` (§3.5) — small, unblocks shared output.
2. Add the tool-using method to `LlmBackend` and implement it in `AnthropicBackend` (§3.1).
3. Extend `Host` with the read-only tool surface; add `analyze()` to `LlmService` (§3.2, §3.3).
4. Budget enforcement — step cap and token cap — before the loop is usable by anyone else.
5. `.jfrs` transcript output (§3.4). This is the feature; do not leave it to last in practice.
6. Only then consider streaming, and the unified-shell wiring once it has a variable store.
