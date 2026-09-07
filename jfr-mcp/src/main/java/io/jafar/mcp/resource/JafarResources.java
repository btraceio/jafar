package io.jafar.mcp.resource;

import io.jafar.mcp.hdump.HdumpTools;
import io.jafar.mcp.jfr.JfrHelpProvider;
import io.jafar.mcp.session.HeapSessionRegistry;
import io.jafar.mcp.session.OtlpSessionRegistry;
import io.jafar.mcp.session.PprofSessionRegistry;
import io.jafar.mcp.session.SessionRegistry;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;

/**
 * MCP resources: readable context a client can pull in without spending a tool call.
 *
 * <p>Two kinds are exposed. {@code jafar://sessions} is live state — which recordings and dumps are
 * currently open, and under which ids and aliases — which a client otherwise has to reconstruct
 * from the results of earlier {@code *_open} calls. The {@code jafar://help/*} resources are the
 * query-language references that were previously reachable only through the {@code *_help} tools,
 * so a client can attach the syntax it needs (in Claude Code, via an {@code @} mention) instead of
 * guessing and burning a turn on a parse error.
 */
public final class JafarResources {

  private static final String MIME_MARKDOWN = "text/markdown";
  private static final String MIME_TEXT = "text/plain";

  private final SessionRegistry jfrSessions;
  private final HeapSessionRegistry heapSessions;
  private final PprofSessionRegistry pprofSessions;
  private final OtlpSessionRegistry otlpSessions;
  private final JfrHelpProvider jfrHelp;
  private final HdumpTools hdumpTools;

  public JafarResources(
      SessionRegistry jfrSessions,
      HeapSessionRegistry heapSessions,
      PprofSessionRegistry pprofSessions,
      OtlpSessionRegistry otlpSessions,
      JfrHelpProvider jfrHelp,
      HdumpTools hdumpTools) {
    this.jfrSessions = jfrSessions;
    this.heapSessions = heapSessions;
    this.pprofSessions = pprofSessions;
    this.otlpSessions = otlpSessions;
    this.jfrHelp = jfrHelp;
    this.hdumpTools = hdumpTools;
  }

  /** All resource specifications offered by the server. */
  public List<McpServerFeatures.SyncResourceSpecification> createResourceSpecifications() {
    List<McpServerFeatures.SyncResourceSpecification> resources = new ArrayList<>();

    resources.add(
        resource(
            "jafar://sessions",
            "Open sessions",
            "Recordings, heap dumps and profiles currently open, with their ids and aliases.",
            MIME_MARKDOWN,
            this::renderSessions));

    resources.add(
        resource(
            "jafar://help/jfrpath",
            "JfrPath reference",
            "Query language for jfr_query: roots, filters, units, pipeline operators and"
                + " correlation.",
            MIME_MARKDOWN,
            () ->
                String.join(
                    "\n\n",
                    jfrHelp.getOverviewHelp(),
                    jfrHelp.getFiltersHelp(),
                    jfrHelp.getPipelineHelp(),
                    jfrHelp.getFunctionsHelp(),
                    jfrHelp.getExamplesHelp())));

    resources.add(
        resource(
            "jafar://help/hdumppath",
            "HdumpPath reference",
            "Query language for hdump_query: roots, predicates and heap analysis operators.",
            MIME_MARKDOWN,
            () -> hdumpTools.help("overview")));

    resources.add(
        resource(
            "jafar://help/tools",
            "Choosing the right tool",
            "Which analysis tool answers which question, and when to prefer one over another.",
            MIME_MARKDOWN,
            jfrHelp::getToolsHelp));

    return resources;
  }

  private McpServerFeatures.SyncResourceSpecification resource(
      String uri, String name, String description, String mimeType, TextSupplier supplier) {
    McpSchema.Resource resource =
        McpSchema.Resource.builder()
            .uri(uri)
            .name(name)
            .description(description)
            .mimeType(mimeType)
            .build();

    return new McpServerFeatures.SyncResourceSpecification(
        resource,
        (exchange, request) ->
            new McpSchema.ReadResourceResult(
                List.of(
                    new McpSchema.TextResourceContents(request.uri(), mimeType, supplier.get()))));
  }

  private String renderSessions() {
    StringBuilder out = new StringBuilder("# Open sessions\n");

    appendSection(
        out,
        "JFR recordings",
        jfrSessions.list().stream()
            .map(info -> describe(info.id(), info.alias(), info.recordingPath().toString()))
            .toList());

    appendSection(
        out,
        "Heap dumps",
        heapSessions.list().stream()
            .map(info -> describe(info.id(), info.alias(), info.path().toString()))
            .toList());

    appendSection(
        out,
        "pprof profiles",
        pprofSessions.list().stream()
            .map(info -> describe(info.id(), info.alias(), info.path().toString()))
            .toList());

    appendSection(
        out,
        "OTLP profiles",
        otlpSessions.list().stream()
            .map(info -> describe(info.id(), info.alias(), info.path().toString()))
            .toList());

    out.append(
        "\nA session id or alias can be passed as `sessionId` to any tool of the matching"
            + " family, and named in cross-session operators such as"
            + " `join(session=...)`.\n");
    return out.toString();
  }

  private static void appendSection(StringBuilder out, String title, List<String> entries) {
    out.append("\n## ").append(title).append('\n');
    if (entries.isEmpty()) {
      out.append("_none open_\n");
      return;
    }
    for (String entry : entries) {
      out.append("- ").append(entry).append('\n');
    }
  }

  private static String describe(int id, String alias, String path) {
    return alias == null || alias.isBlank()
        ? "id `" + id + "` — " + path
        : "id `" + id + "` (alias `" + alias + "`) — " + path;
  }

  @FunctionalInterface
  private interface TextSupplier {
    String get();
  }
}
