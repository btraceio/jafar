package io.jafar.mcp.otlp;

import io.jafar.mcp.result.McpResultFactory;
import io.jafar.mcp.session.OtlpSessionRegistry;
import io.jafar.mcp.tool.ProgressReporter;
import io.jafar.mcp.validation.FieldNameValidator;
import io.jafar.mcp.validation.FileValidator;
import io.jafar.otlp.shell.OtlpSession;
import io.jafar.otlp.shell.otlppath.OtlpPathEvaluator;
import io.jafar.otlp.shell.otlppath.OtlpPathParseException;
import io.jafar.otlp.shell.otlppath.OtlpPathParser;
import io.jafar.shell.core.sampling.SamplingSessionRegistry;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** MCP tool implementations for OpenTelemetry profile analysis. */
public final class OtlpTools {

  private static final Logger LOG = LoggerFactory.getLogger(OtlpTools.class);

  private final OtlpSessionRegistry otlpSessionRegistry;
  private final McpResultFactory resultFactory;
  private final ProgressReporter progressReporter;

  public OtlpTools(
      OtlpSessionRegistry otlpSessionRegistry,
      McpResultFactory resultFactory,
      ProgressReporter progressReporter) {
    this.otlpSessionRegistry = otlpSessionRegistry;
    this.resultFactory = resultFactory;
    this.progressReporter = progressReporter;
  }

  private static Tool buildTool(String name, String description, String schema) {
    return Tool.builder()
        .name(name)
        .description(description)
        .inputSchema(McpJsonDefaults.getMapper(), schema)
        .build();
  }

  private CallToolResult successResult(Map<String, Object> data) {
    return resultFactory.success(data);
  }

  private CallToolResult errorResult(String message) {
    return resultFactory.error(message);
  }

  private Object progressToken(McpSchema.CallToolRequest request) {
    return progressReporter.progressToken(request);
  }

  private void sendProgress(
      McpSyncServerExchange exchange,
      Object progressToken,
      double progress,
      double total,
      String message) {
    progressReporter.send(exchange, progressToken, progress, total, message);
  }

