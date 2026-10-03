package io.jafar.mcp;

import io.jafar.hdump.shell.hdumppath.HdumpPathParser;
import io.jafar.mcp.hdump.HdumpTools;
import io.jafar.mcp.jfr.JfrHelpProvider;
import io.jafar.otlp.shell.otlppath.OtlpPathParser;
import io.jafar.pprof.shell.pprofpath.PprofPathParser;
import io.jafar.shell.core.RequestScope;
import io.jafar.shell.jfrpath.JfrPathParser;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every text surface a Jafar MCP client can read, collected from a live {@link JafarMcpServer}.
 *
 * <p>The server's guidance lives in more places than tool descriptions: help topics, MCP prompts,
 * resources and input-schema property descriptions all name tools, parameters and example queries.
 * A change to any of them can silently strand an agent (a renamed tool turns every skill or help
 * line that still names the old one into confident instructions for a call that fails), so tests
 * that guard the guidance must read all of it — from the same objects a live server registers, not
 * from copies that can drift from the real wiring.
 *
 * <p>Surfaces are tagged with the query language (if any) their examples belong to, so
 * example-extraction tests know which parser must accept them. Prompts and the sessions resource
 * carry no query language: they are scanned only for tool-name references.
 */
final class GuidanceSurfaces {

  /** The query languages the four tool families document, each bound to its real parser. */
  enum QueryLanguage {
    JFRPATH {
      @Override
      void parse(String query) throws Exception {
        JfrPathParser.parse(query);
      }
    },
    HDUMPPATH {
      @Override
      void parse(String query) throws Exception {
        HdumpPathParser.parse(query);
      }
    },
    PPROFPATH {
      @Override
      void parse(String query) throws Exception {
        PprofPathParser.parse(query);
      }
    },
    OTLPPATH {
      @Override
      void parse(String query) throws Exception {
        OtlpPathParser.parse(query);
      }
    };

    /** Parses {@code query}, throwing when the parser rejects it. */
    abstract void parse(String query) throws Exception;

    static QueryLanguage forTool(String toolName) {
      if (toolName.startsWith("jfr_")) return JFRPATH;
      if (toolName.startsWith("hdump_")) return HDUMPPATH;
      if (toolName.startsWith("pprof_")) return PPROFPATH;
      if (toolName.startsWith("otlp_")) return OTLPPATH;
      throw new IllegalArgumentException("no family for tool: " + toolName);
    }
  }

  /** One readable surface: where it came from, its text, and the query language it documents. */
  record Surface(String name, String text, QueryLanguage language) {
    boolean hasLanguage() {
      return language != null;
    }
  }

  private final JafarMcpServer server;
  private final List<McpServerFeatures.SyncToolSpecification> tools;

  GuidanceSurfaces() {
    this.server = new JafarMcpServer();
    this.tools = server.createToolSpecifications();
  }

  List<McpServerFeatures.SyncToolSpecification> tools() {
    return tools;
  }

  /** Names of every registered tool. */
  List<String> toolNames() {
    return tools.stream().map(t -> t.tool().name()).toList();
  }

  /**
   * Every readable surface, in a stable order (tools, help, prompts, resources). Metadata
   * descriptions of prompts and resources are surfaces too: a client's first view of them is the
   * name and description, before any content is fetched.
   */
  List<Surface> surfaces() {
    List<Surface> out = new ArrayList<>();
    for (McpServerFeatures.SyncToolSpecification tool : tools) {
      String name = tool.tool().name();
      out.add(
          new Surface(
              "tool:" + name + " description",
              tool.tool().description(),
              QueryLanguage.forTool(name)));
      out.add(
          new Surface("tool:" + name + " schema", schemaJson(tool), QueryLanguage.forTool(name)));
    }
    out.addAll(jfrHelpSurfaces());
    out.addAll(hdumpHelpSurfaces());
    out.addAll(pprofHelpSurfaces());
    out.addAll(otlpHelpSurfaces());
    out.addAll(promptSurfaces());
    out.addAll(resourceSurfaces());
    return out;
  }

