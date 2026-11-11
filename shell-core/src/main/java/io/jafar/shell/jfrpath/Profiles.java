package io.jafar.shell.jfrpath;

import static io.jafar.shell.jfrpath.JfrPath.*;

import io.jafar.parser.api.ArrayType;
import io.jafar.parser.api.ComplexType;
import io.jafar.parser.api.Values;
import io.jafar.shell.JFRSession;
import io.jafar.shell.core.FlameNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Flame graph and stack-profile aggregation and rendering. */
final class Profiles {
  static final int SPARKLINE_WIDTH = 30;
  private final JfrPathEvaluator eval;

  Profiles(JfrPathEvaluator eval) {
    this.eval = eval;
  }

  private boolean matchesAll(Map<String, Object> map, List<Predicate> predicates) {
    return MatchEngine.matchesAll(map, predicates, eval.defaultListMatchMode);
  }

  List<Map<String, Object>> aggregateFlameGraph(JFRSession session, Query query, String direction)
      throws Exception {
    if (query.root != Root.EVENTS || query.eventTypes.isEmpty()) {
      throw new IllegalArgumentException("flamegraph requires an event type");
    }
    eval.validateEventTypes(session, query.eventTypes);

    FlameNode root = new FlameNode("root");
    Map<String, String> interner = new HashMap<>();
    if (query.isMultiType) {
      Set<String> typeSet = new HashSet<>(query.eventTypes);
      eval.source.streamEvents(
          session.getRecordingPath(),
          ev -> {
            if (!typeSet.contains(ev.typeName())) return;
            Map<String, Object> map = ev.value();
            if (!matchesAll(map, query.predicates)) return;
            List<String> frames = extractFramesForProfile(map, direction);
            if (!frames.isEmpty()) {
              frames.replaceAll(f -> interner.computeIfAbsent(f, k -> k));
              root.addPath(frames);
            }
          });
    } else {
      String eventType = query.eventTypes.get(0);
      eval.source.streamEvents(
          session.getRecordingPath(),
          ev -> {
            if (!eventType.equals(ev.typeName())) return;
            Map<String, Object> map = ev.value();
            if (!matchesAll(map, query.predicates)) return;
            List<String> frames = extractFramesForProfile(map, direction);
            if (!frames.isEmpty()) {
              frames.replaceAll(f -> interner.computeIfAbsent(f, k -> k));
              root.addPath(frames);
            }
          });
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("__flamegraph", root);
    return List.of(result);
  }

  List<Map<String, Object>> applyFlameGraph(List<Map<String, Object>> rows, String direction) {
    FlameNode root = new FlameNode("root");
    Map<String, String> interner = new HashMap<>();
    for (Map<String, Object> row : rows) {
      List<String> frames = extractFramesForProfile(row, direction);
      if (!frames.isEmpty()) {
        frames.replaceAll(f -> interner.computeIfAbsent(f, k -> k));
        root.addPath(frames);
      }
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("__flamegraph", root);
    return List.of(result);
  }

  // === Stack Profile ===

  static final class ProfileNode {
    final String name;
    long total;
    long self;
    long[] timeBuckets;
    final Map<String, ProfileNode> children = new LinkedHashMap<>();
    final Map<String, Long> threadCounts = new LinkedHashMap<>();

    ProfileNode(String name, int bucketCount) {
      this.name = name;
      this.timeBuckets = new long[bucketCount];
    }

    void addPath(List<String> frames, int frameIdx, int bucketIdx, String threadName) {
      total++;
      threadCounts.merge(threadName, 1L, Long::sum);
      if (bucketIdx >= 0 && bucketIdx < timeBuckets.length) {
        timeBuckets[bucketIdx]++;
      }
      if (frameIdx >= frames.size()) {
        self++;
        return;
      }
      String head = frames.get(frameIdx);
      ProfileNode child =
          children.computeIfAbsent(head, n -> new ProfileNode(n, timeBuckets.length));
      child.addPath(frames, frameIdx + 1, bucketIdx, threadName);
    }
  }

  private static void pruneChildren(ProfileNode node, long threshold) {
    if (node.children.isEmpty()) return;
    var it = node.children.entrySet().iterator();
    while (it.hasNext()) {
      ProfileNode child = it.next().getValue();
      if (child.total < threshold) {
        it.remove();
      } else {
        pruneChildren(child, threshold);
      }
    }
  }

  private static int countNodes(ProfileNode node) {
    int count = 1;
    for (ProfileNode child : node.children.values()) count += countNodes(child);
    return count;
  }

  @SuppressWarnings("unchecked")
  List<String> extractFramesForProfile(Map<String, Object> event, String direction) {
    List<String> frames = new ArrayList<>();

    Object stackTrace = event.get("stackTrace");
    if (stackTrace instanceof ComplexType ct) stackTrace = ct.getValue();
    if (stackTrace == null) return frames;

    Object framesObj = null;
    if (stackTrace instanceof Map<?, ?> stMap) {
      framesObj = stMap.get("frames");
    }
    if (framesObj == null) return frames;

    // Unwrap ArrayType wrapper
    if (framesObj instanceof ArrayType at) {
      framesObj = at.getArray();
    }

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

    if (frameArray == null || frameArray.length == 0) return frames;

    for (Object frame : frameArray) {
      String methodName = extractMethodNameForProfile(frame);
      if (methodName != null) {
        frames.add(methodName);
      }
    }

    // JFR stores frames[0] = top of stack (most recent call)
    // bottom-up: keep as-is (hot method first)
    // top-down: reverse (entry point first)
    if ("top-down".equals(direction)) {
      Collections.reverse(frames);
    }
    return frames;
  }

  @SuppressWarnings("unchecked")
  String extractMethodNameForProfile(Object frame) {
    if (frame == null) return null;

    if (frame instanceof ComplexType ct) {
      frame = ct.getValue();
    }
    if (!(frame instanceof Map<?, ?> fm)) return null;
    Map<String, Object> frameMap = (Map<String, Object>) fm;

    Object method = frameMap.get("method");
    if (method == null) return null;
    if (method instanceof ComplexType ct) method = ct.getValue();
    if (!(method instanceof Map<?, ?> mm)) return null;
    Map<String, Object> methodMap = (Map<String, Object>) mm;

    String className = "";
    Object type = methodMap.get("type");
    if (type instanceof ComplexType ct) type = ct.getValue();
    if (type instanceof Map<?, ?> typeMap) {
      Object name = typeMap.get("name");
      if (name instanceof ComplexType ct) name = ct.getValue();
      if (name instanceof Map<?, ?> nameMap) {
        Object str = nameMap.get("string");
        if (str != null) className = str.toString();
      } else if (name != null) {
        className = name.toString();
      }
    }

    String methodName = "";
    Object nameObj = methodMap.get("name");
    if (nameObj instanceof ComplexType ct) nameObj = ct.getValue();
    if (nameObj instanceof Map<?, ?> nameMap) {
      Object str = nameMap.get("string");
      if (str != null) methodName = str.toString();
    } else if (nameObj != null) {
      methodName = nameObj.toString();
    }

    if (className.isEmpty() && methodName.isEmpty()) return null;
    String qualName = className.isEmpty() ? methodName : className + "." + methodName;
    Object line = frameMap.get("lineNumber");
    if (line != null && !"-1".equals(line.toString())) {
      qualName += ":" + line;
    }
    return qualName;
  }

  private static String extractThreadName(Map<String, Object> event) {
    Object tn = Values.get(event, "eventThread", "osName");
    if (tn == null) tn = Values.get(event, "eventThread", "javaName");
    if (tn == null) tn = Values.get(event, "sampledThread", "osName");
    if (tn == null) tn = Values.get(event, "sampledThread", "javaName");
    return tn != null ? tn.toString() : "<unknown>";
  }

  private static boolean hasStackTraceFrames(Map<String, Object> event) {
    Object stackTrace = event.get("stackTrace");
    if (stackTrace instanceof ComplexType ct) stackTrace = ct.getValue();
    if (stackTrace == null) return false;
    Object framesObj = null;
    if (stackTrace instanceof Map<?, ?> stMap) framesObj = stMap.get("frames");
    if (framesObj == null) return false;
    if (framesObj instanceof ArrayType at) framesObj = at.getArray();
    if (framesObj != null && framesObj.getClass().isArray()) {
      return java.lang.reflect.Array.getLength(framesObj) > 0;
    }
    if (framesObj instanceof List<?> list) return !list.isEmpty();
    return false;
  }

  long[] scanStackProfileStats(JFRSession session, Query query) throws Exception {
    long[] stats = {0, Long.MAX_VALUE, Long.MIN_VALUE}; // count, min, max
    if (query.isMultiType) {
      Set<String> typeSet = new HashSet<>(query.eventTypes);
      eval.source.streamEvents(
          session.getRecordingPath(),
          ev -> {
            if (!typeSet.contains(ev.typeName())) return;
            Map<String, Object> map = ev.value();
            if (!matchesAll(map, query.predicates)) return;
            if (!hasStackTraceFrames(map)) return;
            Object st = map.get("startTime");
            long ts = st instanceof Number n ? n.longValue() : 0L;
            stats[0]++;
            if (ts < stats[1]) stats[1] = ts;
            if (ts > stats[2]) stats[2] = ts;
          });
    } else {
      String eventType = query.eventTypes.get(0);
      eval.source.streamEvents(
          session.getRecordingPath(),
          ev -> {
            if (!eventType.equals(ev.typeName())) return;
            Map<String, Object> map = ev.value();
            if (!matchesAll(map, query.predicates)) return;
            if (!hasStackTraceFrames(map)) return;
            Object st = map.get("startTime");
            long ts = st instanceof Number n ? n.longValue() : 0L;
            stats[0]++;
            if (ts < stats[1]) stats[1] = ts;
            if (ts > stats[2]) stats[2] = ts;
          });
    }
    return stats;
  }

  List<Map<String, Object>> aggregateStackProfile(
      JFRSession session,
      Query query,
      String direction,
      int buckets,
      double minPct,
      JfrPathEvaluator.ProgressListener progress)
      throws Exception {
    if (query.root != Root.EVENTS || query.eventTypes.isEmpty()) {
      throw new IllegalArgumentException("stackprofile requires an event type");
    }

    eval.validateEventTypes(session, query.eventTypes);

    // Pass 1: collect statistics
    if (progress != null) {
      progress.onProgress(0, 3, "Scanning statistics...");
    }
    long[] stats = scanStackProfileStats(session, query);
    long totalCount = stats[0];
    long minTime = stats[1];
    long maxTime = stats[2];
    if (totalCount == 0) return List.of();
    if (progress != null) {
      progress.onProgress(1, 3, "Statistics collected: " + totalCount + " events");
    }

    long range = maxTime - minTime;
    long[] pruneState = {(long) Math.floor(totalCount * minPct / 100.0)};
    ProfileNode root = new ProfileNode("root", buckets);
    HashMap<String, String> interner = new HashMap<>();
    long[] eventCount = {0};

    // Pass 2: build tree inline (no intermediate lists)
    long tc = totalCount;
    if (query.isMultiType) {
      Set<String> typeSet = new HashSet<>(query.eventTypes);
      eval.source.streamEvents(
          session.getRecordingPath(),
          ev -> {
            if (!typeSet.contains(ev.typeName())) return;
            Map<String, Object> map = ev.value();
            if (!matchesAll(map, query.predicates)) return;
            List<String> frames = extractFramesForProfile(map, direction);
            if (frames.isEmpty()) return;
            for (int j = 0; j < frames.size(); j++) {
              frames.set(j, interner.computeIfAbsent(frames.get(j), k -> k));
            }
            Object st = map.get("startTime");
            long ts = st instanceof Number n ? n.longValue() : 0L;
            int bucketIdx = range == 0 ? 0 : (int) ((ts - minTime) * (buckets - 1) / range);
            String threadName = extractThreadName(map);
            threadName = interner.computeIfAbsent(threadName, k -> k);
            root.addPath(frames, 0, bucketIdx, threadName);
            eventCount[0]++;
            if (eventCount[0] % 100_000 == 0) {
              if (progress != null) {
                progress.onProgress(
                    1.0 + (double) eventCount[0] / tc,
                    3,
                    "Building profile tree: " + eventCount[0] + "/" + tc + " events");
              }
              if (pruneState[0] > 0) {
                pruneChildren(root, pruneState[0]);
              } else {
                int nodeCount = countNodes(root);
                if (nodeCount > 500_000) {
                  pruneState[0] = Math.max(1, eventCount[0] / 1000);
                  pruneChildren(root, pruneState[0]);
                }
              }
            }
          });
    } else {
      String eventType = query.eventTypes.get(0);
      eval.source.streamEvents(
          session.getRecordingPath(),
          ev -> {
            if (!eventType.equals(ev.typeName())) return;
            Map<String, Object> map = ev.value();
            if (!matchesAll(map, query.predicates)) return;
            List<String> frames = extractFramesForProfile(map, direction);
            if (frames.isEmpty()) return;
            for (int j = 0; j < frames.size(); j++) {
              frames.set(j, interner.computeIfAbsent(frames.get(j), k -> k));
            }
            Object st = map.get("startTime");
            long ts = st instanceof Number n ? n.longValue() : 0L;
            int bucketIdx = range == 0 ? 0 : (int) ((ts - minTime) * (buckets - 1) / range);
            String threadName = extractThreadName(map);
            threadName = interner.computeIfAbsent(threadName, k -> k);
            root.addPath(frames, 0, bucketIdx, threadName);
            eventCount[0]++;
            if (eventCount[0] % 100_000 == 0) {
              if (progress != null) {
                progress.onProgress(
                    1.0 + (double) eventCount[0] / tc,
                    3,
                    "Building profile tree: " + eventCount[0] + "/" + tc + " events");
              }
              if (pruneState[0] > 0) {
                pruneChildren(root, pruneState[0]);
              } else {
                int nodeCount = countNodes(root);
                if (nodeCount > 500_000) {
                  pruneState[0] = Math.max(1, eventCount[0] / 1000);
                  pruneChildren(root, pruneState[0]);
                }
              }
            }
          });
    }

    // Final prune
    if (pruneState[0] > 0) {
      pruneChildren(root, pruneState[0]);
    }

    if (progress != null) {
      progress.onProgress(2, 3, "Tree built, generating output...");
    }
    List<Map<String, Object>> result = flattenProfileTree(root, root.total, minPct, buckets);
    if (progress != null) {
      progress.onProgress(3, 3, "Done");
    }
    return result;
  }

  List<Map<String, Object>> applyStackProfile(
      List<Map<String, Object>> rows, String direction, int buckets, double minPct) {
    // Pass 1: collect statistics (no allocation)
    long totalCount = 0;
    long minTime = Long.MAX_VALUE;
    long maxTime = Long.MIN_VALUE;
    for (Map<String, Object> row : rows) {
      if (!hasStackTraceFrames(row)) continue;
      Object st = row.get("startTime");
      long ts = st instanceof Number n ? n.longValue() : 0L;
      totalCount++;
      if (ts < minTime) minTime = ts;
      if (ts > maxTime) maxTime = ts;
    }
    if (totalCount == 0) return List.of();

    long range = maxTime - minTime;
    long pruneThreshold = (long) Math.floor(totalCount * minPct / 100.0);
    ProfileNode root = new ProfileNode("root", buckets);
    HashMap<String, String> interner = new HashMap<>();
    long eventCount = 0;

    // Pass 2: build tree inline (no intermediate lists)
    for (Map<String, Object> row : rows) {
      List<String> frames = extractFramesForProfile(row, direction);
      if (frames.isEmpty()) continue;
      for (int j = 0; j < frames.size(); j++) {
        frames.set(j, interner.computeIfAbsent(frames.get(j), k -> k));
      }
      Object st = row.get("startTime");
      long ts = st instanceof Number n ? n.longValue() : 0L;
      int bucketIdx = range == 0 ? 0 : (int) ((ts - minTime) * (buckets - 1) / range);
      String threadName = extractThreadName(row);
      threadName = interner.computeIfAbsent(threadName, k -> k);
      root.addPath(frames, 0, bucketIdx, threadName);
      eventCount++;
      if (eventCount % 100_000 == 0) {
        if (pruneThreshold > 0) {
          pruneChildren(root, pruneThreshold);
        } else {
          int nodeCount = countNodes(root);
          if (nodeCount > 500_000) {
            pruneThreshold = Math.max(1, eventCount / 1000);
            pruneChildren(root, pruneThreshold);
          }
        }
      }
    }

    // Final prune
    if (pruneThreshold > 0) {
      pruneChildren(root, pruneThreshold);
    }

    return flattenProfileTree(root, root.total, minPct, buckets);
  }

  List<Map<String, Object>> flattenProfileTree(
      ProfileNode root, long totalSamples, double minPct, int bucketCount) {
    List<Map<String, Object>> rows = new ArrayList<>();
    // Flatten children of root (root itself is synthetic)
    List<ProfileNode> sorted = new ArrayList<>(root.children.values());
    sorted.sort((a, b) -> Long.compare(b.total, a.total));
    for (ProfileNode child : sorted) {
      flattenNode(child, totalSamples, minPct, rows, bucketCount, 0);
    }
    return rows;
  }

  void flattenNode(
      ProfileNode node,
      long totalSamples,
      double minPct,
      List<Map<String, Object>> rows,
      int bucketCount,
      int depth) {
    double pct = totalSamples == 0 ? 0 : (node.total * 100.0) / totalSamples;
    if (pct < minPct) return;

    double selfPctOfTotal = totalSamples == 0 ? 0 : (node.self * 100.0) / totalSamples;
    String pattern = bucketPattern(node.timeBuckets);
    String marker = "";
    if (selfPctOfTotal >= 1.0) {
      marker = "steady".equals(pattern) ? "\u25c6\u25c6" : "\u25c6";
    }
    String indent = depth > 0 ? " ".repeat(depth) : "";

    Map<String, Object> row = new LinkedHashMap<>();
    row.put("timeBuckets", sparkline(node.timeBuckets, SPARKLINE_WIDTH));
    row.put(" ", marker);
    row.put("method", indent + node.name);
    if (!node.threadCounts.isEmpty()) {
      // Sort by count descending, format with percentage and proportional bar
      long maxCount =
          node.threadCounts.values().stream().mapToLong(Long::longValue).max().orElse(1L);
      LinkedHashMap<String, String> threads = new LinkedHashMap<>();
      node.threadCounts.entrySet().stream()
          .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
          .forEach(
              e -> {
                long cnt = e.getValue();
                double threadPct = node.total == 0 ? 0 : (cnt * 100.0) / node.total;
                threads.put(
                    e.getKey(),
                    String.format("%d (%4.1f%%) %s", cnt, threadPct, threadBar(cnt, maxCount, 10)));
              });
      row.put("threads", threads);
    }
    Map<String, Object> profile = new LinkedHashMap<>();
    profile.put("self", node.self);
    profile.put("total", node.total);
    profile.put("totalPct", String.format("%.1f%%", pct));
    double selfPct = node.total == 0 ? 0 : (node.self * 100.0) / node.total;
    profile.put("selfPct", String.format("%.1f%%", selfPct));
    profile.put("pattern", pattern);
    profile.put("timeBuckets", node.timeBuckets.clone());
    profile.put("threadCounts", new LinkedHashMap<>(node.threadCounts));
    row.put("profile", profile);
    rows.add(row);

    List<ProfileNode> sorted = new ArrayList<>(node.children.values());
    sorted.sort((a, b) -> Long.compare(b.total, a.total));
    for (ProfileNode child : sorted) {
      flattenNode(child, totalSamples, minPct, rows, bucketCount, depth + 1);
    }
  }

  private static String threadBar(long count, long maxCount, int width) {
    if (maxCount == 0) return "";
    double ratio = (double) count / maxCount;
    double filled = ratio * width;
    int fullBlocks = (int) filled;
    int fractional = (int) ((filled - fullBlocks) * 8);
    char[] fracs = {'\u258F', '\u258E', '\u258D', '\u258C', '\u258B', '\u258A', '\u2589'};
    StringBuilder sb = new StringBuilder(width);
    for (int i = 0; i < fullBlocks && i < width; i++) sb.append('\u2588');
    if (fullBlocks < width && fractional > 0) sb.append(fracs[fractional - 1]);
    return sb.toString();
  }

  /**
   * Map long[] values to sparkline characters, stretched to fill targetWidth. Each bucket maps to
   * one or more characters proportionally.
   */
  private static String sparkline(long[] values, int targetWidth) {
    if (values == null || values.length == 0) return "";
    char[] blocks = {
      '\u2581', '\u2582', '\u2583', '\u2584', '\u2585', '\u2586', '\u2587', '\u2588'
    };
    int n = values.length;
    int width = Math.max(n, targetWidth);
    long max = 0;
    for (long v : values) {
      if (v > max) max = v;
    }
    StringBuilder sb = new StringBuilder(width);
    for (int i = 0; i < n; i++) {
      char ch = max == 0 ? blocks[0] : blocks[(int) (values[i] * (blocks.length - 1) / max)];
      // Number of chars for this bucket: evenly distribute width across n buckets
      int colEnd = (int) ((long) (i + 1) * width / n);
      int colStart = (int) ((long) i * width / n);
      int repeat = colEnd - colStart;
      for (int r = 0; r < repeat; r++) sb.append(ch);
    }
    return sb.toString();
  }

  /**
   * Classify time bucket distribution as "steady" or "bursty". Uses coefficient of variation
   * (stddev/mean) of non-zero buckets. A steady pattern (low CV) with high self% suggests
   * repetitive work like N+1 queries.
   */
  private static String bucketPattern(long[] buckets) {
    if (buckets == null || buckets.length == 0) return "unknown";
    int nonZero = 0;
    long sum = 0;
    for (long b : buckets) {
      if (b > 0) {
        nonZero++;
        sum += b;
      }
    }
    if (nonZero <= 1) return "bursty";
    double mean = (double) sum / buckets.length;
    double variance = 0;
    for (long b : buckets) {
      double diff = b - mean;
      variance += diff * diff;
    }
    variance /= buckets.length;
    double cv = Math.sqrt(variance) / mean;
    // CV < 0.5 means values are within ~50% of the mean — fairly uniform
    return cv < 0.5 ? "steady" : "bursty";
  }
}
