package io.jafar.shell.core.analysis;

import io.jafar.parser.api.Values;
import io.jafar.shell.core.findings.Finding;
import io.jafar.shell.core.findings.Findings;
import io.jafar.shell.core.findings.JfrFindings;
import io.jafar.shell.jfrpath.JfrPath;
import io.jafar.shell.jfrpath.JfrPathParser;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * The JFR analyses, with no transport attached.
 *
 * <p>These were written inside {@code jfr-mcp}'s tool handlers, every one of them shaped as {@code
 * handleJfrX(...) -> CallToolResult} with the computation woven into the response building. That
 * made real analytical work — USE, TSA, the diagnosis heuristics — reachable only by speaking MCP,
 * so the shell's own {@code analyze} had no way to use any of it and would have had to reimplement
 * it. Here they return data; {@code jfr-mcp} wraps that data in its protocol, and the shell reads
 * it directly.
 *
 * <p>Behaviour is deliberately unchanged in the move. The MCP server's tests are the safety net,
 * and they only work as one if the answers are identical.
 */
public final class JfrAnalyses {

  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(JfrAnalyses.class);

  private final JfrQuerySource evaluator;

  public JfrAnalyses() {
    this(JfrQuerySource.defaultSource());
  }

  /**
   * @param evaluator how to read the recording — injected, so a caller can substitute one
   */
  public JfrAnalyses(JfrQuerySource evaluator) {
    this.evaluator = evaluator;
  }

  /**
   * What is in this recording: event totals, the dominant types, and the highlights that decide
   * where to look next.
   *
   * <p>Counting is a single pass over every type rather than one pass per type, which is the
   * difference between O(file) and O(types x file) on a large recording.
   */
  public Map<String, Object> summary(AnalysisTarget target, Progress progress) throws Exception {
    {
      Map<String, Object> result = new LinkedHashMap<>();

      // Recording metadata
      result.put("recordingPath", target.recordingPath().toString());
      result.put("sessionId", target.sessionId());

      // Single-pass count of all event types — O(file_size) instead of O(N × file_size)
      progress.step(0, 2, "Counting events...");
      Map<String, Long> rawCounts = evaluator.countAllEventTypes(target.session());
      progress.step(1, 2, "Aggregating...");

      Map<String, Long> eventCounts = new LinkedHashMap<>();
      long totalEvents = 0;
      Set<String> types = target.session().getAvailableTypes();
      for (String type : types) {
        long count = rawCounts.getOrDefault(type, 0L);
        if (count > 0) {
          eventCounts.put(type, count);
          totalEvents += count;
        }
      }

      result.put("totalEvents", totalEvents);
      result.put("totalEventTypes", eventCounts.size());

      // Top event types
      final long finalTotalEvents = totalEvents; // Make effectively final for lambda
      List<Map<String, Object>> topTypes = new ArrayList<>();
      eventCounts.entrySet().stream()
          .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
          .limit(15)
          .forEach(
              e -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("type", e.getKey());
                entry.put("count", e.getValue());
                entry.put("pct", String.format("%.1f%%", e.getValue() * 100.0 / finalTotalEvents));
                topTypes.add(entry);
              });
      result.put("topEventTypes", topTypes);

      // Compute highlights
      Map<String, Object> highlights = new LinkedHashMap<>();

      // GC statistics
      try {
        highlights.put("gc", computeGcStats(target));
      } catch (Exception e) {
        highlights.put("gc", Map.of("error", "Unable to compute GC stats"));
      }

      // Exception statistics
      Long exceptionCount =
          eventCounts.entrySet().stream()
              .filter(
                  e -> e.getKey().contains("Exception") || e.getKey().endsWith("ExceptionSample"))
              .mapToLong(Map.Entry::getValue)
              .sum();
      if (exceptionCount > 0) {
        Map<String, Object> exceptionStats = new LinkedHashMap<>();
        exceptionStats.put("totalExceptions", exceptionCount);
        highlights.put("exceptions", exceptionStats);
      }

      // CPU sampling statistics
      Long cpuSamples =
          eventCounts.entrySet().stream()
              .filter(
                  e ->
                      e.getKey().endsWith("ExecutionSample")
                          || e.getKey().equals("jdk.ExecutionSample"))
              .mapToLong(Map.Entry::getValue)
              .sum();
      if (cpuSamples > 0) {
        Map<String, Object> cpuStats = new LinkedHashMap<>();
        cpuStats.put("totalSamples", cpuSamples);

        // Try to get top CPU method
        try {
          String topMethod = getTopCpuMethod(target);
          if (topMethod != null) {
            cpuStats.put("topMethod", topMethod);
          }
        } catch (Exception ignored) {
          // Skip if can't determine
        }

        highlights.put("cpu", cpuStats);
      }

      result.put("highlights", highlights);

      progress.step(2, 2, "Done");
      return result;
    }
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> computeGcStats(AnalysisTarget target) {
    Map<String, Object> stats = new LinkedHashMap<>();

    String[] gcTypes = {
      "jdk.GarbageCollection",
      "jdk.YoungGarbageCollection",
      "jdk.OldGarbageCollection",
      "jdk.G1GarbageCollection"
    };

    Set<String> availableTypes = target.session().getAvailableTypes();
    List<String> presentGcTypes = new ArrayList<>();
    for (String type : gcTypes) {
      if (availableTypes.contains(type)) {
        presentGcTypes.add(type);
      }
    }
    if (presentGcTypes.isEmpty()) {
      return stats;
    }

    String typeExpr =
        presentGcTypes.size() == 1
            ? presentGcTypes.get(0)
            : "(" + String.join("|", presentGcTypes) + ")";

    try {
      JfrPath.Query parsed = JfrPathParser.parse("events/" + typeExpr);
      List<Map<String, Object>> events = evaluator.evaluate(target.session(), parsed);
      if (!events.isEmpty()) {
        long totalPauseNs = 0;
        for (Map<String, Object> event : events) {
          Object duration = event.get("duration");
          if (duration instanceof Number n) {
            totalPauseNs += n.longValue();
          }
        }
        long totalGCs = events.size();
        stats.put("totalCollections", totalGCs);
        stats.put("totalPauseMs", totalPauseNs / 1_000_000.0);
        stats.put("avgPauseMs", totalPauseNs / (totalGCs * 1_000_000.0));
        stats.put("primaryType", presentGcTypes.get(0));
      }
    } catch (Exception ignored) {
    }

    return stats;
  }

  String getTopCpuMethod(AnalysisTarget target) {
    // Find execution sample event type
    String eventType = null;
    Set<String> types = target.session().getAvailableTypes();
    if (types.contains("datadog.ExecutionSample")) {
      eventType = "datadog.ExecutionSample";
    } else if (types.contains("jdk.ExecutionSample")) {
      eventType = "jdk.ExecutionSample";
    }

    if (eventType == null) {
      return null;
    }

    // Stream events and count leaf methods without materialising all events into a list
    try {
      JfrPath.Query parsed = JfrPathParser.parse("events/" + eventType);
      Map<String, Long> methodCounts = new ConcurrentHashMap<>();
      LongAdder total = new LongAdder();
      evaluator.consume(
          target.session(),
          parsed,
          event -> {
            total.increment();
            List<String> frames = extractFrames(event, "bottom-up", 1);
            if (!frames.isEmpty()) {
              methodCounts.merge(frames.get(0), 1L, Long::sum);
            }
          });

      if (methodCounts.isEmpty()) {
        return null;
      }

      final long totalSamples = total.sum();
      return methodCounts.entrySet().stream()
          .max(Comparator.comparingLong(Map.Entry::getValue))
          .map(e -> String.format("%s (%.1f%%)", e.getKey(), e.getValue() * 100.0 / totalSamples))
          .orElse(null);

    } catch (Exception e) {
      return null;
    }
  }

  public List<String> extractFrames(Map<String, Object> event, String direction, Integer maxDepth) {
    List<String> frames = new ArrayList<>();

    Object stackTrace = event.get("stackTrace");
    if (stackTrace == null) {
      return frames;
    }

    Object framesObj = null;
    if (stackTrace instanceof Map<?, ?> stMap) {
      framesObj = stMap.get("frames");
    }

    if (framesObj == null) {
      return frames;
    }

    // Unwrap {type: ..., array: [...]} wrapper if present
    framesObj = unwrapValue(framesObj);

    // Handle array of frames
    Object[] frameArray = null;
    if (framesObj != null && framesObj.getClass().isArray()) {
      int len = java.lang.reflect.Array.getLength(framesObj);
      frameArray = new Object[len];
      for (int i = 0; i < len; i++) {
        frameArray[i] = java.lang.reflect.Array.get(framesObj, i);
      }
    } else if (framesObj instanceof List<?> list) {
      frameArray = list.toArray();
    }

    if (frameArray == null || frameArray.length == 0) {
      return frames;
    }

    // Extract method names from frames
    for (Object frame : frameArray) {
      String methodName = extractMethodName(frame);
      if (methodName != null) {
        frames.add(methodName);
      }
      if (maxDepth != null && frames.size() >= maxDepth) {
        break;
      }
    }

    // For bottom-up: frames[0] is the hot method (leaf), walk to callers
    // JFR stores frames with index 0 = top of stack (most recent call)
    // So for bottom-up we keep order as-is (hot method first)
    // For top-down we reverse (entry point first)
    if ("top-down".equals(direction)) {
      java.util.Collections.reverse(frames);
    }

    return frames;
  }

  @SuppressWarnings("unchecked")
  public String extractMethodName(Object frame) {
    if (frame == null) {
      return null;
    }

    Map<String, Object> frameMap = null;
    if (frame instanceof Map<?, ?> fm) {
      frameMap = (Map<String, Object>) fm;
    } else {
      return null;
    }

    Object method = frameMap.get("method");
    if (method == null) {
      return null;
    }

    // Unwrap {value: ...} wrapper if present (Datadog format)
    method = unwrapValue(method);

    Map<String, Object> methodMap = null;
    if (method instanceof Map<?, ?> mm) {
      methodMap = (Map<String, Object>) mm;
    } else {
      return null;
    }

    // Get class name - handle nested value wrappers
    String className = "";
    Object type = unwrapValue(methodMap.get("type"));
    if (type instanceof Map<?, ?> typeMap) {
      Object name = unwrapValue(typeMap.get("name"));
      if (name instanceof Map<?, ?> nameMap) {
        Object str = nameMap.get("string");
        if (str != null) {
          className = str.toString();
        }
      } else if (name != null) {
        className = name.toString();
      }
    }

    // Get method name - handle nested value wrappers
    String methodName = "";
    Object nameObj = unwrapValue(methodMap.get("name"));
    if (nameObj instanceof Map<?, ?> nameMap) {
      Object str = nameMap.get("string");
      if (str != null) {
        methodName = str.toString();
      }
    } else if (nameObj != null) {
      methodName = nameObj.toString();
    }

    if (className.isEmpty() && methodName.isEmpty()) {
      return null;
    }

    return className.isEmpty() ? methodName : className + "." + methodName;
  }

  public Object unwrapValue(Object obj) {
    if (obj instanceof io.jafar.parser.api.ArrayType arr) {
      return arr.getArray();
    }
    if (obj instanceof io.jafar.parser.api.ComplexType ct) {
      return ct.getValue();
    }
    return obj;
  }

