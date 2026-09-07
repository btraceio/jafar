package io.jafar.mcp.prompt;

import io.jafar.mcp.session.HeapSessionRegistry;
import io.jafar.mcp.session.SessionRegistry;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MCP prompts: reusable analysis playbooks the server offers to any client.
 *
 * <p>The tools tell a client <em>what</em> it can call; they do not say in which order, or what the
 * numbers mean. That methodology used to live only in the {@code *_help} tools, which a client has
 * to know to call. Exposing it as MCP prompts puts it where clients surface it — in Claude Code
 * these appear as {@code /mcp__jafar__<name>} slash commands — so the guidance reaches every MCP
 * client, not only ones bundled with a plugin.
 *
 * <p>Prompts are text, deliberately: they instruct the model which tools to call and how to read
 * the results, rather than executing anything themselves.
 */
public final class JafarPrompts {

  private final SessionRegistry jfrSessions;
  private final HeapSessionRegistry heapSessions;

  public JafarPrompts(SessionRegistry jfrSessions, HeapSessionRegistry heapSessions) {
    this.jfrSessions = jfrSessions;
    this.heapSessions = heapSessions;
  }

  /** All prompt specifications offered by the server. */
  public List<McpServerFeatures.SyncPromptSpecification> createPromptSpecifications() {
    List<McpServerFeatures.SyncPromptSpecification> prompts = new ArrayList<>();
    prompts.add(triage());
    prompts.add(compare());
    prompts.add(leakHunt());
    prompts.add(latency());
    return prompts;
  }

  private McpServerFeatures.SyncPromptSpecification triage() {
    McpSchema.Prompt prompt =
        new McpSchema.Prompt(
            "triage",
            "Triage a recording",
            "Establish what an unfamiliar JFR recording, profile or heap dump contains and which"
                + " investigation to run next.",
            List.of(
                new McpSchema.PromptArgument(
                    "path", "Absolute path to the artifact to analyse", false)));

    return new McpServerFeatures.SyncPromptSpecification(
        prompt,
        (exchange, request) -> {
          String path = argument(request, "path");
          String text =
              """
              Triage %s.

              1. Open it with the tool matching its type: jfr_open for .jfr, hdump_open for \
              .hprof, pprof_open for .pprof/.pb.gz, otlp_open for .otlp.
              2. Run the summary tool (jfr_summary / hdump_summary / ...) and read the event or \
              object mix before forming any hypothesis.
              3. For JFR, run jfr_diagnose. It returns severity-ranked findings, the analyses it \
              ran, and capabilityGaps. Read capabilityGaps first: a negative result about \
              something the recording never captured is not a negative result.
              4. Establish the recording's duration and convert every count you plan to quote \
              into a rate. Absolute counts across recordings of different lengths are not \
              comparable.
              5. Route to the specific investigation the findings point at — CPU, latency and \
              contention, GC and allocation, or heap retention — rather than running everything.

              Report findings ranked by impact. For each one give the tool call that produced it, \
              the numbers, your interpretation, and a confidence level. Do not quote a number you \
              did not measure in this session, and label sampled data as sampled.
              """
                  .formatted(path == null ? "the recording" : path);
          return new McpSchema.GetPromptResult(
              "Triage playbook",
              List.of(
                  new McpSchema.PromptMessage(
                      McpSchema.Role.USER, new McpSchema.TextContent(text))));
        });
  }

  private McpServerFeatures.SyncPromptSpecification compare() {
    McpSchema.Prompt prompt =
        new McpSchema.Prompt(
            "compare",
            "Compare two recordings",
            "Decide whether a candidate recording regressed against a baseline, and attribute the"
                + " change.",
            List.of(
                new McpSchema.PromptArgument("baseline", "Path to the baseline recording", false),
                new McpSchema.PromptArgument(
                    "candidate", "Path to the candidate recording", false)));

    return new McpServerFeatures.SyncPromptSpecification(
        prompt,
        (exchange, request) -> {
          String baseline = argument(request, "baseline");
          String candidate = argument(request, "candidate");
          String text =
              """
              Compare %s (baseline) against %s (candidate).

              1. jfr_open both, giving each a clear alias.
              2. Call jfr_compare with baselineSessionId and candidateSessionId.
              3. Read the `comparability` block before anything else. If it reports different \
              execution-sample event types, very different durations, or low sample counts, say \
              so in your answer and temper every conclusion accordingly.
              4. Treat `frames` as shares of samples, not wall-clock time. A frame growing from \
              3%% to 9%% of samples means the profile shifted; it is evidence of a slowdown only \
              together with a rate or duration change.
              5. Changes below `minDeltaPct` are withheld deliberately. Do not go looking for \
              smaller ones and present them as findings.

              Conclude with either a named regression and the frame or metric that carries it, or \
              an explicit "no regression above the noise floor". Never claim an improvement \
              without the measurement that shows it.
              """
                  .formatted(
                      baseline == null ? "the baseline" : baseline,
                      candidate == null ? "the candidate" : candidate);
          return new McpSchema.GetPromptResult(
              "Regression comparison playbook",
              List.of(
                  new McpSchema.PromptMessage(
                      McpSchema.Role.USER, new McpSchema.TextContent(text))));
        });
  }

