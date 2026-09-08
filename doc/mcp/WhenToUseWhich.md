# Three ways to point an LLM at Jafar

Jafar now offers three AI-assisted surfaces over the same analysis engine. They are not
alternatives to pick between once; they suit different situations, and most people will use more
than one.

| | In-shell `ask` | MCP server | `jafar-perf` plugin |
|---|---|---|---|
| Where the model runs | The shell process | Your MCP client | Claude Code |
| You need | A terminal | An MCP-capable client | Claude Code |
| Auth | API key or OAuth profile | Whatever your client uses | Your Claude Code login, including a subscription |
| Best at | One question, one answer | Multi-step investigation | Guided investigation with methodology |
| Works over SSH on a prod box | Yes | Only if the client is there too | Only if Claude Code is there |
| Works in a script | Yes | Awkward | No |
| Reproducible | The query is printed | The tool calls are in the transcript | The transcript, plus the skills' evidence rules |

## Use the in-shell `ask` when

You are already in `jfr-shell`, on a machine with a recording, and you have a specific question.
It is the shortest path from "I have a recording" to "I have a number", and it teaches you the
query language as it goes because it always prints the query it ran.

It is also the only one of the three that works inside a shell script or over a bare SSH session.

→ [Setup](../cli/LlmSetup.md) · [Tutorial](../cli/AskTutorial.md)

## Use the MCP server when

Your question needs several steps — triage, then drill in, then correlate — and you want the model
to drive. The server exposes 37 tools across JFR, heap dumps, pprof and OTLP, including
`jfr_diagnose` (which runs USE and TSA and merges their findings) and `jfr_compare` (baseline
versus candidate).

It is also the right choice when you want to work on a recording from your own machine using
whatever AI client you already have.

→ [jfr-mcp/README.md](../../jfr-mcp/README.md) · [Tutorial](Tutorial.md)

## Use the `jafar-perf` plugin when

You want the MCP tools *plus* the methodology: which question to ask next, what counts as evidence,
what a finding must contain before it is worth reporting. The plugin ships nine skills and seven
agents, including a `perf-lead` that dispatches specialists and merges their findings.

This is the one to reach for on an open-ended "why is this service slow", and the one that
enforces the reporting discipline — rates not counts, every claim citing its tool call, capability
gaps stated separately from findings.

It is also the answer if you have a **Claude subscription rather than API credits**: Claude Code
uses your subscription, and the plugin gives it the tools.

→ [jbachorik/jafar-perf-box](https://github.com/jbachorik/jafar-perf-box)

## Combining them

They compose, because they share an engine:

- Use `ask` to explore interactively, then hand the recording to the plugin for a full write-up.
- Use `jfr_compare` through the MCP server for a regression check, then `ask` in the shell to drill
  into the frame it named.
- Open a recording and a heap dump in the same MCP session to correlate what is retained with what
  allocated it — the cross-format join only works where both sessions live in one process.

## What none of them do

None will change your code, and none should be trusted without reading what they ran. The in-shell
`ask` prints its query; the MCP tools record their calls; the plugin's skills require every claim
to name the call behind it. That is the common thread, and it is deliberate: an answer you cannot
check is not an answer.