  public Map<String, Object> exceptions(
      AnalysisTarget target, Map<String, Object> args, Progress progress) throws Exception {
    String eventType = (String) args.get("eventType");
    String sessionId = (String) args.get("sessionId");
    int minCount = args.get("minCount") instanceof Number n ? n.intValue() : 1;
    int limit = args.get("limit") instanceof Number n ? n.intValue() : 50;

    {

      // Auto-detect exception event type if not specified
      if (eventType == null || eventType.isBlank()) {
        eventType = detectExceptionEventType(target);
        if (eventType == null) {
          throw new IllegalArgumentException(
              "No exception events found in recording. "
                  + "Specify eventType explicitly (e.g., jdk.JavaExceptionThrow or datadog.ExceptionSample)");
        }
      }

      // Query and stream exception events, accumulating analysis without materialising the list
      progress.step(0, 2, "Querying exception events...");
      JfrPath.Query parsed = JfrPathParser.parse("events/" + eventType);
      ExceptionAnalysis analysis = new ExceptionAnalysis();
      evaluator.consume(
          target.session(),
          parsed,
          event -> {
            analysis.totalEvents.increment();
            ExceptionInfo info = extractExceptionInfo(event);
            if (info.exceptionType != null) {
              analysis.totalExceptions.increment();
              analysis.exceptionTypes.merge(info.exceptionType, 1L, Long::sum);
              if (info.throwSite != null) {
                analysis.throwSites.merge(info.throwSite, 1L, Long::sum);
                analysis
                    .throwSitesByType
                    .computeIfAbsent(info.exceptionType, k -> new ConcurrentHashMap<>())
                    .merge(info.throwSite, 1L, Long::sum);
              }
            }
          });
      // Compute top throw site per exception type
      for (Map.Entry<String, Map<String, Long>> entry : analysis.throwSitesByType.entrySet()) {
        entry.getValue().entrySet().stream()
            .max(Comparator.comparingLong(Map.Entry::getValue))
            .ifPresent(e -> analysis.topThrowSiteByType.put(entry.getKey(), e.getKey()));
      }

      long totalEvents = analysis.totalEvents.sum();
      if (totalEvents == 0) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eventType", eventType);
        result.put("totalExceptions", 0);
        result.put("message", "No exception events found for type: " + eventType);
        return result;
      }

      progress.step(1, 2, "Analyzing exception patterns...");

      // Build result
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("eventType", eventType);
      result.put("totalExceptions", analysis.totalExceptions.sum());

      // Exception types by frequency
      List<Map<String, Object>> byType = new ArrayList<>();
      analysis.exceptionTypes.entrySet().stream()
          .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
          .filter(e -> e.getValue() >= minCount)
          .limit(limit)
          .forEach(
              e -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                String fullName = e.getKey();
                entry.put("type", extractSimpleName(fullName));
                entry.put("fullType", fullName);
                entry.put("count", e.getValue());
                entry.put("pct", String.format("%.1f%%", e.getValue() * 100.0 / totalEvents));
                // Add top throw site for this exception type
                String topSite = analysis.topThrowSiteByType.get(fullName);
                if (topSite != null) {
                  entry.put("topThrowSite", topSite);
                }
                byType.add(entry);
              });
      result.put("byType", byType);

