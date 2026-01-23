package io.jafar.shell.jfrpath;

import static io.jafar.shell.jfrpath.JfrPath.*;

import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.api.Values;
import io.jafar.shell.JFRSession;
import io.jafar.shell.providers.ChunkProvider;
import io.jafar.shell.providers.ConstantPoolProvider;
import io.jafar.shell.providers.MetadataProvider;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Minimal evaluator for JfrPath queries.
 *
 * <p>v0 support: events/<type>[field/op/literal] ...
 */
public final class JfrPathEvaluator {
  public interface EventSource {
    void streamEvents(Path recording, Consumer<Event> consumer) throws Exception;
  }

  public record Event(String typeName, Map<String, Object> value) {}

  private final EventSource source;
  private final JfrPath.MatchMode defaultListMatchMode;

  public JfrPathEvaluator() {
    this(new DefaultEventSource(), JfrPath.MatchMode.ANY);
  }

  public JfrPathEvaluator(EventSource source) {
    this(source, JfrPath.MatchMode.ANY);
  }

  public JfrPathEvaluator(EventSource source, JfrPath.MatchMode defaultListMatchMode) {
    this.source = Objects.requireNonNull(source);
    this.defaultListMatchMode =
        defaultListMatchMode == null ? JfrPath.MatchMode.ANY : defaultListMatchMode;
  }

  public JfrPathEvaluator(JfrPath.MatchMode defaultListMatchMode) {
    this(new DefaultEventSource(), defaultListMatchMode);
  }

