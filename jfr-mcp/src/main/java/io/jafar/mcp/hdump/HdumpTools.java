package io.jafar.mcp.hdump;

import io.jafar.hdump.shell.HeapReportGenerator;
import io.jafar.hdump.shell.HeapSession;
import io.jafar.hdump.shell.hdumppath.HdumpPath;
import io.jafar.hdump.shell.hdumppath.HdumpPathEvaluator;
import io.jafar.hdump.shell.hdumppath.HdumpPathParser;
import io.jafar.mcp.config.McpServerConfig;
import io.jafar.mcp.result.McpResultFactory;
import io.jafar.mcp.result.ResultLimiter;
import io.jafar.mcp.session.HeapSessionRegistry;
import io.jafar.mcp.validation.FileValidator;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** MCP tool implementations for HPROF heap dump analysis. */
public final class HdumpTools {

  private static final Logger LOG = LoggerFactory.getLogger(HdumpTools.class);
  private static final int MAX_HDUMP_FINDINGS = McpServerConfig.MAX_HDUMP_FINDINGS;

  private final HeapSessionRegistry heapSessionRegistry;
  private final McpResultFactory resultFactory;

  public HdumpTools(HeapSessionRegistry heapSessionRegistry, McpResultFactory resultFactory) {
    this.heapSessionRegistry = heapSessionRegistry;
    this.resultFactory = resultFactory;
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

  private static int truncate(List<?> list, int max) {
    return ResultLimiter.truncate(list, max);
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // hdump_open
  // ─────────────────────────────────────────────────────────────────────────────

  public McpServerFeatures.SyncToolSpecification createHdumpOpenTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "path": {
              "type": "string",
              "description": "Absolute path to the HPROF heap dump file"
            },
            "alias": {
              "type": "string",
              "description": "Optional human-readable alias for the session"
            }
          },
          "required": ["path"]
        }
        """;

    return new McpServerFeatures.SyncToolSpecification(
        buildTool(
            "hdump_open",
            "Opens an HPROF heap dump file for analysis. "
                + "Returns a session ID used by other hdump_* tools. "
                + "If no sessionId is supplied to other tools, the most recently opened session is used.",
            schema),
        (exchange, args) -> handleHdumpOpen(args.arguments()));
  }

  public CallToolResult handleHdumpOpen(Map<String, Object> args) {
    String path = (String) args.get("path");
    String alias = (String) args.get("alias");

    if (path == null || path.isBlank()) {
      return errorResult("path is required");
    }

    try {
      Path hprofPath = Path.of(path);

      String fileError = FileValidator.readableRegularFileError(hprofPath, path);
      if (fileError != null) {
        return errorResult(fileError);
      }

      HeapSessionRegistry.SessionInfo info = heapSessionRegistry.open(hprofPath, alias);
      LOG.info("Opened heap dump {} as session {}", path, info.id());

      Map<String, Object> result = info.toMap();
      result.put("message", "Heap dump opened successfully");
      return successResult(result);

    } catch (Exception e) {
      LOG.error("Failed to open heap dump: {}", e.getMessage(), e);
      return errorResult("Failed to open heap dump: " + e.getMessage());
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // hdump_close
  // ─────────────────────────────────────────────────────────────────────────────

  public McpServerFeatures.SyncToolSpecification createHdumpCloseTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "sessionId": {
              "type": "string",
              "description": "Session ID or alias to close; omit to close the current session"
            },
            "closeAll": {
              "type": "boolean",
              "description": "If true, close every open heap dump session"
            }
          }
        }
        """;