      // Top throw sites overall
      List<Map<String, Object>> throwSites = new ArrayList<>();
      analysis.throwSites.entrySet().stream()
          .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
          .filter(e -> e.getValue() >= minCount)
          .limit(20)
          .forEach(
              e -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("site", e.getKey());
                entry.put("count", e.getValue());
                entry.put("pct", String.format("%.1f%%", e.getValue() * 100.0 / totalEvents));
                throwSites.add(entry);
              });
      result.put("topThrowSites", throwSites);

      // Summary statistics
      Map<String, Object> summary = new LinkedHashMap<>();
      summary.put("uniqueExceptionTypes", analysis.exceptionTypes.size());
      summary.put("uniqueThrowSites", analysis.throwSites.size());
      if (analysis.exceptionTypes.size() > 0) {
        String topException =
            analysis.exceptionTypes.entrySet().stream()
                .max(Comparator.comparingLong(Map.Entry::getValue))
                .map(e -> extractSimpleName(e.getKey()))
                .orElse("unknown");
        summary.put("mostCommonException", topException);
      }
      result.put("summary", summary);

      progress.step(2, 2, "Done");
      return result;
    }
  }

  String detectExceptionEventType(AnalysisTarget target) {
    String[] candidateTypes = {
      "jdk.JavaExceptionThrow", "datadog.ExceptionSample", "jdk.ExceptionStatistics"
    };
    try {
      Map<String, Long> counts = evaluator.countAllEventTypes(target.session());
      for (String type : candidateTypes) {
        if (counts.getOrDefault(type, 0L) > 0) return type;
      }
    } catch (Exception ignored) {
    }
    return null;
  }

  ExceptionInfo extractExceptionInfo(Map<String, Object> event) {
    ExceptionInfo info = new ExceptionInfo();

    // First, check for explicit exception type field (jdk.JavaExceptionThrow has thrownClass)
    Object thrownClass = event.get("thrownClass");
    if (thrownClass != null) {
      info.exceptionType = extractClassName(thrownClass);
    }

    // Extract from stack trace
    Object stackTrace = event.get("stackTrace");
    if (stackTrace instanceof Map<?, ?> stMap) {
      Object framesObj = stMap.get("frames");
      framesObj = unwrapValue(framesObj);

      Object[] frameArray = toObjectArray(framesObj);
      if (frameArray != null && frameArray.length > 0) {
        // Find exception type from <init> chain
        String lastExceptionInit = null;
        String firstNonInitFrame = null;

        for (Object frame : frameArray) {
          String methodName = extractMethodName(frame);
          if (methodName == null) continue;

          if (methodName.endsWith(".<init>")) {
            String className = methodName.substring(0, methodName.length() - 7);
            if (isExceptionClass(className)) {
              lastExceptionInit = className;
            }
          } else if (lastExceptionInit != null && firstNonInitFrame == null) {
            firstNonInitFrame = methodName;
          }
        }

        // If we found exception type from stack, use it (more specific than thrownClass)
        if (lastExceptionInit != null) {
          info.exceptionType = lastExceptionInit;
        }
        if (firstNonInitFrame != null) {
          info.throwSite = firstNonInitFrame;
        }
      }
    }

    return info;
  }

  boolean isExceptionClass(String className) {
    return className.endsWith("Exception")
        || className.endsWith("Error")
        || className.endsWith("Throwable")
        || className.contains("/Exception")
        || className.contains("/Error");
  }

  String extractClassName(Object classObj) {
    classObj = unwrapValue(classObj);
    if (classObj instanceof Map<?, ?> classMap) {
      Object name = classMap.get("name");
      name = unwrapValue(name);
      if (name instanceof Map<?, ?> nameMap) {
        Object str = nameMap.get("string");
        if (str != null) return str.toString();
      } else if (name != null) {
        return name.toString();
      }
    }
    return null;
  }

  Object[] toObjectArray(Object obj) {
    if (obj == null) return null;
    if (obj.getClass().isArray()) {
      int len = java.lang.reflect.Array.getLength(obj);
      Object[] result = new Object[len];
      for (int i = 0; i < len; i++) {
        result[i] = java.lang.reflect.Array.get(obj, i);
      }
      return result;
    } else if (obj instanceof List<?> list) {
      return list.toArray();
    }
    return null;
  }

  String extractSimpleName(String fullName) {
    if (fullName == null) return "unknown";
    int lastSlash = fullName.lastIndexOf('/');
    return lastSlash >= 0 ? fullName.substring(lastSlash + 1) : fullName;
  }

  static class ExceptionInfo {
    String exceptionType;
    String throwSite;
  }

  public Map<String, Object> hotmethods(
      AnalysisTarget target, Map<String, Object> args, Progress progress) throws Exception {
    String eventType = (String) args.get("eventType");
    String sessionId = (String) args.get("sessionId");
    int limit = args.get("limit") instanceof Number n ? n.intValue() : 20;
    boolean includeNative = args.get("includeNative") instanceof Boolean b ? b : true;

    {

      // Auto-detect execution sample event type if not specified
      if (eventType == null || eventType.isBlank()) {
        eventType = detectExecutionEventType(target);
        if (eventType == null) {
          throw new IllegalArgumentException(
              "No execution sample events found in recording. "
                  + "Specify eventType explicitly (e.g., jdk.ExecutionSample or datadog.ExecutionSample)");
        }
      }

      // Query execution events
      progress.step(0, 2, "Querying execution samples...");
      JfrPath.Query parsed = JfrPathParser.parse("events/" + eventType);
      Map<String, Long> methodCounts = new ConcurrentHashMap<>();
      LongAdder totalSamples = new LongAdder();
      evaluator.consume(
          target.session(),
          parsed,
          event -> {
            totalSamples.increment();
            List<String> frames = extractFrames(event, "bottom-up", 1);
            if (!frames.isEmpty()) {
              methodCounts.merge(frames.get(0), 1L, Long::sum);
            }
          });

      if (totalSamples.sum() == 0) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eventType", eventType);
        result.put("totalSamples", 0);
        result.put("message", "No execution sample events found for type: " + eventType);
        return result;
      }

      // Build result
      progress.step(1, 2, "Identifying hot methods...");
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("eventType", eventType);
      result.put("totalSamples", totalSamples.sum());
      result.put("uniqueMethods", methodCounts.size());

      // Top methods
      List<Map<String, Object>> methods = new ArrayList<>();
      methodCounts.entrySet().stream()
          .filter(e -> includeNative || !isNativeMethod(e.getKey()))
          .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
          .limit(limit)
          .forEach(
              e -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                String methodName = e.getKey();
                entry.put("method", methodName);
                entry.put("samples", e.getValue());
                entry.put(
                    "pct", String.format("%.1f%%", e.getValue() * 100.0 / totalSamples.sum()));
                entry.put("type", isNativeMethod(methodName) ? "native" : "java");
                methods.add(entry);
              });
      result.put("methods", methods);

      // Category breakdown
      Map<String, Long> categoryBreakdown = new LinkedHashMap<>();
      long nativeSamples = 0;
      long javaSamples = 0;
      for (Map.Entry<String, Long> entry : methodCounts.entrySet()) {
        if (isNativeMethod(entry.getKey())) {
          nativeSamples += entry.getValue();
        } else {
          javaSamples += entry.getValue();
        }
      }
      categoryBreakdown.put("native", nativeSamples);
      categoryBreakdown.put("java", javaSamples);
      result.put("categoryBreakdown", categoryBreakdown);

      progress.step(2, 2, "Done");
      return result;
    }
  }

  public String detectExecutionEventType(AnalysisTarget target) {
    String[] candidateTypes = {
      "jdk.ExecutionSample", "datadog.ExecutionSample", "jdk.NativeMethodSample"
    };
    try {
      Map<String, Long> counts = evaluator.countAllEventTypes(target.session());
      for (String type : candidateTypes) {
        if (counts.getOrDefault(type, 0L) > 0) return type;
      }
    } catch (Exception ignored) {
    }
    return null;
  }

  String detectQueueTimeEventType(AnalysisTarget target) {
    try {
      Map<String, Long> counts = evaluator.countAllEventTypes(target.session());
      return counts.getOrDefault("datadog.QueueTime", 0L) > 0 ? "datadog.QueueTime" : null;
    } catch (Exception ignored) {
      return null;
    }
  }

  String detectAllocationEventType(AnalysisTarget target) {
    String[] candidateTypes = {
      "datadog.ObjectSample",
      "jdk.ObjectAllocationSample",
      "jdk.ObjectAllocationInNewTLAB",
      "jdk.ObjectAllocationOutsideTLAB"
    };
    try {
      Map<String, Long> counts = evaluator.countAllEventTypes(target.session());
      for (String type : candidateTypes) {
        if (counts.getOrDefault(type, 0L) > 0) return type;
      }
    } catch (Exception ignored) {
    }
    return null;
  }

  public boolean isNativeMethod(String methodName) {
    if (methodName == null) return false;
    // C++ mangled names typically have < > :: or start with special chars
    return methodName.contains("<")
        || methodName.contains(">::")
        || methodName.contains("::")
        || methodName.startsWith("_")
        || methodName.toLowerCase().contains("atomic");
  }

  public Map<String, Object> use(AnalysisTarget target, Map<String, Object> args, Progress progress)
      throws Exception {
    String sessionId = (String) args.get("sessionId");
    Long startTimeNs = args.get("startTime") instanceof Number n ? n.longValue() : null;
    Long endTimeNs = args.get("endTime") instanceof Number n ? n.longValue() : null;
    boolean includeInsights = args.get("includeInsights") instanceof Boolean b ? b : true;

    @SuppressWarnings("unchecked")
    List<String> resourcesList =
        args.get("resources") instanceof List<?> l ? (List<String>) l : List.of("all");
    Set<String> resources =
        resourcesList.contains("all")
            ? Set.of("cpu", "memory", "threads", "io")
            : Set.copyOf(resourcesList);

    {
      String timeFilter = buildTimeFilter(startTimeNs, endTimeNs);

      Map<String, Object> result = new LinkedHashMap<>();
      result.put("method", "USE");
      result.put("recordingPath", target.recordingPath().toString());
      if (startTimeNs != null || endTimeNs != null) {
        Map<String, Object> timeWindow = new LinkedHashMap<>();
        if (startTimeNs != null) timeWindow.put("startTime", startTimeNs);
        if (endTimeNs != null) timeWindow.put("endTime", endTimeNs);
        result.put("timeWindow", timeWindow);
      }

      Map<String, Object> resourceMetrics = new LinkedHashMap<>();
      int step = 0;
      int totalSteps = resources.size() + 1;

      // CPU Resource Analysis
      if (resources.contains("cpu")) {
        progress.step(step++, totalSteps, "Analyzing CPU...");
        resourceMetrics.put("cpu", analyzeCpuResource(target, timeFilter));
      }

      // Memory Resource Analysis
      if (resources.contains("memory")) {
        progress.step(step++, totalSteps, "Analyzing memory...");
        resourceMetrics.put("memory", analyzeMemoryResource(target, timeFilter));
      }

      // Threads/Locks Resource Analysis
      if (resources.contains("threads")) {
        progress.step(step++, totalSteps, "Analyzing threads...");
        resourceMetrics.put("threads", analyzeThreadsResource(target, timeFilter));
      }

      // I/O Resource Analysis
      if (resources.contains("io")) {
        progress.step(step++, totalSteps, "Analyzing I/O...");
        resourceMetrics.put("io", analyzeIoResource(target, timeFilter));
      }

      result.put("resources", resourceMetrics);

      // Generate insights and summary
      progress.step(step, totalSteps, "Generating insights...");
      if (includeInsights) {
        result.put("insights", generateUseInsights(resourceMetrics));
        result.put("summary", generateUseSummary(resourceMetrics));
        result.put(
            "findings",
            Findings.toMaps(Findings.merge(JfrFindings.fromUse(resourceMetrics, "jfr_use"))));
      }

      progress.step(totalSteps, totalSteps, "Done");
      return result;
    }
  }

  Map<String, Object> analyzeCpuResource(AnalysisTarget target, String timeFilter) {
    Map<String, Object> cpu = new LinkedHashMap<>();

    try {
      // Query jdk.CPULoad events for actual CPU utilization
      String cpuLoadQuery = "events/jdk.CPULoad" + timeFilter;
      JfrPath.Query parsed = JfrPathParser.parse(cpuLoadQuery);
      List<Map<String, Object>> cpuLoadEvents = evaluator.evaluate(target.session(), parsed);

      if (!cpuLoadEvents.isEmpty()) {
        // Calculate statistics from jdk.CPULoad events
        List<Double> machineTotals = new ArrayList<>();
        List<Double> jvmUsers = new ArrayList<>();
        List<Double> jvmSystems = new ArrayList<>();

        for (Map<String, Object> event : cpuLoadEvents) {
          Object machineTotal = Values.get(event, "machineTotal");
          Object jvmUser = Values.get(event, "jvmUser");
          Object jvmSystem = Values.get(event, "jvmSystem");

          if (machineTotal instanceof Number) {
            machineTotals.add(((Number) machineTotal).doubleValue());
          }
          if (jvmUser instanceof Number) {
            jvmUsers.add(((Number) jvmUser).doubleValue());
          }
          if (jvmSystem instanceof Number) {
            jvmSystems.add(((Number) jvmSystem).doubleValue());
          }
        }

        if (!machineTotals.isEmpty()) {
          // Sort for percentile calculation
          machineTotals.sort(Double::compareTo);
          jvmUsers.sort(Double::compareTo);
          jvmSystems.sort(Double::compareTo);

          double avgMachineTotal = machineTotals.stream().mapToDouble(d -> d).average().orElse(0.0);
          double avgJvmUser = jvmUsers.stream().mapToDouble(d -> d).average().orElse(0.0);
          double avgJvmSystem = jvmSystems.stream().mapToDouble(d -> d).average().orElse(0.0);

          double minMachineTotal = machineTotals.get(0);
          double maxMachineTotal = machineTotals.get(machineTotals.size() - 1);

          int p95Idx = (int) (machineTotals.size() * 0.95);
          int p99Idx = (int) (machineTotals.size() * 0.99);
          double p95MachineTotal = machineTotals.get(Math.min(p95Idx, machineTotals.size() - 1));
          double p99MachineTotal = machineTotals.get(Math.min(p99Idx, machineTotals.size() - 1));

          // Utilization
          Map<String, Object> utilization = new LinkedHashMap<>();
          utilization.put("value", Math.round(avgMachineTotal * 1000) / 10.0); // to percentage
          utilization.put("unit", "%");
          utilization.put(
              "detail",
              String.format(
                  "Avg %.1f%%, min %.1f%%, max %.1f%%, p95 %.1f%%, p99 %.1f%%",
                  avgMachineTotal * 100,
                  minMachineTotal * 100,
                  maxMachineTotal * 100,
                  p95MachineTotal * 100,
                  p99MachineTotal * 100));

          Map<String, Object> breakdown = new LinkedHashMap<>();
          breakdown.put("machineTotal", Math.round(avgMachineTotal * 1000) / 10.0);
          breakdown.put("jvmUser", Math.round(avgJvmUser * 1000) / 10.0);
          breakdown.put("jvmSystem", Math.round(avgJvmSystem * 1000) / 10.0);
          breakdown.put(
              "otherProcesses",
              Math.round((avgMachineTotal - avgJvmUser - avgJvmSystem) * 1000) / 10.0);
          utilization.put("breakdown", breakdown);

          Map<String, Object> stats = new LinkedHashMap<>();
          stats.put("samples", machineTotals.size());
          stats.put("min", Math.round(minMachineTotal * 1000) / 10.0);
          stats.put("max", Math.round(maxMachineTotal * 1000) / 10.0);
          stats.put("avg", Math.round(avgMachineTotal * 1000) / 10.0);
          stats.put("p95", Math.round(p95MachineTotal * 1000) / 10.0);
          stats.put("p99", Math.round(p99MachineTotal * 1000) / 10.0);
          utilization.put("stats", stats);

          cpu.put("utilization", utilization);

          // Check for container CPU throttling
          Map<String, Object> saturation = new LinkedHashMap<>();
          try {
            String throttleQuery = "events/jdk.ContainerCPUThrottling" + timeFilter;
            JfrPath.Query throttleParsed = JfrPathParser.parse(throttleQuery);
            List<Map<String, Object>> throttleEvents =
                evaluator.evaluate(target.session(), throttleParsed);

            long totalThrottledTime = 0;
            long totalThrottledSlices = 0;
            long totalElapsedSlices = 0;

            for (Map<String, Object> event : throttleEvents) {
              Object throttledTime = Values.get(event, "cpuThrottledTime");
              Object throttledSlices = Values.get(event, "cpuThrottledSlices");
              Object elapsedSlices = Values.get(event, "cpuElapsedSlices");

              if (throttledTime instanceof Number) {
                totalThrottledTime += ((Number) throttledTime).longValue();
              }
              if (throttledSlices instanceof Number) {
                totalThrottledSlices += ((Number) throttledSlices).longValue();
              }
              if (elapsedSlices instanceof Number) {
                totalElapsedSlices += ((Number) elapsedSlices).longValue();
              }
            }

            if (!throttleEvents.isEmpty()) {
              saturation.put("throttledTimeNs", totalThrottledTime);
              saturation.put("throttledSlices", totalThrottledSlices);
              saturation.put("elapsedSlices", totalElapsedSlices);

              if (totalThrottledTime > 0) {
                saturation.put("value", totalThrottledSlices);
                saturation.put("unit", "slices");
                saturation.put(
                    "detail",
                    String.format(
                        "Container throttled %d times, %d ns total",
                        totalThrottledSlices, totalThrottledTime));
              } else {
                saturation.put("value", 0);
                saturation.put("detail", "No container CPU throttling detected");
              }
            } else {
              saturation.put("value", 0);
              saturation.put("detail", "Container throttling events not available");
            }
          } catch (Exception e) {
            saturation.put("value", "N/A");
            saturation.put("detail", "Could not check container throttling: " + e.getMessage());
          }

          cpu.put("saturation", saturation);

          // Errors
          Map<String, Object> errors = new LinkedHashMap<>();
          errors.put("value", 0);
          errors.put("detail", "No compilation failures detected");
          cpu.put("errors", errors);

          // Assessment based on actual CPU load
          cpu.put("assessment", assessCpuUtilization(avgMachineTotal * 100));
        } else {
          cpu.put("message", "No valid CPU load data found");
        }
      } else {
        // Fallback to thread state analysis if jdk.CPULoad not available
        cpu.put("warning", "jdk.CPULoad events not found, falling back to thread state analysis");

        String eventType = detectExecutionEventType(target);
        if (eventType == null) {
          cpu.put("error", "No execution sample events found");
          return cpu;
        }

        JfrPath.Query stateParsed = JfrPathParser.parse("events/" + eventType + timeFilter);
        AtomicLongArray counters = new AtomicLongArray(3); // [total, runnable, saturated]
        evaluator.consume(
            target.session(),
            stateParsed,
            event -> {
              counters.incrementAndGet(0);
              String state = extractState(event);
              if ("RUNNABLE".equals(state)) {
                counters.incrementAndGet(1);
              } else if (BLOCKING_STATES.contains(state)) {
                counters.incrementAndGet(2);
              }
            });

        if (counters.get(0) == 0) {
          cpu.put("message", "No execution samples in time window");
          return cpu;
        }

        long runnableCount = counters.get(1);
        long saturatedCount = counters.get(2);
        long totalSamples = counters.get(0);
        double threadStatePct = (runnableCount * 100.0) / totalSamples;

        Map<String, Object> utilization = new LinkedHashMap<>();
        utilization.put("value", Math.round(threadStatePct * 10) / 10.0);
        utilization.put("unit", "%");
        utilization.put(
            "detail",
            String.format(
                "%.1f%% of samples in RUNNABLE state (not actual CPU load)", threadStatePct));
        utilization.put(
            "note",
            "Thread state != CPU utilization. Enable jdk.CPULoad events for accurate data.");
        cpu.put("utilization", utilization);

        Map<String, Object> saturation = new LinkedHashMap<>();
        saturation.put("value", saturatedCount);
        saturation.put("detail", saturatedCount + " samples in blocking states");
        cpu.put("saturation", saturation);

        Map<String, Object> errors = new LinkedHashMap<>();
        errors.put("value", 0);
        errors.put("detail", "No compilation failures detected");
        cpu.put("errors", errors);

        cpu.put("assessment", "UNKNOWN");
      }

    } catch (Exception e) {
      cpu.put("error", "Failed to analyze CPU: " + e.getMessage());
    }

    return cpu;
  }

  Map<String, Object> analyzeMemoryResource(AnalysisTarget target, String timeFilter) {
    Map<String, Object> memory = new LinkedHashMap<>();

    try {
      // Get heap usage (after GC)
      String heapQuery = "events/jdk.GCHeapSummary" + timeFilter;
      JfrPath.Query parsed = JfrPathParser.parse(heapQuery);
      List<Map<String, Object>> heapEvents = evaluator.evaluate(target.session(), parsed);

      Map<String, Object> utilization = new LinkedHashMap<>();
      if (!heapEvents.isEmpty()) {
        // Find most recent "After GC" event
        Map<String, Object> latestHeap = null;
        for (Map<String, Object> event : heapEvents) {
          Object when = Values.get(event, "when", "when");
          if ("After GC".equals(String.valueOf(when))) {
            latestHeap = event;
          }
        }

        if (latestHeap != null) {
          Object heapUsedObj = Values.get(latestHeap, "heapUsed");
          Object heapCommittedObj = Values.get(latestHeap, "heapSpace", "committedSize");

          if (heapUsedObj instanceof Number && heapCommittedObj instanceof Number) {
            long heapUsed = ((Number) heapUsedObj).longValue();
            long heapCommitted = ((Number) heapCommittedObj).longValue();
            double heapPct = (heapUsed * 100.0) / heapCommitted;

            utilization.put("value", Math.round(heapPct * 10) / 10.0);
            utilization.put("unit", "%");
            utilization.put("detail", String.format("Heap %.1f%% full after GC", heapPct));
            utilization.put("heapUsedMB", heapUsed / (1024 * 1024));
            utilization.put("heapCommittedMB", heapCommitted / (1024 * 1024));
          }
        }
      }

      if (utilization.isEmpty()) {
        utilization.put("value", "N/A");
        utilization.put("detail", "No GCHeapSummary events found");
      }
      memory.put("utilization", utilization);

      // Get GC pause statistics
      String gcQuery = "events/jdk.GCPhasePause" + timeFilter;
      parsed = JfrPathParser.parse(gcQuery);
      List<Map<String, Object>> gcEvents = evaluator.evaluate(target.session(), parsed);

      Map<String, Object> saturation = new LinkedHashMap<>();
      if (!gcEvents.isEmpty()) {
        long totalPauseNs = 0;
        long maxPauseNs = 0;
        for (Map<String, Object> event : gcEvents) {
          Object durationObj = Values.get(event, "duration");
          if (durationObj instanceof Number) {
            long durationNs = ((Number) durationObj).longValue();
            totalPauseNs += durationNs;
            maxPauseNs = Math.max(maxPauseNs, durationNs);
          }
        }

        double totalPauseMs = totalPauseNs / 1_000_000.0;
        double avgPauseMs = totalPauseMs / gcEvents.size();
        double maxPauseMs = maxPauseNs / 1_000_000.0;

        saturation.put("gcPauseTimeMs", Math.round(totalPauseMs * 10) / 10.0);
        saturation.put("gcCount", gcEvents.size());
        saturation.put("avgPauseMs", Math.round(avgPauseMs * 10) / 10.0);
        saturation.put("maxPauseMs", Math.round(maxPauseMs * 10) / 10.0);
      } else {
        saturation.put("message", "No GC pause events found");
      }
      memory.put("saturation", saturation);

      // Get top allocators
      try {
        JfrPath.Query allocParsed =
            JfrPathParser.parse("events/jdk.ObjectAllocationSample" + timeFilter);
        Map<String, Long> allocByClass = new ConcurrentHashMap<>();
        evaluator.consume(
            target.session(),
            allocParsed,
            event -> {
              Object classObj = Values.get(event, "objectClass", "name");
              if (classObj == null) {
                classObj = Values.get(event, "objectClass");
              }
              String className = classObj != null ? String.valueOf(classObj) : "unknown";
              Object weightObj = Values.get(event, "weight");
              long weight = weightObj instanceof Number ? ((Number) weightObj).longValue() : 1;
              allocByClass.merge(className, weight, Long::sum);
            });

        if (!allocByClass.isEmpty()) {

          List<Map<String, Object>> topAllocators = new ArrayList<>();
          allocByClass.entrySet().stream()
              .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
              .limit(10)
              .forEach(
                  e -> {
                    Map<String, Object> alloc = new LinkedHashMap<>();
                    alloc.put("class", e.getKey());
                    alloc.put("bytes", e.getValue());
                    alloc.put("mb", Math.round(e.getValue() / (1024.0 * 1024.0) * 10) / 10.0);
                    topAllocators.add(alloc);
                  });

          memory.put("topAllocators", topAllocators);
        }
      } catch (Exception ignored) {
        // Allocation events optional
      }

      // Errors
      Map<String, Object> errors = new LinkedHashMap<>();
      errors.put("value", 0);
      errors.put("detail", "No allocation failures detected");
      memory.put("errors", errors);

      // Assessment
      double heapPct = utilization.get("value") instanceof Number n ? n.doubleValue() : 0.0;
      double gcTimePct = 0.0; // Would need recording duration to calculate
      memory.put("assessment", assessMemoryPressure(heapPct, gcTimePct));

    } catch (Exception e) {
      memory.put("error", "Failed to analyze memory: " + e.getMessage());
    }

    return memory;
  }

  Map<String, Object> analyzeThreadsResource(AnalysisTarget target, String timeFilter) {
    Map<String, Object> threads = new LinkedHashMap<>();

    try {
      // Get unique thread count from execution samples
      String eventType = detectExecutionEventType(target);
      if (eventType != null) {
        JfrPath.Query parsed = JfrPathParser.parse("events/" + eventType + timeFilter);
        Set<String> uniqueThreads = ConcurrentHashMap.newKeySet();
        evaluator.consume(
            target.session(), parsed, event -> uniqueThreads.add(extractThreadId(event)));

        Map<String, Object> utilization = new LinkedHashMap<>();
        utilization.put("value", uniqueThreads.size());
        utilization.put("unit", "threads");
        utilization.put("detail", uniqueThreads.size() + " active threads observed");
        threads.put("utilization", utilization);
      }

      // Get monitor contention
      try {
        JfrPath.Query parsed = JfrPathParser.parse("events/jdk.JavaMonitorEnter" + timeFilter);
        AtomicLongArray monitorCounters = new AtomicLongArray(3); // [count, totalNs, maxNs]
        Map<String, Long> contentionByClass = new ConcurrentHashMap<>();
        evaluator.consume(
            target.session(),
            parsed,
            event -> {
              monitorCounters.incrementAndGet(0);
              Object durationObj = Values.get(event, "duration");
              if (durationObj instanceof Number) {
                long durationNs = ((Number) durationObj).longValue();
                monitorCounters.addAndGet(1, durationNs);
                monitorCounters.accumulateAndGet(2, durationNs, Math::max);
              }
              Object classObj = Values.get(event, "monitorClass", "name");
              if (classObj == null) classObj = Values.get(event, "monitorClass");
              String className = classObj != null ? String.valueOf(classObj) : "unknown";
              contentionByClass.merge(className, 1L, Long::sum);
            });

        Map<String, Object> saturation = new LinkedHashMap<>();
        if (monitorCounters.get(0) > 0) {
          double totalContentionMs = monitorCounters.get(1) / 1_000_000.0;
          double avgContentionMs = totalContentionMs / monitorCounters.get(0);
          double maxContentionMs = monitorCounters.get(2) / 1_000_000.0;

          saturation.put("contentionEvents", monitorCounters.get(0));
          saturation.put("totalContentionMs", Math.round(totalContentionMs * 10) / 10.0);
          saturation.put("avgContentionMs", Math.round(avgContentionMs * 10) / 10.0);
          saturation.put("maxContentionMs", Math.round(maxContentionMs * 10) / 10.0);

          contentionByClass.entrySet().stream()
              .max(Map.Entry.comparingByValue())
              .ifPresent(e -> saturation.put("topContendedClass", e.getKey()));

          saturation.put(
              "assessment",
              monitorCounters.get(0) < 100 ? "LOW_CONTENTION" : "MODERATE_CONTENTION");
        } else {
          saturation.put("message", "No monitor contention detected");
          saturation.put("assessment", "NO_CONTENTION");
        }
        threads.put("saturation", saturation);
      } catch (Exception ignored) {
        Map<String, Object> saturation = new LinkedHashMap<>();
        saturation.put("message", "No monitor events available");
        threads.put("saturation", saturation);
      }

      // Get queue saturation
      String queueEventType = detectQueueTimeEventType(target);
      if (queueEventType != null) {
        try {
          JfrPath.Query parsed = JfrPathParser.parse("events/" + queueEventType + timeFilter);
          Map<String, QueueCorrelation> queueMetrics = new ConcurrentHashMap<>();
          AtomicLongArray queueTotals = new AtomicLongArray(2); // [totalNs, totalItems]
          evaluator.consume(
              target.session(),
              parsed,
              event -> {
                Object durationObj = Values.get(event, "duration");
                if (!(durationObj instanceof Number)) return;
                long durationNs = ((Number) durationObj).longValue();
                queueTotals.addAndGet(0, durationNs);
                queueTotals.incrementAndGet(1);

                Object schedulerObj = Values.get(event, "scheduler", "name");
                if (schedulerObj == null) schedulerObj = Values.get(event, "scheduler");
                String scheduler =
                    extractSimpleClassName(
                        schedulerObj != null ? String.valueOf(schedulerObj) : "unknown");

                Object queueTypeObj = Values.get(event, "queueType", "name");
                if (queueTypeObj == null) queueTypeObj = Values.get(event, "queueType");
                String queueType =
                    extractSimpleClassName(
                        queueTypeObj != null ? String.valueOf(queueTypeObj) : "unknown");

                String threadId = extractThreadId(event);
                String key = scheduler + "|" + queueType;
                queueMetrics
                    .computeIfAbsent(key, k -> new QueueCorrelation(scheduler, queueType))
                    .addSample(durationNs, threadId);
              });

          if (!queueMetrics.isEmpty()) {
            long totalQueueTimeNs = queueTotals.get(0);
            long totalQueuedItems = queueTotals.get(1);

            // Build queue saturation output
            Map<String, Object> queueSaturation = new LinkedHashMap<>();
            queueSaturation.put(
                "totalQueueTimeMs", Math.round(totalQueueTimeNs / 1_000_000.0 * 10) / 10.0);
            queueSaturation.put("totalQueuedItems", totalQueuedItems);

            double avgQueueMs =
                totalQueuedItems > 0
                    ? (totalQueueTimeNs / (double) totalQueuedItems) / 1_000_000.0
                    : 0.0;
            queueSaturation.put("avgQueueTimeMs", Math.round(avgQueueMs * 10) / 10.0);

            // Find max queue time
            long maxQueueNs =
                queueMetrics.values().stream()
                    .mapToLong(c -> c.maxDurationNs.get())
                    .max()
                    .orElse(0);
            queueSaturation.put("maxQueueTimeMs", Math.round(maxQueueNs / 1_000_000.0 * 10) / 10.0);

            // Group by scheduler
            Map<String, Object> byScheduler = new LinkedHashMap<>();
            queueMetrics.entrySet().stream()
                .sorted(
                    (a, b) -> Long.compare(b.getValue().samples.sum(), a.getValue().samples.sum()))
                .limit(10)
                .forEach(
                    e -> {
                      QueueCorrelation corr = e.getValue();
                      Map<String, Object> schedulerInfo = new LinkedHashMap<>();
                      schedulerInfo.put("queueType", corr.queueType);
                      schedulerInfo.put("count", corr.samples.sum());
                      schedulerInfo.put(
                          "totalTimeMs",
                          Math.round(corr.totalDurationNs.sum() / 1_000_000.0 * 10) / 10.0);
                      schedulerInfo.put(
                          "avgTimeMs", Math.round(corr.getAvgDurationMs() * 10) / 10.0);
                      schedulerInfo.put(
                          "maxTimeMs",
                          Math.round(corr.maxDurationNs.get() / 1_000_000.0 * 10) / 10.0);
                      byScheduler.put(corr.scheduler, schedulerInfo);
                    });
            queueSaturation.put("byScheduler", byScheduler);

            queueSaturation.put("assessment", assessQueueSaturation(avgQueueMs));

            // Merge with existing saturation (lock contention)
            if (threads.containsKey("saturation")) {
              @SuppressWarnings("unchecked")
              Map<String, Object> existingSat = (Map<String, Object>) threads.get("saturation");

              // Restructure to have both lock and queue saturation
              Map<String, Object> lockContention = new LinkedHashMap<>();
              lockContention.put("contentionEvents", existingSat.remove("contentionEvents"));
              lockContention.put("totalContentionMs", existingSat.remove("totalContentionMs"));
              lockContention.put("avgContentionMs", existingSat.remove("avgContentionMs"));
              lockContention.put("maxContentionMs", existingSat.remove("maxContentionMs"));
              Object topContendedClass = existingSat.remove("topContendedClass");
              if (topContendedClass != null) {
                lockContention.put("topContendedClass", topContendedClass);
              }
              Object message = existingSat.remove("message");
              if (message != null) {
                lockContention.put("message", message);
              }
              lockContention.put("assessment", existingSat.remove("assessment"));

              existingSat.put("lockContention", lockContention);
              existingSat.put("queueSaturation", queueSaturation);
            } else {
              Map<String, Object> saturation = new LinkedHashMap<>();
              saturation.put("queueSaturation", queueSaturation);
              threads.put("saturation", saturation);
            }
          }
        } catch (Exception e) {
          LOG.debug("Failed to analyze queue saturation: {}", e.getMessage());
        }
      }

      // Errors
      Map<String, Object> errors = new LinkedHashMap<>();
      errors.put("value", "N/A");
      errors.put("detail", "Deadlock detection not available in JFR");
      threads.put("errors", errors);

    } catch (Exception e) {
      threads.put("error", "Failed to analyze threads: " + e.getMessage());
    }

    return threads;
  }

  Map<String, Object> analyzeIoResource(AnalysisTarget target, String timeFilter) {
    Map<String, Object> io = new LinkedHashMap<>();

    try {
      LongAdder ioOps = new LongAdder();
      LongAdder ioTotalNs = new LongAdder();
      AtomicLong ioMaxNs = new AtomicLong(0L);
      LongAdder ioSlowCount = new LongAdder();

      // Single-pass over all four I/O types
      JfrPath.Query ioParsed =
          JfrPathParser.parse(
              "events/(jdk.FileRead|jdk.FileWrite|jdk.SocketRead|jdk.SocketWrite)" + timeFilter);
      evaluator.consume(
          target.session(),
          ioParsed,
          event -> {
            ioOps.increment();
            Object durationObj = Values.get(event, "duration");
            if (durationObj instanceof Number) {
              long durationNs = ((Number) durationObj).longValue();
              ioTotalNs.add(durationNs);
              ioMaxNs.accumulateAndGet(durationNs, Math::max);
              if (durationNs > 10_000_000) {
                ioSlowCount.increment();
              }
            }
          });
      long totalOps = ioOps.longValue();

      if (totalOps > 0) {
        Map<String, Object> utilization = new LinkedHashMap<>();
        utilization.put("totalOperations", totalOps);
        utilization.put("totalTimeMs", Math.round(ioTotalNs.longValue() / 1_000_000.0 * 10) / 10.0);
        io.put("utilization", utilization);

        Map<String, Object> saturation = new LinkedHashMap<>();
        saturation.put("maxDurationMs", Math.round(ioMaxNs.longValue() / 1_000_000.0 * 10) / 10.0);
        saturation.put("slowOperations", ioSlowCount.longValue());
        saturation.put("slowThreshold", "10ms");
        io.put("saturation", saturation);

        io.put("assessment", totalOps < 1000 ? "LOW_IO" : "MODERATE_IO");
      } else {
        io.put("message", "No I/O events detected");
        io.put("assessment", "NO_IO");
      }

      // Errors
      Map<String, Object> errors = new LinkedHashMap<>();
      errors.put("value", "N/A");
      errors.put("detail", "I/O failure tracking not available in standard JFR");
      io.put("errors", errors);

    } catch (Exception e) {
      io.put("error", "Failed to analyze I/O: " + e.getMessage());
    }

    return io;
  }

  Map<String, Object> generateUseInsights(Map<String, Object> resourceMetrics) {
    Map<String, Object> insights = new LinkedHashMap<>();
    List<String> recommendations = new ArrayList<>();
    List<String> bottlenecks = new ArrayList<>();

    // Analyze CPU
    @SuppressWarnings("unchecked")
    Map<String, Object> cpu = (Map<String, Object>) resourceMetrics.get("cpu");
    if (cpu != null && !cpu.containsKey("error")) {
      @SuppressWarnings("unchecked")
      Map<String, Object> cpuSat = (Map<String, Object>) cpu.get("saturation");
      if (cpuSat != null && cpuSat.get("value") instanceof Number) {
        double satPct = ((Number) cpuSat.get("value")).doubleValue();
        if (satPct > 30) {
          bottlenecks.add("cpu_saturation");
          recommendations.add(
              String.format(
                  "Investigate thread blocking: %.1f%% of CPU time spent waiting/blocked", satPct));
        }
      }
    }

    // Analyze Memory
    @SuppressWarnings("unchecked")
    Map<String, Object> memory = (Map<String, Object>) resourceMetrics.get("memory");
    if (memory != null && !memory.containsKey("error")) {
      String assessment = (String) memory.get("assessment");
      if ("HIGH_PRESSURE".equals(assessment) || "MODERATE_PRESSURE".equals(assessment)) {
        bottlenecks.add("memory_pressure");
        recommendations.add("Consider heap tuning or reducing allocation rate");
      }
    }

    // Analyze Threads
    @SuppressWarnings("unchecked")
    Map<String, Object> threadsRes = (Map<String, Object>) resourceMetrics.get("threads");
    if (threadsRes != null && !threadsRes.containsKey("error")) {
      @SuppressWarnings("unchecked")
      Map<String, Object> threadsSat = (Map<String, Object>) threadsRes.get("saturation");
      if (threadsSat != null) {
        // Check lock contention (may be nested or flat structure)
        Object contentionEvents = threadsSat.get("contentionEvents");
        if (contentionEvents == null && threadsSat.containsKey("lockContention")) {
          @SuppressWarnings("unchecked")
          Map<String, Object> lockCont = (Map<String, Object>) threadsSat.get("lockContention");
          contentionEvents = lockCont.get("contentionEvents");
        }
        if (contentionEvents instanceof Number && ((Number) contentionEvents).intValue() > 100) {
          bottlenecks.add("thread_contention");
          Object topClass = threadsSat.get("topContendedClass");
          if (topClass == null && threadsSat.containsKey("lockContention")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> lockCont = (Map<String, Object>) threadsSat.get("lockContention");
            topClass = lockCont.get("topContendedClass");
          }
          if (topClass != null) {
            recommendations.add(
                "Lock contention detected on " + topClass + " - review synchronization");
          }
        }

        // Check queue saturation
        if (threadsSat.containsKey("queueSaturation")) {
          @SuppressWarnings("unchecked")
          Map<String, Object> queueSat = (Map<String, Object>) threadsSat.get("queueSaturation");
          String queueAssessment = (String) queueSat.get("assessment");
          if ("HIGH_QUEUE_SATURATION".equals(queueAssessment)) {
            bottlenecks.add("queue_saturation");
            Object avgQueueMs = queueSat.get("avgQueueTimeMs");
            recommendations.add(
                String.format(
                    "High queue saturation detected (avg: %.1f ms) - consider increasing executor pool sizes",
                    avgQueueMs instanceof Number ? ((Number) avgQueueMs).doubleValue() : 0.0));
          } else if ("MODERATE_QUEUE_SATURATION".equals(queueAssessment)) {
            recommendations.add("Moderate queue saturation - monitor executor capacity");
          }
        }

        // Warn if Datadog profiler but no queue events
        String eventType = null;
        if (threadsRes.containsKey("utilization")) {
          // Try to detect if Datadog profiler is being used
          // This is a heuristic - we check if we have any Datadog-specific data
          if (threadsSat != null && !threadsSat.containsKey("queueSaturation")) {
            // Check if we might be using Datadog profiler
            // For now, we skip this warning as we can't reliably detect profiler type
            // without additional context
          }
        }
      }
    }

    if (recommendations.isEmpty()) {
      recommendations.add("No significant bottlenecks detected - system appears healthy");
    }

    insights.put("recommendations", recommendations);
    insights.put("bottlenecks", bottlenecks);

    return insights;
  }

  Map<String, Object> generateUseSummary(Map<String, Object> resourceMetrics) {
    Map<String, Object> summary = new LinkedHashMap<>();

    // Find worst resource
    String worstResource = null;
    String worstMetric = null;
    double worstValue = 0;

    for (Map.Entry<String, Object> entry : resourceMetrics.entrySet()) {
      @SuppressWarnings("unchecked")
      Map<String, Object> resource = (Map<String, Object>) entry.getValue();
      if (resource.containsKey("error")) continue;

      // Check saturation
      @SuppressWarnings("unchecked")
      Map<String, Object> saturation = (Map<String, Object>) resource.get("saturation");
      if (saturation != null && saturation.get("value") instanceof Number) {
        double value = ((Number) saturation.get("value")).doubleValue();
        if (value > worstValue) {
          worstValue = value;
          worstResource = entry.getKey();
          worstMetric = "saturation";
        }
      }
    }

    if (worstResource != null) {
      summary.put("worstResource", worstResource);
      summary.put("worstMetric", worstMetric);
      summary.put("overallAssessment", worstValue > 50 ? "NEEDS_ATTENTION" : "ACCEPTABLE");
    } else {
      summary.put("overallAssessment", "HEALTHY");
    }

    return summary;
  }

  public Map<String, Object> tsa(AnalysisTarget target, Map<String, Object> args, Progress progress)
      throws Exception {
    String sessionId = (String) args.get("sessionId");
    Long startTimeNs = args.get("startTime") instanceof Number n ? n.longValue() : null;
    Long endTimeNs = args.get("endTime") instanceof Number n ? n.longValue() : null;
    int topThreads = args.get("topThreads") instanceof Number n ? n.intValue() : 10;
    int minSamples = args.get("minSamples") instanceof Number n ? n.intValue() : 5;
    boolean correlateBlocking = args.get("correlateBlocking") instanceof Boolean b ? b : true;
    boolean includeInsights = args.get("includeInsights") instanceof Boolean b ? b : true;

    {
      String timeFilter = buildTimeFilter(startTimeNs, endTimeNs);

      // Detect execution event type
      String eventType = detectExecutionEventType(target);
      if (eventType == null) {
        throw new IllegalArgumentException("No execution sample events found in recording");
      }

      // Get all execution samples
      progress.step(0, 3, "Querying execution samples...");
      JfrPath.Query parsed = JfrPathParser.parse("events/" + eventType + timeFilter);
      Map<String, ThreadStateMetrics> threadMetrics = new ConcurrentHashMap<>();
      Map<String, Long> globalStateCount = new ConcurrentHashMap<>();
      LongAdder totalSamplesArr = new LongAdder();

      evaluator.consume(
          target.session(),
          parsed,
          event -> {
            totalSamplesArr.increment();
            String threadId = extractThreadId(event);
            String threadName = extractThreadName(event);
            String state = extractState(event);
            ThreadStateMetrics metrics =
                threadMetrics.computeIfAbsent(
                    threadId, k -> new ThreadStateMetrics(threadId, threadName));
            metrics.totalSamples.increment();
            metrics.stateCount.merge(state, 1L, Long::sum);
            globalStateCount.merge(state, 1L, Long::sum);
          });

      if (totalSamplesArr.sum() == 0) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("method", "TSA");
        result.put("message", "No execution samples in time window");
        return result;
      }

      // Filter by minSamples
      threadMetrics.values().removeIf(m -> m.totalSamples.sum() < minSamples);

      long totalSamples = totalSamplesArr.sum();

      // Correlate with blocking events if requested
      progress.step(1, 3, "Analyzing thread states...");
      Map<String, MonitorCorrelation> correlations = new HashMap<>();
      Map<String, QueueCorrelation> queueCorrelations = new HashMap<>();
      if (correlateBlocking) {
        progress.step(2, 3, "Correlating blocking events...");
        correlations = correlateWithBlockingEvents(target, timeFilter);
        queueCorrelations = correlateWithQueueEvents(target, timeFilter);
      }

      // Build result
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("method", "TSA");
      result.put("recordingPath", target.recordingPath().toString());
      if (startTimeNs != null || endTimeNs != null) {
        Map<String, Object> timeWindow = new LinkedHashMap<>();
        if (startTimeNs != null) timeWindow.put("startTime", startTimeNs);
        if (endTimeNs != null) timeWindow.put("endTime", endTimeNs);
        result.put("timeWindow", timeWindow);
      }
      result.put("totalSamples", totalSamples);
      result.put("totalThreads", threadMetrics.size());

      // Global state distribution
      Map<String, Object> stateDistribution = new LinkedHashMap<>();
      for (Map.Entry<String, Long> entry : globalStateCount.entrySet()) {
        Map<String, Object> stateInfo = new LinkedHashMap<>();
        stateInfo.put("samples", entry.getValue());
        stateInfo.put("percentage", Math.round(entry.getValue() * 1000.0 / totalSamples) / 10.0);
        stateDistribution.put(entry.getKey(), stateInfo);
      }
      result.put("stateDistribution", stateDistribution);

      // Top threads by state
      Map<String, Object> topThreadsByState =
          buildTopThreadsByState(threadMetrics, globalStateCount, topThreads);
      result.put("topThreadsByState", topThreadsByState);

      // Thread profiles
      List<Map<String, Object>> threadProfiles =
          buildThreadProfiles(threadMetrics, totalSamples, correlations, queueCorrelations);
      result.put("threadProfiles", threadProfiles);

      // Correlations
      if (!correlations.isEmpty() || !queueCorrelations.isEmpty()) {
        Map<String, Object> allCorrelations = new LinkedHashMap<>();
        if (!correlations.isEmpty()) {
          allCorrelations.putAll(buildCorrelationsOutput(correlations));
        }
        if (!queueCorrelations.isEmpty()) {
          allCorrelations.putAll(buildQueueCorrelationsOutput(queueCorrelations));
        }
        result.put("correlations", allCorrelations);
      }

      // Insights
      if (includeInsights) {
        result.put(
            "insights",
            generateTsaInsights(
                threadMetrics, globalStateCount, totalSamples, correlations, queueCorrelations));
        result.put(
            "findings", Findings.toMaps(Findings.merge(JfrFindings.fromTsa(result, "jfr_tsa"))));
      }

      progress.step(3, 3, "Done");
      return result;
    }
  }

  Map<String, MonitorCorrelation> correlateWithBlockingEvents(
      AnalysisTarget target, String timeFilter) {
    Map<String, MonitorCorrelation> correlations = new ConcurrentHashMap<>();

    try {
      JfrPath.Query parsed = JfrPathParser.parse("events/jdk.JavaMonitorEnter" + timeFilter);
      evaluator.consume(
          target.session(),
          parsed,
          event -> {
            Object classObj = Values.get(event, "monitorClass", "name");
            if (classObj == null) {
              classObj = Values.get(event, "monitorClass");
            }
            String monitorClass = classObj != null ? String.valueOf(classObj) : "unknown";
            MonitorCorrelation corr =
                correlations.computeIfAbsent(monitorClass, MonitorCorrelation::new);
            corr.samples.increment();
            Object durationObj = Values.get(event, "duration");
            if (durationObj instanceof Number) {
              corr.totalDurationNs.add(((Number) durationObj).longValue());
            }
            corr.threads.add(extractThreadId(event));
          });
    } catch (Exception e) {
      LOG.debug("Failed to correlate blocking events: {}", e.getMessage());
    }

    return correlations;
  }

  Map<String, QueueCorrelation> correlateWithQueueEvents(AnalysisTarget target, String timeFilter) {
    Map<String, QueueCorrelation> correlations = new ConcurrentHashMap<>();

    try {
      String queueEventType = detectQueueTimeEventType(target);
      if (queueEventType == null) return correlations;

      JfrPath.Query parsed = JfrPathParser.parse("events/" + queueEventType + timeFilter);
      evaluator.consume(
          target.session(),
          parsed,
          event -> {
            Object schedulerObj = Values.get(event, "scheduler", "name");
            if (schedulerObj == null) schedulerObj = Values.get(event, "scheduler");
            String scheduler =
                extractSimpleClassName(
                    schedulerObj != null ? String.valueOf(schedulerObj) : "unknown");

            Object queueTypeObj = Values.get(event, "queueType", "name");
            if (queueTypeObj == null) queueTypeObj = Values.get(event, "queueType");
            String queueType =
                extractSimpleClassName(
                    queueTypeObj != null ? String.valueOf(queueTypeObj) : "unknown");

            String threadId = extractThreadId(event);
            QueueCorrelation corr =
                correlations.computeIfAbsent(
                    scheduler, k -> new QueueCorrelation(scheduler, queueType));

            Object durationObj = Values.get(event, "duration");
            if (durationObj instanceof Number) {
              corr.addSample(((Number) durationObj).longValue(), threadId);
            } else {
              corr.samples.increment();
              corr.threads.add(threadId);
            }
          });

    } catch (Exception e) {
      LOG.debug("Failed to correlate queue events: {}", e.getMessage());
    }

    return correlations;
  }

  Map<String, Object> buildTopThreadsByState(
      Map<String, ThreadStateMetrics> threadMetrics, Map<String, Long> globalStateCount, int topN) {
    Map<String, Object> topThreadsByState = new LinkedHashMap<>();

    for (String state : globalStateCount.keySet()) {
      List<Map<String, Object>> topThreads =
          threadMetrics.values().stream()
              .filter(m -> m.stateCount.containsKey(state))
              .sorted(
                  (a, b) ->
                      Long.compare(
                          b.stateCount.getOrDefault(state, 0L),
                          a.stateCount.getOrDefault(state, 0L)))
              .limit(topN)
              .map(
                  m -> {
                    Map<String, Object> thread = new LinkedHashMap<>();
                    thread.put("threadId", m.threadId);
                    thread.put("threadName", m.threadName);
                    long stateSamples = m.stateCount.get(state);
                    thread.put("samples", stateSamples);
                    thread.put(
                        "percentage",
                        Math.round(stateSamples * 1000.0 / globalStateCount.get(state)) / 10.0);
                    thread.put(
                        "percentOfTotal",
                        Math.round(stateSamples * 1000.0 / m.totalSamples.sum()) / 10.0);
                    return thread;
                  })
              .toList();

      if (!topThreads.isEmpty()) {
        topThreadsByState.put(state, topThreads);
      }
    }

    return topThreadsByState;
  }

  List<Map<String, Object>> buildThreadProfiles(
      Map<String, ThreadStateMetrics> threadMetrics,
      long totalSamples,
      Map<String, MonitorCorrelation> correlations,
      Map<String, QueueCorrelation> queueCorrelations) {
    return threadMetrics.values().stream()
        .sorted((a, b) -> Long.compare(b.totalSamples.sum(), a.totalSamples.sum()))
        .limit(20) // Top 20 threads by sample count
        .map(
            m -> {
              Map<String, Object> profile = new LinkedHashMap<>();
              profile.put("threadId", m.threadId);
              profile.put("threadName", m.threadName);
              profile.put("totalSamples", m.totalSamples.sum());
              profile.put(
                  "percentOfRecording",
                  Math.round(m.totalSamples.sum() * 1000.0 / totalSamples) / 10.0);

              // State breakdown
              Map<String, Object> stateBreakdown = new LinkedHashMap<>();
              for (Map.Entry<String, Long> entry : m.stateCount.entrySet()) {
                Map<String, Object> stateInfo = new LinkedHashMap<>();
                stateInfo.put("samples", entry.getValue());
                stateInfo.put(
                    "pct", Math.round(entry.getValue() * 1000.0 / m.totalSamples.sum()) / 10.0);
                stateBreakdown.put(entry.getKey(), stateInfo);
              }
              profile.put("stateBreakdown", stateBreakdown);

              // Assessment
              profile.put("assessment", assessThreadBehavior(m.stateCount, m.totalSamples.sum()));

              // Add queue correlation info if available
              if (queueCorrelations != null && !queueCorrelations.isEmpty()) {
                List<String> queuedOnExecutors =
                    queueCorrelations.entrySet().stream()
                        .filter(e -> e.getValue().threads.contains(m.threadId))
                        .map(Map.Entry::getKey)
                        .toList();
                if (!queuedOnExecutors.isEmpty()) {
                  profile.put("queuedOn", queuedOnExecutors);
                }
              }

              return profile;
            })
        .toList();
  }

  Map<String, Object> buildCorrelationsOutput(Map<String, MonitorCorrelation> correlations) {
    Map<String, Object> output = new LinkedHashMap<>();

    Map<String, Object> blockedOn = new LinkedHashMap<>();
    correlations.entrySet().stream()
        .sorted((a, b) -> Long.compare(b.getValue().samples.sum(), a.getValue().samples.sum()))
        .limit(10)
        .forEach(
            e -> {
              MonitorCorrelation corr = e.getValue();
              Map<String, Object> info = new LinkedHashMap<>();
              info.put("samples", corr.samples.sum());
              info.put("threads", corr.threads.size());
              if (corr.totalDurationNs.sum() > 0) {
                double avgMs =
                    (corr.totalDurationNs.sum() / (double) corr.samples.sum()) / 1_000_000.0;
                info.put("avgBlockTimeMs", Math.round(avgMs * 10) / 10.0);
              }
              info.put("monitorClass", e.getKey());
              blockedOn.put(e.getKey(), info);
            });

    if (!blockedOn.isEmpty()) {
      output.put("blockedOn", blockedOn);
    }

    return output;
  }

  Map<String, Object> buildQueueCorrelationsOutput(
      Map<String, QueueCorrelation> queueCorrelations) {
    Map<String, Object> output = new LinkedHashMap<>();

    Map<String, Object> queuedOn = new LinkedHashMap<>();
    queueCorrelations.entrySet().stream()
        .sorted((a, b) -> Long.compare(b.getValue().samples.sum(), a.getValue().samples.sum()))
        .limit(10)
        .forEach(
            e -> {
              QueueCorrelation corr = e.getValue();
              Map<String, Object> info = new LinkedHashMap<>();
              info.put("queueType", corr.queueType);
              info.put("samples", corr.samples.sum());
              info.put("threads", corr.threads.size());
              if (corr.totalDurationNs.sum() > 0 && corr.samples.sum() > 0) {
                info.put("avgQueueTimeMs", Math.round(corr.getAvgDurationMs() * 10) / 10.0);
                info.put(
                    "maxQueueTimeMs",
                    Math.round(corr.maxDurationNs.get() / 1_000_000.0 * 10) / 10.0);
              }
              queuedOn.put(e.getKey(), info);
            });

    if (!queuedOn.isEmpty()) {
      output.put("queuedOn", queuedOn);
    }

    return output;
  }

  Map<String, Object> generateTsaInsights(
      Map<String, ThreadStateMetrics> threadMetrics,
      Map<String, Long> globalStateCount,
      long totalSamples,
      Map<String, MonitorCorrelation> correlations,
      Map<String, QueueCorrelation> queueCorrelations) {
    Map<String, Object> insights = new LinkedHashMap<>();
    List<String> patterns = new ArrayList<>();
    List<Map<String, Object>> problematicThreads = new ArrayList<>();
    List<String> recommendations = new ArrayList<>();

    // Analyze global state distribution
    for (Map.Entry<String, Long> entry : globalStateCount.entrySet()) {
      double pct = (entry.getValue() * 100.0) / totalSamples;
      String state = entry.getKey();

      if ("RUNNABLE".equals(state)) {
        if (pct > 70) {
          patterns.add(String.format("High CPU utilization (%.1f%% RUNNABLE)", pct));
        } else if (pct < 30) {
          patterns.add(
              String.format("Low CPU utilization (%.1f%% RUNNABLE) - threads mostly waiting", pct));
        } else {
          patterns.add(String.format("Healthy CPU utilization (%.1f%% RUNNABLE)", pct));
        }
      } else if ("WAITING".equals(state) || "TIMED_WAITING".equals(state)) {
        if (pct > 30) {
          patterns.add(
              String.format(
                  "Significant time in %s (%.1f%%) - likely I/O or queue waits", state, pct));
        }
      } else if ("BLOCKED".equals(state)) {
        if (pct > 10) {
          patterns.add(String.format("High lock contention (%.1f%% BLOCKED)", pct));
          recommendations.add(
              "Investigate lock contention - threads spending significant time blocked on monitors");
        }
      }
    }

    // Find problematic threads
    for (ThreadStateMetrics m : threadMetrics.values()) {
      String assessment = assessThreadBehavior(m.stateCount, m.totalSamples.sum());
      if ("LOCK_CONTENTION".equals(assessment)) {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("thread", m.threadName);
        long blockedSamples = m.stateCount.getOrDefault("BLOCKED", 0L);
        double blockedPct = (blockedSamples * 100.0) / m.totalSamples.sum();
        problem.put("issue", String.format("%.1f%% of time spent BLOCKED on locks", blockedPct));
        problem.put("recommendation", "Review synchronization strategy for this thread");
        problematicThreads.add(problem);
      }
    }

    // Analyze correlations
    if (!correlations.isEmpty()) {
      MonitorCorrelation topContention =
          correlations.values().stream()
              .max(Comparator.comparingLong(c -> c.samples.sum()))
              .orElse(null);
      if (topContention != null && topContention.samples.sum() > 50) {
        recommendations.add(
            String.format(
                "Monitor class '%s' has high contention (%d events) - consider lock-free alternatives",
                topContention.monitorClass, topContention.samples.sum()));
      }
    }

    // Analyze queue correlations
    if (queueCorrelations != null && !queueCorrelations.isEmpty()) {
      QueueCorrelation maxQueue =
          queueCorrelations.values().stream()
              .max(Comparator.comparingDouble(QueueCorrelation::getAvgDurationMs))
              .orElse(null);

      if (maxQueue != null && maxQueue.getAvgDurationMs() > 50) {
        patterns.add(
            String.format(
                "High executor queue times on %s (avg: %.1f ms)",
                maxQueue.scheduler, maxQueue.getAvgDurationMs()));
        recommendations.add(
            String.format(
                "Consider increasing thread pool size for %s or optimizing task submission rate",
                maxQueue.scheduler));
      }
    }

    if (patterns.isEmpty()) {
      patterns.add("No significant patterns detected");
    }
    if (recommendations.isEmpty()) {
      recommendations.add("Thread state distribution appears healthy");
    }

    insights.put("patterns", patterns);
    if (!problematicThreads.isEmpty()) {
      insights.put("problematicThreads", problematicThreads);
    }
    insights.put("recommendations", recommendations);

    return insights;
  }

  static class MonitorCorrelation {
    final String monitorClass;
    final LongAdder samples = new LongAdder();
    final LongAdder totalDurationNs = new LongAdder();
    final Set<String> threads = ConcurrentHashMap.newKeySet();

    MonitorCorrelation(String monitorClass) {
      this.monitorClass = monitorClass;
    }
  }

  static class QueueCorrelation {
    final String scheduler;
    final String queueType;
    final LongAdder samples = new LongAdder();
    final LongAdder totalDurationNs = new LongAdder();
    final AtomicLong maxDurationNs = new AtomicLong(0L);
    final Set<String> threads = ConcurrentHashMap.newKeySet();

    QueueCorrelation(String scheduler, String queueType) {
      this.scheduler = scheduler;
      this.queueType = queueType;
    }

    void addSample(long durationNs, String threadId) {
      samples.increment();
      totalDurationNs.add(durationNs);
      maxDurationNs.accumulateAndGet(durationNs, Math::max);
      threads.add(threadId);
    }

    double getAvgDurationMs() {
      long s = samples.sum();
      return s > 0 ? (totalDurationNs.sum() / (double) s) / 1_000_000.0 : 0.0;
    }
  }

  String extractState(Map<String, Object> event) {
    Object state = Values.get(event, "state", "name");
    if (state == null) {
      state = Values.get(event, "state");
    }
    return state != null ? String.valueOf(unwrapValue(state)) : "UNKNOWN";
  }

  String extractThreadId(Map<String, Object> event) {
    Object tid = Values.get(event, "eventThread", "javaThreadId");
    return tid != null ? String.valueOf(tid) : "unknown";
  }

  String extractThreadName(Map<String, Object> event) {
    Object name = Values.get(event, "eventThread", "javaName");
    if (name == null) {
      name = Values.get(event, "eventThread", "osName");
    }
    return name != null ? String.valueOf(name) : "unknown";
  }

  String extractSimpleClassName(String fullClassName) {
    if (fullClassName == null || fullClassName.isEmpty()) return "unknown";
    int lastDot = fullClassName.lastIndexOf('.');
    int lastDollar = fullClassName.lastIndexOf('$');
    int splitIdx = Math.max(lastDot, lastDollar);
    return splitIdx >= 0 ? fullClassName.substring(splitIdx + 1) : fullClassName;
  }

  String buildTimeFilter(Long startNs, Long endNs) {
    if (startNs == null && endNs == null) {
      return "";
    }
    List<String> conditions = new ArrayList<>();
    if (startNs != null) {
      conditions.add("startTime>=" + startNs);
    }
    if (endNs != null) {
      conditions.add("startTime<=" + endNs);
    }
    return "[" + String.join(" and ", conditions) + "]";
  }

  String assessCpuUtilization(double pct) {
    if (pct < 30) return "LOW";
    if (pct < 70) return "MODERATE_UTILIZATION";
    if (pct < 90) return "HIGH_UTILIZATION";
    return "SATURATED";
  }

  String assessMemoryPressure(double heapPct, double gcTimePct) {
    if (heapPct > 90 || gcTimePct > 10) return "HIGH_PRESSURE";
    if (heapPct > 75 || gcTimePct > 5) return "MODERATE_PRESSURE";
    return "HEALTHY";
  }

  String assessThreadBehavior(Map<String, Long> states, long total) {
    if (total == 0) return "NO_SAMPLES";
    double runnablePct = states.getOrDefault("RUNNABLE", 0L) * 100.0 / total;
    double waitingPct =
        (states.getOrDefault("WAITING", 0L) + states.getOrDefault("TIMED_WAITING", 0L))
            * 100.0
            / total;
    double blockedPct = states.getOrDefault("BLOCKED", 0L) * 100.0 / total;

    if (runnablePct > 80) return "CPU_INTENSIVE";
    if (waitingPct > 70) return "IO_WAITING";
    if (blockedPct > 20) return "LOCK_CONTENTION";
    return "BALANCED";
  }

  String assessQueueSaturation(double avgQueueMs) {
    if (avgQueueMs > 100) return "HIGH_QUEUE_SATURATION";
    if (avgQueueMs > 20) return "MODERATE_QUEUE_SATURATION";
    return "LOW_QUEUE_SATURATION";
  }

  public Map<String, Object> diagnose(
      AnalysisTarget target, Map<String, Object> args, Progress progress) throws Exception {
    String sessionId = (String) args.get("sessionId");
    Boolean includeAnalysis = args.get("includeAnalysis") instanceof Boolean b ? b : true;
    String depth = args.get("depth") instanceof String d ? d : "full";
    boolean runDeepAnalysis = !"quick".equalsIgnoreCase(depth);

    {
      Map<String, Object> diagnosis = new LinkedHashMap<>();
      diagnosis.put("recordingPath", target.recordingPath().toString());
      diagnosis.put("sessionId", target.sessionId());

      // Step 1: Get summary data
      progress.step(0, 6, "Running summary...");
      Map<String, Object> summary = summary(target, Progress.NONE);

      // Extract key metrics
      Long totalEvents = ((Number) summary.get("totalEvents")).longValue();
      Map<String, Object> highlights = (Map<String, Object>) summary.get("highlights");

      List<String> headlines = new ArrayList<>();
      List<String> recommendations = new ArrayList<>();
      List<String> capabilityGaps = new ArrayList<>();
      List<Finding> thresholdFindings = new ArrayList<>();
      Map<String, Object> analyses = new LinkedHashMap<>();

      // Step 2: Analyze exception patterns
      progress.step(1, 6, "Analyzing exceptions...");
      if (highlights.containsKey("exceptions")) {
        Map<String, Object> exceptionStats = (Map<String, Object>) highlights.get("exceptions");
        Long exceptionCount = ((Number) exceptionStats.get("totalExceptions")).longValue();

        if (exceptionCount > 1000) {
          headlines.add(
              String.format("HIGH EXCEPTION RATE: %,d exceptions detected", exceptionCount));
          thresholdFindings.add(
              Finding.of("exceptions", "rate")
                  .warning()
                  .title("High exception rate: %,d exceptions", exceptionCount)
                  .description(
                      "Exception construction fills in stack traces, which is expensive when it"
                          + " happens on a hot path. High rates usually mean control flow by"
                          + " exception, a misconfiguration, or a failing dependency.")
                  .source("jfr_diagnose")
                  .evidence("totalExceptions", exceptionCount)
                  .action("Identify the dominant exception type and its throw site")
                  .build());

          // Run exception analysis
          if (includeAnalysis) {
            try {
              analyses.put("exceptions", exceptions(target, args, Progress.NONE));
            } catch (Exception e) {
              LOG.debug("Exception analysis unavailable during diagnose");
            }
          }

          recommendations.add(
              "Investigate exception types - high exception rates often indicate misconfiguration "
                  + "or error handling issues");
        } else if (exceptionCount > 100) {
          headlines.add(
              String.format("MODERATE EXCEPTION RATE: %,d exceptions detected", exceptionCount));
          thresholdFindings.add(
              Finding.of("exceptions", "rate")
                  .info()
                  .title("Moderate exception rate: %,d exceptions", exceptionCount)
                  .source("jfr_diagnose")
                  .evidence("totalExceptions", exceptionCount)
                  .build());
        }
      }

      // Step 3: Analyze GC pressure
      progress.step(2, 6, "Analyzing GC pressure...");
      if (highlights.containsKey("gc")) {
        Map<String, Object> gcStats = (Map<String, Object>) highlights.get("gc");
        if (gcStats.containsKey("totalCollections")) {
          Long gcCount = ((Number) gcStats.get("totalCollections")).longValue();
          Double avgPauseMs = ((Number) gcStats.get("avgPauseMs")).doubleValue();
          Double totalPauseMs = ((Number) gcStats.get("totalPauseMs")).doubleValue();

          if (avgPauseMs > 100 || totalPauseMs > 10000) {
            headlines.add(
                String.format(
                    "HIGH GC PRESSURE: %,d collections, %.1fms avg pause, %.1fs total pause",
                    gcCount, avgPauseMs, totalPauseMs / 1000.0));
            thresholdFindings.add(
                Finding.of("gc", "pressure")
                    .warning()
                    .title(
                        "High GC pressure: %,d collections, %.1f ms average pause",
                        gcCount, avgPauseMs)
                    .description(
                        "Compare total pause against the recording wall clock before acting: the"
                            + " fraction of time lost to pauses is what matters, not the count.")
                    .source("jfr_diagnose")
                    .evidence("totalCollections", gcCount)
                    .evidence("avgPauseMs", avgPauseMs)
                    .evidence("totalPauseMs", totalPauseMs)
                    .action("Find allocation hotspots before tuning collector flags")
                    .query("events/jdk.GCPhasePause | quantiles(0.5, 0.9, 0.99, path=duration)")
                    .build());

            recommendations.add(
                "GC pressure indicates memory saturation - consider running jfr_use to analyze "
                    + "memory resource utilization");

            // Detect and recommend appropriate allocation event type
            String allocEventTypeForGc = detectAllocationEventType(target);
            if (allocEventTypeForGc != null) {
              recommendations.add(
                  String.format(
                      "Run jfr_flamegraph with %s to identify allocation hotspots",
                      allocEventTypeForGc));
            } else {
              recommendations.add(
                  "Allocation profiling not enabled in this recording - consider enabling "
                      + "for future recordings to identify allocation hotspots");
            }
          } else if (avgPauseMs > 50 || totalPauseMs > 5000) {
            headlines.add(
                String.format(
                    "MODERATE GC PRESSURE: %,d collections, %.1fms avg pause",
                    gcCount, avgPauseMs));
            thresholdFindings.add(
                Finding.of("gc", "pressure")
                    .info()
                    .title(
                        "Moderate GC pressure: %,d collections, %.1f ms average pause",
                        gcCount, avgPauseMs)
                    .source("jfr_diagnose")
                    .evidence("totalCollections", gcCount)
                    .evidence("avgPauseMs", avgPauseMs)
                    .evidence("totalPauseMs", totalPauseMs)
                    .build());
          }
        }
      }

      // Step 4: Analyze CPU patterns
      progress.step(3, 6, "Analyzing CPU patterns...");
      if (highlights.containsKey("cpu")) {
        Map<String, Object> cpuStats = (Map<String, Object>) highlights.get("cpu");
        Long cpuSamples = ((Number) cpuStats.get("totalSamples")).longValue();

        if (cpuSamples > 5000) {
          headlines.add(String.format("CPU INTENSIVE: %,d execution samples captured", cpuSamples));

          // Run hotmethods analysis
          try {
            Map<String, Object> hotmethods = hotmethods(target, args, Progress.NONE);
            if (includeAnalysis) {
              analyses.put("hotmethods", hotmethods);
            }
            thresholdFindings.addAll(topHotMethodFindings(hotmethods));
          } catch (Exception e) {
            LOG.debug("Hot method analysis unavailable during diagnose");
          }

          recommendations.add(
              "Run jfr_flamegraph with execution samples to understand full call stacks");
        }
      }

      // Step 5: Resource bottlenecks (USE) - run it rather than only recommending it
      Map<String, Object> useResult = null;
      Map<String, Object> tsaResult = null;
      if (runDeepAnalysis) {
        progress.step(4, 6, "Analyzing resources (USE)...");
        try {
          useResult = use(target, args, Progress.NONE);
          if (includeAnalysis) {
            analyses.put("use", useResult);
          }
        } catch (Exception e) {
          LOG.debug("USE analysis unavailable during diagnose");
        }

        // Step 6: Thread states (TSA)
        progress.step(5, 6, "Analyzing thread states (TSA)...");
        try {
          tsaResult = tsa(target, args, Progress.NONE);
          if (includeAnalysis) {
            analyses.put("tsa", tsaResult);
          }
        } catch (Exception e) {
          LOG.debug("TSA analysis unavailable during diagnose");
        }
      } else {
        recommendations.add(
            "Run jfr_use and jfr_tsa for resource and thread-state analysis "
                + "(or call jfr_diagnose with depth=full)");
      }

      // Capability gaps: what this recording cannot answer, stated separately from findings
      String allocEventType = detectAllocationEventType(target);
      if (allocEventType != null) {
        headlines.add(
            String.format(
                "ALLOCATION PROFILING: %s events available for analysis", allocEventType));
      } else {
        headlines.add("ALLOCATION PROFILING: Not enabled in this recording");
        capabilityGaps.add(
            "Allocation profiling was not enabled, so allocation and memory-churn questions "
                + "cannot be answered from this recording. Enable with "
                + "-XX:StartFlightRecording:settings=profile (JDK) or use a profiler that "
                + "records allocation samples.");
        recommendations.add(
            "Consider enabling allocation profiling (JDK: -XX:StartFlightRecording:settings=profile, "
                + "Datadog: included by default) for memory analysis");
      }
      if (detectExecutionEventType(target) == null) {
        capabilityGaps.add(
            "No execution-sample events were found, so CPU attribution is not possible from "
                + "this recording.");
      }

      // Build the merged, de-duplicated findings list
      List<Finding> merged =
          Findings.merge(
              thresholdFindings,
              JfrFindings.fromUse(
                  useResult == null ? null : asStringObjectMap(useResult.get("resources")),
                  "jfr_use"),
              JfrFindings.fromTsa(tsaResult, "jfr_tsa"));

      diagnosis.put("findings", Findings.toMaps(merged));
      diagnosis.put("findingCounts", Findings.countBySeverity(merged));
      diagnosis.put("headlines", headlines);
      diagnosis.put("recommendations", recommendations);
      diagnosis.put("capabilityGaps", capabilityGaps);
      diagnosis.put("analysisDepth", runDeepAnalysis ? "full" : "quick");

      if (includeAnalysis && !analyses.isEmpty()) {
        diagnosis.put("detailedAnalysis", analyses);
      }

      // Add summary for context
      diagnosis.put(
          "summary",
          Map.of(
              "totalEvents", totalEvents,
              "eventTypes", summary.get("totalEventTypes"),
              "highlights", highlights));

      progress.step(6, 6, "Done");
      return diagnosis;
    }
  }

  List<Finding> topHotMethodFindings(Map<String, Object> hotmethods) {
    List<Finding> findings = new ArrayList<>();
    Object methodsObj = hotmethods.get("methods");
    Object totalObj = hotmethods.get("totalSamples");
    if (!(methodsObj instanceof List<?> methods) || !(totalObj instanceof Number total)) {
      return findings;
    }
    long totalSamples = total.longValue();
    if (totalSamples <= 0) {
      return findings;
    }
    for (Object entry : methods) {
      if (!(entry instanceof Map<?, ?> raw)) {
        continue;
      }
      Map<String, Object> method = (Map<String, Object>) raw;
      Object samplesObj = method.get("samples");
      if (!(samplesObj instanceof Number samples)) {
        continue;
      }
      double pct = samples.doubleValue() * 100.0 / totalSamples;
      if (pct < 5.0) {
        continue;
      }
      String name = String.valueOf(method.get("method"));
      findings.add(
          Finding.of("cpu", "hot-method-" + name)
              .warning()
              .title("Hot method: %s holds %.1f%% of execution samples", name, pct)
              .description(
                  "Self time only - this is the leaf frame of the sampled stacks, not the cost of"
                      + " the whole call path.")
              .source("jfr_hotmethods")
              .evidence("method", name)
              .evidence("samples", samples.longValue())
              .evidence("totalSamples", totalSamples)
              .evidence("selfPct", pct)
              .evidence("type", method.get("type"))
              .action("Use jfr_flamegraph bottom-up to see which call paths reach this frame")
              .build());
    }
    return findings;
  }

  static Map<String, Object> asStringObjectMap(Object value) {
    return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
  }

  static final Set<String> BLOCKING_STATES =
      Set.of("WAITING", "BLOCKED", "PARKED", "TIMED_WAITING");

  static class ExceptionAnalysis {
    final LongAdder totalEvents = new LongAdder();
    final LongAdder totalExceptions = new LongAdder();
    final Map<String, Long> exceptionTypes = new ConcurrentHashMap<>();
    final Map<String, Long> throwSites = new ConcurrentHashMap<>();
    final Map<String, Map<String, Long>> throwSitesByType = new ConcurrentHashMap<>();
    final Map<String, String> topThrowSiteByType = new ConcurrentHashMap<>();
  }

  static class ThreadStateMetrics {
    final String threadId;
    final String threadName;
    final LongAdder totalSamples = new LongAdder();
    final Map<String, Long> stateCount = new ConcurrentHashMap<>();

    ThreadStateMetrics(String threadId, String threadName) {
      this.threadId = threadId;
      this.threadName = threadName;
    }
  }
}