  private static String schemaJson(McpServerFeatures.SyncToolSpecification tool) {
    try {
      return io.modelcontextprotocol.json.McpJsonDefaults.getMapper()
          .writeValueAsString(tool.tool().inputSchema());
    } catch (java.io.IOException e) {
      throw new IllegalStateException("cannot serialize schema of " + tool.tool().name(), e);
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Help-topic surfaces (the same texts the *_help tools and jafar://help/* serve)
  // ─────────────────────────────────────────────────────────────────────────────

  private List<Surface> jfrHelpSurfaces() {
    JfrHelpProvider help = new JfrHelpProvider();
    return List.of(
        new Surface("help:jfr/overview", help.getOverviewHelp(), QueryLanguage.JFRPATH),
        new Surface("help:jfr/filters", help.getFiltersHelp(), QueryLanguage.JFRPATH),
        new Surface("help:jfr/pipeline", help.getPipelineHelp(), QueryLanguage.JFRPATH),
        new Surface("help:jfr/functions", help.getFunctionsHelp(), QueryLanguage.JFRPATH),
        new Surface("help:jfr/examples", help.getExamplesHelp(), QueryLanguage.JFRPATH),
        new Surface("help:jfr/event_types", help.getEventTypesHelp(), QueryLanguage.JFRPATH),
        new Surface("help:jfr/tools", help.getToolsHelp(), QueryLanguage.JFRPATH));
  }

  private List<Surface> hdumpHelpSurfaces() {
    HdumpTools help = privateField(server, "hdumpTools");
    List<Surface> out = new ArrayList<>();
    for (String topic :
        List.of("overview", "roots", "filters", "operators", "examples", "patterns", "tools")) {
      out.add(new Surface("help:hdump/" + topic, help.help(topic), QueryLanguage.HDUMPPATH));
    }
    return out;
  }

  private List<Surface> pprofHelpSurfaces() {
    return List.of(
        new Surface("help:pprof", pprofTools().getPprofHelpText(), QueryLanguage.PPROFPATH));
  }

  private List<Surface> otlpHelpSurfaces() {
    return List.of(new Surface("help:otlp", otlpTools().getOtlpHelpText(), QueryLanguage.OTLPPATH));
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Prompt and resource surfaces (rendered exactly as a client would receive them)
  // ─────────────────────────────────────────────────────────────────────────────

  /** Prompt specifications, in registration order. */
  List<McpServerFeatures.SyncPromptSpecification> promptSpecs() {
    io.jafar.mcp.prompt.JafarPrompts prompts = privateField(server, "jafarPrompts");
    return prompts.createPromptSpecifications();
  }

  /** Resource specifications, in registration order. */
  List<McpServerFeatures.SyncResourceSpecification> resourceSpecs() {
    io.jafar.mcp.resource.JafarResources resources = privateField(server, "jafarResources");
    return resources.createResourceSpecifications();
  }

  /**
   * The fixed client scope the contract is rendered in.
   *
   * <p>Since the server scopes session visibility to the MCP client session (see {@link
   * RequestScope}), surfaces must be read as a caller, not as a raw thread. A single synthetic id
   * keeps the rendered contract deterministic: it is never used to open anything, so every surface
   * renders the fresh-client view.
   */
  private static final String RENDER_SCOPE = "guidance-contract-render";

  /** A real exchange carrying {@link #RENDER_SCOPE}, built the way the runtime does it. */
  private static McpSyncServerExchange renderExchange() {
    return new McpSyncServerExchange(
        new McpAsyncServerExchange(RENDER_SCOPE, null, null, null, McpTransportContext.EMPTY));
  }

  /** Prompt texts, rendered by invoking each prompt handler with empty arguments. */
  Map<String, String> promptTexts() {
    Map<String, String> out = new LinkedHashMap<>();
    var exchange = renderExchange();
    for (var spec : promptSpecs()) {
      String name = spec.prompt().name();
      RequestScope.clear();
      McpSchema.GetPromptResult result =
          spec.promptHandler().apply(exchange, new McpSchema.GetPromptRequest(name, Map.of()));
      StringBuilder text = new StringBuilder();
      for (var message : result.messages()) {
        if (message.content() instanceof McpSchema.TextContent content) {
          text.append(content.text()).append('\n');
        }
      }
      out.put(name, text.toString());
    }
    return out;
  }

  /** Resource contents keyed by URI, rendered by invoking each read handler. */
  Map<String, String> resourceTexts() {
    Map<String, String> out = new LinkedHashMap<>();
    for (var spec : resourceSpecs()) {
      String uri = spec.resource().uri();
      RequestScope.clear();
      McpSchema.ReadResourceResult result =
          spec.readHandler().apply(renderExchange(), new McpSchema.ReadResourceRequest(uri));
      StringBuilder text = new StringBuilder();
      for (var content : result.contents()) {
        if (content instanceof McpSchema.TextResourceContents textContent) {
          text.append(textContent.text()).append('\n');
        }
      }
      out.put(uri, text.toString());
    }
    return out;
  }

  private List<Surface> promptSurfaces() {
    List<Surface> out = new ArrayList<>();
    for (var spec : promptSpecs()) {
      McpSchema.Prompt prompt = spec.prompt();
      StringBuilder meta =
          new StringBuilder(prompt.description() == null ? "" : prompt.description());
      for (McpSchema.PromptArgument arg : prompt.arguments()) {
        meta.append('\n').append(arg.name()).append(": ").append(arg.description());
      }
      out.add(new Surface("prompt:" + prompt.name() + " description", meta.toString(), null));
    }
    promptTexts().forEach((name, text) -> out.add(new Surface("prompt:" + name, text, null)));
    return out;
  }

  private List<Surface> resourceSurfaces() {
    List<Surface> out = new ArrayList<>();
    for (var spec : resourceSpecs()) {
      McpSchema.Resource resource = spec.resource();
      String meta =
          (resource.name() == null ? "" : resource.name())
              + ": "
              + (resource.description() == null ? "" : resource.description());
      out.add(new Surface("resource-meta:" + resource.uri(), meta, null));
    }
    resourceTexts()
        .forEach(
            (uri, text) ->
                out.add(
                    new Surface(
                        "resource:" + uri,
                        text,
                        switch (uri) {
                          case "jafar://help/jfrpath", "jafar://help/tools" ->
                              QueryLanguage.JFRPATH;
                          case "jafar://help/hdumppath" -> QueryLanguage.HDUMPPATH;
                          default -> null;
                        })));
    return out;
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Helpers
  // ─────────────────────────────────────────────────────────────────────────────

  private io.jafar.mcp.pprof.PprofTools pprofTools() {
    return privateField(server, "pprofTools");
  }

  private io.jafar.mcp.otlp.OtlpTools otlpTools() {
    return privateField(server, "otlpTools");
  }

  @SuppressWarnings("unchecked")
  private static <T> T privateField(JafarMcpServer server, String name) {
    try {
      Field field = JafarMcpServer.class.getDeclaredField(name);
      field.setAccessible(true);
      return (T) field.get(server);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("missing field " + name + " on JafarMcpServer", e);
    }
  }
}
