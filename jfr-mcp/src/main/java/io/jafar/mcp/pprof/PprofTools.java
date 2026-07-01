package io.jafar.mcp.pprof;

import io.jafar.mcp.result.McpResultFactory;
import io.jafar.mcp.session.PprofSessionRegistry;
import io.jafar.mcp.tool.ProgressReporter;
import io.jafar.mcp.validation.FieldNameValidator;
import io.jafar.mcp.validation.FileValidator;
import io.jafar.pprof.shell.PprofProfile;
import io.jafar.pprof.shell.PprofSession;
import io.jafar.pprof.shell.pprofpath.PprofPathEvaluator;
import io.jafar.pprof.shell.pprofpath.PprofPathParseException;
import io.jafar.pprof.shell.pprofpath.PprofPathParser;
import io.jafar.shell.core.sampling.SamplingSessionRegistry;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** MCP tool implementations for pprof profile analysis. */
public final class PprofTools {

  private static final Logger LOG = LoggerFactory.getLogger(PprofTools.class);

  private final PprofSessionRegistry pprofSessionRegistry;
  private final McpResultFactory resultFactory;
  private final ProgressReporter progressReporter;

  public PprofTools(
      PprofSessionRegistry pprofSessionRegistry,
      McpResultFactory resultFactory,
      ProgressReporter progressReporter) {
    this.pprofSessionRegistry = pprofSessionRegistry;
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
  // pprof_open
  // ─────────────────────────────────────────────────────────────────────────────

  public McpServerFeatures.SyncToolSpecification createPprofOpenTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "path": {
              "type": "string",
              "description": "Absolute path to the pprof profile file (.pb.gz or .pprof)"
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
            "pprof_open",
            "Opens a pprof profile file (.pb.gz or .pprof) for analysis. "
                + "Returns a session ID and profile metadata (sample types, duration, counts). "
                + "Use the returned session ID with other pprof_* tools.",
            schema),
        (exchange, args) -> handlePprofOpen(args.arguments()));
  }

  public CallToolResult handlePprofOpen(Map<String, Object> args) {
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

      var info = pprofSessionRegistry.open(profilePath, alias);
      LOG.info("Opened pprof profile {} as session {}", path, info.id());

      Map<String, Object> result = info.toMap();
      result.put("message", "pprof profile opened successfully");
      return successResult(result);

    } catch (Exception e) {
      LOG.error("Failed to open pprof profile: {}", e.getMessage(), e);
      return errorResult("Failed to open pprof profile: " + e.getMessage());
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // pprof_close
  // ─────────────────────────────────────────────────────────────────────────────

  public McpServerFeatures.SyncToolSpecification createPprofCloseTool() {
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
        buildTool("pprof_close", "Closes a pprof session and releases its resources.", schema),
        (exchange, args) -> handlePprofClose(args.arguments()));
  }

  public CallToolResult handlePprofClose(Map<String, Object> args) {
    String sessionId = (String) args.get("sessionId");

    try {
      var info = pprofSessionRegistry.getOrCurrent(sessionId);
      pprofSessionRegistry.close(String.valueOf(info.id()));

      Map<String, Object> result = new LinkedHashMap<>();
      result.put("closed", info.id());
      result.put("path", info.path().toString());
      result.put("message", "Session closed");
      return successResult(result);

    } catch (IllegalArgumentException e) {
      return errorResult(e.getMessage());
    } catch (Exception e) {
      LOG.error("Failed to close pprof session: {}", e.getMessage(), e);
      return errorResult("Failed to close session: " + e.getMessage());
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // pprof_query
  // ─────────────────────────────────────────────────────────────────────────────

  public McpServerFeatures.SyncToolSpecification createPprofQueryTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "query": {
              "type": "string",
              "description": "PprofPath query string, e.g. 'samples | top(10, cpu)'"
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
            "pprof_query",
            "Executes a PprofPath query against an open pprof profile. "
                + "Query syntax: 'samples[predicate] | operator(args)'. "
                + "Examples: 'samples | count()', 'samples | top(10, cpu)', "
                + "'samples | groupBy(thread, sum(cpu))', 'samples[thread=\\'main\\'] | head(5)'. "
                + "Use pprof_help for full query language reference.",
            schema),
        (exchange, args) -> handlePprofQuery(args.arguments()));
  }

  public CallToolResult handlePprofQuery(Map<String, Object> args) {
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
      var info = pprofSessionRegistry.getOrCurrent(sessionId);
      var query = PprofPathParser.parse(queryStr);
      List<Map<String, Object>> rows = PprofPathEvaluator.evaluate(info.session(), query);

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

    } catch (PprofPathParseException e) {
      LOG.warn("PprofPath query parse error: {}", e.getMessage());
      return errorResult("Query parse error: " + e.getMessage());
    } catch (IllegalArgumentException e) {
      LOG.warn("PprofPath query error: {}", e.getMessage());
      return errorResult(e.getMessage());
    } catch (Exception e) {
      LOG.error("Failed to execute pprof query: {}", e.getMessage(), e);
      return errorResult("Failed to execute query: " + e.getMessage());
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // pprof_summary
  // ─────────────────────────────────────────────────────────────────────────────

  public McpServerFeatures.SyncToolSpecification createPprofSummaryTool() {
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
            "pprof_summary",
            "Returns a quick overview of a pprof profile: sample types, counts, duration, "
                + "and top functions by the primary value type.",
            schema),
        (exchange, args) -> handlePprofSummary(args.arguments()));
  }

  public CallToolResult handlePprofSummary(Map<String, Object> args) {
    String sessionId = (String) args.get("sessionId");

    try {
      var info = pprofSessionRegistry.getOrCurrent(sessionId);
      var profile = info.session().getProfile();

      Map<String, Object> response = new LinkedHashMap<>();
      response.put("sessionId", info.id());
      response.put("path", info.path().toString());
      response.putAll(info.session().getStatistics());

      // Top functions by first value type
      if (!profile.sampleTypes().isEmpty()) {
        String firstType = requireSafeFieldName(profile.sampleTypes().get(0).type(), "sampleType");
        try {
          var query = PprofPathParser.parse("samples | top(10, " + firstType + ")");
          List<Map<String, Object>> topFns = PprofPathEvaluator.evaluate(info.session(), query);
          response.put("topFunctions", topFns);
        } catch (Exception e) {
          LOG.debug("Could not compute top functions: {}", e.getMessage());
        }
      }

      return successResult(response);

    } catch (IllegalArgumentException e) {
      return errorResult(e.getMessage());
    } catch (Exception e) {
      LOG.error("Failed to get pprof summary: {}", e.getMessage(), e);
      return errorResult("Failed to get summary: " + e.getMessage());
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // pprof_flamegraph
  // ─────────────────────────────────────────────────────────────────────────────

  public McpServerFeatures.SyncToolSpecification createPprofFlamegraphTool() {
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
              "description": "Value type to aggregate (e.g. 'cpu', 'alloc_space'). Defaults to first sample type."
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
            "pprof_flamegraph",
            "Produces folded stack profile data suitable for flame graph visualization. "
                + "Returns rows of {stack: 'root;parent;leaf', <valueField>: N} sorted by value descending. "
                + "Stack frames are separated by ';' with root frame first.",
            schema),
        (exchange, args) -> handlePprofFlamegraph(args.arguments()));
  }

  public CallToolResult handlePprofFlamegraph(Map<String, Object> args) {
    String sessionId = (String) args.get("sessionId");
    String valueField = (String) args.get("valueField");
    String filter = (String) args.get("filter");
    int limit = args.get("limit") instanceof Number n ? n.intValue() : 50;

    try {
      var info = pprofSessionRegistry.getOrCurrent(sessionId);

      // Resolve default value field
      String effectiveValue = valueField;
      if (effectiveValue == null || effectiveValue.isBlank()) {
        var profile = info.session().getProfile();
        if (!profile.sampleTypes().isEmpty()) {
          effectiveValue = profile.sampleTypes().get(0).type();
        }
      }

      if (effectiveValue == null) {
        return errorResult("No sample types found in profile");
      }

      requireSafeFieldName(effectiveValue, "valueField");

      if (filter != null && filter.contains("]")) {
        return errorResult("Invalid filter: unexpected ']' in filter expression");
      }

      // Build query: samples[<filter>] | stackprofile(<valueField>)
      String queryStr =
          "samples"
              + (filter != null && !filter.isBlank() ? "[" + filter + "]" : "")
              + " | stackprofile("
              + effectiveValue
              + ")";

      var query = PprofPathParser.parse(queryStr);
      List<Map<String, Object>> rows = PprofPathEvaluator.evaluate(info.session(), query);

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

    } catch (PprofPathParseException e) {
      return errorResult("Query parse error: " + e.getMessage());
    } catch (IllegalArgumentException e) {
      return errorResult(e.getMessage());
    } catch (Exception e) {
      LOG.error("Failed to generate pprof flamegraph: {}", e.getMessage(), e);
      return errorResult("Failed to generate flamegraph: " + e.getMessage());
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // pprof_use - USE Method Analysis (Utilization, Saturation, Errors)
  // ─────────────────────────────────────────────────────────────────────────────

  public McpServerFeatures.SyncToolSpecification createPprofUseTool() {
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
              "items": { "type": "string", "enum": ["cpu", "memory", "threads", "errors"] },
              "description": "Resources to analyze. Defaults to all: cpu, memory, threads, errors."
            }
          }
        }
        """;
    return new McpServerFeatures.SyncToolSpecification(
        buildTool(
            "pprof_use",
            "Analyzes a pprof profile using Brendan Gregg's USE Method (Utilization, Saturation, Errors). "
                + "CPU: utilization stats and top consuming functions. "
                + "Memory: saturation analysis if allocation sample types are present. "
                + "Threads: distribution across threads to identify serial bottlenecks. "
                + "Errors: heuristic scan of hot function names for error-related patterns.",
            schema),
        (exchange, args) -> handlePprofUse(exchange, args.arguments(), progressToken(args)));
  }

  @SuppressWarnings("unchecked")
  public CallToolResult handlePprofUse(
      McpSyncServerExchange exchange, Map<String, Object> args, Object progressToken) {
    String sessionId = (String) args.get("sessionId");
    List<String> resourcesList =
        args.get("resources") instanceof List<?> l ? (List<String>) l : List.of("all");
    Set<String> resources =
        resourcesList.contains("all")
            ? Set.of("cpu", "memory", "threads", "errors")
            : Set.copyOf(resourcesList);

    try {
      var info = pprofSessionRegistry.getOrCurrent(sessionId);
      var profile = info.session().getProfile();

      Map<String, Object> result = new LinkedHashMap<>();
      result.put("method", "USE");
      result.put("profilePath", info.path().toString());

      Map<String, Object> resourceMetrics = new LinkedHashMap<>();
      int step = 0;
      int totalSteps = resources.size() + 1;

      if (resources.contains("cpu")) {
        sendProgress(exchange, progressToken, step++, totalSteps, "Analyzing CPU...");
        resourceMetrics.put("cpu", analyzePprofCpu(info, profile));
      }

      if (resources.contains("memory")) {
        sendProgress(exchange, progressToken, step++, totalSteps, "Analyzing memory...");
        Map<String, Object> memMetrics = analyzePprofMemory(info, profile);
        if (!memMetrics.isEmpty()) {
          resourceMetrics.put("memory", memMetrics);
        }
      }

      if (resources.contains("threads")) {
        sendProgress(exchange, progressToken, step++, totalSteps, "Analyzing threads...");
        Map<String, Object> threadMetrics = analyzePprofThreads(info, profile);
        if (!threadMetrics.isEmpty()) {
          resourceMetrics.put("threads", threadMetrics);
        }
      }

      if (resources.contains("errors")) {
        sendProgress(exchange, progressToken, step++, totalSteps, "Scanning for errors...");
        resourceMetrics.put("errors", analyzePprofErrors(info));
      }

      result.put("resources", resourceMetrics);

      sendProgress(exchange, progressToken, step, totalSteps, "Generating insights...");
      result.put("insights", generatePprofUseInsights(resourceMetrics, profile));

      sendProgress(exchange, progressToken, totalSteps, totalSteps, "Done");
      return successResult(result);

    } catch (IllegalArgumentException e) {
      return errorResult(e.getMessage());
    } catch (Exception e) {
      LOG.error("Failed to perform pprof USE analysis: {}", e.getMessage(), e);
      return errorResult("Failed to perform USE analysis: " + e.getMessage());
    }
  }

  private Map<String, Object> analyzePprofCpu(
      SamplingSessionRegistry.SessionInfo<PprofSession> info, PprofProfile.Profile profile) {
    Map<String, Object> cpu = new LinkedHashMap<>();

    // Find the first cpu-like sample type
    String cpuType =
        profile.sampleTypes().stream()
            .map(PprofProfile.ValueType::type)
            .filter(t -> t.contains("cpu") || t.contains("wall") || t.contains("time"))
            .findFirst()
            .orElse(profile.sampleTypes().isEmpty() ? null : profile.sampleTypes().get(0).type());

    if (cpuType == null) {
      cpu.put("note", "No CPU-like sample type found");
      return cpu;
    }

    requireSafeFieldName(cpuType, "sampleType");
    cpu.put("sampleType", cpuType);
    try {
      var statsQuery = PprofPathParser.parse("samples | stats(" + cpuType + ")");
      List<Map<String, Object>> statsRows = PprofPathEvaluator.evaluate(info.session(), statsQuery);
      if (!statsRows.isEmpty()) {
        Map<String, Object> util = new LinkedHashMap<>();
        util.put("stats", statsRows.get(0));
        String unit =
            profile.sampleTypes().stream()
                .filter(vt -> vt.type().equals(cpuType))
                .map(PprofProfile.ValueType::unit)
                .findFirst()
                .orElse("");
        util.put("unit", unit);
        cpu.put("utilization", util);
      }

      var topQuery = PprofPathParser.parse("samples | top(10, " + cpuType + ")");
      List<Map<String, Object>> topRows = PprofPathEvaluator.evaluate(info.session(), topQuery);
      cpu.put("topFunctions", topRows);
    } catch (Exception e) {
      LOG.debug("Error in pprof CPU analysis: {}", e.getMessage());
    }
    return cpu;
  }

  private Map<String, Object> analyzePprofMemory(
      SamplingSessionRegistry.SessionInfo<PprofSession> info, PprofProfile.Profile profile) {
    Map<String, Object> mem = new LinkedHashMap<>();

    // Look for allocation or in-use memory sample types
    List<String> memTypes =
        profile.sampleTypes().stream()
            .map(PprofProfile.ValueType::type)
            .filter(
                t ->
                    t.contains("alloc")
                        || t.contains("inuse")
                        || t.contains("heap")
                        || t.contains("mem"))
            .toList();

    if (memTypes.isEmpty()) {
      return mem; // no memory data
    }

    for (String memType : memTypes) {
      Map<String, Object> typeMetrics = new LinkedHashMap<>();
      try {
        requireSafeFieldName(memType, "sampleType");
        var statsQuery = PprofPathParser.parse("samples | stats(" + memType + ")");
        List<Map<String, Object>> statsRows =
            PprofPathEvaluator.evaluate(info.session(), statsQuery);
        if (!statsRows.isEmpty()) {
          typeMetrics.put("stats", statsRows.get(0));
        }

        var topQuery = PprofPathParser.parse("samples | top(10, " + memType + ")");
        List<Map<String, Object>> topRows = PprofPathEvaluator.evaluate(info.session(), topQuery);
        typeMetrics.put("topAllocators", topRows);

        String unit =
            profile.sampleTypes().stream()
                .filter(vt -> vt.type().equals(memType))
                .map(PprofProfile.ValueType::unit)
                .findFirst()
                .orElse("");
        typeMetrics.put("unit", unit);
      } catch (Exception e) {
        LOG.debug("Error in pprof memory analysis for {}: {}", memType, e.getMessage());
      }
      mem.put(memType, typeMetrics);
    }
    return mem;
  }

  private Map<String, Object> analyzePprofThreads(
      SamplingSessionRegistry.SessionInfo<PprofSession> info, PprofProfile.Profile profile) {
    Map<String, Object> threads = new LinkedHashMap<>();

    // Check if thread label is present by attempting groupBy
    try {
      var threadQuery = PprofPathParser.parse("samples | groupBy(thread)");
      List<Map<String, Object>> threadRows =
          PprofPathEvaluator.evaluate(info.session(), threadQuery);

      if (threadRows.isEmpty()
          || (threadRows.size() == 1 && "<null>".equals(threadRows.get(0).get("thread")))) {
        threads.put("note", "No thread label found in this profile");
        return threads;
      }

      threads.put("threadCount", threadRows.size());
      threads.put("distribution", threadRows);

      // Detect single-threaded saturation
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
      LOG.debug("Error in pprof thread analysis: {}", e.getMessage());
    }
    return threads;
  }

  private Map<String, Object> analyzePprofErrors(
      SamplingSessionRegistry.SessionInfo<PprofSession> info) {
    Map<String, Object> errors = new LinkedHashMap<>();
    Set<String> errorKeywords =
        Set.of("exception", "error", "panic", "throw", "fail", "fatal", "abort", "crash");

    try {
      var topFnQuery = PprofPathParser.parse("samples | groupBy(stackTrace/0/name) | head(30)");
      List<Map<String, Object>> topFns = PprofPathEvaluator.evaluate(info.session(), topFnQuery);

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
      LOG.debug("Error in pprof error analysis: {}", e.getMessage());
    }
    return errors;
  }

  private List<String> generatePprofUseInsights(
      Map<String, Object> resourceMetrics, PprofProfile.Profile profile) {
    List<String> insights = new ArrayList<>();

    // CPU insights
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
                    + " CPU samples. Use pprof_flamegraph for visual breakdown.");
          }
        }
      }
    }

    // Thread saturation insights
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

    // Memory insights
    if (resourceMetrics.containsKey("memory")) {
      @SuppressWarnings("unchecked")
      Map<String, Object> mem = (Map<String, Object>) resourceMetrics.get("memory");
      if (!mem.isEmpty()) {
        insights.add(
            "Allocation profile present ("
                + String.join(", ", mem.keySet())
                + "). Use pprof_hotmethods to identify top allocators.");
      }
    }

    // Error insights
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
      insights.add("No significant issues detected. Use pprof_flamegraph for detailed analysis.");
    }
    return insights;
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // pprof_hotmethods
  // ─────────────────────────────────────────────────────────────────────────────

  public McpServerFeatures.SyncToolSpecification createPprofHotmethodsTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "sessionId": {
              "type": "string",
              "description": "Session ID or alias. Omit to use the current session."
            },
            "topN": {
              "type": "integer",
              "description": "Number of top methods to return (default: 20)"
            },
            "valueField": {
              "type": "string",
              "description": "Value type to rank by (e.g. 'cpu', 'alloc_objects'). Defaults to first sample type."
            }
          }
        }
        """;
    return new McpServerFeatures.SyncToolSpecification(
        buildTool(
            "pprof_hotmethods",
            "Identifies the hottest methods by direct (leaf-frame) cost. "
                + "Groups samples by the leaf function name and sums the chosen value type. "
                + "Shows what each function is directly responsible for (not including callees). "
                + "Use pprof_flamegraph for inclusive (total) cost breakdown.",
            schema),
        (exchange, args) -> handlePprofHotmethods(args.arguments()));
  }

  public CallToolResult handlePprofHotmethods(Map<String, Object> args) {
    String sessionId = (String) args.get("sessionId");
    int topN = args.get("topN") instanceof Number n ? n.intValue() : 20;
    String valueField = (String) args.get("valueField");

    try {
      var info = pprofSessionRegistry.getOrCurrent(sessionId);
      var profile = info.session().getProfile();

      // Resolve value field
      String effectiveField = valueField;
      if (effectiveField == null || effectiveField.isBlank()) {
        effectiveField =
            profile.sampleTypes().isEmpty() ? null : profile.sampleTypes().get(0).type();
      }
      if (effectiveField == null) {
        return errorResult("No sample types found in profile");
      }

      requireSafeFieldName(effectiveField, "valueField");

      Map<String, Object> response = new LinkedHashMap<>();
      response.put("sessionId", info.id());
      response.put("valueField", effectiveField);

      // Direct cost: group by leaf function, sum value
      var directQuery =
          PprofPathParser.parse(
              "samples | groupBy(stackTrace/0/name, sum("
                  + effectiveField
                  + ")) | head("
                  + topN
                  + ")");
      List<Map<String, Object>> directCost =
          PprofPathEvaluator.evaluate(info.session(), directQuery);

      // Compute total for percentage
      long total = 0;
      try {
        var statsQuery = PprofPathParser.parse("samples | stats(" + effectiveField + ")");
        List<Map<String, Object>> statsRows =
            PprofPathEvaluator.evaluate(info.session(), statsQuery);
        if (!statsRows.isEmpty() && statsRows.get(0).get("sum") instanceof Number n) {
          total = n.longValue();
        }
      } catch (Exception ignored) {
        // best effort
      }

      // Annotate with percentage
      final long totalFinal = total;
      if (totalFinal > 0) {
        for (Map<String, Object> row : directCost) {
          Object val = row.get("sum_" + effectiveField);
          if (val instanceof Number n) {
            row.put("pct", Math.round(n.doubleValue() * 1000.0 / totalFinal) / 10.0);
          }
        }
      }

      response.put("totalSamples", total);
      response.put("topMethods", directCost);

      final String finalField = effectiveField;
      String unit =
          profile.sampleTypes().stream()
              .filter(vt -> vt.type().equals(finalField))
              .map(PprofProfile.ValueType::unit)
              .findFirst()
              .orElse("");
      response.put("unit", unit);

      return successResult(response);

    } catch (PprofPathParseException e) {
      return errorResult("Query parse error: " + e.getMessage());
    } catch (IllegalArgumentException e) {
      return errorResult(e.getMessage());
    } catch (Exception e) {
      LOG.error("Failed to compute pprof hotmethods: {}", e.getMessage(), e);
      return errorResult("Failed to compute hot methods: " + e.getMessage());
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // pprof_tsa - Thread State Analysis (TSA Method)
  // ─────────────────────────────────────────────────────────────────────────────

  public McpServerFeatures.SyncToolSpecification createPprofTsaTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "sessionId": {
              "type": "string",
              "description": "Session ID or alias. Omit to use the current session."
            },
            "topThreads": {
              "type": "integer",
              "description": "Number of top threads to profile in detail (default: 10)"
            },
            "includeInsights": {
              "type": "boolean",
              "description": "Whether to include generated insights (default: true)"
            }
          }
        }
        """;
    return new McpServerFeatures.SyncToolSpecification(
        buildTool(
            "pprof_tsa",
            "Thread State Analysis for pprof profiles. Shows how threads distribute their samples "
                + "and infers thread states (RUNNING, WAITING, IO_BLOCKED, LOCK_BLOCKED) from "
                + "leaf function names. Useful for identifying serial bottlenecks, blocking patterns, "
                + "and I/O-heavy threads. Note: state inference is heuristic since pprof does not "
                + "record explicit thread states.",
            schema),
        (exchange, args) -> handlePprofTsa(exchange, args.arguments(), progressToken(args)));
  }

  // Keywords used to infer thread state from leaf function names
  private static final Set<String> WAITING_KEYWORDS =
      Set.of("wait", "park", "sleep", "futex", "cond_wait", "pthread_cond_wait", "timedwait");
  private static final Set<String> IO_KEYWORDS =
      Set.of(
          "read",
          "write",
          "recv",
          "send",
          "accept",
          "poll",
          "epoll",
          "select",
          "pread",
          "pwrite",
          "recvfrom",
          "sendto");
  private static final Set<String> LOCK_KEYWORDS =
      Set.of("lock", "mutex", "synchronized", "acquire", "monitor", "trylock", "spinlock");

  public CallToolResult handlePprofTsa(
      McpSyncServerExchange exchange, Map<String, Object> args, Object progressToken) {
    String sessionId = (String) args.get("sessionId");
    int topThreads = args.get("topThreads") instanceof Number n ? n.intValue() : 10;
    boolean includeInsights = !(args.get("includeInsights") instanceof Boolean b) || b;

    try {
      var info = pprofSessionRegistry.getOrCurrent(sessionId);
      var profile = info.session().getProfile();

      String valueField =
          profile.sampleTypes().isEmpty() ? null : profile.sampleTypes().get(0).type();

      Map<String, Object> result = new LinkedHashMap<>();
      result.put("method", "TSA");
      result.put("profilePath", info.path().toString());

      // Step 1: Thread distribution
      sendProgress(exchange, progressToken, 0, 3, "Analyzing thread distribution...");
      List<Map<String, Object>> threadDist;
      try {
        var tq = PprofPathParser.parse("samples | groupBy(thread)");
        threadDist = PprofPathEvaluator.evaluate(info.session(), tq);
      } catch (Exception e) {
        threadDist = List.of();
      }

      boolean hasThreadLabel =
          !threadDist.isEmpty() && !"<null>".equals(threadDist.get(0).get("thread"));

      long totalSamples = 0;
      try {
        var cq = PprofPathParser.parse("samples | count()");
        List<Map<String, Object>> cr = PprofPathEvaluator.evaluate(info.session(), cq);
        if (!cr.isEmpty() && cr.get(0).get("count") instanceof Number n) {
          totalSamples = n.longValue();
        }
      } catch (Exception ignored) {
        // best effort
      }

      result.put("totalSamples", totalSamples);

      if (hasThreadLabel) {
        result.put("totalThreads", threadDist.size());
        result.put("threadDistribution", threadDist);
      } else {
        result.put("note", "No 'thread' label in this profile; thread analysis unavailable");
      }

      // Step 2: Infer global state distribution from top leaf functions
      sendProgress(exchange, progressToken, 1, 3, "Inferring thread states...");
      Map<String, Long> stateCounts = new LinkedHashMap<>();
      stateCounts.put("RUNNING", 0L);
      stateCounts.put("WAITING", 0L);
      stateCounts.put("IO_BLOCKED", 0L);
      stateCounts.put("LOCK_BLOCKED", 0L);

      try {
        var leafQuery = PprofPathParser.parse("samples | groupBy(stackTrace/0/name)");
        List<Map<String, Object>> leafFns = PprofPathEvaluator.evaluate(info.session(), leafQuery);
        for (Map<String, Object> row : leafFns) {
          String fnName = row.get("stackTrace/0/name") instanceof String s ? s.toLowerCase() : "";
          long count = row.get("count") instanceof Number n ? n.longValue() : 0L;
          String state = inferStateFromFunctionName(fnName);
          stateCounts.merge(state, count, Long::sum);
        }
      } catch (Exception e) {
        LOG.debug("Could not infer thread states: {}", e.getMessage());
      }

      // Annotate with percentages
      long stateTotal = stateCounts.values().stream().mapToLong(Long::longValue).sum();
      Map<String, Object> stateDistribution = new LinkedHashMap<>();
      for (Map.Entry<String, Long> e : stateCounts.entrySet()) {
        Map<String, Object> sd = new LinkedHashMap<>();
        sd.put("samples", e.getValue());
        if (stateTotal > 0) {
          sd.put("pct", Math.round(e.getValue() * 1000.0 / stateTotal) / 10.0);
        }
        stateDistribution.put(e.getKey(), sd);
      }
      result.put("inferredStateDistribution", stateDistribution);
      result.put(
          "stateInferenceNote",
          "States inferred from leaf function names; most CPU-profiled samples appear as RUNNING");

      // Step 3: Per-thread profiles for top threads
      sendProgress(exchange, progressToken, 2, 3, "Building thread profiles...");
      if (hasThreadLabel && valueField != null) {
        List<Map<String, Object>> threadProfiles = new ArrayList<>();
        int profileLimit = Math.min(topThreads, threadDist.size());
        for (int i = 0; i < profileLimit; i++) {
          Map<String, Object> tRow = threadDist.get(i);
          String threadName = tRow.get("thread") instanceof String s ? s : "";
          if (threadName.isBlank() || "<null>".equals(threadName)) continue;

          Map<String, Object> profile2 = new LinkedHashMap<>();
          profile2.put("thread", threadName);
          profile2.put("samples", tRow.get("count"));

          // Top functions for this thread
          try {
            String escapedThread = threadName.replace("\\", "\\\\").replace("'", "\\'");
            requireSafeFieldName(valueField, "valueField");
            var tfq =
                PprofPathParser.parse(
                    "samples[thread='" + escapedThread + "'] | top(5, " + valueField + ")");
            List<Map<String, Object>> topFns = PprofPathEvaluator.evaluate(info.session(), tfq);
            profile2.put("topFunctions", topFns);
          } catch (Exception e) {
            LOG.debug("Could not get top functions for thread {}: {}", threadName, e.getMessage());
          }

          threadProfiles.add(profile2);
        }
        result.put("threadProfiles", threadProfiles);
      }

      // Insights
      if (includeInsights) {
        result.put(
            "insights",
            generatePprofTsaInsights(
                threadDist, stateCounts, stateTotal, totalSamples, hasThreadLabel));
      }

      sendProgress(exchange, progressToken, 3, 3, "Done");
      return successResult(result);

    } catch (IllegalArgumentException e) {
      return errorResult(e.getMessage());
    } catch (Exception e) {
      LOG.error("Failed to perform pprof TSA: {}", e.getMessage(), e);
      return errorResult("Failed to perform TSA: " + e.getMessage());
    }
  }

  private String inferStateFromFunctionName(String fnNameLower) {
    // Split on '.' and '_' separators to avoid substring false-positives
    // e.g. "clock" must not match "lock", "readConfig" must not match "read"
    String[] segments = fnNameLower.split("[._]");
    for (String seg : segments) {
      if (WAITING_KEYWORDS.contains(seg)) return "WAITING";
    }
    for (String seg : segments) {
      if (IO_KEYWORDS.contains(seg)) return "IO_BLOCKED";
    }
    for (String seg : segments) {
      if (LOCK_KEYWORDS.contains(seg)) return "LOCK_BLOCKED";
    }
    return "RUNNING";
  }

  private List<String> generatePprofTsaInsights(
      List<Map<String, Object>> threadDist,
      Map<String, Long> stateCounts,
      long stateTotal,
      long totalSamples,
      boolean hasThreadLabel) {
    List<String> insights = new ArrayList<>();

    if (hasThreadLabel && !threadDist.isEmpty() && totalSamples > 0) {
      long topCount = threadDist.get(0).get("count") instanceof Number n ? n.longValue() : 0L;
      double topPct = 100.0 * topCount / totalSamples;
      if (topPct > 90) {
        insights.add(
            String.format(
                "Single-threaded bottleneck: thread '%s' holds %.1f%% of all samples",
                threadDist.get(0).get("thread"), topPct));
      } else if (threadDist.size() == 1) {
        insights.add("Only one thread observed — workload appears single-threaded");
      }
    }

    if (stateTotal > 0) {
      long waitSamples =
          stateCounts.getOrDefault("WAITING", 0L) + stateCounts.getOrDefault("IO_BLOCKED", 0L);
      double waitPct = 100.0 * waitSamples / stateTotal;
      if (waitPct > 20) {
        insights.add(
            String.format(
                "%.1f%% of leaf frames suggest blocking/waiting — consider async I/O or off-CPU "
                    + "profiling (e.g. async-profiler --event=wall) for deeper insight",
                waitPct));
      }
      long lockSamples = stateCounts.getOrDefault("LOCK_BLOCKED", 0L);
      double lockPct = 100.0 * lockSamples / stateTotal;
      if (lockPct > 10) {
        insights.add(
            String.format(
                "%.1f%% of leaf frames suggest lock contention — review synchronization patterns",
                lockPct));
      }
    }

    if (insights.isEmpty()) {
      insights.add("Profile appears CPU-bound with good thread distribution");
    }
    return insights;
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // pprof_help
  // ─────────────────────────────────────────────────────────────────────────────

  public McpServerFeatures.SyncToolSpecification createPprofHelpTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {}
        }
        """;
    return new McpServerFeatures.SyncToolSpecification(
        buildTool(
            "pprof_help", "Returns PprofPath query language documentation and examples.", schema),
        (_, _) -> {
          Map<String, Object> result = new LinkedHashMap<>();
          result.put("language", "PprofPath");
          result.put("documentation", getPprofHelpText());
          return successResult(result);
        });
  }

  public String getPprofHelpText() {
    return """
        ## PprofPath Query Language

        ### Syntax
        ```
        samples[predicate] | operator(args)
        ```

        ### Root
        - `samples` — all profiling samples in the profile

        ### Predicates (filter before pipeline)
        ```
        samples[thread='main']          string equality
        samples[cpu > 1000000]          numeric comparison (>, >=, <, <=, !=)
        samples[thread ~ 'Worker.*']    regex match
        samples[thread='main' and cpu > 500000]   AND
        samples[cpu < 100 or cpu > 900]            OR
        samples[stackTrace/0/name ~ 'HashMap.*']   nested path
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
        - Value type fields (e.g. `cpu`, `alloc_objects`, `alloc_space`)
        - `stackTrace` — list of frames, each with `name`, `filename`, `line`
        - Label fields (e.g. `thread`, `goroutine`, `request_id`)

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
        1. pprof_open  — open the file
        2. pprof_summary — get an overview
        3. pprof_query — run targeted queries
        4. pprof_flamegraph — visualize hot stacks
        5. pprof_use — USE method analysis
        6. pprof_hotmethods — direct cost breakdown
        7. pprof_tsa — thread state analysis
        8. pprof_close — release resources
        """;
  }
}
