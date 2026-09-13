# LLM in the shell (`ask`)

The SPI, the backends, and the decisions that shaped them.

## LLM in the Shell (`ask`)
`jfr-shell` can translate a question into a query and run it: `ask <question>`, `explain`,
`llm status`, `llm cost`. Either verb takes `--dry-run` (`ask --dry-run <question>`,
`explain --dry-run`) to print exactly what would be sent without sending it.

Architecture, and the reasons it is shaped this way:
- The SPI (`io.jafar.shell.core.llm`) lives in **shell-core with no new dependencies**. Backends
  live in **llm-anthropic** (Anthropic Java SDK) and **llm-openai** (chat-completions over the JDK
  HTTP client, no provider SDK), which both shells take as `runtimeOnly` and discover via
  `ServiceLoader`. Dropping those dependencies removes every provider SDK and the commands degrade
  to a clear message — air-gapped use is a supported configuration, not an accident.
- **No provider is privileged.** `llm.backend` selects one by id (`anthropic`, `openai`, `ollama`);
  `auto` takes the first that reports ready. Each backend supplies its own `defaultModel()`, so
  `LlmConfig` holds no cross-provider model default — setting `llm.model` for one provider and then
  switching would otherwise send a model id the new provider has never heard of.
- **`llm.base-url` is what makes "OpenAI-compatible" mean it.** `OpenAiCompatibleBackend` is a
  `Profile` (id, display name, default base URL, default model, key env vars, whether a key is
  required) plus the wire code; `openai` and `ollama` are two instances of it. Adding vLLM or Groq
  as a named id is a new `Profile`, not new transport code.
