# LLM in the shell: what is built, and where B plugs in

Companion to [llm-in-the-shell.md](llm-in-the-shell.md), which laid out alternatives A, B and C.
**Alternative A is implemented.** This document records what exists, the decisions behind it, the
seams deliberately left for B, and what is explicitly not done — so the next person (or the next
session) can start from the seams rather than from the design.

## 1. What shipped

| Piece | Where |
|---|---|
| Backend SPI, config, redaction, prompts, parsing, orchestration | `shell-core/src/main/java/io/jafar/shell/core/llm/` |
| Anthropic backend and credential diagnostics | `llm-core/src/main/java/io/jafar/shell/llm/` |
| `ask`, `explain`, `llm` commands | `jfr-shell/src/main/java/io/jafar/shell/cli/LlmCommands.java` |
| Wiring — `jfr-shell` (JFR only) | `CommandDispatcher.java` — cases at the top of the switch, `llmCommands()` host adapter |
| Wiring — `jafar-shell` (all four formats) | `unified/Shell.java` — branches in the command chain, `llmCommands()` host adapter |
| Docs | `doc/cli/LlmSetup.md`, `AskTutorial.md`, `LlmPrivacy.md`, `doc/mcp/WhenToUseWhich.md` |

Commands: `ask <question>`, `explain`, `llm status`, `llm dry-run <question>`, `llm cost`.

## 2. The five decisions worth not re-litigating

**The model composes queries; it never sees raw events.** This is what makes the feature cheap and
correct at the same time. A 900 MB recording costs the same as a 2 MB one. Any future work that
starts feeding event data to the model should be treated as a redesign, not an increment.

**LLM support is optional at every level.** The SPI is in `shell-core` (no new dependencies); the
Anthropic SDK is only in `llm-core`, which `jfr-shell` takes as `runtimeOnly` and discovers with
`ServiceLoader`. Delete that one line and the SDK is gone, the commands degrade to a message, and
nothing else changes. Air-gapped users are a real part of this tool's audience.

**Both auth modes are the SDK's job.** `AnthropicOkHttpClient.fromEnv()` resolves API key, OAuth
profile and WIF. Jafar contributes no auth code — only the *diagnostics*, because the SDK does not
fail fast when credentials are missing.

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
C's delegate backend is a second implementation in `llm-core` and one line in a services file** —
no changes to the command layer, the config, or the redaction path.

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
| Live API test | No test in this repository makes a real API call. See §6. |

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

llm-core/src/main/java/io/jafar/shell/llm/
  AnthropicBackend.java       the SDK call, prompt caching, error->remedy mapping
  CredentialDiagnostics.java  which credential wins, and the shadowing traps

jfr-shell/src/main/java/io/jafar/shell/cli/
  LlmCommands.java       command behaviour, Host interface           <- B's tools extend Host
  CommandDispatcher.java switch cases + the Host adapter
```

Two invariants to preserve:

1. **`LanguageReference` strings must stay byte-stable between calls.** They are the cached prompt
   prefix. A timestamp or session id in there silently costs full price on every request. The test
   `LlmServiceTest.theSystemPrefixIsByteStableAcrossCalls` guards this.
2. **Unit tests must never reach a real backend.** `llm-core` is on `jfr-shell`'s test runtime
   classpath, so the Anthropic backend *is* discoverable in tests. `LlmCommandsTest` pins
   `llm.backend` to a non-existent id for exactly this reason — without it, running the suite on a
   machine with `ANTHROPIC_API_KEY` set would issue live, billable calls. Keep that pin.

## 6. Verification status — read this before trusting anything

**Tested, and passing:**

- 27 unit tests across `shell-core` and `jfr-shell`: redaction (including nesting and
  non-mutation), config precedence and defaults, reply parsing in six shapes, prompt construction,
  prefix stability, data fencing, truncation declaration, dry-run/actual equivalence, and every
  command's degraded path.
- End-to-end in a built shell against a real recording: `llm status`, `llm dry-run`, and `ask`
  without credentials, plus both credential traps (empty key; key and token together) — each
  produced the intended local diagnostic and remedy.
- ServiceLoader discovery of `AnthropicBackend` from the shell's classpath.

**Not tested:** the live API path. No credentials were available and spending someone's money from
a test is not acceptable, so `AnthropicBackend.complete` has never executed against
`api.anthropic.com`. What that leaves unverified, concretely:

- that the request shape is accepted (model id, `systemOfTextBlockParams` with `cacheControl`,
  `maxTokens`);
- that the cached prefix produces a non-zero `cache_read_input_tokens` on the second call;
- that a real model reply parses — `QueryProposal` is tested against six hand-written shapes, not
  against actual output;
- that `remedyFor` matches the SDK's real error messages for 401/403/429/404. It matches on
  substrings of the message, which is the fragile part.

**The first thing to do with a credential** is run `llm dry-run`, then `ask`, then `llm cost`, and
check that the cached-token count is non-zero on the second `ask`. That exercises every one of the
above in under a minute.

## 7. Suggested order for B

1. Move `Finding` to `shell-core` (§3.5) — small, unblocks shared output.
2. Add the tool-using method to `LlmBackend` and implement it in `AnthropicBackend` (§3.1).
3. Extend `Host` with the read-only tool surface; add `analyze()` to `LlmService` (§3.2, §3.3).
4. Budget enforcement — step cap and token cap — before the loop is usable by anyone else.
5. `.jfrs` transcript output (§3.4). This is the feature; do not leave it to last in practice.
6. Only then consider streaming, and the unified-shell wiring once it has a variable store.