    return new McpServerFeatures.SyncToolSpecification(
        buildTool("hdump_close", "Closes one or all open heap dump sessions.", schema),
        (exchange, args) -> handleHdumpClose(args.arguments()));
  }

  public CallToolResult handleHdumpClose(Map<String, Object> args) {
    boolean closeAll = Boolean.TRUE.equals(args.get("closeAll"));
    String sessionId = (String) args.get("sessionId");

    try {
      if (closeAll) {
        int count = heapSessionRegistry.size();
        heapSessionRegistry.closeAll();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "Closed " + count + " session(s)");
        result.put("remainingSessions", 0);
        return successResult(result);
      }

      HeapSessionRegistry.SessionInfo info = heapSessionRegistry.getOrCurrent(sessionId);
      heapSessionRegistry.close(String.valueOf(info.id()));

      Map<String, Object> result = new LinkedHashMap<>();
      result.put("success", true);
      result.put("message", "Closed heap session " + info.id());
      result.put("remainingSessions", heapSessionRegistry.size());
      return successResult(result);

    } catch (IllegalArgumentException e) {
      return errorResult(e.getMessage());
    } catch (Exception e) {
      LOG.error("Failed to close heap session: {}", e.getMessage(), e);
      return errorResult("Failed to close heap session: " + e.getMessage());
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // hdump_query
  // ─────────────────────────────────────────────────────────────────────────────

  public McpServerFeatures.SyncToolSpecification createHdumpQueryTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "query": {
              "type": "string",
              "description": "HdumpPath query string"
            },
            "sessionId": {
              "type": "string",
              "description": "Session ID or alias (uses current if not specified)"
            },
            "limit": {
              "type": "integer",
              "description": "Maximum rows to return (default: 100)"
            }
          },
          "required": ["query"]
        }
        """;

    String description =
        "Executes an HdumpPath query against an open heap dump session.\n\n"
            + "QUERY SYNTAX\n"
            + "  <root>[/<type>][<predicates>] [| <operator>]*\n\n"
            + "ROOT TYPES\n"
            + "  objects    — heap object instances\n"
            + "               fields: id, class, shallow, retained, arrayLength, stringValue\n"
            + "  classes    — class metadata\n"
            + "               fields: id, name, simpleName, instanceCount, instanceSize, superClass, isArray\n"
            + "  gcroots    — GC root references\n"
            + "               fields: type, objectId, object, shallow, retained\n"
            + "               types: JNI_GLOBAL, JNI_LOCAL, JAVA_FRAME, NATIVE_STACK, STICKY_CLASS,\n"
            + "                      THREAD_BLOCK, MONITOR_USED, THREAD_OBJ\n"
            + "  clusters   — graph-based leak clusters (expensive first time)\n"
            + "               fields: id, objectCount, retainedSize, score, dominantClass, anchorType\n"
            + "  duplicates — structurally-identical subgraphs; depth=N controls fingerprint depth (default 3)\n"
            + "               fields: id, rootClass, copies, uniqueSize, wastedBytes, depth, nodeCount\n"
            + "  ages       — objects enriched with estimated age score\n"
            + "               fields: all object fields + estimatedAge, ageBucket, ageSignals\n\n"
            + "TYPE SPECIFIERS (after /)\n"
            + "  objects/java.lang.String\n"
            + "  objects/java.util.*                        (glob)\n"
            + "  objects/instanceof/java.util.Map           (subtypes)\n"
            + "  objects/int[]  or  objects/[I              (arrays)\n\n"
            + "PREDICATES   [field op value]\n"
            + "  ops: =  !=  >  >=  <  <=  ~(regex)\n"
            + "  boolean: and, or, not, ()\n"
            + "  size units: K KB M MB G GB\n"
            + "  functions: contains(f,\"s\")  startsWith(f,\"p\")  between(f,lo,hi)  exists(f)\n"
            + "  examples:  [shallow > 1MB]  [class = \"java.lang.String\"]\n\n"
            + "PIPELINE OPERATORS  (chained with |)\n"
            + "  top(n [,field] [,asc|desc])      sortBy(field [asc|desc])\n"
            + "  filter(predicate)                head(n)  tail(n)\n"
            + "  groupBy(field [,agg=count|sum|avg|min|max] [,value=expr])\n"
            + "  count  sum(field)  stats(field)  select(f1, f2 as alias, ...)  distinct(field)\n"
            + "  waste()               — Map/List capacity waste: capacity, size, wastedBytes\n"
            + "  cacheStats()          — Map cache stats: entryCount, fillRatio, costPerEntry, isLruMode\n"
            + "  checkLeaks([detector=\"name\"] [,minSize=N])\n"
            + "                        — detectors: threadlocal-leak  classloader-leak  duplicate-strings\n"
            + "                                     growing-collections  listener-leak  finalizer-queue\n"
            + "  pathToRoot()          — trace object to nearest GC root\n"
            + "  retentionPaths()      — merged class-level retention paths\n"
            + "  retainedBreakdown([depth=N])   — dominator subtree by class\n"
            + "  dominators([groupBy=\"class\"|\"package\"] [,minRetained=size])\n"
            + "  threadOwner()         — enrich with ownerThread, ownership\n"
            + "  dominatedSize()       — for THREAD_OBJ roots: threadName, dominated, dominatedCount\n"
            + "  estimateAge()         — estimatedAge, ageBucket, ageSignals\n"
            + "  whatif()              — simulate removal: freedBytes, freedPct, remainingRetained\n"
            + "  join(session=id|alias [,root=\"eventType\", by=field])\n"
            + "                        — heap diff (two hprof) or JFR correlation\n\n"
            + "NOTE: retained-size queries trigger approximate retained-size computation on first use.\n"
            + "For large heaps (>500K objects) this may take several minutes.\n\n"
            + "COMMON PATTERNS\n"
            + "  Top 10 memory hogs:      objects | top(10, retained)\n"
            + "  Duplicate waste:         duplicates | sortBy(wastedBytes desc) | top(20)\n"
            + "  Leak clusters:           clusters | sortBy(score desc) | head(10)\n"
            + "  String duplicates:       checkLeaks(detector=\"duplicate-strings\")\n"
            + "  Thread memory:           gcroots/THREAD_OBJ | dominatedSize() | sortBy(dominated desc)\n"
            + "  Collection waste:        objects/instanceof/java.util.HashMap | waste() | top(20, wastedBytes)\n"
            + "  Heap diff:               classes | join(session=1) | sortBy(instanceCountDelta desc)\n"
            + "  Retention paths:         objects[retained > 100MB] | retentionPaths()";

    return new McpServerFeatures.SyncToolSpecification(
        buildTool("hdump_query", description, schema),
        (exchange, args) -> handleHdumpQuery(args.arguments()));
  }

  public CallToolResult handleHdumpQuery(Map<String, Object> args) {
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
      HeapSessionRegistry.SessionInfo info = heapSessionRegistry.getOrCurrent(sessionId);
      HdumpPath.Query query = HdumpPathParser.parse(queryStr);
      List<Map<String, Object>> rows =
          HdumpPathEvaluator.evaluate(info.session(), query, heapSessionRegistry.asResolver());

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

    } catch (IllegalArgumentException e) {
      LOG.warn("HdumpPath query error: {}", e.getMessage());
      return errorResult(e.getMessage());
    } catch (Exception e) {
      LOG.error("Failed to execute hdump query: {}", e.getMessage(), e);
      return errorResult("Failed to execute query: " + e.getMessage());
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // hdump_summary
  // ─────────────────────────────────────────────────────────────────────────────

  public McpServerFeatures.SyncToolSpecification createHdumpSummaryTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "sessionId": {
              "type": "string",
              "description": "Session ID or alias (uses current if not specified)"
            }
          }
        }
        """;

    return new McpServerFeatures.SyncToolSpecification(
        buildTool(
            "hdump_summary",
            "Quick overview of an open heap dump. Does NOT trigger retained-size computation. "
                + "Returns object/class counts, heap size, top classes by instance count, and GC root types.",
            schema),
        (exchange, args) -> handleHdumpSummary(args.arguments()));
  }

  public CallToolResult handleHdumpSummary(Map<String, Object> args) {
    String sessionId = (String) args.get("sessionId");

    try {
      HeapSessionRegistry.SessionInfo info = heapSessionRegistry.getOrCurrent(sessionId);
      HeapSession session = info.session();

      // Top 20 classes by instance count
      List<Map<String, Object>> topClasses =
          HdumpPathEvaluator.evaluate(
              session, HdumpPathParser.parse("classes | top(20, instanceCount)"));

      // GC root type distribution
      List<Map<String, Object>> gcRootTypes =
          HdumpPathEvaluator.evaluate(session, HdumpPathParser.parse("gcroots | groupBy(type)"));

      Map<String, Object> stats = session.getStatistics();
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("sessionId", info.id());
      response.put("path", info.path().toString());
      response.put("objectCount", stats.get("objects"));
      response.put("classCount", stats.get("classes"));
      response.put("heapSize", stats.get("totalHeapSize"));
      response.put("topClasses", topClasses);
      response.put("gcRootTypes", gcRootTypes);
      return successResult(response);

    } catch (IllegalArgumentException e) {
      return errorResult(e.getMessage());
    } catch (Exception e) {
      LOG.error("Failed to get heap summary: {}", e.getMessage(), e);
      return errorResult("Failed to get heap summary: " + e.getMessage());
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // hdump_report
  // ─────────────────────────────────────────────────────────────────────────────

  public McpServerFeatures.SyncToolSpecification createHdumpReportTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "sessionId": {
              "type": "string",
              "description": "Session ID or alias (uses current if not specified)"
            },
            "focus": {
              "type": "string",
              "description": "Comma-separated subset of analyses to run: leaks, waste, duplicates, histogram. Omit to run all."
            },
            "format": {
              "type": "string",
              "description": "Output format: text (default) or markdown",
              "enum": ["text", "markdown"]
            }
          }
        }
        """;

    return new McpServerFeatures.SyncToolSpecification(
        buildTool(
            "hdump_report",
            "Full heap health report. Triggers retained-size computation, leak detection, and "
                + "duplicate subgraph analysis. May take several minutes on large heaps. "
                + "Returns severity-ranked findings (CRITICAL > WARNING > INFO) with suggested queries. "
                + "Use 'focus' to limit analyses: leaks, waste, duplicates, histogram.",
            schema),
        (exchange, args) -> handleHdumpReport(args.arguments()));
  }

  public CallToolResult handleHdumpReport(Map<String, Object> args) {
    String sessionId = (String) args.get("sessionId");
    String focusStr = (String) args.get("focus");
    String format = (String) args.get("format");

    try {
      HeapSessionRegistry.SessionInfo info = heapSessionRegistry.getOrCurrent(sessionId);

      Set<String> focus = new HashSet<>();
      if (focusStr != null && !focusStr.isBlank()) {
        for (String f : focusStr.split(",")) {
          String trimmed = f.trim();
          if (!trimmed.isEmpty()) {
            focus.add(trimmed);
          }
        }
      }

      List<HeapReportGenerator.Finding> findings =
          HeapReportGenerator.generate(info.session(), focus);

      int dropped = truncate(findings, MAX_HDUMP_FINDINGS);

      String reportText =
          "markdown".equalsIgnoreCase(format)
              ? HeapReportGenerator.formatMarkdown(findings, info.session())
              : HeapReportGenerator.formatText(findings, info.session());

      // Also return structured findings
      List<Map<String, Object>> findingMaps = new ArrayList<>();
      for (HeapReportGenerator.Finding f : findings) {
        Map<String, Object> fm = new LinkedHashMap<>();
        fm.put("severity", f.severity().name());
        fm.put("category", f.category());
        fm.put("title", f.title());
        if (f.description() != null) fm.put("description", f.description());
        if (f.retainedSize() >= 0) fm.put("retainedSize", f.retainedSize());
        if (f.affectedObjects() >= 0) fm.put("affectedObjects", f.affectedObjects());
        if (f.action() != null) fm.put("action", f.action());
        if (f.query() != null) fm.put("query", f.query());
        findingMaps.add(fm);
      }

      Map<String, Object> response = new LinkedHashMap<>();
      response.put("sessionId", info.id());
      response.put("findingCount", findings.size());
      if (dropped > 0) {
        LOG.warn("hdump_report truncated {} findings beyond cap {}", dropped, MAX_HDUMP_FINDINGS);
        response.put("truncated", true);
        response.put("droppedRows", dropped);
      }
      response.put("findings", findingMaps);
      response.put("report", reportText);
      return successResult(response);

    } catch (IllegalArgumentException e) {
      return errorResult(e.getMessage());
    } catch (Exception e) {
      LOG.error("Failed to generate heap report: {}", e.getMessage(), e);
      return errorResult("Failed to generate heap report: " + e.getMessage());
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // hdump_help
  // ─────────────────────────────────────────────────────────────────────────────

  public McpServerFeatures.SyncToolSpecification createHdumpHelpTool() {
    String schema =
        """
        {
          "type": "object",
          "properties": {
            "topic": {
              "type": "string",
              "description": "Help topic: overview, roots, filters, operators, examples, patterns, tools. Omit for full reference.",
              "enum": ["overview", "roots", "filters", "operators", "examples", "patterns", "tools"]
            }
          }
        }
        """;

    return new McpServerFeatures.SyncToolSpecification(
        buildTool(
            "hdump_help",
            "HdumpPath query language documentation. "
                + "Topics: overview, roots, filters, operators, examples, patterns, tools. "
                + "Call hdump_help(topic=\"tools\") to see all hdump_* MCP tools.",
            schema),
        (exchange, args) -> handleHdumpHelp(args.arguments()));
  }

  public CallToolResult handleHdumpHelp(Map<String, Object> args) {
    String topic = (String) args.get("topic");
    if (topic == null || topic.isBlank()) {
      topic = "overview";
    }

    String content =
        switch (topic.toLowerCase()) {
          case "overview" -> getHdumpOverviewHelp();
          case "roots" -> getHdumpRootsHelp();
          case "filters" -> getHdumpFiltersHelp();
          case "operators" -> getHdumpOperatorsHelp();
          case "examples" -> getHdumpExamplesHelp();
          case "patterns" -> getHdumpPatternsHelp();
          case "tools" -> getHdumpToolsHelp();
          default ->
              "Unknown topic: "
                  + topic
                  + ". Valid topics: overview, roots, filters, operators, examples, patterns, tools";
        };

    return new CallToolResult(List.of(new TextContent(content)), false, null, null);
  }

  private String getHdumpOverviewHelp() {
    return """
        # HdumpPath Query Language

        HdumpPath is a path-based query language for heap dump analysis. Queries select a
        root data set, optionally filter by type and predicates, then apply a pipeline of
        transformation operators.

        ## Query Structure
        ```
        <root>[/<type>][<predicates>] [| <operator>]*
        ```

        ## Roots
        - `objects`    — all heap object instances
        - `classes`    — class metadata (no retained-size needed)
        - `gcroots`    — GC root references
        - `clusters`   — graph-based leak clusters (triggers retained-size on first use)
        - `duplicates` — structurally-identical subgraphs (cached after first run)
        - `ages`       — objects annotated with estimated age

        ## Quick Examples
        ```
        objects | top(10, retained)
        classes | top(20, instanceCount)
        objects/java.lang.String | count
        objects/instanceof/java.util.Map | waste() | top(10, wastedBytes)
        duplicates | sortBy(wastedBytes desc) | top(20)
        gcroots/THREAD_OBJ | dominatedSize() | sortBy(dominated desc)
        checkLeaks(detector="duplicate-strings")
        clusters | sortBy(score desc) | head(10)
        ```

        ## Performance Notes
        Operators that compute retained sizes (retained, pathToRoot, retentionPaths,
        dominators, waste, cacheStats, checkLeaks) trigger approximate retained-size
        computation on first use. For large heaps (>500K objects) this may take minutes.
        Results are cached; subsequent queries on the same session are fast.
        """;
  }

  private String getHdumpRootsHelp() {
    return """
        # HdumpPath Root Types

        ## objects
        Heap object instances. Fields:
        - `id`          — object ID (long)
        - `class`       — class name (human-readable)
        - `shallow`     — shallow size in bytes
        - `retained`    — retained size in bytes (triggers computation on first use)
        - `arrayLength` — array length (-1 for non-arrays)
        - `stringValue` — string value for java.lang.String objects

        Type specifiers:
        - `objects/java.lang.String`                — exact class
        - `objects/java.util.*`                     — glob pattern
        - `objects/instanceof/java.util.Map`        — subtypes (polymorphic)
        - `objects/int[]` or `objects/[I`           — array types

        ## classes
        Class metadata. Fields:
        - `id`            — class ID
        - `name`          — internal class name
        - `simpleName`    — human-readable class name
        - `instanceCount` — number of live instances
        - `instanceSize`  — per-instance shallow size
        - `superClass`    — superclass name
        - `isArray`       — true for array types

        ## gcroots
        GC root references. Fields:
        - `type`     — root type (see below)
        - `objectId` — referenced object ID
        - `object`   — object class name
        - `shallow`  — object shallow size
        - `retained` — object retained size

        Root types: JNI_GLOBAL, JNI_LOCAL, JAVA_FRAME, NATIVE_STACK, STICKY_CLASS,
                    THREAD_BLOCK, MONITOR_USED, THREAD_OBJ

        Example: `gcroots/THREAD_OBJ | dominatedSize() | sortBy(dominated desc)`

        ## clusters
        Graph-based leak clusters. Fields:
        - `id`            — cluster ID
        - `objectCount`   — objects in cluster
        - `retainedSize`  — total retained bytes
        - `score`         — leak likelihood score
        - `dominantClass` — most common class in cluster
        - `anchorType`    — GC root type holding this cluster

        ## duplicates
        Structurally-identical subgraphs. Fields:
        - `id`          — group ID
        - `rootClass`   — root class of the duplicated subgraph
        - `copies`      — number of duplicate copies
        - `uniqueSize`  — size of one unique copy
        - `wastedBytes` — bytes that could be freed by deduplication
        - `depth`       — fingerprint depth used
        - `nodeCount`   — nodes in subgraph

        Example: `duplicates | sortBy(wastedBytes desc) | top(20)`

        ## ages
        Objects annotated with age estimation. Fields: all `objects` fields plus:
        - `estimatedAge` — relative age score (higher = older)
        - `ageBucket`    — OLD, MATURE, YOUNG, UNKNOWN
        - `ageSignals`   — signals that contributed to the estimate
        """;
  }

  private String getHdumpFiltersHelp() {
    return """
        # HdumpPath Predicates and Filters

        ## Predicate Syntax
        ```
        [field op value]
        [field op value and field2 op value2]
        [field op value or field2 op value2]
        [not (field op value)]
        ```

        ## Comparison Operators
        - `=`   — equals
        - `!=`  — not equals
        - `>`   — greater than
        - `>=`  — greater or equal
        - `<`   — less than
        - `<=`  — less or equal
        - `~`   — regex match (e.g., `[class ~ "java\\.util\\..*"]`)

        ## Size Literals
        Numeric literals can include size units: `K KB M MB G GB`
        Examples: `[shallow > 1MB]`, `[retained >= 10M]`, `[wastedBytes > 512K]`

        ## Filter Functions
        - `contains(field, "substr")`      — field contains substring
        - `startsWith(field, "prefix")`    — field starts with prefix
        - `between(field, lo, hi)`         — field in [lo, hi] range (inclusive)
        - `exists(field)`                  — field is present and non-null

        ## filter() Pipeline Operator
        Apply predicates after type expansion:
        ```
        objects | filter(shallow > 1MB)
        objects/instanceof/java.util.Map | filter(shallow > 100K and class ~ "HashMap")
        ```

        ## Examples
        ```
        objects[shallow > 1MB]
        objects/java.lang.String[stringValue ~ "error.*"]
        classes[instanceCount > 1000]
        gcroots[type = "THREAD_OBJ"]
        duplicates[wastedBytes > 100K]
        objects | filter(retained > 10MB and class ~ "cache|Cache")
        ```
        """;
  }

  private String getHdumpOperatorsHelp() {
    return """
        # HdumpPath Pipeline Operators

        Operators are chained with `|` after the root (and optional predicates).

        ## Sorting and Limiting
        - `top(n [,field] [,asc|desc])` — return top N rows sorted by field (default: desc)
        - `sortBy(field [asc|desc])`    — sort without limiting
        - `head(n)`                     — first N rows
        - `tail(n)`                     — last N rows

        ## Filtering and Projection
        - `filter(predicate)`           — apply predicate filter
        - `select(f1, f2 as alias, ...)` — project specific fields
        - `distinct(field)`             — deduplicate by field value

        ## Aggregation
        - `count`                       — total row count
        - `sum(field)`                  — sum of field values
        - `stats(field)`                — min/max/avg/count/sum for field
        - `groupBy(field [,agg=count|sum|avg|min|max] [,value=expr])` — group and aggregate

        ## Memory Analysis
        - `waste()`             — Map/List capacity waste
                                  output: capacity, size, wastedBytes, wasteRatio
        - `cacheStats()`        — Map-as-cache metrics
                                  output: entryCount, maxSize, fillRatio, costPerEntry, isLruMode
        - `checkLeaks([detector="name"] [,minSize=N])`
                                  detectors: threadlocal-leak, classloader-leak,
                                             duplicate-strings, growing-collections,
                                             listener-leak, finalizer-queue

        ## Dominator / Retention
        - `pathToRoot()`        — trace each object to its nearest GC root
        - `retentionPaths()`    — merged class-level retention paths
        - `retainedBreakdown([depth=N])` — dominator subtree breakdown by class
        - `dominators([groupBy="class"|"package"] [,minRetained=size])`
                                  top retained-size dominators
        - `whatif()`            — simulate removal: freedBytes, freedPct, remainingRetained

        ## Thread Attribution
        - `threadOwner()`       — add ownerThread, ownership fields
        - `dominatedSize()`     — for THREAD_OBJ roots: threadName, dominated, dominatedCount

        ## Age Estimation
        - `estimateAge()`       — add estimatedAge, ageBucket, ageSignals

        ## Cross-Session
        - `join(session=id|alias [,root="eventType" ,by=field])`
                                  heap diff (two hprof) or JFR correlation
                                  with no extra args: class-level instance count delta

        ## Notes
        - Operators requiring retained sizes trigger computation on first use.
        - `clusters` root automatically computes retained sizes.
        - `duplicates` root caches results per depth; default depth = 3.
        """;
  }

  private String getHdumpExamplesHelp() {
    return """
        # HdumpPath Query Examples

        ## Memory Overview
        ```
        # Top 10 objects by retained size
        objects | top(10, retained)

        # Top 20 classes by instance count
        classes | top(20, instanceCount)

        # All String objects by shallow size
        objects/java.lang.String | top(20, shallow)

        # Objects larger than 1 MB
        objects[shallow > 1MB] | sortBy(shallow desc)
        ```

        ## Duplicate Detection
        ```
        # All duplicate subgraph groups, most wasteful first
        duplicates | sortBy(wastedBytes desc) | top(20)

        # Only groups with significant waste
        duplicates[wastedBytes > 100K] | sortBy(wastedBytes desc)

        # Duplicate strings (via leak detector)
        checkLeaks(detector="duplicate-strings")
        ```

        ## Leak Detection
        ```
        # Run all leak detectors
        checkLeaks()

        # Thread-local leak detection
        checkLeaks(detector="threadlocal-leak")

        # Growing collections only
        checkLeaks(detector="growing-collections", minSize=1048576)

        # Graph-based leak clusters
        clusters | sortBy(score desc) | head(10)
        ```

        ## Collection Waste
        ```
        # HashMap waste analysis
        objects/instanceof/java.util.HashMap | waste() | top(20, wastedBytes)

        # LRU cache analysis
        objects/java.util.LinkedHashMap | cacheStats() | filter(isLruMode = true)

        # High fill ratio caches
        objects/instanceof/java.util.Map | cacheStats() | sortBy(costPerEntry desc) | top(10)
        ```

        ## Thread Memory Attribution
        ```
        # Memory dominated by each thread
        gcroots/THREAD_OBJ | dominatedSize() | sortBy(dominated desc)

        # Objects owned by a thread
        objects | threadOwner() | groupBy(ownerThread, agg=sum, value=retained)
        ```

        ## Retention Path Analysis
        ```
        # Retention paths for large objects
        objects[retained > 50MB] | retentionPaths()

        # Dominator objects
        objects | dominators(groupBy="class", minRetained=10MB)

        # Simulate freeing an object
        objects[class = "com.example.Cache"] | whatif()
        ```

        ## Age Estimation
        ```
        # Old objects by retained size
        objects | estimateAge() | filter(ageBucket = "OLD") | top(10, retained)

        # Age distribution by class
        ages | groupBy(ageBucket) | sortBy(count desc)
        ```

        ## Heap Diff (Two Dumps)
        ```
        # Classes with growing instance counts (session 1 = baseline, session 2 = current)
        classes | join(session=1) | sortBy(instanceCountDelta desc) | head(20)
        ```

        ## GC Roots
        ```
        # Summary of GC root types
        gcroots | groupBy(type)

        # Large objects referenced as JNI globals
        gcroots/JNI_GLOBAL | sortBy(retained desc) | top(10)
        ```
        """;
  }

  private String getHdumpPatternsHelp() {
    return """
        # HdumpPath Analysis Workflows

        ## Workflow 1: Memory Leak Investigation
        1. Quick overview:
           `hdump_summary`
        2. Top retained objects:
           `objects | top(10, retained)`
        3. Leak cluster analysis:
           `clusters | sortBy(score desc) | head(10)`
        4. Retention paths for suspects:
           `objects[class ~ "SuspectClass"] | retentionPaths()`
        5. Confirm via dominator tree:
           `objects | dominators(groupBy="class", minRetained=10MB)`

        ## Workflow 2: Heap Size Reduction
        1. Top waste from collections:
           `objects/instanceof/java.util.HashMap | waste() | top(20, wastedBytes)`
        2. Duplicate subgraphs:
           `duplicates | sortBy(wastedBytes desc) | top(20)`
        3. Duplicate strings (common in config-heavy apps):
           `checkLeaks(detector="duplicate-strings")`
        4. What-if simulation:
           `objects[class = "com.example.ConfigCache"] | whatif()`

        ## Workflow 3: Thread Memory Attribution
        1. Per-thread memory dominance:
           `gcroots/THREAD_OBJ | dominatedSize() | sortBy(dominated desc)`
        2. Objects owned by a specific thread:
           `objects | threadOwner() | filter(ownerThread ~ "worker-.*") | top(20, retained)`

        ## Workflow 4: Memory Regression (Heap Diff)
        1. Open baseline: `hdump_open path="/baseline.hprof" alias=baseline`
        2. Open current:  `hdump_open path="/current.hprof"`
        3. Growing classes:
           `classes | join(session=baseline) | sortBy(instanceCountDelta desc) | head(20)`
        4. New large objects:
           `classes | join(session=baseline) | filter(instanceCountDelta > 0 and instanceSize > 1000)`

        ## Workflow 5: Cache Analysis
        1. Find LRU caches:
           `objects/java.util.LinkedHashMap | cacheStats() | filter(isLruMode = true)`
        2. Expensive caches (high cost per entry):
           `objects/instanceof/java.util.Map | cacheStats() | sortBy(costPerEntry desc) | top(10)`
        3. Overfilled caches:
           `objects/instanceof/java.util.Map | cacheStats() | filter(fillRatio > 0.9)`

        ## Workflow 6: Quick Health Check
        Run `hdump_report` — it executes all analyses and returns severity-ranked findings.
        Use `focus` parameter to limit scope: e.g., `focus="leaks,waste"`.
        """;
  }

  private String getHdumpToolsHelp() {
    return """
        # hdump_* MCP Tools

        ## hdump_open
        Opens an HPROF heap dump file for analysis.
        Parameters: path (required), alias (optional)
        Returns: sessionId, objectCount, classCount, heapSize

        ## hdump_close
        Closes one or all heap dump sessions.
        Parameters: sessionId (optional), closeAll (boolean, optional)
        Returns: success, message, remainingSessions

        ## hdump_query
        Executes an HdumpPath query against an open heap dump session.
        Parameters: query (required), sessionId (optional), limit (optional, default 100)
        Returns: resultCount, results (rows), truncated (if limit hit)
        Use hdump_help(topic="operators") for full operator reference.
        Use hdump_help(topic="examples") for query examples.

        ## hdump_summary
        Quick heap overview. Does NOT trigger retained-size computation.
        Parameters: sessionId (optional)
        Returns: objectCount, classCount, heapSize, topClasses (top 20 by count), gcRootTypes

        ## hdump_report
        Full heap health report. Triggers retained-size computation and all analyses.
        May take several minutes on large heaps (>500K objects).
        Parameters: sessionId (optional), focus (optional: leaks,waste,duplicates,histogram),
                    format (optional: text|markdown)
        Returns: findings (severity-ranked), report (formatted text or markdown)

        Severity levels:
          CRITICAL — retained size > 100 MB
          WARNING  — retained size > 10 MB, or duplicate waste > 1 MB
          INFO     — general observations

        ## hdump_help
        HdumpPath documentation.
        Parameters: topic (optional): overview, roots, filters, operators, examples, patterns, tools

        ## Recommended Analysis Order
        1. hdump_open       — open the heap dump
        2. hdump_summary    — quick orientation (cheap, no retained-size computation)
        3. hdump_query      — targeted queries (e.g., top(10, retained), waste(), checkLeaks)
        4. hdump_report     — full automated analysis when you need a comprehensive view
        5. hdump_close      — release memory when done
        """;
  }
}