- **The model is told what each event type is for, from the recording's own metadata.** JFR
  annotates event classes with `@Label` and `@Description` ("CPU Load", "Information about the
  recent CPU usage of the JVM process"), and `ask` sends those so a type is chosen on meaning
  rather than on a name that happens to share a word with the question. It lives in the **cached
  system prefix**, because it is fixed for a recording — which means `PromptBuilder.renderInventory`
  must stay byte-stable, so it sorts. Event counts are *not* sent: `JFRSession` only accumulates
  them while a query runs, so before one they are all zero, and computing them for real means
  scanning the recording. Type names and descriptions are attacker-controllable in a recording you
  did not produce, so they stay inside the `RECORDING_DATA` fence even though they now sit in the
  system prompt.
- **Event counts decide which types are offered at all.** `scanMetadata` reads only the first
  chunk's metadata, so `getAvailableTypes` is everything the JVM *declared* — including types that
  emitted nothing. A recording from an agent with its own sampler carries an empty
  `jdk.ExecutionSample` beside a vendor type with thousands of events, and a model given only names
  picks the familiar one. `CommandDispatcher.eventCounts` counts once via
  `JfrPathEvaluator.countAllEventTypes`, caches in-session and across sessions
  (`EventCountCache`, keyed on path+size+mtime), and `renderInventory` puts zero-count types in a
  separate "do not query" line. A type absent from a *successful* count is 0, not unknown — the
  distinction matters, since -1 means counting did not happen and the model must infer nothing.
  Disable with `llm.count-events = false`.
- **Fields are fetched in a second round, not shipped in the prefix.** JFR is self-describing, so a
  field name cannot be inferred from a type name — the fields are whatever the recording declares,
  and a custom event's are unknowable in advance. Sending all of them costs ~9,800 tokens on an
  ordinary recording (measured: 181 event types, 994 fields) and is unbounded on one with custom
  events. Instead the model may answer `FIELDS: <types>` and is sent those types' fields plus the
  types they lead to — one level, which is what makes `sampledThread/javaName` derivable rather
  than guessed. Bounded by `PromptBuilder.MAX_FIELD_REQUEST` types and `MAX_FIELD_ROUNDS` rounds; a
  model that keeps asking is reported, not looped on. `fieldsByName` is the structured field map —
  `fields` is a list of rendered display strings, and reading it yields an empty list with no error.
- **`analyze` is a loop; `ask` is not.** `LlmService.analyze` runs up to `llm.max-steps` moves,
  each one a `QUERY:`, `FIELDS:` or `ANSWER:` line, feeding redacted and truncated rows back
  between them. It uses the **text protocol, not native tool calling** — a deliberate departure
  from the handoff document's §3.1, which expected `completeWithTools` on `LlmBackend`: tool use
  exists on the hosted providers and not on a small local model behind an OpenAI-compatible
  endpoint, so building on it would have made the loop hosted-only and split the SPI. `FIELDS:`
  already proved a text protocol carries a multi-round conversation through every backend
  unchanged. Bounded on two axes (steps and total tokens) because an unbounded loop against a paid
  API loses money quietly, and the remaining step count is in every turn so the model concludes
  rather than being truncated. Each run writes its queries to a `.jfrs` transcript — handoff §3.4
  argues that is the feature, since it converts the loop's non-determinism into something a human
  can re-run.
- **The model never sees raw events.** It composes a query; the shell runs it. Recording size does
  not affect cost. Do not add code paths that feed event data to the model.
- `LanguageReference` strings are the **cached prompt prefix and must stay byte-stable** between
  calls; anything varying in there costs full price every request.
- Recording-derived content is fenced in `<<<RECORDING_DATA ... RECORDING_DATA>>>` markers and the
  system prompt declares it data, never instruction. Thread names and heap strings are
  attacker-controllable when the recording came from someone else.
- Egress redaction reuses the same field-name model as the scrubber in `tools/`.
- **The token ceiling discovers reasoning models rather than listing them.** `llm.max-tokens`
  defaults to 2048, which is right for the answer and wrong for a model that thinks first: it is
  cut off mid-thought and returns no query, having billed the full ceiling. `LlmService` escalates
  to `LlmConfig.MAX_TOKENS_WHEN_THINKING` when a reply stops on `length` without a query, reports
  the raise, and remembers it per model for the session. The signal is the reply's stop reason, not
  the model's name — a name list would be stale within a month. A user-set ceiling is never
  lowered.
- **A candidate query is validated locally before it runs.** `LlmCommands.Host.validateQuery`
  parses it with the same parser that would execute it; on rejection `LlmService` sends the parser's
  own error back and asks for a correction, up to `llm.max-retries` (default 1, capped at 3). This
  is the difference between the feature working and not working on a small local model.
- **Unit tests must never reach a real backend.** `llm-anthropic` and `llm-openai` are both on
  `jfr-shell`'s test runtime classpath, so `LlmCommandsTest` pins `llm.backend` to a non-existent
  id; without that, a machine with `ANTHROPIC_API_KEY` set would make live billable calls during
  the test suite. `llm-openai`'s own tests drive a `com.sun.net.httpserver.HttpServer` bound to
  loopback — a real socket, no provider account.
- **`CommandDispatcher` has two query paths and the LLM host adapter must know both.** With a
  `JfrSelector` it delegates; without one (how the interactive `io.jafar.shell.Shell` builds it) it
  parses and evaluates JfrPath directly. `LlmHostAdapterTest` guards this: an adapter that knows
  only the selector leaves `ask` broken in the interactive shell while every fake-host unit test
  stays green.

For the Anthropic backend both authentication modes are the SDK's job
(`AnthropicOkHttpClient.fromEnv()`): `ANTHROPIC_API_KEY`, or a keyless OAuth profile from
`ant auth login`. Jafar contributes only the diagnostics, because the SDK does not fail fast when
credentials are absent. The OpenAI-compatible backends take a bearer token from `llm.api-key` or the
profile's env vars, and send no `Authorization` header at all when there is none — an empty bearer
breaks several local servers. A loopback `llm.base-url` is probed with `GET /models` so
`llm status` can say "reachable" or "cannot reach" instead of failing at request time.

See [doc/cli/LlmSetup.md](../../doc/cli/LlmSetup.md), [doc/cli/LlmPrivacy.md](../../doc/cli/LlmPrivacy.md), and
[doc/plans/llm-in-the-shell-handoff.md](../../doc/plans/llm-in-the-shell-handoff.md) for the seams left
for the planned agentic mode.
