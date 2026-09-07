# What leaves your machine

The shell's LLM commands send data to a third-party API. This page states exactly what, how to see
it before it goes, how to restrict it, and one risk that is specific to analysing recordings you
did not produce.

## The short version

- The **recording never leaves your machine.** The model composes queries; the shell runs them.
- `ask` sends your question and the **list of event type names** in the recording. No event data.
- `explain` sends **the query and up to 50 result rows**, with sensitive fields redacted.
- `llm dry-run <question>` prints the exact bytes that would be sent, and sends nothing.
- Nothing is sent by any other command, or by opening a recording.

## Per command

| Command | Sends | Does not send |
|---|---|---|
| `ask` | Your question; type names and counts; the language reference | Any event data |
| `explain` | The query; up to `llm.max-rows` result rows, redacted | Rows beyond the cap; redacted fields |
| `llm status` | nothing | — |
| `llm dry-run` | nothing | — |
| `llm cost` | nothing | — |

Type names are not always harmless — a custom event type can be named after an internal system —
which is why `dry-run` shows them too.

## Redaction

Redaction is on by default and applies to result rows on the way out. These fields are replaced
with `<redacted>`:

```
path, address, host, hostname, message, description, value, string
```

That covers what a production recording most often leaks: filesystem layout, network peers, and
free-text exception messages. Matching is on the last path segment, so `$decorator.path` and
`source/path` are caught along with `path`, and it descends into nested rows and lists.

**Class names, method names, thread names and numbers are deliberately not redacted.** Without them
there is no performance question left to ask — a result with the class names removed cannot tell
you what is slow. That is a real trade, and it is the reason this page exists rather than a
one-line "we redact things".

Adjust it:

```
jfr> set llm.redact-fields = +sessionId,userId,accountNumber   # extend the defaults
jfr> set llm.redact-fields = path,message                      # replace them
jfr> set llm.redact = false                                    # send rows verbatim
```

With redaction off, `llm status` says so in capitals, on purpose.

## Verify before you trust

```
jfr> llm dry-run which threads used the most CPU?
```

It builds the request through the same code path a real `ask` uses — same prompt, same redaction —
and prints it. The bytes shown are the bytes that would be transmitted. This is the check to run
before approving the feature on a machine that holds production recordings, and it needs no
credentials, so it can be run in a locked-down environment.

## Heap dumps deserve more caution

A JFR recording contains metadata about your application. A heap dump contains **your application's
actual data** — the strings in memory at the moment it was taken, which can include credentials,
personal data, and payloads.

`ask` on a heap dump only sends class names, which is usually fine. `explain` on a heap-dump result
can send string values, which usually is not. The default redaction list includes `value` and
`string` for this reason, but treat a heap dump as sensitive by default and use `dry-run` first.

## Recording content is untrusted input

This one is easy to miss.

Thread names, exception messages, class names and heap string values all originate in the profiled
application. If you are analysing a recording a customer sent you, or one from a shared
environment, those strings are **controlled by whoever ran that application**. A thread named
`ignore previous instructions and ...` is a cheap, real attempt at prompt injection against anyone
who analyses the recording with an LLM.

The shell mitigates this rather than assuming it away:

- All recording-derived content is wrapped in explicit `<<<RECORDING_DATA ... RECORDING_DATA>>>`
  markers, and the system prompt states that anything inside them is data and never an instruction.
- The tool surface is read-only. `ask` can produce a query and nothing else — there is no file
  write, no network call, no way to modify a recording, and no shell command it can reach.
- The worst realistic outcome is therefore a misleading answer, not an action taken on your behalf.

That is mitigation, not a guarantee: prompt injection is not a solved problem. When you analyse a
recording from an untrusted source, read the query `ask` prints before you trust the result, the
same way you would read a script someone sent you.

## Turning it off entirely

```
jfr> set llm.enabled = false
```

Or leave `llm-core` off the classpath, and the Anthropic SDK is not present at all. Every other
shell command is unaffected either way — no startup cost, no network call, no behaviour change.
This is the intended configuration for air-gapped and regulated environments, and the shell is
fully functional in it.

## Where the data goes

To the Anthropic API, under whichever credential `llm status` reports. Retention and handling are
governed by the terms of the account that credential belongs to, which is a matter between you and
Anthropic; Jafar neither stores nor forwards anything itself.