  private McpServerFeatures.SyncPromptSpecification leakHunt() {
    McpSchema.Prompt prompt =
        new McpSchema.Prompt(
            "leak-hunt",
            "Hunt a memory leak",
            "Find unintended retention in a heap dump, and attribute it to the code that allocated"
                + " it.",
            List.of(
                new McpSchema.PromptArgument("dump", "Path to the .hprof heap dump", false),
                new McpSchema.PromptArgument(
                    "recording", "Optional JFR recording from the same interval", false)));

    return new McpServerFeatures.SyncPromptSpecification(
        prompt,
        (exchange, request) -> {
          String dump = argument(request, "dump");
          String recording = argument(request, "recording");
          StringBuilder text = new StringBuilder();
          text.append(
              """
              Hunt for a memory leak in %s.

              1. hdump_open, then hdump_summary to orient.
              2. hdump_report focus=leaks. Work the findings from highest severity with the \
              largest retainedSize.
              3. Rank by retained size, not shallow size: hdump_query "classes | \
              sortBy(retained desc) | top(20)". A large char[] or byte[] population is normal; \
              its dominator is the finding.
              4. Try the named detectors for known patterns (threadlocal-leak, classloader-leak, \
              growing-collections, listener-leak, duplicate-strings, finalizer-queue), then \
              `clusters` for patterns nobody wrote a detector for.
              5. Prove retention with a path to a GC root — pathToRoot() per object, or \
              retentionPaths() merged at class level. A leak claim without a root path is a \
              guess. The field named in that path is the fix.
              """
                  .formatted(dump == null ? "the heap dump" : dump));
          if (recording != null) {
            text.append(
                """

                6. Open %s with jfr_open and correlate retention with allocation:
                   hdump_query "classes | join(session=<jfr alias>, \
                root=\\"jdk.ObjectAllocationSample\\", by=class) | filter(retained > 10MB) | \
                select(name, retained, allocCount, topAllocSite)"
                   topAllocSite names the code that created the retained objects. High \
                allocCount with low retained is churn, not a leak.
                """
                    .formatted(recording));
          }
          text.append(
              """

              Distinguish a leak from intended retention: a cache that is configured to be large \
              is working as designed, and the finding is then about its sizing, not a bug.
              """);
          return new McpSchema.GetPromptResult(
              "Leak hunt playbook",
              List.of(
                  new McpSchema.PromptMessage(
                      McpSchema.Role.USER, new McpSchema.TextContent(text.toString()))));
        });
  }

  private McpServerFeatures.SyncPromptSpecification latency() {
    McpSchema.Prompt prompt =
        new McpSchema.Prompt(
            "latency",
            "Investigate latency",
            "Investigate response-time problems that are not CPU-bound: contention, parking, queue"
                + " saturation and blocking I/O.",
            List.of(new McpSchema.PromptArgument("path", "Path to the JFR recording", false)));

    return new McpServerFeatures.SyncPromptSpecification(
        prompt,
        (exchange, request) -> {
          String path = argument(request, "path");
          String text =
              """
              Investigate latency in %s.

              Latency is usually waiting, and waiting produces no execution samples — so a healthy
              flamegraph proves nothing here.

              1. jfr_tsa with correlateBlocking=true. Read stateDistribution first: if most time \
              is RUNNABLE this is a CPU problem, and you should switch to hot-method analysis.
              2. jfr_use resources=all. Look at insights.bottlenecks, and treat queue_saturation \
              as first-class: work waiting in an executor queue cannot be recovered by making \
              methods faster.
              3. Separate jdk.JavaMonitorEnter (blocked acquiring) from jdk.JavaMonitorWait \
              (waiting on a condition) — they mean different things. Rank monitors by summed \
              duration relative to the recording's wall clock, never by event count.
              4. For the code that contends, correlate samples with the wait window on the same \
              thread using decorateByTime(jdk.JavaMonitorWait, fields=monitorClass,duration).
              5. Check jdk.ThreadPark grouped by parked class. A pool parked on its own queue is \
              idle and healthy; a request thread parked on a future or a connection pool is the \
              bug.

              Remember JFR's monitor events have a duration threshold, so absence of events is \
              not absence of contention. Report time-overlap correlations as "concurrent with", \
              never as proof of cause.
              """
                  .formatted(path == null ? "the recording" : path);
          return new McpSchema.GetPromptResult(
              "Latency playbook",
              List.of(
                  new McpSchema.PromptMessage(
                      McpSchema.Role.USER, new McpSchema.TextContent(text))));
        });
  }

  private static String argument(McpSchema.GetPromptRequest request, String name) {
    Map<String, Object> arguments = request.arguments();
    if (arguments == null) {
      return null;
    }
    Object value = arguments.get(name);
    return value == null ? null : String.valueOf(value);
  }
}
