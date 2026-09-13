package io.jafar.shell.core.analysis;

import io.jafar.shell.jfrpath.JfrPath;
import io.jafar.shell.jfrpath.JfrPathEvaluator;
import io.jafar.shell.jfrpath.JfrPathParser;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

  private final JfrPathEvaluator evaluator;

  public JfrAnalyses() {
    this(new JfrPathEvaluator());
  }

  public JfrAnalyses(JfrPathEvaluator evaluator) {
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

  List<String> extractFrames(Map<String, Object> event, String direction, Integer maxDepth) {
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

  Object unwrapValue(Object obj) {
    if (obj instanceof io.jafar.parser.api.ArrayType arr) {
      return arr.getArray();
    }
    if (obj instanceof io.jafar.parser.api.ComplexType ct) {
      return ct.getValue();
    }
    return obj;
  }
}