  public List<Map<String, Object>> evaluate(JFRSession session, Query query) throws Exception {
    if (query.pipeline != null && !query.pipeline.isEmpty()) {
      return evaluateAggregate(session, query);
    }
    if (query.root == Root.EVENTS) {
      if (query.eventTypes.isEmpty()) {
        throw new IllegalArgumentException("Expected event type segment after 'events/'");
      }

      // Validate event types exist
      validateEventTypes(session, query.eventTypes);

      List<Map<String, Object>> out = new ArrayList<>();

      if (query.isMultiType) {
        // Multi-type query: use Set for O(1) lookup
        Set<String> typeSet = new java.util.HashSet<>(query.eventTypes);
        source.streamEvents(
            session.getRecordingPath(),
            ev -> {
              if (!typeSet.contains(ev.typeName)) return;
              Map<String, Object> map = ev.value;
              if (matchesAll(map, query.predicates)) {
                out.add(Values.resolvedShallow(map));
              }
            });
      } else {
        // Single-type query: use direct comparison (faster)
        String eventType = query.eventTypes.get(0);
        source.streamEvents(
            session.getRecordingPath(),
            ev -> {
              if (!eventType.equals(ev.typeName)) return;
              Map<String, Object> map = ev.value;
              if (matchesAll(map, query.predicates)) {
                out.add(Values.resolvedShallow(map));
              }
            });
      }
      return out;
    } else if (query.root == Root.METADATA) {
      // Handle metadata queries: metadata/Type or metadata/[filter] or metadata
      if (query.segments.isEmpty()) {
        // No type specified: load all metadata types and filter
        // This handles: metadata or metadata/[filter]
        List<Map<String, Object>> allMetadata =
            MetadataProvider.loadAllClasses(session.getRecordingPath());
        if (query.predicates.isEmpty()) {
          return allMetadata;
        }
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> meta : allMetadata) {
          if (matchesAll(meta, query.predicates)) {
            filtered.add(meta);
          }
        }
        return filtered;
      }
      // Specific type: metadata/TypeName or metadata/TypeName[filter]
      String typeName = query.segments.get(0);
      Map<String, Object> meta = MetadataProvider.loadClass(session.getRecordingPath(), typeName);
      if (meta == null) return java.util.Collections.emptyList();
      if (matchesAll(meta, query.predicates)) {
        return java.util.List.of(meta);
      } else {
        return java.util.Collections.emptyList();
      }
    } else if (query.root == Root.CHUNKS) {
      if (!query.segments.isEmpty()) {
        // show chunks/0 - specific chunk by ID
        try {
          int chunkId = Integer.parseInt(query.segments.get(0));
          Map<String, Object> chunk = ChunkProvider.loadChunk(session.getRecordingPath(), chunkId);
          if (chunk == null) return java.util.Collections.emptyList();
          return matchesAll(chunk, query.predicates)
              ? java.util.List.of(chunk)
              : java.util.Collections.emptyList();
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException("Invalid chunk index: " + query.segments.get(0));
        }
      }
      // show chunks or show chunks[filter]
      if (query.predicates.isEmpty()) {
        return ChunkProvider.loadAllChunks(session.getRecordingPath());
      } else {
        return ChunkProvider.loadChunks(
            session.getRecordingPath(), row -> matchesAll(row, query.predicates));
      }
    } else if (query.root == Root.CP) {
      if (!query.segments.isEmpty()) {
        String type = query.segments.get(0);
        return ConstantPoolProvider.loadEntries(
            session.getRecordingPath(), type, row -> matchesAll(row, query.predicates));
      } else {
        return ConstantPoolProvider.loadSummary(session.getRecordingPath());
      }
    } else {
      throw new UnsupportedOperationException("Unsupported root: " + query.root);
    }
  }

  /**
   * Evaluate events with early-stop when limit is reached; other roots fall back to {@link
   * #evaluate}.
   */
  public List<Map<String, Object>> evaluateWithLimit(JFRSession session, Query query, Integer limit)
      throws Exception {
    if (query.root != Root.EVENTS || limit == null) {
      return evaluate(session, query);
    }
    if (query.eventTypes.isEmpty()) {
      throw new IllegalArgumentException("Expected event type segment after 'events/'");
    }

    // Validate event types exist
    validateEventTypes(session, query.eventTypes);

    List<Map<String, Object>> out = new ArrayList<>();
    try (UntypedJafarParser p =
        io.jafar.parser.api.ParsingContext.create().newUntypedParser(session.getRecordingPath())) {
      if (query.isMultiType) {
        // Multi-type query: use Set for O(1) lookup
        Set<String> typeSet = new java.util.HashSet<>(query.eventTypes);
        p.handle(
            (type, value, ctl) -> {
              if (!typeSet.contains(type.getName())) return;
              @SuppressWarnings("unchecked")
              Map<String, Object> map = (Map<String, Object>) value;
              if (matchesAll(map, query.predicates)) {
                out.add(Values.resolvedShallow(map));
                if (out.size() >= limit) {
                  ctl.abort();
                }
              }
            });
      } else {
        // Single-type query: use direct comparison (faster)
        String eventType = query.eventTypes.get(0);
        p.handle(
            (type, value, ctl) -> {
              if (!eventType.equals(type.getName())) return;
              @SuppressWarnings("unchecked")
              Map<String, Object> map = (Map<String, Object>) value;
              if (matchesAll(map, query.predicates)) {
                out.add(Values.resolvedShallow(map));
                if (out.size() >= limit) {
                  ctl.abort();
                }
              }
            });
      }
      p.run();
    }
    return out;
  }

  /** Evaluate a query projecting a specific attribute path after the event type. */
  public List<Object> evaluateValues(JFRSession session, Query query) throws Exception {
    if (query.pipeline != null && !query.pipeline.isEmpty()) {
      // For projections, aggregations handled by evaluateAggregate returning rows
      throw new UnsupportedOperationException("Use evaluate(...) for aggregation pipelines");
    }
    if (query.root == Root.EVENTS) {
      if (query.segments.isEmpty()) {
        throw new IllegalArgumentException("Expected event type segment after 'events/'");
      }
      if (query.segments.size() < 2) {
        throw new IllegalArgumentException("No projection path provided after event type");
      }
      String eventType = query.segments.get(0);
      List<String> proj = query.segments.subList(1, query.segments.size());
      List<Object> out = new ArrayList<>();
      source.streamEvents(
          session.getRecordingPath(),
          ev -> {
            if (!eventType.equals(ev.typeName)) return; // filter type
            Map<String, Object> map = ev.value;
            if (matchesAll(map, query.predicates)) {
              extractWithIndexing(map, proj, out);
            }
          });
      return out;
    } else if (query.root == Root.METADATA) {
      if (query.segments.isEmpty()) {
        throw new IllegalArgumentException("Expected type segment after 'metadata/'");
      }
      if (query.segments.size() < 2) {
        throw new IllegalArgumentException("No projection path provided after metadata type");
      }
      String typeName = query.segments.get(0);
      List<String> proj = query.segments.subList(1, query.segments.size());
      Map<String, Object> meta = MetadataProvider.loadClass(session.getRecordingPath(), typeName);
      if (meta == null) return java.util.Collections.emptyList();
      if (!matchesAll(meta, query.predicates)) return java.util.Collections.emptyList();
      // Special handling: 'fields[/<name>[/...]]' maps to fieldsByName
      if (!proj.isEmpty() && ("fields".equals(proj.get(0)) || proj.get(0).startsWith("fields."))) {
        // Support alias: fields.<name>[/...]
        if (proj.get(0).startsWith("fields.")) {
          String rest = proj.get(0).substring("fields.".length());
          java.util.List<String> np = new java.util.ArrayList<>();
          np.add("fields");
          if (!rest.isEmpty()) np.add(rest);
          if (proj.size() > 1) np.addAll(proj.subList(1, proj.size()));
          proj = np;
        }
        Object fbn = meta.get("fieldsByName");
        if (!(fbn instanceof java.util.Map<?, ?> m)) {
          return java.util.Collections.emptyList();
        }
        if (proj.size() == 1) {
          java.util.List<String> names = new java.util.ArrayList<>();
          for (Object k : m.keySet()) names.add(String.valueOf(k));
          java.util.Collections.sort(names);
          return new java.util.ArrayList<>(names);
        } else {
          String fname = proj.get(1);
          Object entry = m.get(fname);
          if (!(entry instanceof java.util.Map<?, ?> em)) return java.util.Collections.emptyList();
          if (proj.size() == 2) {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> val = (java.util.Map<String, Object>) em;
            return java.util.List.of(val);
          } else {
            java.util.List<String> rest = proj.subList(2, proj.size());
            Object v = Values.get((java.util.Map<String, Object>) em, rest.toArray());
            return v == null ? java.util.Collections.emptyList() : java.util.List.of(v);
          }
        }
      }

      // Support alias: fieldsByName.<name>[/...]
      if (!proj.isEmpty()
          && ("fieldsByName".equals(proj.get(0)) || proj.get(0).startsWith("fieldsByName."))) {
        if (proj.get(0).startsWith("fieldsByName.")) {
          String rest = proj.get(0).substring("fieldsByName.".length());
          java.util.List<String> np = new java.util.ArrayList<>();
          np.add("fieldsByName");
          if (!rest.isEmpty()) np.add(rest);
          if (proj.size() > 1) np.addAll(proj.subList(1, proj.size()));
          proj = np;
        }
        Object fbn2 = meta.get("fieldsByName");
        if (!(fbn2 instanceof java.util.Map<?, ?> m2)) return java.util.Collections.emptyList();
        if (proj.size() == 1) {
          java.util.List<String> names = new java.util.ArrayList<>();
          for (Object k : m2.keySet()) names.add(String.valueOf(k));
          java.util.Collections.sort(names);
          return new java.util.ArrayList<>(names);
        }
        String fname2 = proj.get(1);
        Object entry2 = m2.get(fname2);
        if (!(entry2 instanceof java.util.Map<?, ?> em2)) return java.util.Collections.emptyList();
        if (proj.size() == 2) {
          @SuppressWarnings("unchecked")
          java.util.Map<String, Object> val2 = (java.util.Map<String, Object>) em2;
          return java.util.List.of(val2);
        }
        java.util.List<String> rest2 = proj.subList(2, proj.size());
        Object v2 = Values.get((java.util.Map<String, Object>) em2, rest2.toArray());
        return v2 == null ? java.util.Collections.emptyList() : java.util.List.of(v2);
      }

      List<Object> out = new ArrayList<>();
      extractWithIndexing(meta, proj, out);
      return out;
    } else if (query.root == Root.CHUNKS) {
      if (query.segments.isEmpty()) {
        throw new IllegalArgumentException("No projection path provided after 'chunks'");
      }
      List<Map<String, Object>> rows = ChunkProvider.loadAllChunks(session.getRecordingPath());
      List<Object> out = new ArrayList<>();
      for (Map<String, Object> row : rows) {
        extractWithIndexing(row, query.segments, out);
      }
      return out;
    } else if (query.root == Root.CP) {
      if (query.segments.isEmpty()) {
        // projection from summary rows
        List<Map<String, Object>> rows =
            ConstantPoolProvider.loadSummary(session.getRecordingPath());
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
          Object v = Values.get(r, query.segments.toArray());
          if (v != null) out.add(v);
        }
        return out;
      } else {
        // projection from entries of specific type
        String type = query.segments.get(0);
        List<Map<String, Object>> rows =
            ConstantPoolProvider.loadEntries(
                session.getRecordingPath(), type, r -> matchesAll(r, query.predicates));
        List<String> proj = query.segments.subList(1, query.segments.size());
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
          extractWithIndexing(r, proj, out);
        }
        return out;
      }
    } else {
      throw new UnsupportedOperationException("Unsupported root: " + query.root);
    }
  }

  // Indexing and tail-slice support for projection. Slice [start:end] supported only on last
  // segment.
  private void extractWithIndexing(Map<String, Object> root, List<String> proj, List<Object> out) {
    if (proj.isEmpty()) return;
    boolean debug =
        Boolean.getBoolean("jfr.shell.debug") || System.getenv("JFR_SHELL_DEBUG") != null;
    if (debug || proj.contains("0")) { // Always debug if path contains "0"
      try {
        java.nio.file.Files.writeString(
            java.nio.file.Paths.get("/tmp/jfr-shell-debug.log"),
            "[extractWithIndexing] proj = " + proj + "\n",
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.APPEND);
      } catch (Exception ignore) {
      }
      System.err.println("[extractWithIndexing] DEBUG=" + debug + ", proj = " + proj);
    }
    String last = proj.get(proj.size() - 1);
    Slice sl = parseSlice(last);
    if (sl != null) {
      // Resolve up to base of last segment
      String base = baseName(last);
      List<String> head = new java.util.ArrayList<>(proj);
      head.set(head.size() - 1, base);
      Object arrVal = Values.get(root, buildPathTokens(head).toArray());
      Object arr = unwrapArrayLike(arrVal);
      if (arr == null) return;
      int len = java.lang.reflect.Array.getLength(arr);
      int from = Math.max(0, sl.start());
      int to = sl.end() < 0 ? len : Math.min(len, sl.end());
      for (int i = from; i < to; i++) {
        out.add(java.lang.reflect.Array.get(arr, i));
      }
      return;
    }
    // Try new recursive array iteration approach
    extractWithArrayIteration(root, buildPathTokens(proj), out);
  }

  /**
   * Recursively extract values with automatic array iteration. When navigating a path encounters an
   * array and the next segment is a field name (not an Integer index), automatically iterates the
   * array and continues navigating on each element.
   *
   * @param current the current value to navigate from
   * @param pathTokens remaining path tokens (Strings for fields, Integers for indices)
   * @param out output list to collect results
   */
  @SuppressWarnings("unchecked")
  private void extractWithArrayIteration(
      Object current, List<Object> pathTokens, List<Object> out) {
    if (pathTokens.isEmpty()) {
      // Reached end of path
      if (current != null) {
        // Unwrap ComplexType and ArrayType for final result
        if (current instanceof io.jafar.parser.api.ComplexType ct) {
          current = ct.getValue();
        }
        Object unwrapped = unwrapArrayLike(current);
        if (unwrapped != null) {
          out.add(unwrapped);
        } else {
          out.add(current);
        }
      }
      return;
    }

    // Unwrap ComplexType
    if (current instanceof io.jafar.parser.api.ComplexType ct) {
      current = ct.getValue();
    }

    Object firstToken = pathTokens.get(0);
    List<Object> remainingTokens = pathTokens.subList(1, pathTokens.size());

    // Handle Integer index - explicit array access
    if (firstToken instanceof Integer idx) {
      Object arr = unwrapArrayLike(current);
      if (arr != null) {
        int len = java.lang.reflect.Array.getLength(arr);
        if (idx >= 0 && idx < len) {
          Object element = java.lang.reflect.Array.get(arr, idx);
          extractWithArrayIteration(element, remainingTokens, out);
        }
      }
      return;
    }

    // Handle String key - field access
    if (firstToken instanceof String key) {
      // Check if current is an array WITHOUT an explicit index
      Object arr = unwrapArrayLike(current);
      if (arr != null) {
        // Automatic array iteration: navigate remaining path on each element
        int len = java.lang.reflect.Array.getLength(arr);
        for (int i = 0; i < len; i++) {
          Object element = java.lang.reflect.Array.get(arr, i);
          // Continue with the FULL remaining path (including current key)
          extractWithArrayIteration(element, pathTokens, out);
        }
        return;
      }

      // Current is not an array, try map access
      if (current instanceof Map<?, ?> map) {
        Map<String, Object> m = (Map<String, Object>) map;
        Object value = m.get(key);
        extractWithArrayIteration(value, remainingTokens, out);
        return;
      }
    }
  }

  /**
   * Helper to extract all values from a map using a path with automatic array iteration. Returns a
   * list of all matching values (may be empty if path doesn't match).
   *
   * @param map the root map
   * @param pathTokens path tokens (Strings for fields, Integers for indices)
   * @return list of extracted values (may contain multiple results if path traverses arrays)
   */
  private List<Object> extractAllValues(Map<String, Object> map, List<Object> pathTokens) {
    List<Object> results = new ArrayList<>();
    extractWithArrayIteration(map, pathTokens, results);
    return results;
  }

  private static List<Object> buildPathTokens(List<String> segs) {
    List<Object> tokens = new ArrayList<>();
    for (String seg : segs) {
      int b = seg.indexOf('[');
      if (b < 0) {
        // No brackets - check if segment is a numeric index
        try {
          int index = Integer.parseInt(seg);
          tokens.add(index);
        } catch (NumberFormatException e) {
          // Not a number, add as string
          tokens.add(seg);
        }
        continue;
      }
      String name = seg.substring(0, b);
      if (!name.isEmpty()) tokens.add(name);
      int e = seg.lastIndexOf(']');
      if (e > b) {
        String inside = seg.substring(b + 1, e);
        if (!inside.contains(":")) {
          try {
            tokens.add(Integer.parseInt(inside.trim()));
          } catch (Exception ignore) {
          }
        } else {
          // slice only supported at tail; ignore here
        }
      }
    }
    return tokens;
  }

  private static String baseName(String seg) {
    int b = seg.indexOf('[');
    return b < 0 ? seg : seg.substring(0, b);
  }

  private static Slice parseSlice(String seg) {
    int b = seg.indexOf('[');
    int e = seg.endsWith("]") ? seg.lastIndexOf(']') : -1;
    if (b < 0 || e < b) return null;
    String inside = seg.substring(b + 1, e);
    int c = inside.indexOf(':');
    if (c < 0) return null;
    int from = 0, to = -1;
    String a = inside.substring(0, c).trim();
    String bb = inside.substring(c + 1).trim();
    if (!a.isEmpty())
      try {
        from = Integer.parseInt(a);
      } catch (Exception ignore) {
      }
    if (!bb.isEmpty())
      try {
        to = Integer.parseInt(bb);
      } catch (Exception ignore) {
      }
    return new Slice(from, to);
  }

  private static Object unwrapArrayLike(Object v) {
    if (v == null) return null;
    if (v.getClass().isArray()) return v;
    if (v instanceof java.util.Collection<?> c) return c.toArray();
    if (v instanceof io.jafar.parser.api.ArrayType at) return at.getArray();
    return null;
  }

  private record Slice(int start, int end) {}

  // Aggregations
  private List<Map<String, Object>> evaluateAggregate(JFRSession session, Query query)
      throws Exception {
    // Execute the first operator
    JfrPath.PipelineOp op = query.pipeline.get(0);
    List<Map<String, Object>> result =
        switch (op) {
          case JfrPath.CountOp c -> aggregateCount(session, query);
          case JfrPath.StatsOp s -> aggregateStats(session, query, s.valuePath);
          case JfrPath.QuantilesOp q -> aggregateQuantiles(session, query, q.valuePath, q.qs);
          case JfrPath.SketchOp sk -> aggregateSketch(session, query, sk.valuePath);
          case JfrPath.SumOp sm -> aggregateSum(session, query, sm.valuePath);
          case JfrPath.GroupByOp gb -> aggregateGroupBy(
              session, query, gb.keyPath, gb.aggFunc, gb.valuePath);
          case JfrPath.TopOp tp -> aggregateTop(session, query, tp.n, tp.byPath, tp.ascending);
          case JfrPath.LenOp ln -> aggregateLen(session, query, ln.valuePath);
          case JfrPath.UppercaseOp up -> aggregateStringTransform(
              session, query, up.valuePath, "uppercase");
          case JfrPath.LowercaseOp lo -> aggregateStringTransform(
              session, query, lo.valuePath, "lowercase");
          case JfrPath.TrimOp tr -> aggregateStringTransform(session, query, tr.valuePath, "trim");
          case JfrPath.AbsOp ab -> aggregateNumberTransform(session, query, ab.valuePath, "abs");
          case JfrPath.RoundOp ro -> aggregateNumberTransform(
              session, query, ro.valuePath, "round");
          case JfrPath.FloorOp flo -> aggregateNumberTransform(
              session, query, flo.valuePath, "floor");
          case JfrPath.CeilOp cei -> aggregateNumberTransform(
              session, query, cei.valuePath, "ceil");
          case JfrPath.ContainsOp co -> aggregateStringPredicate(
              session, query, co.valuePath, "contains", co.substr);
          case JfrPath.ReplaceOp rp -> aggregateStringReplace(
              session, query, rp.valuePath, rp.target, rp.replacement);
          case JfrPath.DecorateByTimeOp dt -> evaluateDecorateByTime(session, query, dt);
          case JfrPath.DecorateByKeyOp dk -> evaluateDecorateByKey(session, query, dk);
          case JfrPath.SelectOp so -> evaluateSelect(session, query, so);
          case JfrPath.ToMapOp tm -> evaluateToMap(session, query, tm);
          case JfrPath.TimeRangeOp tr -> aggregateTimeRange(session, query, tr);
        };

    // Apply remaining operators in sequence
    if (query.pipeline.size() > 1) {
      List<JfrPath.PipelineOp> remainingOps = query.pipeline.subList(1, query.pipeline.size());
      result = applyToRows(result, remainingOps);
    }

    return result;
  }

  private List<Map<String, Object>> evaluateSelect(
      JFRSession session, Query query, JfrPath.SelectOp op) throws Exception {
    if (op.items.isEmpty()) {
      throw new IllegalArgumentException("select() requires at least one field");
    }

    // Get the base result set (evaluate prior pipeline operations if any)
    List<Map<String, Object>> baseResults;

    // Find index of current select operation in pipeline
    int selectIndex = -1;
    for (int i = 0; i < query.pipeline.size(); i++) {
      if (query.pipeline.get(i) == op) {
        selectIndex = i;
        break;
      }
    }

    if (selectIndex == 0) {
      // No prior pipeline operations, evaluate base query
      Query baseQuery = new Query(query.root, query.segments, query.predicates, List.of());
      baseResults = evaluate(session, baseQuery);
    } else {
      // Evaluate all prior pipeline operations
      List<JfrPath.PipelineOp> priorOps = query.pipeline.subList(0, selectIndex);
      Query baseQuery = new Query(query.root, query.segments, query.predicates, priorOps);
      baseResults = evaluate(session, baseQuery);
    }

    // Project selected fields and evaluate expressions
    List<Map<String, Object>> result = new ArrayList<>();
    for (Map<String, Object> row : baseResults) {
      Map<String, Object> projected = new LinkedHashMap<>();

      for (JfrPath.SelectItem item : op.items) {
        String columnName = item.outputName();
        Object value;

        if (item instanceof JfrPath.FieldSelection fieldSel) {
          // Simple field path
          value = Values.get(row, fieldSel.fieldPath.toArray());
        } else if (item instanceof JfrPath.ExpressionSelection exprSel) {
          // Evaluate expression
          value = evaluateExpression(exprSel.expression, row);
        } else {
          throw new IllegalStateException("Unknown SelectItem type: " + item.getClass());
        }

        projected.put(columnName, value);
      }

      result.add(projected);
    }

    return result;
  }

  private List<Map<String, Object>> evaluateToMap(
      JFRSession session, Query query, JfrPath.ToMapOp op) throws Exception {

    // Get the base result set (evaluate prior pipeline operations if any)
    List<Map<String, Object>> baseResults;

    // Find index of current toMap operation in pipeline
    int toMapIndex = -1;
    for (int i = 0; i < query.pipeline.size(); i++) {
      if (query.pipeline.get(i) == op) {
        toMapIndex = i;
        break;
      }
    }

    if (toMapIndex == 0) {
      // No prior pipeline operations, evaluate base query
      Query baseQuery = new Query(query.root, query.segments, query.predicates, List.of());
      baseResults = evaluate(session, baseQuery);
    } else {
      // Evaluate all prior pipeline operations
      List<JfrPath.PipelineOp> priorOps = query.pipeline.subList(0, toMapIndex);
      Query baseQuery = new Query(query.root, query.segments, query.predicates, priorOps);
      baseResults = evaluate(session, baseQuery);
    }

    // Apply toMap transformation
    return applyToMap(baseResults, op.keyField, op.valueField);
  }

  private List<Map<String, Object>> aggregateTimeRange(
      JFRSession session, Query query, JfrPath.TimeRangeOp op) throws Exception {

    // Load chunk metadata to get timing info for conversion
    List<Map<String, Object>> chunks = ChunkProvider.loadAllChunks(session.getRecordingPath());
    if (chunks.isEmpty()) {
      throw new IllegalStateException("No chunks found in recording");
    }

    // Use first chunk's timing info (all chunks in a recording share the same clock)
    Map<String, Object> firstChunk = chunks.get(0);
    long startTicks = ((Number) firstChunk.get("startTicks")).longValue();
    long startNanos = ((Number) firstChunk.get("startNanos")).longValue();
    long frequency = ((Number) firstChunk.get("frequency")).longValue();

    // Track min/max tick values
    long[] minMax = {Long.MAX_VALUE, Long.MIN_VALUE};
    long[] count = {0};

    List<String> valuePath = op.valuePath;
    List<String> durationPath = op.durationPath;
    boolean hasDuration = !durationPath.isEmpty();

    if (query.root == Root.EVENTS) {
      if (query.eventTypes.isEmpty()) {
        throw new IllegalArgumentException("events root requires type");
      }

      validateEventTypes(session, query.eventTypes);

      if (query.isMultiType) {
        Set<String> typeSet = new java.util.HashSet<>(query.eventTypes);
        source.streamEvents(
            session.getRecordingPath(),
            ev -> {
              if (!typeSet.contains(ev.typeName())) return;
              Map<String, Object> map = ev.value();
              if (!matchesAll(map, query.predicates)) return;

              Object val = Values.get(map, valuePath.toArray());
              if (val instanceof Number n) {
                long ticks = n.longValue();
                if (ticks < minMax[0]) minMax[0] = ticks;
                // For max, if duration is specified, use startTime + duration
                long endTicks = ticks;
                if (hasDuration) {
                  Object durVal = Values.get(map, durationPath.toArray());
                  if (durVal instanceof Number dn) {
                    endTicks = ticks + dn.longValue();
                  }
                }
                if (endTicks > minMax[1]) minMax[1] = endTicks;
                count[0]++;
              }
            });
      } else {
        String eventType = query.eventTypes.get(0);
        source.streamEvents(
            session.getRecordingPath(),
            ev -> {
              if (!eventType.equals(ev.typeName())) return;
              Map<String, Object> map = ev.value();
              if (!matchesAll(map, query.predicates)) return;

              Object val = Values.get(map, valuePath.toArray());
              if (val instanceof Number n) {
                long ticks = n.longValue();
                if (ticks < minMax[0]) minMax[0] = ticks;
                // For max, if duration is specified, use startTime + duration
                long endTicks = ticks;
                if (hasDuration) {
                  Object durVal = Values.get(map, durationPath.toArray());
                  if (durVal instanceof Number dn) {
                    endTicks = ticks + dn.longValue();
                  }
                }
                if (endTicks > minMax[1]) minMax[1] = endTicks;
                count[0]++;
              }
            });
      }
    } else {
      // For non-event roots, materialize rows first
      List<Map<String, Object>> rows =
          evaluate(session, new Query(query.root, query.segments, query.predicates));
      for (Map<String, Object> row : rows) {
        Object val = Values.get(row, valuePath.toArray());
        if (val instanceof Number n) {
          long ticks = n.longValue();
          if (ticks < minMax[0]) minMax[0] = ticks;
          // For max, if duration is specified, use startTime + duration
          long endTicks = ticks;
          if (hasDuration) {
            Object durVal = Values.get(row, durationPath.toArray());
            if (durVal instanceof Number dn) {
              endTicks = ticks + dn.longValue();
            }
          }
          if (endTicks > minMax[1]) minMax[1] = endTicks;
          count[0]++;
        }
      }
    }

    // Build result
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("count", count[0]);
    result.put("field", String.join("/", valuePath));

    if (count[0] == 0) {
      result.put("minTicks", null);
      result.put("maxTicks", null);
      result.put("minTime", null);
      result.put("maxTime", null);
      result.put("durationNanos", null);
      result.put("durationMs", null);
      result.put("duration", null);
    } else {
      result.put("minTicks", minMax[0]);
      result.put("maxTicks", minMax[1]);

      // Convert ticks to wall-clock time
      Instant minInstant = ticksToInstant(minMax[0], startTicks, startNanos, frequency);
      Instant maxInstant = ticksToInstant(minMax[1], startTicks, startNanos, frequency);

      // Format the times
      DateTimeFormatter formatter = getFormatter(op.format);
      result.put("minTime", formatter.format(minInstant.atZone(ZoneId.systemDefault())));
      result.put("maxTime", formatter.format(maxInstant.atZone(ZoneId.systemDefault())));

      // Calculate duration
      long durationNanos = ticksToNanos(minMax[1] - minMax[0], frequency);
      result.put("durationNanos", durationNanos);
      result.put("durationMs", durationNanos / 1_000_000.0);
      result.put("duration", formatDuration(durationNanos));
    }

    return List.of(result);
  }

  private static String formatDuration(long nanos) {
    if (nanos < 1_000) {
      return nanos + "ns";
    } else if (nanos < 1_000_000) {
      return String.format("%.2fus", nanos / 1_000.0);
    } else if (nanos < 1_000_000_000) {
      return String.format("%.2fms", nanos / 1_000_000.0);
    } else if (nanos < 60_000_000_000L) {
      return String.format("%.2fs", nanos / 1_000_000_000.0);
    } else if (nanos < 3_600_000_000_000L) {
      long totalSecs = nanos / 1_000_000_000L;
      long mins = totalSecs / 60;
      long secs = totalSecs % 60;
      return String.format("%dm %ds", mins, secs);
    } else {
      long totalSecs = nanos / 1_000_000_000L;
      long hours = totalSecs / 3600;
      long mins = (totalSecs % 3600) / 60;
      long secs = totalSecs % 60;
      return String.format("%dh %dm %ds", hours, mins, secs);
    }
  }

  private static Instant ticksToInstant(
      long ticks, long startTicks, long startNanos, long frequency) {
    long tickDiff = ticks - startTicks;
    long nanoDiff = ticksToNanos(tickDiff, frequency);
    return Instant.ofEpochSecond(0, startNanos + nanoDiff);
  }

  private static long ticksToNanos(long ticks, long frequency) {
    // frequency is ticks per second, so:
    // nanos = ticks * (1e9 / frequency) = (ticks * 1_000_000_000L) / frequency
    // Use BigDecimal-like approach to avoid overflow
    return Math.round(ticks * (1_000_000_000.0 / frequency));
  }

  private static DateTimeFormatter getFormatter(String format) {
    if (format == null || format.isEmpty()) {
      return DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    }
    return DateTimeFormatter.ofPattern(format);
  }

  // Expression evaluator for computed fields in select()
  private Object evaluateExpression(JfrPath.Expr expr, Map<String, Object> row) {
    if (expr instanceof JfrPath.Literal lit) {
      return lit.value;
    }

    if (expr instanceof JfrPath.FieldRef fieldRef) {
      return Values.get(row, fieldRef.fieldPath.toArray());
    }

    if (expr instanceof JfrPath.BinExpr binExpr) {
      Object left = evaluateExpression(binExpr.left, row);
      Object right = evaluateExpression(binExpr.right, row);
      return evaluateBinaryOp(binExpr.op, left, right);
    }

    if (expr instanceof JfrPath.FuncExpr funcExpr) {
      return evaluateFunction(funcExpr.funcName, funcExpr.args, row);
    }

    if (expr instanceof JfrPath.StringTemplate template) {
      return evaluateStringTemplate(template, row);
    }

    throw new IllegalArgumentException("Unknown expression type: " + expr.getClass());
  }

  private String evaluateStringTemplate(JfrPath.StringTemplate template, Map<String, Object> row) {
    StringBuilder result = new StringBuilder();

    // Invariant: parts.size() == expressions.size() + 1
    // Interleave parts and evaluated expressions
    for (int i = 0; i < template.expressions.size(); i++) {
      result.append(template.parts.get(i));
      Object value = evaluateExpression(template.expressions.get(i), row);
      result.append(value == null ? "" : String.valueOf(value));
    }
    // Append final part
    result.append(template.parts.get(template.parts.size() - 1));

    return result.toString();
  }

  private Object evaluateBinaryOp(JfrPath.Op op, Object left, Object right) {
    switch (op) {
      case PLUS:
        // String concatenation or numeric addition
        if (left instanceof String || right instanceof String) {
          return String.valueOf(left) + String.valueOf(right);
        }
        return addNumbers(left, right);

      case MINUS:
        return subtractNumbers(left, right);

      case MULT:
        return multiplyNumbers(left, right);

      case DIV:
        return divideNumbers(left, right);

      default:
        throw new IllegalArgumentException("Unsupported binary operator in expression: " + op);
    }
  }

  private double addNumbers(Object a, Object b) {
    return toNumber(a) + toNumber(b);
  }

  private double subtractNumbers(Object a, Object b) {
    return toNumber(a) - toNumber(b);
  }

  private double multiplyNumbers(Object a, Object b) {
    return toNumber(a) * toNumber(b);
  }

  private double divideNumbers(Object a, Object b) {
    double divisor = toNumber(b);
    if (divisor == 0) {
      return Double.NaN;
    }
    return toNumber(a) / divisor;
  }

  private double toNumber(Object obj) {
    if (obj == null) return 0.0;
    if (obj instanceof Number n) return n.doubleValue();
    if (obj instanceof String s) {
      try {
        return Double.parseDouble(s);
      } catch (NumberFormatException e) {
        return 0.0;
      }
    }
    return 0.0;
  }

  private Object evaluateFunction(
      String funcName, List<JfrPath.Expr> args, Map<String, Object> row) {
    switch (funcName.toLowerCase()) {
      case "if":
        return evalIf(args, row);
      case "substring":
        return evalSubstring(args, row);
      case "upper":
        return evalUpper(args, row);
      case "lower":
        return evalLower(args, row);
      case "length":
        return evalLength(args, row);
      case "coalesce":
        return evalCoalesce(args, row);
      default:
        throw new IllegalArgumentException("Unknown function: " + funcName);
    }
  }

  private Object evalIf(List<JfrPath.Expr> args, Map<String, Object> row) {
    if (args.size() != 3) {
      throw new IllegalArgumentException(
          "if() requires 3 arguments: condition, trueValue, falseValue");
    }

    Object condition = evaluateExpression(args.get(0), row);
    boolean isTrue = toBoolean(condition);

    return evaluateExpression(isTrue ? args.get(1) : args.get(2), row);
  }

  private Object evalSubstring(List<JfrPath.Expr> args, Map<String, Object> row) {
    if (args.size() < 2 || args.size() > 3) {
      throw new IllegalArgumentException(
          "substring() requires 2-3 arguments: string, start, [length]");
    }

    String str = String.valueOf(evaluateExpression(args.get(0), row));
    int start = ((Number) evaluateExpression(args.get(1), row)).intValue();

    if (args.size() == 3) {
      int length = ((Number) evaluateExpression(args.get(2), row)).intValue();
      return str.substring(start, Math.min(start + length, str.length()));
    } else {
      return str.substring(start);
    }
  }

  private Object evalUpper(List<JfrPath.Expr> args, Map<String, Object> row) {
    if (args.size() != 1) {
      throw new IllegalArgumentException("upper() requires 1 argument");
    }
    return String.valueOf(evaluateExpression(args.get(0), row)).toUpperCase();
  }

  private Object evalLower(List<JfrPath.Expr> args, Map<String, Object> row) {
    if (args.size() != 1) {
      throw new IllegalArgumentException("lower() requires 1 argument");
    }
    return String.valueOf(evaluateExpression(args.get(0), row)).toLowerCase();
  }

  private Object evalLength(List<JfrPath.Expr> args, Map<String, Object> row) {
    if (args.size() != 1) {
      throw new IllegalArgumentException("length() requires 1 argument");
    }
    return String.valueOf(evaluateExpression(args.get(0), row)).length();
  }

  private Object evalCoalesce(List<JfrPath.Expr> args, Map<String, Object> row) {
    for (JfrPath.Expr arg : args) {
      Object value = evaluateExpression(arg, row);
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  private boolean toBoolean(Object obj) {
    if (obj == null) return false;
    if (obj instanceof Boolean b) return b;
    if (obj instanceof Number n) return n.doubleValue() != 0;
    if (obj instanceof String s) return !s.isEmpty();
    return true;
  }

  private List<Map<String, Object>> aggregateCount(JFRSession session, Query query)
      throws Exception {
    long count = 0;
    if (query.root == Root.EVENTS) {
      if (query.eventTypes.isEmpty())
        throw new IllegalArgumentException("events root requires type");

      // Validate event types exist
      validateEventTypes(session, query.eventTypes);

      final long[] c = new long[1];
      if (query.isMultiType) {
        // Multi-type query: use Set for O(1) lookup
        Set<String> typeSet = new java.util.HashSet<>(query.eventTypes);
        source.streamEvents(
            session.getRecordingPath(),
            ev -> {
              if (!typeSet.contains(ev.typeName())) return;
              if (matchesAll(ev.value(), query.predicates)) c[0]++;
            });
      } else {
        // Single-type query: use direct comparison (faster)
        String eventType = query.eventTypes.get(0);
        source.streamEvents(
            session.getRecordingPath(),
            ev -> {
              if (!eventType.equals(ev.typeName())) return;
              if (matchesAll(ev.value(), query.predicates)) c[0]++;
            });
      }
      count = c[0];
    } else if (query.root == Root.METADATA) {
      if (query.segments.isEmpty())
        throw new IllegalArgumentException("metadata root requires type");
      // base row presence as count=1 if matches, else 0; projection lists use evaluateValues
      if (query.segments.size() > 1) {
        List<Object> vals =
            evaluateValues(session, new Query(query.root, query.segments, query.predicates));
        count = vals.size();
      } else {
        List<Map<String, Object>> rows =
            evaluate(session, new Query(query.root, query.segments, query.predicates));
        count = rows.size();
      }
    } else if (query.root == Root.CHUNKS || query.root == Root.CP) {
      List<Map<String, Object>> rows =
          evaluate(session, new Query(query.root, query.segments, query.predicates));
      count = rows.size();
    } else {
      throw new UnsupportedOperationException("Unsupported root: " + query.root);
    }
    Map<String, Object> out = new HashMap<>();
    out.put("count", count);
    return List.of(out);
  }

  private List<Map<String, Object>> aggregateStats(
      JFRSession session, Query query, List<String> valuePathOverride) throws Exception {
    List<String> vpath = valuePathOverride;
    if (vpath == null || vpath.isEmpty()) {
      if (query.segments.size() < 2)
        throw new IllegalArgumentException("stats() requires projection or a value path");
      vpath = query.segments.subList(1, query.segments.size());
    }
    StatsAgg agg = new StatsAgg();
    if (query.root == Root.EVENTS) {
      // Validate event types exist
      validateEventTypes(session, query.eventTypes);

      List<String> path = vpath;
      if (query.isMultiType) {
        // Multi-type query: use Set for O(1) lookup
        Set<String> typeSet = new java.util.HashSet<>(query.eventTypes);
        source.streamEvents(
            session.getRecordingPath(),
            ev -> {
              if (!typeSet.contains(ev.typeName())) return;
              Map<String, Object> map = ev.value();
              if (!matchesAll(map, query.predicates)) return;
              Object val = Values.get(map, path.toArray());
              if (val instanceof Number n) agg.add(n.doubleValue());
            });
      } else {
        // Single-type query: use direct comparison (faster)
        String eventType = query.eventTypes.get(0);
        source.streamEvents(
            session.getRecordingPath(),
            ev -> {
              if (!eventType.equals(ev.typeName())) return;
              Map<String, Object> map = ev.value();
              if (!matchesAll(map, query.predicates)) return;
              Object val = Values.get(map, path.toArray());
              if (val instanceof Number n) agg.add(n.doubleValue());
            });
      }
    } else {
      // For non-events: derive rows or values then apply
      List<Object> vals;
      if (query.segments.size() >= 1) {
        vals =
            evaluateValues(
                session,
                new Query(
                    query.root,
                    new ArrayList<>(List.of(query.segments.get(0))).subList(0, 1),
                    query.predicates));
        // Fallback: get values by explicitly evaluating the original query and extracting
        vals = evaluateValues(session, new Query(query.root, query.segments, query.predicates));
      } else {
        throw new IllegalArgumentException("stats() not applicable");
      }
      for (Object v : vals) if (v instanceof Number n) agg.add(n.doubleValue());
    }
    return List.of(agg.toRow());
  }

  private List<Map<String, Object>> aggregateQuantiles(
      JFRSession session, Query query, List<String> valuePathOverride, List<Double> qs)
      throws Exception {
    List<String> vpath = valuePathOverride;
    if (vpath == null || vpath.isEmpty()) {
      if (query.segments.size() < 2)
        throw new IllegalArgumentException("quantiles() requires projection or a value path");
      vpath = query.segments.subList(1, query.segments.size());
    }
    List<Double> values = new ArrayList<>();
    if (query.root == Root.EVENTS) {
      String eventType = query.segments.get(0);
      List<String> path = vpath;
      source.streamEvents(
          session.getRecordingPath(),
          ev -> {
            if (!eventType.equals(ev.typeName())) return;
            Map<String, Object> map = ev.value();
            if (!matchesAll(map, query.predicates)) return;
            Object val = Values.get(map, path.toArray());
            if (val instanceof Number n) values.add(n.doubleValue());
          });
    } else {
      List<Object> vals =
          evaluateValues(session, new Query(query.root, query.segments, query.predicates));
      for (Object v : vals) if (v instanceof Number n) values.add(n.doubleValue());
    }
    java.util.Collections.sort(values);
    Map<String, Object> row = new HashMap<>();
    row.put("count", values.size());
    for (Double q : qs) {
      if (values.isEmpty()) {
        row.put(pcol(q), null);
        continue;
      }
      double qv = quantileNearestRank(values, q);
      row.put(pcol(q), qv);
    }
    return List.of(row);
  }

  private List<Map<String, Object>> aggregateSketch(
      JFRSession session, Query query, List<String> valuePath) throws Exception {
    // Combine stats + default quantiles
    Map<String, Object> stats = aggregateStats(session, query, valuePath).get(0);
    Map<String, Object> quants =
        aggregateQuantiles(session, query, valuePath, List.of(0.5, 0.9, 0.99)).get(0);
    Map<String, Object> out = new HashMap<>();
    out.putAll(stats);
    out.putAll(quants);
    return List.of(out);
  }

  private List<Map<String, Object>> aggregateSum(
      JFRSession session, Query query, List<String> valuePathOverride) throws Exception {
    List<String> vpath = valuePathOverride;
    if (vpath == null || vpath.isEmpty()) {
      if (query.segments.size() < 2)
        throw new IllegalArgumentException("sum() requires projection or a value path");
      vpath = query.segments.subList(1, query.segments.size());
    }

    double sum = 0.0;
    long count = 0;
    final List<String> path = vpath;

    if (query.root == Root.EVENTS) {
      if (query.eventTypes.isEmpty())
        throw new IllegalArgumentException("events root requires type");

      // Validate event types exist
      validateEventTypes(session, query.eventTypes);

      final double[] s = {0.0};
      final long[] c = {0};
      if (query.isMultiType) {
        // Multi-type query: use Set for O(1) lookup
        Set<String> typeSet = new java.util.HashSet<>(query.eventTypes);
        source.streamEvents(
            session.getRecordingPath(),
            ev -> {
              if (!typeSet.contains(ev.typeName())) return;
              Map<String, Object> map = ev.value();
              if (!matchesAll(map, query.predicates)) return;
              Object val = Values.get(map, path.toArray());
              if (val instanceof Number n) {
                s[0] += n.doubleValue();
                c[0]++;
              }
            });
      } else {
        // Single-type query: use direct comparison (faster)
        String eventType = query.eventTypes.get(0);
        source.streamEvents(
            session.getRecordingPath(),
            ev -> {
              if (!eventType.equals(ev.typeName())) return;
              Map<String, Object> map = ev.value();
              if (!matchesAll(map, query.predicates)) return;
              Object val = Values.get(map, path.toArray());
              if (val instanceof Number n) {
                s[0] += n.doubleValue();
                c[0]++;
              }
            });
      }
      sum = s[0];
      count = c[0];
    } else {
      List<Object> vals =
          evaluateValues(session, new Query(query.root, query.segments, query.predicates));
      for (Object v : vals) {
        if (v instanceof Number n) {
          sum += n.doubleValue();
          count++;
        }
      }
    }

    Map<String, Object> out = new HashMap<>();
    out.put("sum", sum);
    out.put("count", count);
    return List.of(out);
  }

  private List<Map<String, Object>> aggregateGroupBy(
      JFRSession session, Query query, List<String> keyPath, String aggFunc, List<String> valuePath)
      throws Exception {
    Map<Object, GroupAccumulator> groups = new LinkedHashMap<>();

    // Pre-build path tokens for array iteration support
    List<Object> keyTokens = buildPathTokens(keyPath);
    List<Object> valueTokens = valuePath.isEmpty() ? null : buildPathTokens(valuePath);

    if (query.root == Root.EVENTS) {
      if (query.eventTypes.isEmpty())
        throw new IllegalArgumentException("events root requires type");

      // Validate event types exist
      validateEventTypes(session, query.eventTypes);

      if (query.isMultiType) {
        // Multi-type query: use Set for O(1) lookup
        Set<String> typeSet = new java.util.HashSet<>(query.eventTypes);
        source.streamEvents(
            session.getRecordingPath(),
            ev -> {
              if (!typeSet.contains(ev.typeName())) return;
              Map<String, Object> map = ev.value();
              if (!matchesAll(map, query.predicates)) return;

              // Extract all keys (handles arrays automatically)
              List<Object> keys = extractAllValues(map, keyTokens);
              for (Object key : keys) {
                GroupAccumulator acc =
                    groups.computeIfAbsent(key, k -> new GroupAccumulator(aggFunc));

                if ("count".equals(aggFunc)) {
                  acc.add(1);
                } else {
                  List<Object> vals =
                      valueTokens == null ? List.of() : extractAllValues(map, valueTokens);
                  for (Object val : vals) {
                    if (val instanceof Number n) {
                      acc.add(n.doubleValue());
                    }
                  }
                }
              }
            });
      } else {
        // Single-type query: use direct comparison (faster)
        String eventType = query.eventTypes.get(0);
        source.streamEvents(
            session.getRecordingPath(),
            ev -> {
              if (!eventType.equals(ev.typeName())) return;
              Map<String, Object> map = ev.value();
              if (!matchesAll(map, query.predicates)) return;

              // Extract all keys (handles arrays automatically)
              List<Object> keys = extractAllValues(map, keyTokens);
              for (Object key : keys) {
                GroupAccumulator acc =
                    groups.computeIfAbsent(key, k -> new GroupAccumulator(aggFunc));

                if ("count".equals(aggFunc)) {
                  acc.add(1);
                } else {
                  List<Object> vals =
                      valueTokens == null ? List.of() : extractAllValues(map, valueTokens);
                  for (Object val : vals) {
                    if (val instanceof Number n) {
                      acc.add(n.doubleValue());
                    }
                  }
                }
              }
            });
      }
    } else {
      // For metadata/chunks/cp: materialize rows first
      List<Map<String, Object>> rows =
          evaluate(session, new Query(query.root, query.segments, query.predicates));
      for (Map<String, Object> row : rows) {
        // Extract all keys (handles arrays automatically)
        List<Object> keys = extractAllValues(row, keyTokens);
        for (Object key : keys) {
          GroupAccumulator acc = groups.computeIfAbsent(key, k -> new GroupAccumulator(aggFunc));

          if ("count".equals(aggFunc)) {
            acc.add(1);
          } else {
            List<Object> vals =
                valueTokens == null ? List.of() : extractAllValues(row, valueTokens);
            for (Object val : vals) {
              if (val instanceof Number n) {
                acc.add(n.doubleValue());
              }
            }
          }
        }
      }
    }

    List<Map<String, Object>> result = new ArrayList<>();
    for (Map.Entry<Object, GroupAccumulator> entry : groups.entrySet()) {
      Map<String, Object> row = new HashMap<>();
      row.put("key", entry.getKey());
      row.put(aggFunc, entry.getValue().getResult());
      result.add(row);
    }

    return result;
  }

  private List<Map<String, Object>> aggregateTop(
      JFRSession session, Query query, int n, List<String> byPath, boolean ascending)
      throws Exception {
    // Materialize all rows, then sort and take top N
    List<Map<String, Object>> rows;

    if (query.segments.size() > 1) {
      // Value projection: convert to single-column rows
      List<Object> values = evaluateValues(session, query);
      rows =
          values.stream()
              .map(
                  v -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("value", v);
                    return m;
                  })
              .collect(java.util.stream.Collectors.toList());
    } else {
      // Full rows
      rows = evaluate(session, new Query(query.root, query.segments, query.predicates));
    }

    // Sort by path
    rows = new ArrayList<>(rows);
    rows.sort(
        (a, b) -> {
          Object aVal = Values.get(a, buildPathTokens(byPath).toArray());
          Object bVal = Values.get(b, buildPathTokens(byPath).toArray());
          int cmp = compareValues(aVal, bVal);
          return ascending ? cmp : -cmp;
        });

    // Take top N
    return rows.subList(0, Math.min(n, rows.size()));
  }

  // Helper class for groupBy accumulation
  private static class GroupAccumulator {
    private final String func;
    private long count = 0;
    private double sum = 0.0;
    private double min = Double.MAX_VALUE;
    private double max = Double.MIN_VALUE;

    GroupAccumulator(String func) {
      this.func = func;
    }

    void add(double value) {
      count++;
      sum += value;
      min = Math.min(min, value);
      max = Math.max(max, value);
    }

    Object getResult() {
      return switch (func) {
        case "count" -> count;
        case "sum" -> sum;
        case "avg" -> count == 0 ? 0.0 : sum / count;
        case "min" -> count == 0 ? null : min;
        case "max" -> count == 0 ? null : max;
        default -> count;
      };
    }
  }

  private static int compareValues(Object a, Object b) {
    if (a == null && b == null) return 0;
    if (a == null) return -1;
    if (b == null) return 1;
    if (a instanceof Number na && b instanceof Number nb) {
      return Double.compare(na.doubleValue(), nb.doubleValue());
    }
    return String.valueOf(a).compareTo(String.valueOf(b));
  }

  private List<Map<String, Object>> aggregateLen(
      JFRSession session, Query query, List<String> valuePathOverride) throws Exception {
    List<String> vpath = valuePathOverride;
    if (vpath == null || vpath.isEmpty()) {
      if (query.segments.size() < 2)
        throw new IllegalArgumentException("len() requires projection or a value path");
      vpath = query.segments.subList(1, query.segments.size());
    }
    List<Map<String, Object>> out = new ArrayList<>();
    final List<String> path = vpath;
    java.util.function.Consumer<Object> addLen =
        (val) -> {
          if (val == null) {
            Map<String, Object> row = new HashMap<>();
            row.put("len", null);
            out.add(row);
            return;
          }
          Integer len = valueLength(val);
          if (len == null) {
            throw new IllegalArgumentException(
                "len() expects string or array/list, got " + val.getClass().getName());
          }
          Map<String, Object> row = new HashMap<>();
          row.put("len", len);
          out.add(row);
        };
    if (query.root == Root.EVENTS) {
      if (query.eventTypes.isEmpty())
        throw new IllegalArgumentException("events root requires type");

      // Validate event types exist
      validateEventTypes(session, query.eventTypes);

      if (query.isMultiType) {
        // Multi-type query: use Set for O(1) lookup
        Set<String> typeSet = new java.util.HashSet<>(query.eventTypes);
        source.streamEvents(
            session.getRecordingPath(),
            ev -> {
              if (!typeSet.contains(ev.typeName())) return;
              Map<String, Object> map = ev.value();
              if (!matchesAll(map, query.predicates)) return;
              Object val = Values.get(map, path.toArray());
              addLen.accept(val);
            });
      } else {
        // Single-type query: use direct comparison (faster)
        String eventType = query.eventTypes.get(0);
        source.streamEvents(
            session.getRecordingPath(),
            ev -> {
              if (!eventType.equals(ev.typeName())) return;
              Map<String, Object> map = ev.value();
              if (!matchesAll(map, query.predicates)) return;
              Object val = Values.get(map, path.toArray());
              addLen.accept(val);
            });
      }
    } else {
      // For non-events, leverage evaluateValues with the original query to get a list of values
      List<Object> vals =
          evaluateValues(session, new Query(query.root, query.segments, query.predicates));
      for (Object v : vals) addLen.accept(v);
    }
    return out;
  }

  private static Integer valueLength(Object val) {
    if (val == null) return null;
    if (val instanceof CharSequence s) return s.length();
    if (val instanceof java.util.Collection<?> c) return c.size();
    Object arr = val;
    if (arr.getClass().isArray()) return java.lang.reflect.Array.getLength(arr);
    return null;
  }

  private List<Map<String, Object>> aggregateStringTransform(
      JFRSession session, Query query, List<String> valuePathOverride, String opName)
      throws Exception {
    List<String> vpath = valuePathOverride;
    if (vpath == null || vpath.isEmpty()) {
      if (query.segments.size() < 2)
        throw new IllegalArgumentException(opName + "() requires projection or a value path");
      vpath = query.segments.subList(1, query.segments.size());
    }
    List<Map<String, Object>> out = new ArrayList<>();
    final List<String> path = vpath;
    java.util.function.Consumer<Object> addTransformed =
        (val) -> {
          if (val == null) {
            Map<String, Object> row = new HashMap<>();
            row.put("value", null);
            out.add(row);
            return;
          }
          if (!(val instanceof CharSequence s)) {
            throw new IllegalArgumentException(
                opName + "() expects string, got " + val.getClass().getName());
          }
          String res =
              switch (opName) {
                case "uppercase" -> s.toString().toUpperCase(java.util.Locale.ROOT);
                case "lowercase" -> s.toString().toLowerCase(java.util.Locale.ROOT);
                case "trim" -> s.toString().trim();
                default -> throw new IllegalArgumentException("Unsupported op: " + opName);
              };
          Map<String, Object> row = new HashMap<>();
          row.put("value", res);
          out.add(row);
        };
    if (query.root == Root.EVENTS) {
      if (query.eventTypes.isEmpty())
        throw new IllegalArgumentException("events root requires type");

      // Validate event types exist
      validateEventTypes(session, query.eventTypes);

      if (query.isMultiType) {
        // Multi-type query: use Set for O(1) lookup
        Set<String> typeSet = new java.util.HashSet<>(query.eventTypes);
        source.streamEvents(
            session.getRecordingPath(),
            ev -> {
              if (!typeSet.contains(ev.typeName())) return;
              Map<String, Object> map = ev.value();
              if (!matchesAll(map, query.predicates)) return;
              Object val = Values.get(map, path.toArray());
              addTransformed.accept(val);
            });
      } else {
        // Single-type query: use direct comparison (faster)
        String eventType = query.eventTypes.get(0);
        source.streamEvents(
            session.getRecordingPath(),
            ev -> {
              if (!eventType.equals(ev.typeName())) return;
              Map<String, Object> map = ev.value();
              if (!matchesAll(map, query.predicates)) return;
              Object val = Values.get(map, path.toArray());
              addTransformed.accept(val);
            });
      }
    } else {
      List<Object> vals =
          evaluateValues(session, new Query(query.root, query.segments, query.predicates));
      for (Object v : vals) addTransformed.accept(v);
    }
    return out;
  }

  private List<Map<String, Object>> aggregateNumberTransform(
      JFRSession session, Query query, List<String> valuePathOverride, String opName)
      throws Exception {
    List<String> vpath = valuePathOverride;
    if (vpath == null || vpath.isEmpty()) {
      if (query.segments.size() < 2)
        throw new IllegalArgumentException(opName + "() requires projection or a value path");
      vpath = query.segments.subList(1, query.segments.size());
    }
    List<Map<String, Object>> out = new ArrayList<>();
    final List<String> path = vpath;
    java.util.function.Consumer<Object> addTransformed =
        (val) -> {
          if (val == null) {
            Map<String, Object> row = new HashMap<>();
            row.put("value", null);
            out.add(row);
            return;
          }
          if (!(val instanceof Number n)) {
            throw new IllegalArgumentException(
                opName + "() expects number, got " + val.getClass().getName());
          }
          Number res;
          switch (opName) {
            case "abs" -> {
              if (n instanceof Integer) res = Math.abs(n.intValue());
              else if (n instanceof Long) res = Math.abs(n.longValue());
              else if (n instanceof Short) res = (short) Math.abs(n.shortValue());
              else if (n instanceof Byte) res = (byte) Math.abs(n.byteValue());
              else if (n instanceof Float) res = Math.abs(n.floatValue());
              else res = Math.abs(n.doubleValue());
            }
            case "round" -> {
              if (n instanceof Float || n instanceof Double) res = Math.round(n.doubleValue());
              else res = n.longValue();
            }
            case "floor" -> res = Math.floor(n.doubleValue());
            case "ceil" -> res = Math.ceil(n.doubleValue());
            default -> throw new IllegalArgumentException("Unsupported op: " + opName);
          }
          Map<String, Object> row = new HashMap<>();
          row.put("value", res);
          out.add(row);
        };
    if (query.root == Root.EVENTS) {
      if (query.eventTypes.isEmpty())
        throw new IllegalArgumentException("events root requires type");

      // Validate event types exist
      validateEventTypes(session, query.eventTypes);

      if (query.isMultiType) {
        // Multi-type query: use Set for O(1) lookup
        Set<String> typeSet = new java.util.HashSet<>(query.eventTypes);
        source.streamEvents(
            session.getRecordingPath(),
            ev -> {
              if (!typeSet.contains(ev.typeName())) return;
              Map<String, Object> map = ev.value();
              if (!matchesAll(map, query.predicates)) return;
              Object val = Values.get(map, path.toArray());
              addTransformed.accept(val);
            });
      } else {
        // Single-type query: use direct comparison (faster)
        String eventType = query.eventTypes.get(0);
        source.streamEvents(
            session.getRecordingPath(),
            ev -> {
              if (!eventType.equals(ev.typeName())) return;
              Map<String, Object> map = ev.value();
              if (!matchesAll(map, query.predicates)) return;
              Object val = Values.get(map, path.toArray());
              addTransformed.accept(val);
            });
      }
    } else {
      List<Object> vals =
          evaluateValues(session, new Query(query.root, query.segments, query.predicates));
      for (Object v : vals) addTransformed.accept(v);
    }
    return out;
  }

  private List<Map<String, Object>> aggregateStringPredicate(
      JFRSession session, Query query, List<String> valuePathOverride, String opName, String arg)
      throws Exception {
    if (arg == null) throw new IllegalArgumentException(opName + "() requires a string argument");
    List<String> vpath = valuePathOverride;
    if (vpath == null || vpath.isEmpty()) {
      if (query.segments.size() < 2)
        throw new IllegalArgumentException(opName + "() requires projection or a value path");
      vpath = query.segments.subList(1, query.segments.size());
    }
    List<Map<String, Object>> out = new ArrayList<>();
    final List<String> path = vpath;
    java.util.function.Consumer<Object> add =
        (val) -> {
          Boolean res = null;
          if (val == null) res = null;
          else if (val instanceof CharSequence s) res = s.toString().contains(arg);
          else
            throw new IllegalArgumentException(
                opName + "() expects string, got " + val.getClass().getName());
          Map<String, Object> row = new HashMap<>();
          row.put("value", res);
          out.add(row);
        };
    if (query.root == Root.EVENTS) {
      String eventType = query.segments.get(0);
      source.streamEvents(
          session.getRecordingPath(),
          ev -> {
            if (!eventType.equals(ev.typeName())) return;
            Map<String, Object> map = ev.value();
            if (!matchesAll(map, query.predicates)) return;
            Object val = Values.get(map, path.toArray());
            add.accept(val);
          });
    } else {
      List<Object> vals =
          evaluateValues(session, new Query(query.root, query.segments, query.predicates));
      for (Object v : vals) add.accept(v);
    }
    return out;
  }

  private List<Map<String, Object>> aggregateStringReplace(
      JFRSession session, Query query, List<String> valuePathOverride, String target, String repl)
      throws Exception {
    if (target == null || repl == null)
      throw new IllegalArgumentException("replace() requires target and replacement strings");
    List<String> vpath = valuePathOverride;
    if (vpath == null || vpath.isEmpty()) {
      if (query.segments.size() < 2)
        throw new IllegalArgumentException("replace() requires projection or a value path");
      vpath = query.segments.subList(1, query.segments.size());
    }
    List<Map<String, Object>> out = new ArrayList<>();
    final List<String> path = vpath;
    java.util.function.Consumer<Object> add =
        (val) -> {
          String res = null;
          if (val == null) res = null;
          else if (val instanceof CharSequence s) res = s.toString().replace(target, repl);
          else
            throw new IllegalArgumentException(
                "replace() expects string, got " + val.getClass().getName());
          Map<String, Object> row = new HashMap<>();
          row.put("value", res);
          out.add(row);
        };
    if (query.root == Root.EVENTS) {
      String eventType = query.segments.get(0);
      source.streamEvents(
          session.getRecordingPath(),
          ev -> {
            if (!eventType.equals(ev.typeName())) return;
            Map<String, Object> map = ev.value();
            if (!matchesAll(map, query.predicates)) return;
            Object val = Values.get(map, path.toArray());
            add.accept(val);
          });
    } else {
      List<Object> vals =
          evaluateValues(session, new Query(query.root, query.segments, query.predicates));
      for (Object v : vals) add.accept(v);
    }
    return out;
  }

  private static String pcol(double q) {
    int pct = (int) Math.round(q * 100);
    return "p" + pct;
  }

  private static double quantileNearestRank(List<Double> sortedValues, double q) {
    if (sortedValues.isEmpty()) return Double.NaN;
    int n = sortedValues.size();
    if (q <= 0) return sortedValues.get(0);
    if (q >= 1) return sortedValues.get(n - 1);
    // Special-case median: average of middle two when even-sized
    if (Math.abs(q - 0.5) < 1e-9) {
      if ((n & 1) == 1) {
        return sortedValues.get(n / 2);
      } else {
        double a = sortedValues.get(n / 2 - 1);
        double b = sortedValues.get(n / 2);
        return (a + b) / 2.0;
      }
    }
    // For other quantiles, use nearest-rank (ceil(q*n))
    int rank = (int) Math.ceil(q * n);
    rank = Math.max(1, Math.min(n, rank));
    return sortedValues.get(rank - 1);
  }

  private static final class StatsAgg {
    long count = 0;
    double min = Double.POSITIVE_INFINITY;
    double max = Double.NEGATIVE_INFINITY;
    double mean = 0.0;
    double m2 = 0.0; // for variance via Welford

    void add(double x) {
      count++;
      if (x < min) min = x;
      if (x > max) max = x;
      double delta = x - mean;
      mean += delta / count;
      double delta2 = x - mean;
      m2 += delta * delta2;
    }

    Map<String, Object> toRow() {
      Map<String, Object> row = new HashMap<>();
      row.put("count", count);
      row.put("min", count > 0 ? min : null);
      row.put("max", count > 0 ? max : null);
      row.put("avg", count > 0 ? mean : null);
      double variance = (count > 0) ? (m2 / count) : Double.NaN; // population variance
      row.put("stddev", count > 0 ? Math.sqrt(variance) : null);
      return row;
    }
  }

  /**
   * Evaluate event projection with early-stop when limit is reached; other roots fall back to
   * {@link #evaluateValues}.
   */
  public List<Object> evaluateValuesWithLimit(JFRSession session, Query query, Integer limit)
      throws Exception {
    if (query.root != Root.EVENTS || limit == null) {
      return evaluateValues(session, query);
    }
    if (query.segments.isEmpty()) {
      throw new IllegalArgumentException("Expected event type segment after 'events/'");
    }
    if (query.segments.size() < 2) {
      throw new IllegalArgumentException("No projection path provided after event type");
    }
    String eventType = query.segments.get(0);
    List<String> proj = query.segments.subList(1, query.segments.size());
    List<Object> out = new ArrayList<>();
    try (UntypedJafarParser p =
        io.jafar.parser.api.ParsingContext.create().newUntypedParser(session.getRecordingPath())) {
      p.handle(
          (type, value, ctl) -> {
            if (!eventType.equals(type.getName())) return;
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) value;
            if (matchesAll(map, query.predicates)) {
              extractWithIndexing(map, proj, out);
              if (out.size() >= limit) {
                ctl.abort();
              }
            }
          });
      p.run();
    }
    return out;
  }

  private boolean matchesAll(Map<String, Object> map, List<Predicate> predicates) {
    for (Predicate p : predicates) {
      if (p instanceof FieldPredicate fp) {
        if (!deepMatch(
            map,
            fp.fieldPath,
            0,
            fp.op,
            fp.literal,
            fp.matchMode != null ? fp.matchMode : defaultListMatchMode)) {
          return false;
        }
      } else if (p instanceof io.jafar.shell.jfrpath.JfrPath.ExprPredicate ep) {
        if (!evalBoolExpr(map, ep.expr)) return false;
      }
    }
    return true;
  }

  private boolean evalBoolExpr(
      Map<String, Object> root, io.jafar.shell.jfrpath.JfrPath.BoolExpr expr) {
    if (expr instanceof io.jafar.shell.jfrpath.JfrPath.CompExpr ce) {
      Object val = evalValueExpr(root, ce.lhs);
      return compare(val, ce.op, ce.literal);
    } else if (expr instanceof io.jafar.shell.jfrpath.JfrPath.FuncBoolExpr fb) {
      String n = fb.name.toLowerCase(java.util.Locale.ROOT);
      java.util.List<io.jafar.shell.jfrpath.JfrPath.Arg> args = fb.args;
      switch (n) {
        case "contains" -> {
          ensureArgs(args, 2);
          Object s = resolveArg(root, args.get(0));
          String sub = String.valueOf(resolveArg(root, args.get(1)));
          return s != null && String.valueOf(s).contains(sub);
        }
        case "starts_with" -> {
          ensureArgs(args, 2);
          Object s = resolveArg(root, args.get(0));
          String pre = String.valueOf(resolveArg(root, args.get(1)));
          return s != null && String.valueOf(s).startsWith(pre);
        }
        case "ends_with" -> {
          ensureArgs(args, 2);
          Object s = resolveArg(root, args.get(0));
          String suf = String.valueOf(resolveArg(root, args.get(1)));
          return s != null && String.valueOf(s).endsWith(suf);
        }
        case "matches" -> {
          ensureArgsMin(args, 2);
          Object s = resolveArg(root, args.get(0));
          String re = String.valueOf(resolveArg(root, args.get(1)));
          int flags = 0;
          if (args.size() >= 3
              && "i".equalsIgnoreCase(String.valueOf(resolveArg(root, args.get(2)))))
            flags = java.util.regex.Pattern.CASE_INSENSITIVE;
          return s != null
              && java.util.regex.Pattern.compile(re, flags).matcher(String.valueOf(s)).find();
        }
        case "exists" -> {
          ensureArgs(args, 1);
          return resolveArg(root, args.get(0)) != null;
        }
        case "empty" -> {
          ensureArgs(args, 1);
          Object v = resolveArg(root, args.get(0));
          if (v == null) return true;
          if (v instanceof CharSequence cs) return cs.length() == 0;
          Object arr = unwrapArrayLike(v);
          if (arr != null) return java.lang.reflect.Array.getLength(arr) == 0;
          return false;
        }
        case "between" -> {
          ensureArgs(args, 3);
          Object v = resolveArg(root, args.get(0));
          if (!(v instanceof Number)) return false;
          double x = ((Number) v).doubleValue();
          double a = toDouble(resolveArg(root, args.get(1)));
          double b = toDouble(resolveArg(root, args.get(2)));
          return x >= a && x <= b;
        }
        default -> throw new IllegalArgumentException("Unknown function in filter: " + fb.name);
      }
    } else if (expr instanceof io.jafar.shell.jfrpath.JfrPath.LogicalExpr le) {
      boolean l = evalBoolExpr(root, le.left);
      if (le.op == io.jafar.shell.jfrpath.JfrPath.LogicalExpr.Lop.AND)
        return l && evalBoolExpr(root, le.right);
      else return l || evalBoolExpr(root, le.right);
    } else if (expr instanceof io.jafar.shell.jfrpath.JfrPath.NotExpr ne) {
      return !evalBoolExpr(root, ne.inner);
    }
    return false;
  }

  private static void ensureArgs(java.util.List<io.jafar.shell.jfrpath.JfrPath.Arg> args, int n) {
    if (args.size() != n) throw new IllegalArgumentException("Function expects " + n + " args");
  }

  private static void ensureArgsMin(
      java.util.List<io.jafar.shell.jfrpath.JfrPath.Arg> args, int n) {
    if (args.size() < n)
      throw new IllegalArgumentException("Function expects at least " + n + " args");
  }

  private static double toDouble(Object o) {
    return (o instanceof Number)
        ? ((Number) o).doubleValue()
        : Double.parseDouble(String.valueOf(o));
  }

  private Object evalValueExpr(
      Map<String, Object> root, io.jafar.shell.jfrpath.JfrPath.ValueExpr vexpr) {
    if (vexpr instanceof io.jafar.shell.jfrpath.JfrPath.PathRef pr) {
      return Values.get(root, buildPathTokens(pr.path).toArray());
    } else if (vexpr instanceof io.jafar.shell.jfrpath.JfrPath.FuncValueExpr fv) {
      String n = fv.name.toLowerCase(java.util.Locale.ROOT);
      java.util.List<io.jafar.shell.jfrpath.JfrPath.Arg> args = fv.args;
      switch (n) {
        case "len" -> {
          ensureArgs(args, 1);
          Object v = resolveArg(root, args.get(0));
          Integer L = valueLength(v);
          return L;
        }
        default -> throw new IllegalArgumentException("Unknown value function: " + fv.name);
      }
    }
    return null;
  }

  private Object resolveArg(Map<String, Object> root, io.jafar.shell.jfrpath.JfrPath.Arg arg) {
    if (arg instanceof io.jafar.shell.jfrpath.JfrPath.LiteralArg la) return la.value;
    if (arg instanceof io.jafar.shell.jfrpath.JfrPath.PathArg pa)
      return Values.get(root, buildPathTokens(pa.path).toArray());
    return null;
  }

  @SuppressWarnings("unchecked")
  private boolean deepMatch(
      Object current, List<String> path, int idx, Op op, Object lit, JfrPath.MatchMode mode) {
    if (current instanceof io.jafar.parser.api.ComplexType ct) {
      current = ct.getValue();
    }
    if (current instanceof io.jafar.parser.api.ArrayType at) {
      current = at.getArray();
    }
    if (idx >= path.size()) {
      // At leaf; apply comparison to current
      return compare(current, op, lit);
    }
    String seg = path.get(idx);
    // Parse possible index/slice syntax in segment
    String segName = seg;
    Integer segIndex = null;
    int b = seg.indexOf('[');
    int e = seg.endsWith("]") ? seg.lastIndexOf(']') : -1;
    Integer sliceFrom = null, sliceTo = null;
    if (b >= 0 && e > b) {
      segName = seg.substring(0, b);
      String inside = seg.substring(b + 1, e);
      int c = inside.indexOf(':');
      if (c >= 0) {
        try {
          String a = inside.substring(0, c).trim();
          String bb = inside.substring(c + 1).trim();
          sliceFrom = a.isEmpty() ? 0 : Integer.parseInt(a);
          sliceTo = bb.isEmpty() ? Integer.MAX_VALUE : Integer.parseInt(bb);
        } catch (Exception ignore) {
        }
      } else {
        try {
          segIndex = Integer.parseInt(inside.trim());
        } catch (Exception ignore) {
        }
      }
    }
    // If current is a map-like structure
    if (current instanceof java.util.Map<?, ?> m) {
      Object next = ((java.util.Map<String, Object>) m).get(segName);
      if (segIndex != null) {
        Object arr = unwrapArrayLike(next);
        if (arr == null) return false;
        int len = java.lang.reflect.Array.getLength(arr);
        if (segIndex < 0 || segIndex >= len) return false;
        next = java.lang.reflect.Array.get(arr, segIndex);
      } else if (sliceFrom != null) {
        Object arr = unwrapArrayLike(next);
        if (arr == null) return false;
        int len = java.lang.reflect.Array.getLength(arr);
        int from = Math.max(0, sliceFrom);
        int to = Math.min(len, sliceTo == null ? len : sliceTo);
        int matches = 0, total = Math.max(0, to - from);
        for (int i = from; i < to; i++) {
          Object el = java.lang.reflect.Array.get(arr, i);
          if (deepMatch(el, path, idx + 1, op, lit, mode)) matches++;
        }
        return applyMode(matches, total, mode);
      }
      return deepMatch(next, path, idx + 1, op, lit, mode);
    }
    // If current is an array/list, apply match mode across elements without consuming seg
    if (current != null && current.getClass().isArray()) {
      int len = java.lang.reflect.Array.getLength(current);
      int matches = 0;
      for (int i = 0; i < len; i++) {
        Object el = java.lang.reflect.Array.get(current, i);
        if (deepMatch(el, path, idx, op, lit, mode)) matches++;
      }
      return applyMode(matches, len, mode);
    }
    if (current instanceof java.util.Collection<?> coll) {
      int len = 0, matches = 0;
      for (Object el : coll) {
        len++;
        if (deepMatch(el, path, idx, op, lit, mode)) matches++;
      }
      return applyMode(matches, len, mode);
    }
    // No further navigation possible
    return false;
  }

  private static boolean applyMode(int matches, int total, JfrPath.MatchMode mode) {
    return switch (mode) {
      case ANY -> matches > 0;
      case ALL -> total > 0 && matches == total;
      case NONE -> matches == 0;
    };
  }

  private static boolean compare(Object actual, Op op, Object lit) {
    if (actual == null) return false;
    return switch (op) {
      case EQ -> Objects.equals(coerce(actual, lit), lit);
      case NE -> !Objects.equals(coerce(actual, lit), lit);
      case GT -> compareNum(actual, lit) > 0;
      case GE -> compareNum(actual, lit) >= 0;
      case LT -> compareNum(actual, lit) < 0;
      case LE -> compareNum(actual, lit) <= 0;
      case REGEX -> String.valueOf(actual).matches(String.valueOf(lit));
      case PLUS, MINUS, MULT, DIV -> throw new IllegalArgumentException(
          "Arithmetic operators not supported in comparisons");
    };
  }

  private static Object coerce(Object actual, Object lit) {
    if (lit instanceof Number) {
      if (actual instanceof Number) return actual;
      try {
        return Double.parseDouble(String.valueOf(actual));
      } catch (Exception ignore) {
      }
    }
    if (lit instanceof String) return String.valueOf(actual);
    return actual;
  }

  private static int compareNum(Object a, Object b) {
    double da =
        (a instanceof Number) ? ((Number) a).doubleValue() : Double.parseDouble(String.valueOf(a));
    double db =
        (b instanceof Number) ? ((Number) b).doubleValue() : Double.parseDouble(String.valueOf(b));
    return Double.compare(da, db);
  }

  // Decoration evaluation methods

  private List<Map<String, Object>> evaluateDecorateByTime(
      JFRSession session, Query query, JfrPath.DecorateByTimeOp op) throws Exception {
    if (query.root != Root.EVENTS) {
      throw new UnsupportedOperationException("decorateByTime only supports events root");
    }
    if (query.segments.isEmpty()) {
      throw new IllegalArgumentException("decorateByTime requires primary event type");
    }

    String primaryType = query.segments.get(0);

    // PASS 1: Collect decorator events with time ranges
    List<DecoratorTimeRange> decorators =
        collectTimeRangeDecorators(session.getRecordingPath(), op);

    // PASS 2: Stream primary events and decorate
    List<Map<String, Object>> result = new ArrayList<>();
    source.streamEvents(
        session.getRecordingPath(),
        ev -> {
          if (!primaryType.equals(ev.typeName())) return;
          if (!matchesAll(ev.value(), query.predicates)) return;

          // Find matching decorators for this event
          List<Map<String, Object>> matchingDecorators =
              findTimeRangeMatches(ev.value(), decorators, op.threadPathPrimary);

          // Wrap event with decorators
          Map<String, Object> decorated =
              new DecoratedEventMap(ev.value(), matchingDecorators, op.decoratorFields);

          result.add(decorated);
        });

    return result;
  }

  private List<DecoratorTimeRange> collectTimeRangeDecorators(
      Path recording, JfrPath.DecorateByTimeOp op) throws Exception {
    List<DecoratorTimeRange> decorators = new ArrayList<>();

    source.streamEvents(
        recording,
        ev -> {
          if (!op.decoratorEventType.equals(ev.typeName())) return;

          Map<String, Object> event = ev.value();

          // Extract thread ID
          Object threadId = Values.get(event, op.threadPathDecorator.toArray());
          if (threadId == null) return;

          // Extract startTime and duration
          Object startTimeObj = event.get("startTime");
          Object durationObj = event.get("duration");

          if (startTimeObj == null) return;

          long startTime = toLong(startTimeObj);
          long duration = durationObj != null ? toLong(durationObj) : 0;
          long endTime = startTime + duration;

          decorators.add(new DecoratorTimeRange(threadId, startTime, endTime, event));
        });

    // Sort by thread ID and start time for efficient lookup
    decorators.sort(
        java.util.Comparator.comparing((DecoratorTimeRange d) -> String.valueOf(d.threadId))
            .thenComparingLong(d -> d.startTimeNanos));

    return decorators;
  }

  private List<Map<String, Object>> findTimeRangeMatches(
      Map<String, Object> primaryEvent,
      List<DecoratorTimeRange> decorators,
      List<String> threadPathPrimary) {

    // Extract primary event's thread ID and time range
    Object primaryThreadId = Values.get(primaryEvent, threadPathPrimary.toArray());
    if (primaryThreadId == null) return List.of();

    Object startTimeObj = primaryEvent.get("startTime");
    if (startTimeObj == null) return List.of();

    long primaryStartTime = toLong(startTimeObj);
    Object durationObj = primaryEvent.get("duration");
    long primaryDuration = durationObj != null ? toLong(durationObj) : 0;
    long primaryEndTime = primaryStartTime + primaryDuration;

    // Find overlapping decorators on same thread
    List<Map<String, Object>> matches = new ArrayList<>();

    for (DecoratorTimeRange dec : decorators) {
      if (!Objects.equals(dec.threadId, primaryThreadId)) continue;

      // Check time overlap: primaryStart < decoratorEnd && primaryEnd > decoratorStart
      if (primaryStartTime < dec.endTimeNanos && primaryEndTime > dec.startTimeNanos) {
        matches.add(dec.decoratorEvent);
      }
    }

    return matches;
  }

  private List<Map<String, Object>> evaluateDecorateByKey(
      JFRSession session, Query query, JfrPath.DecorateByKeyOp op) throws Exception {
    if (query.root != Root.EVENTS) {
      throw new UnsupportedOperationException("decorateByKey only supports events root");
    }
    if (query.segments.isEmpty()) {
      throw new IllegalArgumentException("decorateByKey requires primary event type");
    }

    String primaryType = query.segments.get(0);

    // PASS 1: Collect decorator events indexed by correlation key
    Map<Object, List<Map<String, Object>>> decoratorIndex =
        collectKeyedDecorators(session.getRecordingPath(), op);

    // PASS 2: Stream primary events and decorate
    List<Map<String, Object>> result = new ArrayList<>();
    source.streamEvents(
        session.getRecordingPath(),
        ev -> {
          if (!primaryType.equals(ev.typeName())) return;
          if (!matchesAll(ev.value(), query.predicates)) return;

          // Compute correlation key for primary event
          Object primaryKey = evaluateKeyExpr(ev.value(), op.primaryKey);
          List<Map<String, Object>> matchingDecorators =
              primaryKey == null ? List.of() : decoratorIndex.getOrDefault(primaryKey, List.of());

          // Wrap event with decorators
          Map<String, Object> decorated =
              new DecoratedEventMap(ev.value(), matchingDecorators, op.decoratorFields);

          result.add(decorated);
        });

    return result;
  }

  private Map<Object, List<Map<String, Object>>> collectKeyedDecorators(
      Path recording, JfrPath.DecorateByKeyOp op) throws Exception {
    Map<Object, List<Map<String, Object>>> index = new HashMap<>();

    source.streamEvents(
        recording,
        ev -> {
          if (!op.decoratorEventType.equals(ev.typeName())) return;

          Map<String, Object> event = ev.value();

          // Compute correlation key
          Object key = evaluateKeyExpr(event, op.decoratorKey);
          if (key == null) return;

          index.computeIfAbsent(key, k -> new ArrayList<>()).add(event);
        });

    return index;
  }

  private Object evaluateKeyExpr(Map<String, Object> event, JfrPath.KeyExpr keyExpr) {
    if (keyExpr instanceof JfrPath.PathKeyExpr pke) {
      return Values.get(event, pke.path.toArray());
    }
    return null;
  }

  private static long toLong(Object obj) {
    if (obj instanceof Number n) return n.longValue();
    return Long.parseLong(String.valueOf(obj));
  }

  // Helper class for time-range decorators
  private static class DecoratorTimeRange {
    final Object threadId;
    final long startTimeNanos;
    final long endTimeNanos;
    final Map<String, Object> decoratorEvent;

    DecoratorTimeRange(
        Object threadId,
        long startTimeNanos,
        long endTimeNanos,
        Map<String, Object> decoratorEvent) {
      this.threadId = threadId;
      this.startTimeNanos = startTimeNanos;
      this.endTimeNanos = endTimeNanos;
      this.decoratorEvent = decoratorEvent;
    }
  }

  // Lazy decorator wrapper for decorated events
  private static class DecoratedEventMap extends java.util.AbstractMap<String, Object> {
    private static final String DECORATOR_PREFIX = "$decorator.";

    private final Map<String, Object> primaryEvent;
    private final List<Map<String, Object>> decorators;
    private final List<String> decoratorFields;

    DecoratedEventMap(
        Map<String, Object> primaryEvent,
        List<Map<String, Object>> decorators,
        List<String> decoratorFields) {
      this.primaryEvent = primaryEvent;
      this.decorators = decorators;
      this.decoratorFields = decoratorFields;
    }

    @Override
    public Object get(Object key) {
      String keyStr = String.valueOf(key);

      // Check if accessing decorator field
      if (keyStr.startsWith(DECORATOR_PREFIX)) {
        String decoratorField = keyStr.substring(DECORATOR_PREFIX.length());

        // Only expose requested fields (if specified)
        if (!decoratorFields.isEmpty() && !decoratorFields.contains(decoratorField)) {
          return null;
        }

        // Return from first matching decorator
        if (!decorators.isEmpty()) {
          return decorators.get(0).get(decoratorField);
        }
        return null;
      }

      // Otherwise delegate to primary event
      return primaryEvent.get(key);
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
      // Lazily compute entry set combining primary + decorator fields
      Set<Entry<String, Object>> entries = new java.util.HashSet<>(primaryEvent.entrySet());

      if (!decorators.isEmpty()) {
        Map<String, Object> firstDecorator = decorators.get(0);
        Iterable<String> fieldsToExpose =
            decoratorFields.isEmpty() ? firstDecorator.keySet() : decoratorFields;

        for (String field : fieldsToExpose) {
          Object value = firstDecorator.get(field);
          if (value != null) {
            entries.add(Map.entry(DECORATOR_PREFIX + field, value));
          }
        }
      }

      return entries;
    }

    @Override
    public boolean containsKey(Object key) {
      String keyStr = String.valueOf(key);
      if (keyStr.startsWith(DECORATOR_PREFIX)) {
        String decoratorField = keyStr.substring(DECORATOR_PREFIX.length());
        if (!decoratorFields.isEmpty() && !decoratorFields.contains(decoratorField)) {
          return false;
        }
        return !decorators.isEmpty() && decorators.get(0).containsKey(decoratorField);
      }
      return primaryEvent.containsKey(key);
    }

    @Override
    public int size() {
      int size = primaryEvent.size();
      if (!decorators.isEmpty()) {
        Map<String, Object> firstDecorator = decorators.get(0);
        size += decoratorFields.isEmpty() ? firstDecorator.size() : decoratorFields.size();
      }
      return size;
    }
  }

  private void validateEventTypes(JFRSession session, List<String> requestedTypes)
      throws Exception {
    Set<String> availableTypes = session.getAvailableEventTypes();

    // Skip validation if event type information is not available
    if (availableTypes == null || availableTypes.isEmpty()) {
      return;
    }

    for (String requestedType : requestedTypes) {
      if (!availableTypes.contains(requestedType)) {
        String suggestion = findClosestMatch(requestedType, availableTypes);
        if (suggestion != null) {
          throw new IllegalArgumentException(
              "Event type '" + requestedType + "' not found. Did you mean '" + suggestion + "'?");
        }
        throw new IllegalArgumentException("Event type '" + requestedType + "' not found");
      }
    }
  }

  /**
   * Finds the closest matching event type using prefix matching and Levenshtein distance.
   *
   * @return closest match or null if no good match found
   */
  private String findClosestMatch(String requested, Set<String> available) {
    // Prefix matching first
    for (String avail : available) {
      if (avail.startsWith(requested) || requested.startsWith(avail)) {
        return avail;
      }
    }

    // Levenshtein distance fallback (max 3 edits)
    int minDist = Integer.MAX_VALUE;
    String closest = null;
    for (String avail : available) {
      int dist = levenshteinDistance(requested, avail);
      if (dist < minDist && dist <= 3) {
        minDist = dist;
        closest = avail;
      }
    }
    return closest;
  }

  /** Calculates Levenshtein distance between two strings. */
  private int levenshteinDistance(String s1, String s2) {
    int[][] dp = new int[s1.length() + 1][s2.length() + 1];
    for (int i = 0; i <= s1.length(); i++) dp[i][0] = i;
    for (int j = 0; j <= s2.length(); j++) dp[0][j] = j;

    for (int i = 1; i <= s1.length(); i++) {
      for (int j = 1; j <= s2.length(); j++) {
        int cost = s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1;
        dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
      }
    }
    return dp[s1.length()][s2.length()];
  }

  /** Default EventSource that streams all events untyped from a recording. */
  static final class DefaultEventSource implements EventSource {
    @Override
    public void streamEvents(Path recording, Consumer<Event> consumer) throws Exception {
      try (UntypedJafarParser p =
          io.jafar.parser.api.ParsingContext.create().newUntypedParser(recording)) {
        p.handle((type, value, ctl) -> consumer.accept(new Event(type.getName(), value)));
        p.run();
      }
    }
  }

  // Metadata is provided by MetadataProvider; keep a public wrapper for compatibility

  /** Load metadata for a single field name under a type. */
  public Map<String, Object> loadFieldMetadata(Path recording, String typeName, String fieldName)
      throws Exception {
    return MetadataProvider.loadField(recording, typeName, fieldName);
  }

  /**
   * Applies pipeline operators to an existing list of rows. This allows post-processing cached
   * results from lazy variables without re-evaluating the original query.
   *
   * @param rows the input rows
   * @param pipeline the pipeline operators to apply
   * @return the processed result
   */
  @SuppressWarnings("unchecked")
  public List<Map<String, Object>> applyToRows(
      List<Map<String, Object>> rows, List<JfrPath.PipelineOp> pipeline) {
    if (pipeline == null || pipeline.isEmpty()) {
      return rows;
    }

    List<Map<String, Object>> result = new ArrayList<>(rows);

    for (JfrPath.PipelineOp op : pipeline) {
      result = applySingleOp(result, op);
    }

    return result;
  }

  private List<Map<String, Object>> applySingleOp(
      List<Map<String, Object>> rows, JfrPath.PipelineOp op) {
    return switch (op) {
      case JfrPath.TopOp top -> applyTop(rows, top.n, top.byPath, top.ascending);
      case JfrPath.CountOp c -> applyCount(rows);
      case JfrPath.SumOp sum -> applySum(rows, sum.valuePath);
      case JfrPath.StatsOp stats -> applyStats(rows, stats.valuePath);
      case JfrPath.SelectOp sel -> applySelect(rows, sel);
      case JfrPath.GroupByOp gb -> applyGroupBy(rows, gb.keyPath, gb.aggFunc, gb.valuePath);
      case JfrPath.QuantilesOp q -> applyQuantiles(rows, q.valuePath, q.qs);
      case JfrPath.LenOp len -> applyLen(rows, len.valuePath);
      case JfrPath.UppercaseOp up -> applyStringTransform(rows, up.valuePath, String::toUpperCase);
      case JfrPath.LowercaseOp lo -> applyStringTransform(rows, lo.valuePath, String::toLowerCase);
      case JfrPath.TrimOp tr -> applyStringTransform(rows, tr.valuePath, String::trim);
      case JfrPath.AbsOp ab -> applyNumberTransform(rows, ab.valuePath, Math::abs);
      case JfrPath.RoundOp ro -> applyNumberTransform(rows, ro.valuePath, Math::round);
      case JfrPath.FloorOp fl -> applyNumberTransform(rows, fl.valuePath, Math::floor);
      case JfrPath.CeilOp ce -> applyNumberTransform(rows, ce.valuePath, Math::ceil);
      case JfrPath.ContainsOp co -> applyContains(rows, co.valuePath, co.substr);
      case JfrPath.ReplaceOp rp -> applyReplace(rows, rp.valuePath, rp.target, rp.replacement);
      case JfrPath.ToMapOp tm -> applyToMap(rows, tm.keyField, tm.valueField);
      case JfrPath.TimeRangeOp tr -> applyTimeRange(rows, tr.valuePath, tr.durationPath);
      default -> rows; // DecorateByTime/DecorateByKey not supported for cached rows
    };
  }

  private List<Map<String, Object>> applyTop(
      List<Map<String, Object>> rows, int n, List<String> byPath, boolean ascending) {
    if (rows.isEmpty()) return rows;
    List<Map<String, Object>> sorted = new ArrayList<>(rows);
    sorted.sort(
        (a, b) -> {
          Object aVal = Values.get(a, buildPathTokens(byPath).toArray());
          Object bVal = Values.get(b, buildPathTokens(byPath).toArray());
          int cmp = compareValues(aVal, bVal);
          return ascending ? cmp : -cmp;
        });
    return sorted.subList(0, Math.min(n, sorted.size()));
  }

  private List<Map<String, Object>> applyCount(List<Map<String, Object>> rows) {
    Map<String, Object> row = new HashMap<>();
    row.put("count", (long) rows.size());
    return List.of(row);
  }

  private List<Map<String, Object>> applySum(List<Map<String, Object>> rows, List<String> path) {
    double sum = 0;
    String key = path.isEmpty() ? "value" : String.join("/", path);
    for (Map<String, Object> row : rows) {
      Object val =
          path.isEmpty() ? row.values().iterator().next() : Values.get(row, path.toArray());
      if (val instanceof Number num) {
        sum += num.doubleValue();
      }
    }
    Map<String, Object> result = new HashMap<>();
    result.put("sum(" + key + ")", sum);
    return List.of(result);
  }

  private List<Map<String, Object>> applyStats(List<Map<String, Object>> rows, List<String> path) {
    StatsAgg agg = new StatsAgg();
    for (Map<String, Object> row : rows) {
      Object val =
          path.isEmpty() ? row.values().iterator().next() : Values.get(row, path.toArray());
      if (val instanceof Number num) {
        agg.add(num.doubleValue());
      }
    }
    return List.of(agg.toRow());
  }

  private List<Map<String, Object>> applySelect(
      List<Map<String, Object>> rows, JfrPath.SelectOp op) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      Map<String, Object> selected = new LinkedHashMap<>();

      for (JfrPath.SelectItem item : op.items) {
        String columnName = item.outputName(); // Use consistent naming with evaluateSelect
        Object value;

        if (item instanceof JfrPath.FieldSelection fieldSel) {
          value = Values.get(row, fieldSel.fieldPath.toArray());
        } else if (item instanceof JfrPath.ExpressionSelection exprSel) {
          // Evaluate expression
          value = evaluateExpression(exprSel.expression, row);
        } else {
          throw new IllegalStateException("Unknown SelectItem type");
        }

        selected.put(columnName, value);
      }

      result.add(selected);
    }
    return result;
  }

  private List<Map<String, Object>> applyGroupBy(
      List<Map<String, Object>> rows,
      List<String> keyPath,
      String aggFunc,
      List<String> valuePath) {
    Map<Object, GroupAccumulator> groups = new LinkedHashMap<>();

    for (Map<String, Object> row : rows) {
      Object keyVal = Values.get(row, buildPathTokens(keyPath).toArray());
      GroupAccumulator acc = groups.computeIfAbsent(keyVal, k -> new GroupAccumulator(aggFunc));

      if ("count".equals(aggFunc)) {
        acc.add(0); // Just increment count
      } else {
        Object val =
            valuePath.isEmpty()
                ? row.values().iterator().next()
                : Values.get(row, buildPathTokens(valuePath).toArray());
        if (val instanceof Number num) {
          acc.add(num.doubleValue());
        }
      }
    }

    List<Map<String, Object>> result = new ArrayList<>();
    for (var entry : groups.entrySet()) {
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("key", entry.getKey());
      out.put(aggFunc, entry.getValue().getResult());
      result.add(out);
    }
    return result;
  }

  private List<Map<String, Object>> applyQuantiles(
      List<Map<String, Object>> rows, List<String> path, List<Double> qs) {
    List<Double> values = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      Object val =
          path.isEmpty() ? row.values().iterator().next() : Values.get(row, path.toArray());
      if (val instanceof Number num) {
        values.add(num.doubleValue());
      }
    }
    Collections.sort(values);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("count", (long) values.size());
    for (double q : qs) {
      result.put(pcol(q), quantileNearestRank(values, q));
    }
    return List.of(result);
  }

  private List<Map<String, Object>> applyLen(List<Map<String, Object>> rows, List<String> path) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      Map<String, Object> out = new LinkedHashMap<>(row);
      Object val =
          path.isEmpty() ? row.values().iterator().next() : Values.get(row, path.toArray());
      int len = 0;
      if (val instanceof String s) {
        len = s.length();
      } else if (val instanceof Collection<?> c) {
        len = c.size();
      } else if (val != null && val.getClass().isArray()) {
        len = java.lang.reflect.Array.getLength(val);
      }
      out.put("len", len);
      result.add(out);
    }
    return result;
  }

  private List<Map<String, Object>> applyStringTransform(
      List<Map<String, Object>> rows,
      List<String> path,
      java.util.function.Function<String, String> transform) {
    List<Map<String, Object>> result = new ArrayList<>();
    String key = path.isEmpty() ? null : String.join("/", path);
    for (Map<String, Object> row : rows) {
      Map<String, Object> out = new LinkedHashMap<>(row);
      Object val =
          path.isEmpty() ? row.values().iterator().next() : Values.get(row, path.toArray());
      if (val instanceof String s) {
        String transformed = transform.apply(s);
        if (key != null) {
          out.put(key, transformed);
        } else if (!row.isEmpty()) {
          String firstKey = row.keySet().iterator().next();
          out.put(firstKey, transformed);
        }
      }
      result.add(out);
    }
    return result;
  }

  private List<Map<String, Object>> applyNumberTransform(
      List<Map<String, Object>> rows,
      List<String> path,
      java.util.function.DoubleUnaryOperator transform) {
    List<Map<String, Object>> result = new ArrayList<>();
    String key = path.isEmpty() ? null : String.join("/", path);
    for (Map<String, Object> row : rows) {
      Map<String, Object> out = new LinkedHashMap<>(row);
      Object val =
          path.isEmpty() ? row.values().iterator().next() : Values.get(row, path.toArray());
      if (val instanceof Number num) {
        double transformed = transform.applyAsDouble(num.doubleValue());
        if (key != null) {
          out.put(key, transformed);
        } else if (!row.isEmpty()) {
          String firstKey = row.keySet().iterator().next();
          out.put(firstKey, transformed);
        }
      }
      result.add(out);
    }
    return result;
  }

  private List<Map<String, Object>> applyContains(
      List<Map<String, Object>> rows, List<String> path, String substr) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      Object val =
          path.isEmpty() ? row.values().iterator().next() : Values.get(row, path.toArray());
      if (val instanceof String s && s.contains(substr)) {
        result.add(row);
      }
    }
    return result;
  }

  private List<Map<String, Object>> applyReplace(
      List<Map<String, Object>> rows, List<String> path, String target, String replacement) {
    List<Map<String, Object>> result = new ArrayList<>();
    String key = path.isEmpty() ? null : String.join("/", path);
    for (Map<String, Object> row : rows) {
      Map<String, Object> out = new LinkedHashMap<>(row);
      Object val =
          path.isEmpty() ? row.values().iterator().next() : Values.get(row, path.toArray());
      if (val instanceof String s) {
        String replaced = s.replace(target, replacement);
        if (key != null) {
          out.put(key, replaced);
        } else if (!row.isEmpty()) {
          String firstKey = row.keySet().iterator().next();
          out.put(firstKey, replaced);
        }
      }
      result.add(out);
    }
    return result;
  }

  private List<Map<String, Object>> applyToMap(
      List<Map<String, Object>> rows, List<String> keyField, List<String> valueField) {

    Map<String, Object> resultMap = new LinkedHashMap<>();

    for (Map<String, Object> row : rows) {
      // Extract key and value from row
      Object keyObj = Values.get(row, keyField.toArray());
      Object valueObj = Values.get(row, valueField.toArray());

      // Skip rows with missing key field
      if (keyObj == null) {
        continue;
      }

      // Convert key to string for consistency and safety
      String key = String.valueOf(keyObj);

      // Store in map (last value wins for duplicate keys)
      resultMap.put(key, valueObj);
    }

    // Return as single-row list containing the map
    return List.of(resultMap);
  }

  private List<Map<String, Object>> applyTimeRange(
      List<Map<String, Object>> rows, List<String> valuePath, List<String> durationPath) {
    long minTicks = Long.MAX_VALUE;
    long maxTicks = Long.MIN_VALUE;
    long count = 0;
    boolean hasDuration = durationPath != null && !durationPath.isEmpty();

    for (Map<String, Object> row : rows) {
      Object val = Values.get(row, valuePath.toArray());
      if (val instanceof Number n) {
        long ticks = n.longValue();
        if (ticks < minTicks) minTicks = ticks;
        long endTicks = ticks;
        if (hasDuration) {
          Object durVal = Values.get(row, durationPath.toArray());
          if (durVal instanceof Number dn) {
            endTicks = ticks + dn.longValue();
          }
        }
        if (endTicks > maxTicks) maxTicks = endTicks;
        count++;
      }
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("count", count);
    result.put("field", String.join("/", valuePath));

    if (count == 0) {
      result.put("minTicks", null);
      result.put("maxTicks", null);
      result.put("durationTicks", null);
      result.put("note", "Time conversion unavailable for cached rows");
    } else {
      result.put("minTicks", minTicks);
      result.put("maxTicks", maxTicks);
      result.put("durationTicks", maxTicks - minTicks);
      result.put(
          "note",
          "Time conversion unavailable for cached rows; use timerange() directly on events");
    }

    return List.of(result);
  }
}