  private static String requireSafeFieldName(String name, String paramName) {
    return FieldNameValidator.requireSafeFieldName(name, paramName);
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // otlp_open
  // ─────────────────────────────────────────────────────────────────────────────

  public McpServerFeatures.SyncToolSpecification createOtlpOpenTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "path": {
              "type": "string",
              "description": "Absolute path to the OTLP profiles file (.otlp)"
            },
            "alias": {
              "type": "string",
              "description": "Optional alias for the session"
            }
          },
          "required": ["path"]
        }
        """;
    return new McpServerFeatures.SyncToolSpecification(
        buildTool(
            "otlp_open",
            "Opens an OpenTelemetry profiling file (.otlp) for analysis. "
                + "Returns a session ID and profile metadata (sample type, duration, counts). "
                + "Use the returned session ID with other otlp_* tools.",
            schema),
        (exchange, args) -> handleOtlpOpen(args.arguments()));
  }

  public CallToolResult handleOtlpOpen(Map<String, Object> args) {
    String path = (String) args.get("path");
    String alias = (String) args.get("alias");

    if (path == null || path.isBlank()) {
      return errorResult("path is required");
    }

    try {
      Path profilePath = Path.of(path);

      String fileError = FileValidator.readableRegularFileError(profilePath, path);
      if (fileError != null) {
        return errorResult(fileError);
      }

      var info = otlpSessionRegistry.open(profilePath, alias);
      LOG.info("Opened OTLP profile {} as session {}", path, info.id());

      Map<String, Object> result = info.toMap();
      result.put("message", "OTLP profile opened successfully");
      return successResult(result);

    } catch (Exception e) {
      LOG.error("Failed to open OTLP profile: {}", e.getMessage(), e);
      return errorResult("Failed to open OTLP profile: " + e.getMessage());
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // otlp_close
  // ─────────────────────────────────────────────────────────────────────────────

  public McpServerFeatures.SyncToolSpecification createOtlpCloseTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "sessionId": {
              "type": "string",
              "description": "Session ID or alias to close. Omit to close the current session."
            }
          }
        }
        """;
    return new McpServerFeatures.SyncToolSpecification(
        buildTool("otlp_close", "Closes an otlp session and releases its resources.", schema),
        (exchange, args) -> handleOtlpClose(args.arguments()));
  }

  public CallToolResult handleOtlpClose(Map<String, Object> args) {
    String sessionId = (String) args.get("sessionId");

    try {
      var info = otlpSessionRegistry.getOrCurrent(sessionId);
      otlpSessionRegistry.close(String.valueOf(info.id()));

      Map<String, Object> result = new LinkedHashMap<>();
      result.put("closed", info.id());
      result.put("path", info.path().toString());
      result.put("message", "Session closed");
      return successResult(result);

    } catch (IllegalArgumentException e) {
      return errorResult(e.getMessage());
    } catch (Exception e) {
      LOG.error("Failed to close otlp session: {}", e.getMessage(), e);
      return errorResult("Failed to close session: " + e.getMessage());
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // otlp_query
  // ─────────────────────────────────────────────────────────────────────────────

  public McpServerFeatures.SyncToolSpecification createOtlpQueryTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "query": {
              "type": "string",
              "description": "OtlpPath query string, e.g. 'samples | top(10, cpu)'"
            },
            "sessionId": {
              "type": "string",
              "description": "Session ID or alias. Omit to use the current session."
            },
            "limit": {
              "type": "integer",
              "description": "Maximum number of results to return (default: 100)"
            }
          },
          "required": ["query"]
        }
        """;
    return new McpServerFeatures.SyncToolSpecification(
        buildTool(
            "otlp_query",
            "Executes an OtlpPath query against an open OTLP profile. "
                + "Query syntax: 'samples[predicate] | operator(args)'. "
                + "Examples: 'samples | count()', 'samples | top(10, cpu)', "
                + "'samples | groupBy(thread, sum(cpu))', 'samples[thread=\\'main\\'] | head(5)'. "
                + "Use otlp_help for full query language reference.",
            schema),
        (exchange, args) -> handleOtlpQuery(args.arguments()));
  }

  public CallToolResult handleOtlpQuery(Map<String, Object> args) {
    String queryStr = (String) args.get("query");
    String sessionId = (String) args.get("sessionId");
    int limit = args.get("limit") instanceof Number n ? n.intValue() : 100;

    if (queryStr == null || queryStr.isBlank()) {
      return errorResult("query is required");
    }
    if (limit <= 0) {
      return errorResult("limit must be positive");
    }

    try {
      var info = otlpSessionRegistry.getOrCurrent(sessionId);
      var query = OtlpPathParser.parse(queryStr);
      List<Map<String, Object>> rows = OtlpPathEvaluator.evaluate(info.session(), query);

      boolean truncated = rows.size() > limit;
      if (truncated) {
        rows = rows.subList(0, limit);
      }

      Map<String, Object> response = new LinkedHashMap<>();
      response.put("query", queryStr);
      response.put("sessionId", info.id());
      response.put("resultCount", rows.size());
      response.put("results", rows);
      if (truncated) {
        response.put("truncated", true);
        response.put(
            "message", "Results truncated to " + limit + ". Use 'limit' parameter for more.");
      }
      return successResult(response);

    } catch (OtlpPathParseException e) {
      LOG.warn("OtlpPath query parse error: {}", e.getMessage());
      return errorResult("Query parse error: " + e.getMessage());
    } catch (IllegalArgumentException e) {
      LOG.warn("OtlpPath query error: {}", e.getMessage());
      return errorResult(e.getMessage());
    } catch (Exception e) {
      LOG.error("Failed to execute otlp query: {}", e.getMessage(), e);
      return errorResult("Failed to execute query: " + e.getMessage());
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // otlp_summary
  // ─────────────────────────────────────────────────────────────────────────────

  public McpServerFeatures.SyncToolSpecification createOtlpSummaryTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "sessionId": {
              "type": "string",
              "description": "Session ID or alias. Omit to use the current session."
            }
          }
        }
        """;
    return new McpServerFeatures.SyncToolSpecification(
        buildTool(
            "otlp_summary",
            "Returns a quick overview of an OTLP profile: sample type, counts, duration, "
                + "and top functions by the primary value type.",
            schema),
        (exchange, args) -> handleOtlpSummary(args.arguments()));
  }

  public CallToolResult handleOtlpSummary(Map<String, Object> args) {
    String sessionId = (String) args.get("sessionId");

    try {
      var info = otlpSessionRegistry.getOrCurrent(sessionId);
      var data = info.session().getData();

      Map<String, Object> response = new LinkedHashMap<>();
      response.put("sessionId", info.id());
      response.put("path", info.path().toString());
      response.putAll(info.session().getStatistics());

      // Top functions by first profile's sample type
      if (!data.profiles().isEmpty()) {
        var firstProfile = data.profiles().get(0);
        var sampleType = firstProfile.sampleType();
        if (sampleType != null && !sampleType.type().isEmpty()) {
          try {
            String stType = requireSafeFieldName(sampleType.type(), "sampleType");
            var query = OtlpPathParser.parse("samples | top(10, " + stType + ")");
            List<Map<String, Object>> topFns = OtlpPathEvaluator.evaluate(info.session(), query);
            response.put("topFunctions", topFns);
          } catch (Exception e) {
            LOG.debug("Could not compute top functions: {}", e.getMessage());
          }
        }
      }

      return successResult(response);

    } catch (IllegalArgumentException e) {
      return errorResult(e.getMessage());
    } catch (Exception e) {
      LOG.error("Failed to get otlp summary: {}", e.getMessage(), e);
      return errorResult("Failed to get summary: " + e.getMessage());
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // otlp_flamegraph
  // ─────────────────────────────────────────────────────────────────────────────

  public McpServerFeatures.SyncToolSpecification createOtlpFlamegraphTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "sessionId": {
              "type": "string",
              "description": "Session ID or alias. Omit to use the current session."
            },
            "valueField": {
              "type": "string",
              "description": "Value type to aggregate (e.g. 'cpu'). Defaults to first sample type."
            },
            "filter": {
              "type": "string",
              "description": "Optional filter predicate, e.g. \\"thread='main'\\""
            },
            "limit": {
              "type": "integer",
              "description": "Maximum number of stack entries to return (default: 50)"
            }
          }
        }
        """;
    return new McpServerFeatures.SyncToolSpecification(
        buildTool(
            "otlp_flamegraph",
            "Produces folded stack profile data suitable for flame graph visualization. "
                + "Returns rows of {stack: 'root;parent;leaf', <valueField>: N} sorted by value descending. "
                + "Stack frames are separated by ';' with root frame first.",
            schema),
        (exchange, args) -> handleOtlpFlamegraph(args.arguments()));
  }

  public CallToolResult handleOtlpFlamegraph(Map<String, Object> args) {
    String sessionId = (String) args.get("sessionId");
    String valueField = (String) args.get("valueField");
    String filter = (String) args.get("filter");
    int limit = args.get("limit") instanceof Number n ? n.intValue() : 50;

    try {
      var info = otlpSessionRegistry.getOrCurrent(sessionId);

      // Resolve default value field from first profile's sample type
      String effectiveValue = valueField;
      if (effectiveValue == null || effectiveValue.isBlank()) {
        var data = info.session().getData();
        if (!data.profiles().isEmpty()) {
          var st = data.profiles().get(0).sampleType();
          if (st != null && !st.type().isEmpty()) {
            effectiveValue = st.type();
          }
        }
      }

      if (effectiveValue == null) {
        return errorResult("No sample types found in profile");
      }

      requireSafeFieldName(effectiveValue, "valueField");

      if (filter != null && filter.contains("]")) {
        return errorResult("Invalid filter: unexpected ']' in filter expression");
      }

      String queryStr =
          "samples"
              + (filter != null && !filter.isBlank() ? "[" + filter + "]" : "")
              + " | stackprofile("
              + effectiveValue
              + ")";

      var query = OtlpPathParser.parse(queryStr);
      List<Map<String, Object>> rows = OtlpPathEvaluator.evaluate(info.session(), query);

      boolean truncated = rows.size() > limit;
      if (truncated) {
        rows = rows.subList(0, limit);
      }

      final String finalValue = effectiveValue;
      long total =
          rows.stream()
              .mapToLong(r -> r.get(finalValue) instanceof Number n ? n.longValue() : 0L)
              .sum();

      Map<String, Object> response = new LinkedHashMap<>();
      response.put("sessionId", info.id());
      response.put("valueField", effectiveValue);
      response.put("total", total);
      response.put("rowCount", rows.size());
      response.put("rows", rows);
      if (truncated) {
        response.put("truncated", true);
      }
      return successResult(response);

    } catch (OtlpPathParseException e) {
      return errorResult("Query parse error: " + e.getMessage());
    } catch (IllegalArgumentException e) {
      return errorResult(e.getMessage());
    } catch (Exception e) {
      LOG.error("Failed to generate otlp flamegraph: {}", e.getMessage(), e);
      return errorResult("Failed to generate flamegraph: " + e.getMessage());
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // otlp_use - USE Method Analysis (Utilization, Saturation, Errors)
  // ─────────────────────────────────────────────────────────────────────────────

  public McpServerFeatures.SyncToolSpecification createOtlpUseTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "sessionId": {
              "type": "string",
              "description": "Session ID or alias. Omit to use the current session."
            },
            "resources": {
              "type": "array",
              "items": { "type": "string", "enum": ["cpu", "threads", "errors"] },
              "description": "Resources to analyze. Defaults to all: cpu, threads, errors."
            }
          }
        }
        """;
    return new McpServerFeatures.SyncToolSpecification(
        buildTool(
            "otlp_use",
            "Analyzes an OTLP profile using Brendan Gregg's USE Method (Utilization, Saturation, Errors). "
                + "CPU: utilization stats and top consuming functions. "
                + "Threads: distribution across threads to identify serial bottlenecks. "
                + "Errors: heuristic scan of hot function names for error-related patterns.",
            schema),
        (exchange, args) -> handleOtlpUse(exchange, args.arguments(), progressToken(args)));
  }

  @SuppressWarnings("unchecked")
  public CallToolResult handleOtlpUse(
      McpSyncServerExchange exchange, Map<String, Object> args, Object progressToken) {
    String sessionId = (String) args.get("sessionId");
    List<String> resourcesList =
        args.get("resources") instanceof List<?> l ? (List<String>) l : List.of("all");
    Set<String> resources =
        resourcesList.contains("all")
            ? Set.of("cpu", "threads", "errors")
            : Set.copyOf(resourcesList);

    try {
      var info = otlpSessionRegistry.getOrCurrent(sessionId);
      var data = info.session().getData();
      String defaultType =
          (!data.profiles().isEmpty() && data.profiles().get(0).sampleType() != null)
              ? data.profiles().get(0).sampleType().type()
              : null;

      Map<String, Object> result = new LinkedHashMap<>();
      result.put("method", "USE");
      result.put("profilePath", info.path().toString());

      Map<String, Object> resourceMetrics = new LinkedHashMap<>();
      int step = 0;
      int totalSteps = resources.size() + 1;

      if (resources.contains("cpu")) {
        sendProgress(exchange, progressToken, step++, totalSteps, "Analyzing CPU...");
        resourceMetrics.put("cpu", analyzeOtlpCpu(info, defaultType));
      }

      if (resources.contains("threads")) {
        sendProgress(exchange, progressToken, step++, totalSteps, "Analyzing threads...");
        Map<String, Object> threadMetrics = analyzeOtlpThreads(info);
        if (!threadMetrics.isEmpty()) {
          resourceMetrics.put("threads", threadMetrics);
        }
      }

      if (resources.contains("errors")) {
        sendProgress(exchange, progressToken, step++, totalSteps, "Scanning for errors...");
        resourceMetrics.put("errors", analyzeOtlpErrors(info));
      }

      result.put("resources", resourceMetrics);

      sendProgress(exchange, progressToken, step, totalSteps, "Generating insights...");
      result.put("insights", generateOtlpUseInsights(resourceMetrics));

      sendProgress(exchange, progressToken, totalSteps, totalSteps, "Done");
      return successResult(result);

    } catch (IllegalArgumentException e) {
      return errorResult(e.getMessage());
    } catch (Exception e) {
      LOG.error("Failed to perform otlp USE analysis: {}", e.getMessage(), e);
      return errorResult("Failed to perform USE analysis: " + e.getMessage());
    }
  }

  private Map<String, Object> analyzeOtlpCpu(
      SamplingSessionRegistry.SessionInfo<OtlpSession> info, String defaultType) {
    Map<String, Object> cpu = new LinkedHashMap<>();

    if (defaultType == null) {
      cpu.put("note", "No sample type found");
      return cpu;
    }

    requireSafeFieldName(defaultType, "sampleType");
    cpu.put("sampleType", defaultType);
    try {
      var statsQuery = OtlpPathParser.parse("samples | stats(" + defaultType + ")");
      List<Map<String, Object>> statsRows = OtlpPathEvaluator.evaluate(info.session(), statsQuery);
      if (!statsRows.isEmpty()) {
        cpu.put("utilization", Map.of("stats", statsRows.get(0)));
      }

      var topQuery = OtlpPathParser.parse("samples | top(10, " + defaultType + ")");
      List<Map<String, Object>> topRows = OtlpPathEvaluator.evaluate(info.session(), topQuery);
      cpu.put("topFunctions", topRows);
    } catch (Exception e) {
      LOG.debug("Error in otlp CPU analysis: {}", e.getMessage());
    }
    return cpu;
  }

  private Map<String, Object> analyzeOtlpThreads(
      SamplingSessionRegistry.SessionInfo<OtlpSession> info) {
    Map<String, Object> threads = new LinkedHashMap<>();

    try {
      var threadQuery = OtlpPathParser.parse("samples | groupBy(thread)");
      List<Map<String, Object>> threadRows =
          OtlpPathEvaluator.evaluate(info.session(), threadQuery);

      if (threadRows.isEmpty()
          || (threadRows.size() == 1 && "<null>".equals(threadRows.get(0).get("thread")))) {
        threads.put("note", "No thread attribute found in this profile");
        return threads;
      }

      threads.put("threadCount", threadRows.size());
      threads.put("distribution", threadRows);

      long totalSamples =
          threadRows.stream()
              .mapToLong(r -> r.get("count") instanceof Number n ? n.longValue() : 0L)
              .sum();
      if (!threadRows.isEmpty() && totalSamples > 0) {
        long topCount = threadRows.get(0).get("count") instanceof Number n ? n.longValue() : 0L;
        double topPct = 100.0 * topCount / totalSamples;
        Map<String, Object> saturation = new LinkedHashMap<>();
        saturation.put("dominantThread", threadRows.get(0).get("thread"));
        saturation.put("dominantThreadPct", Math.round(topPct * 10) / 10.0);
        if (topPct > 90) {
          saturation.put(
              "finding", "Single-threaded: one thread holds >" + (int) topPct + "% of all samples");
        }
        threads.put("saturation", saturation);
      }
    } catch (Exception e) {
      LOG.debug("Error in otlp thread analysis: {}", e.getMessage());
    }
    return threads;
  }

  private Map<String, Object> analyzeOtlpErrors(
      SamplingSessionRegistry.SessionInfo<OtlpSession> info) {
    Map<String, Object> errors = new LinkedHashMap<>();
    Set<String> errorKeywords =
        Set.of("exception", "error", "panic", "throw", "fail", "fatal", "abort", "crash");

    try {
      var topFnQuery = OtlpPathParser.parse("samples | groupBy(stackTrace/0/name) | head(30)");
      List<Map<String, Object>> topFns = OtlpPathEvaluator.evaluate(info.session(), topFnQuery);

      List<Map<String, Object>> suspects = new ArrayList<>();
      for (Map<String, Object> row : topFns) {
        String name = row.get("stackTrace/0/name") instanceof String s ? s : "";
        String nameLower = name.toLowerCase();
        for (String kw : errorKeywords) {
          if (nameLower.contains(kw)) {
            suspects.add(row);
            break;
          }
        }
      }

      errors.put("suspectFunctions", suspects);
      errors.put(
          "note",
          "Error detection is heuristic: scans hot leaf functions for error-related keywords");
    } catch (Exception e) {
      LOG.debug("Error in otlp error analysis: {}", e.getMessage());
    }
    return errors;
  }

  private List<String> generateOtlpUseInsights(Map<String, Object> resourceMetrics) {
    List<String> insights = new ArrayList<>();

    if (resourceMetrics.containsKey("cpu")) {
      @SuppressWarnings("unchecked")
      Map<String, Object> cpu = (Map<String, Object>) resourceMetrics.get("cpu");
      if (cpu.containsKey("utilization")) {
        @SuppressWarnings("unchecked")
        Map<String, Object> util = (Map<String, Object>) cpu.get("utilization");
        if (util.containsKey("stats")) {
          @SuppressWarnings("unchecked")
          Map<String, Object> stats = (Map<String, Object>) util.get("stats");
          Object count = stats.get("count");
          if (count instanceof Number n && n.longValue() > 0) {
            insights.add(
                "Profile contains "
                    + n.longValue()
                    + " samples. Use otlp_flamegraph for visual breakdown.");
          }
        }
      }
    }

    if (resourceMetrics.containsKey("threads")) {
      @SuppressWarnings("unchecked")
      Map<String, Object> threads = (Map<String, Object>) resourceMetrics.get("threads");
      if (threads.containsKey("saturation")) {
        @SuppressWarnings("unchecked")
        Map<String, Object> sat = (Map<String, Object>) threads.get("saturation");
        if (sat.containsKey("finding")) {
          insights.add(sat.get("finding").toString() + " — consider parallelizing the workload");
        }
      }
    }

    if (resourceMetrics.containsKey("errors")) {
      @SuppressWarnings("unchecked")
      Map<String, Object> errMap = (Map<String, Object>) resourceMetrics.get("errors");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> suspects =
          errMap.get("suspectFunctions") instanceof List<?> l
              ? (List<Map<String, Object>>) l
              : List.of();
      if (!suspects.isEmpty()) {
        insights.add(
            "Found "
                + suspects.size()
                + " error-related function(s) in hot paths — review for exception overhead.");
      }
    }

    if (insights.isEmpty()) {
      insights.add("No significant issues detected. Use otlp_flamegraph for detailed analysis.");
    }
    return insights;
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // otlp_help
  // ─────────────────────────────────────────────────────────────────────────────

  public McpServerFeatures.SyncToolSpecification createOtlpHelpTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {}
        }
        """;
    return new McpServerFeatures.SyncToolSpecification(
        buildTool(
            "otlp_help", "Returns OtlpPath query language documentation and examples.", schema),
        (_, _) -> {
          Map<String, Object> result = new LinkedHashMap<>();
          result.put("language", "OtlpPath");
          result.put("documentation", getOtlpHelpText());
          return successResult(result);
        });
  }

  public String getOtlpHelpText() {
    return """
        ## OtlpPath Query Language

        ### Syntax
        ```
        samples[predicate] | operator(args)
        ```

        ### Root
        - `samples` — all profiling samples in the OTLP profile

        ### Predicates (filter before pipeline)
        ```
        samples[thread='main']                    string equality
        samples[cpu > 1000000]                    numeric comparison (>, >=, <, <=, !=)
        samples[thread ~ 'Worker.*']              regex match
        samples[thread='main' and cpu > 500000]   AND
        samples[cpu < 100 or cpu > 900]           OR
        samples[stackTrace/0/name ~ 'HashMap.*']  nested path
        ```

        ### Pipeline Operators
        | Operator | Description |
        |----------|-------------|
        | `count()` | Total number of samples |
        | `top(n, field, [asc])` | Top N by field (default descending) |
        | `head(n)` / `tail(n)` | First/last N rows |
        | `groupBy(field)` | Group and count by field |
        | `groupBy(field, sum(f))` | Group by field, sum another |
        | `stats(field)` | Min/max/avg/sum/count for field |
        | `filter(pred)` | Additional filtering in pipeline |
        | `select(f1, f2, ...)` | Project to subset of fields |
        | `sortBy(field, [asc])` | Sort by field (default descending) |
        | `stackprofile([field])` | Folded stacks for flame graphs |
        | `distinct(field)` | Unique values of field |

        ### Row Fields
        Each sample row contains:
        - Value type field named after the profile's sample type (e.g. `cpu`, `wall`)
        - `stackTrace` — list of frames, each with `name`, `filename`, `line`
        - Attribute fields from the sample's attribute table (e.g. `thread`)

        ### Examples
        ```
        samples | count()
        samples | top(10, cpu)
        samples | groupBy(thread, sum(cpu))
        samples[thread='main'] | top(5, cpu)
        samples | stats(cpu)
        samples | stackprofile(cpu)
        samples | groupBy(stackTrace/0/name, sum(cpu)) | head(20)
        samples[thread ~ 'Worker.*'] | groupBy(thread) | sortBy(count)
        ```

        ### Workflow
        1. otlp_open     — open the file
        2. otlp_summary  — get an overview
        3. otlp_query    — run targeted queries
        4. otlp_flamegraph — visualize hot stacks
        5. otlp_use      — USE method analysis
        6. otlp_close    — release resources
        """;
  }

}
