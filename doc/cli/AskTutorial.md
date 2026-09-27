# Asking a recording a question

This tutorial is about `as-query`, and about the fact that `as-query` is a JfrPath teacher rather
than a JfrPath replacement. Its sibling `ask` — `?` for short — investigates over several queries
and is covered in [LLM setup](LlmSetup.md#ask--more-than-one-query).

Prerequisite: [LLM setup](LlmSetup.md), and `llm status` reporting READY.

Available in `jfr-shell` (JFR recordings) and in the unified `jafar-shell` (JFR recordings, heap
dumps, pprof and OTLP profiles). Note that `jafar-shell` has no `set` command yet, so configure it
there with the `JAFAR_LLM_*` environment variables.

## The first question

```
$ jfr-shell recording.jfr
jfr> as-query which threads used the most CPU?
```

```
# Groups execution samples by thread name and ranks the ten busiest.

events/jdk.ExecutionSample | groupBy(sampledThread/javaName) | top(10, by=count)

key                          count
---------------------------  -----
http-nio-8080-exec-7          4821
http-nio-8080-exec-3          4402
C2 CompilerThread0            1180
...

[llm: 412 in, 96 out, 8104 cached]
```

Three things happened, in this order, and the order is the design:

1. The model saw your question and the recording's **type inventory** — the names of the event
   types present. It did not see any event data.
2. It answered with a query and a one-line rationale, printed as the `#` comment.
3. The shell ran the query locally and rendered the result the way any other command would.

## Why the query is always printed

Because you should be able to check it, and because you will learn it.

The model is guessing at your intent from one sentence. Printing the query makes a wrong guess
obvious — if you asked about *wall-clock* time and the query counts *samples*, you can see that
immediately rather than acting on a plausible number. Hiding the query would trade a small amount
of convenience for the ability to be confidently wrong.

The side effect is the more valuable one. After a dozen questions you will have seen `groupBy`,
`top(n, by=...)`, `stats`, and the bracket-filter syntax in context, applied to your own
recordings. That is a better JfrPath tutorial than [the reference](JFRPath.md), because every
example is one you asked for.

If you want the query without running it:

```
jfr> set llm.confirm = true
jfr> as-query how long were the GC pauses?
```

## Following up

`explain` describes the result you just looked at:

```
jfr> events/jdk.GCPhasePause | stats(duration)
jfr> explain
```

`explain` is the one command that sends result data, so it is the one where redaction applies. It
sends at most `llm.max-rows` rows (50 by default) and tells the model when it truncated, so the
answer is not built on a silent sample. See [what leaves your machine](LlmPrivacy.md).

## When the recording cannot answer

A good answer is sometimes "you did not record that":

```
jfr> as-query which methods allocate the most?
```

```
# Allocation profiling was not enabled in this recording, so allocation cannot be assessed.
  Re-record with -XX:StartFlightRecording:settings=profile.

The model reports this recording cannot answer that question. Nothing was run.
```

The prompt tells the model to say this rather than guess, because a query against an event type
that is not there returns nothing, and "no results" reads like "no problem" — which is the wrong
conclusion and an easy one to draw.

## Working across formats

`as-query` follows the current session and uses the query language that session needs — JfrPath for
recordings, HdumpPath for heap dumps, the samples language for pprof and OTLP profiles.

**Which shell you are in matters here.** `jfr-shell` only opens JFR recordings, so `as-query` there
is always JfrPath. The unified `jafar-shell` opens all four formats, and that is where it reaches
the other languages:

```
$ jafar-shell
jafar> open heap.hprof
hdump> as-query what is holding the most memory?
```

```
# Ranks classes by retained size, which is what leak size is measured in.

classes | sortBy(retained desc) | top(20)
```

Note it reached for **retained** rather than shallow size. That distinction decides most heap
investigations, and it is in the reference the model is given.

## Questions that work well, and ones that do not

Well:

- "which threads used the most CPU" — a clear aggregation over a known type
- "how long were the GC pauses" — names the concept, lets the model pick the type
- "which files were read most often" — a group-and-rank
- "show me monitor contention by class" — names the shape of the answer

Less well:

- "why is my app slow?" — too open for a single query, so use `ask` (or `?`) instead: it runs
  several, reads each result, and concludes. `jfr_diagnose` through the MCP server and the
  `perf-lead` agent from the [plugin](https://github.com/btraceio/jafar-perf-box) do the same from
  outside the shell.
- "is this normal?" — nothing in the recording says what normal is. Compare two recordings instead.
- "fix the regression" — these commands compose queries; they do not change code.

## What it costs

The recording never leaves your machine, so recording size does not affect cost. The language
reference dominates each request and is cached after the first call — the `cached` figure in the
usage line is that working. A typical `as-query` is a few hundred uncached tokens.

```
jfr> llm cost
requests : 4
tokens   : 1608 in, 402 out, 32416 cached
```

## Next

- [What leaves your machine](LlmPrivacy.md)
- [JfrPath reference](JFRPath.md) — for when you want the language properly
- [Scripting](Scripting.md) — `as-query` is interactive; scripts should carry the real query, so that
  they are reproducible
