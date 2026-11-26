package io.jafar.shell.jfrpath;

import static io.jafar.shell.jfrpath.JfrPath.*;

import io.jafar.parser.api.Values;
import io.jafar.shell.JFRSession;
import io.jafar.shell.backend.BackendRegistry;
import io.jafar.shell.backend.JfrBackend;
import io.jafar.shell.providers.ChunkProvider;
import io.jafar.shell.providers.ConstantPoolProvider;
import io.jafar.shell.providers.MetadataProvider;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/** Pipeline operators that aggregate over the event stream (count, stats, groupBy, select, ...). */
final class Aggregations {
  private final JfrPathEvaluator eval;

  Aggregations(JfrPathEvaluator eval) {
    this.eval = eval;
  }

  static final String CROSSREF_USAGE =
      "crossref() must directly follow constants/<type>, with no filter or projection:"
          + " constants/<type> | crossref()";

  /**
   * {@code constants/<type> | crossref()}: one summary row. {@code referencingEventTypes} lists,
   * per event type that reaches the type (directly or through other pool entries), the events that
   * do, the distinct entries they reach and their direct references; most events first.
   */
  List<Map<String, Object>> aggregateCrossref(JFRSession session, Query query) throws Exception {
    if (query.root != Root.CONSTANTS
        || query.segments.size() != 1
        || (query.predicates != null && !query.predicates.isEmpty())) {
      throw new IllegalArgumentException(CROSSREF_USAGE);
    }
    if (!ConstantPoolProvider.isSupported()) {
      JfrBackend backend = BackendRegistry.getInstance().getCurrent();
      throw new UnsupportedOperationException(
          "Backend '" + backend.getId() + "' does not support constant pool queries");
    }
    String type = query.segments.get(0);
    Map<String, Object> result = ConstantPoolProvider.crossref(session.getRecordingPath(), type);
    Map<String, Long> events = longMap(result.get("eventCounts"));
    Map<String, Long> unique = longMap(result.get("uniqueRefsByEventType"));
    Map<String, Long> direct = longMap(result.get("totalRefsByEventType"));

    Set<String> eventTypes = new HashSet<>(events.keySet());
    eventTypes.addAll(unique.keySet());
    eventTypes.addAll(direct.keySet());
    List<Map<String, Object>> referencing = new ArrayList<>(eventTypes.size());
    eventTypes.stream()
        .sorted(
            Comparator.comparing((String t) -> events.getOrDefault(t, 0L))
                .reversed()
                .thenComparing(Comparator.naturalOrder()))
        .forEach(
            t -> {
              Map<String, Object> r = new LinkedHashMap<>();
              r.put("eventType", t);
              r.put("events", events.getOrDefault(t, 0L));
              r.put("uniqueRefs", unique.getOrDefault(t, 0L));
              r.put("directRefs", direct.getOrDefault(t, 0L));
              referencing.add(r);
            });

    Map<String, Object> row = new LinkedHashMap<>();
    row.put("type", result.get("type"));
    row.put("total", ((Number) result.get("cpTotal")).longValue());
    row.put("referenced", ((Number) result.get("cpReferenced")).longValue());
    row.put("unreferenced", ((Number) result.get("cpUnused")).longValue());
    row.put("referencedPercent", ((Number) result.get("usedPercent")).doubleValue());
    row.put("unreferencedPercent", ((Number) result.get("unusedPercent")).doubleValue());
    row.put("referencingEventTypes", referencing);
    return List.of(row);
  }

  private static Map<String, Long> longMap(Object o) {
    Map<String, Long> out = new HashMap<>();
    if (o instanceof Map<?, ?> m) {
      m.forEach((k, v) -> out.put(String.valueOf(k), ((Number) v).longValue()));
    }
    return out;
  }

  List<Map<String, Object>> evaluateSelect(JFRSession session, Query query, JfrPath.SelectOp op)
      throws Exception {
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
      baseResults = eval.evaluate(session, baseQuery);
    } else {
      // Evaluate all prior pipeline operations
      List<JfrPath.PipelineOp> priorOps = query.pipeline.subList(0, selectIndex);
      Query baseQuery = new Query(query.root, query.segments, query.predicates, priorOps);
      baseResults = eval.evaluate(session, baseQuery);
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
          value = PathValues.resolveSingle(row, fieldSel.fieldPath);
        } else if (item instanceof JfrPath.ExpressionSelection exprSel) {
          // Evaluate expression
          value = ExpressionEvaluator.evaluateExpression(exprSel.expression, row);
        } else {
          throw new IllegalStateException("Unknown SelectItem type: " + item.getClass());
        }

        projected.put(columnName, value);
      }

      result.add(projected);
    }

    return result;
  }

  List<Map<String, Object>> evaluateToMap(JFRSession session, Query query, JfrPath.ToMapOp op)
      throws Exception {

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
      baseResults = eval.evaluate(session, baseQuery);
    } else {
      // Evaluate all prior pipeline operations
      List<JfrPath.PipelineOp> priorOps = query.pipeline.subList(0, toMapIndex);
      Query baseQuery = new Query(query.root, query.segments, query.predicates, priorOps);
      baseResults = eval.evaluate(session, baseQuery);
    }

    // Apply toMap transformation
    return RowOps.applyToMap(baseResults, op.keyField, op.valueField);
  }

  List<Map<String, Object>> aggregateTimeRange(
      JFRSession session, Query query, JfrPath.TimeRangeOp op) throws Exception {

    if (!ChunkProvider.isSupported()) {
      JfrBackend backend = BackendRegistry.getInstance().getCurrent();
      throw new UnsupportedOperationException(
          "Backend '"
              + backend.getId()
              + "' does not support timeRange() - chunk metadata required");
    }

    // Load chunk metadata to validate the recording has chunks; event times are already epoch
    // nanos (normalized by the parser), so no conversion from ticks is needed
    List<Map<String, Object>> chunks = ChunkProvider.loadAllChunks(session.getRecordingPath());
    if (chunks.isEmpty()) {
      throw new IllegalStateException("No chunks found in recording");
    }

    // Track min/max epoch-nanos values (including duration when given)
    TimeRangeAgg range = new TimeRangeAgg();

    List<String> valuePath = op.valuePath;
    List<String> durationPath = op.durationPath;

    if (query.root == Root.EVENTS) {
      if (query.eventTypes.isEmpty()) {
        throw new IllegalArgumentException("events root requires type");
      }

      eval.validateEventTypes(session, query.eventTypes);

      if (query.isMultiType) {
        Set<String> typeSet = new HashSet<>(query.eventTypes);
        eval.forEachMatching(
            session.getRecordingPath(),
            typeSet,
            query.predicates,
            map -> {
              range.offer(map, valuePath, durationPath);
            });
      } else {
        String eventType = query.eventTypes.get(0);
        eval.forEachMatching(
            session.getRecordingPath(),
            List.of(eventType),
            query.predicates,
            map -> {
              range.offer(map, valuePath, durationPath);
            });
      }
    } else {
      // For non-event roots, materialize rows first
      List<Map<String, Object>> rows =
          eval.evaluate(session, new Query(query.root, query.segments, query.predicates));
      for (Map<String, Object> row : rows) {
        range.offer(row, valuePath, durationPath);
      }
    }

    // Build result
    long[] minMax = new long[2];
    long[] count = new long[1];
    range.snapshot(minMax, count);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("count", count[0]);
    result.put("field", String.join("/", valuePath));

    if (count[0] == 0) {
      result.put("minEpochNanos", null);
      result.put("maxEpochNanos", null);
      result.put("minTime", null);
      result.put("maxTime", null);
      result.put("durationNanos", null);
      result.put("durationMs", null);
      result.put("duration", null);
    } else {
      result.put("minEpochNanos", minMax[0]);
      result.put("maxEpochNanos", minMax[1]);

      // minMax values are already epoch nanos (normalized by the parser)
      Instant minInstant = Instant.ofEpochSecond(0, minMax[0]);
      Instant maxInstant = Instant.ofEpochSecond(0, minMax[1]);

      // Format the times
      DateTimeFormatter formatter = TimeFormats.getFormatter(op.format);
      result.put("minTime", formatter.format(minInstant.atZone(TimeFormats.SYSTEM_ZONE)));
      result.put("maxTime", formatter.format(maxInstant.atZone(TimeFormats.SYSTEM_ZONE)));

      // Calculate duration (both values are epoch nanos)
      long durationNanos = minMax[1] - minMax[0];
      result.put("durationNanos", durationNanos);
      result.put("durationMs", durationNanos / 1_000_000.0);
      result.put("duration", TimeFormats.formatDuration(durationNanos));
    }

    return List.of(result);
  }

  List<Map<String, Object>> aggregateAsDateTime(
      JFRSession session, Query query, List<String> valuePath, String format) throws Exception {
    DateTimeFormatter formatter = TimeFormats.getFormatter(format);
    List<Map<String, Object>> out = new ArrayList<>();
    final List<String> path = valuePath;
    Consumer<Object> addFormatted =
        (val) -> {
          Map<String, Object> row = new HashMap<>();
          if (val instanceof Number n) {
            Instant instant = Instant.ofEpochSecond(0, n.longValue());
            row.put("value", formatter.format(instant.atZone(TimeFormats.SYSTEM_ZONE)));
          } else {
            row.put("value", null);
          }
          synchronized (out) {
            out.add(row);
          }
        };
    if (query.root == Root.EVENTS) {
      if (query.eventTypes.isEmpty())
        throw new IllegalArgumentException("events root requires type");
      eval.validateEventTypes(session, query.eventTypes);
      if (query.isMultiType) {
        Set<String> typeSet = new HashSet<>(query.eventTypes);
        eval.forEachMatching(
            session.getRecordingPath(),
            typeSet,
            query.predicates,
            map -> {
              addFormatted.accept(Values.get(map, path.toArray()));
            });
      } else {
        String eventType = query.eventTypes.get(0);
        eval.forEachMatching(
            session.getRecordingPath(),
            List.of(eventType),
            query.predicates,
            map -> {
              addFormatted.accept(Values.get(map, path.toArray()));
            });
      }
    } else {
      List<Object> vals =
          eval.evaluateValues(session, new Query(query.root, query.segments, query.predicates));
      for (Object v : vals) addFormatted.accept(v);
    }
    return out;
  }

  List<Map<String, Object>> aggregateCount(JFRSession session, Query query) throws Exception {
    long count = 0;
    if (query.root == Root.EVENTS) {
      if (query.eventTypes.isEmpty())
        throw new IllegalArgumentException("events root requires type");

      // Validate event types exist
      eval.validateEventTypes(session, query.eventTypes);

      // The consumer runs on several chunk-parsing threads at once: count with a LongAdder.
      final LongAdder c = new LongAdder();
      if (query.predicates.isEmpty()) {
        // Nothing to test on an event: count headers, decode nothing. Same types either way:
        // forEachMatching streams exactly query.eventTypes.
        eval.source
            .countEvents(session.getRecordingPath(), new HashSet<>(query.eventTypes))
            .values()
            .forEach(c::add);
      } else if (query.isMultiType) {
        // Multi-type query: use Set for O(1) lookup
        Set<String> typeSet = new HashSet<>(query.eventTypes);
        eval.forEachMatching(
            session.getRecordingPath(), typeSet, query.predicates, map -> c.increment());
      } else {
        // Single-type query: use direct comparison (faster)
        String eventType = query.eventTypes.get(0);
        eval.forEachMatching(
            session.getRecordingPath(), List.of(eventType), query.predicates, map -> c.increment());
      }
      count = c.sum();
    } else if (query.root == Root.METADATA) {
      if (query.segments.isEmpty())
        throw new IllegalArgumentException("metadata root requires type");
      // base row presence as count=1 if matches, else 0; projection lists use evaluateValues
      if (query.segments.size() > 1) {
        List<Object> vals =
            eval.evaluateValues(session, new Query(query.root, query.segments, query.predicates));
        count = vals.size();
      } else {
        List<Map<String, Object>> rows =
            eval.evaluate(session, new Query(query.root, query.segments, query.predicates));
        count = rows.size();
      }
    } else if (query.root == Root.CHUNKS || query.root == Root.CONSTANTS) {
      List<Map<String, Object>> rows =
          eval.evaluate(session, new Query(query.root, query.segments, query.predicates));
      count = rows.size();
    } else {
      throw new UnsupportedOperationException("Unsupported root: " + query.root);
    }
    Map<String, Object> out = new HashMap<>();
    out.put("count", count);
    return List.of(out);
  }

  List<Map<String, Object>> aggregateStats(
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
      eval.validateEventTypes(session, query.eventTypes);

      List<String> path = vpath;
      if (query.isMultiType) {
        // Multi-type query: use Set for O(1) lookup
        Set<String> typeSet = new HashSet<>(query.eventTypes);
        eval.forEachMatching(
            session.getRecordingPath(),
            typeSet,
            query.predicates,
            map -> {
              Object val = Values.get(map, path.toArray());
              if (val instanceof Number n) agg.add(n.doubleValue());
            });
      } else {
        // Single-type query: use direct comparison (faster)
        String eventType = query.eventTypes.get(0);
        eval.forEachMatching(
            session.getRecordingPath(),
            List.of(eventType),
            query.predicates,
            map -> {
              Object val = Values.get(map, path.toArray());
              if (val instanceof Number n) agg.add(n.doubleValue());
            });
      }
    } else {
      // For non-events: derive values from the original query
      if (query.segments.size() >= 1) {
        List<Object> vals =
            eval.evaluateValues(session, new Query(query.root, query.segments, query.predicates));
        for (Object v : vals) if (v instanceof Number n) agg.add(n.doubleValue());
      } else {
        throw new IllegalArgumentException("stats() not applicable");
      }
    }
    return List.of(agg.toRow());
  }

  List<Map<String, Object>> aggregateQuantiles(
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
      eval.forEachMatching(
          session.getRecordingPath(),
          List.of(eventType),
          query.predicates,
          map -> {
            Object val = Values.get(map, path.toArray());
            if (val instanceof Number n) {
              synchronized (values) {
                values.add(n.doubleValue());
              }
            }
          });
    } else {
      List<Object> vals =
          eval.evaluateValues(session, new Query(query.root, query.segments, query.predicates));
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

  List<Map<String, Object>> aggregateSketch(JFRSession session, Query query, List<String> valuePath)
      throws Exception {
    // Combine stats + default quantiles
    Map<String, Object> stats = aggregateStats(session, query, valuePath).get(0);
    Map<String, Object> quants =
        aggregateQuantiles(session, query, valuePath, List.of(0.5, 0.9, 0.99)).get(0);
    Map<String, Object> out = new HashMap<>();
    out.putAll(stats);
    out.putAll(quants);
    return List.of(out);
  }

  List<Map<String, Object>> aggregateSum(
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
      eval.validateEventTypes(session, query.eventTypes);

      final DoubleAdder s = new DoubleAdder();
      final LongAdder c = new LongAdder();
      if (query.isMultiType) {
        // Multi-type query: use Set for O(1) lookup
        Set<String> typeSet = new HashSet<>(query.eventTypes);
        eval.forEachMatching(
            session.getRecordingPath(),
            typeSet,
            query.predicates,
            map -> {
              Object val = Values.get(map, path.toArray());
              if (val instanceof Number n) {
                s.add(n.doubleValue());
                c.increment();
              }
            });
      } else {
        // Single-type query: use direct comparison (faster)
        String eventType = query.eventTypes.get(0);
        eval.forEachMatching(
            session.getRecordingPath(),
            List.of(eventType),
            query.predicates,
            map -> {
              Object val = Values.get(map, path.toArray());
              if (val instanceof Number n) {
                s.add(n.doubleValue());
                c.increment();
              }
            });
      }
      sum = s.sum();
      count = c.sum();
    } else {
      List<Object> vals =
          eval.evaluateValues(session, new Query(query.root, query.segments, query.predicates));
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

  List<Map<String, Object>> aggregateGroupBy(
      JFRSession session,
      Query query,
      List<String> keyPath,
      JfrPath.Expr keyExpr,
      String aggFunc,
      List<String> valuePath,
      JfrPath.Expr valueExpr,
      String sortBy,
      boolean ascending)
      throws Exception {
    Map<Object, GroupAccumulator> groups = new LinkedHashMap<>();
    // How many events the key was offered. Only used to tell "nothing matched the filter" apart
    // from "the key path matched nothing in events that were there" — see reportUnmatchedKey.
    LongAdder offered = new LongAdder();

    // Pre-build path tokens for array iteration support. A FieldRef key expression is folded into
    // keyPath by the parser, so indexed paths keep the plain-path fan-out behaviour.
    boolean pathKeyed = keyExpr == null || keyExpr instanceof JfrPath.FieldRef;
    List<Object> keyTokens = PathValues.buildPathTokens(keyPath);
    String keyDisplay =
        pathKeyed ? String.join("/", keyPath) : ExpressionEvaluator.describe(keyExpr);
    List<Object> valueTokens =
        (valuePath.isEmpty() && valueExpr == null) ? null : PathValues.buildPathTokens(valuePath);

    if (query.root == Root.EVENTS) {
      if (query.eventTypes.isEmpty())
        throw new IllegalArgumentException("events root requires type");

      // Validate event types exist
      eval.validateEventTypes(session, query.eventTypes);

      if (query.isMultiType) {
        // Multi-type query: use Set for O(1) lookup
        Set<String> typeSet = new HashSet<>(query.eventTypes);
        eval.forEachMatching(
            session.getRecordingPath(),
            typeSet,
            query.predicates,
            map -> {
              offered.increment();
              addToGroups(
                  groups,
                  map,
                  keyTokens,
                  keyExpr,
                  pathKeyed,
                  aggFunc,
                  keyDisplay,
                  valueExpr,
                  valueTokens);
            });
      } else {
        // Single-type query: use direct comparison (faster)
        String eventType = query.eventTypes.get(0);
        eval.forEachMatching(
            session.getRecordingPath(),
            List.of(eventType),
            query.predicates,
            map -> {
              offered.increment();
              addToGroups(
                  groups,
                  map,
                  keyTokens,
                  keyExpr,
                  pathKeyed,
                  aggFunc,
                  keyDisplay,
                  valueExpr,
                  valueTokens);
            });
      }
    } else {
      // For metadata/chunks/cp: materialize rows first
      List<Map<String, Object>> rows =
          eval.evaluate(session, new Query(query.root, query.segments, query.predicates));
      for (Map<String, Object> row : rows) {
        addToGroups(
            groups,
            row,
            keyTokens,
            keyExpr,
            pathKeyed,
            aggFunc,
            keyDisplay,
            valueExpr,
            valueTokens);
      }
    }

    if (groups.isEmpty() && offered.sum() > 0) {
      reportUnmatchedKey(session, query, keyDisplay, offered.sum());
    }

    List<Map<String, Object>> result = new ArrayList<>();
    for (Map.Entry<Object, GroupAccumulator> entry : groups.entrySet()) {
      Map<String, Object> row = new HashMap<>();
      row.put("key", entry.getKey());
      row.put(aggFunc, entry.getValue().getResult());
      result.add(row);
    }

    // Sort results if sortBy is specified
    if (sortBy != null) {
      RowOps.sortGroupByResults(result, sortBy, aggFunc, ascending);
    }

    return result;
  }

  /**
   * Adds one event (or row) to the groups it belongs to.
   *
   * <p>Called from several chunk-parsing threads at once. Keys and values are worked out from the
   * event first, without a lock; only the update of {@code groups} and its accumulators is
   * serialized, on {@code groups}.
   */
  private static void addToGroups(
      Map<Object, GroupAccumulator> groups,
      Map<String, Object> map,
      List<Object> keyTokens,
      JfrPath.Expr keyExpr,
      boolean pathKeyed,
      String aggFunc,
      String keyDisplay,
      JfrPath.Expr valueExpr,
      List<Object> valueTokens) {
    List<Object> keys = groupKeys(map, keyTokens, keyExpr, pathKeyed);
    if (keys.isEmpty()) return;

    boolean counting = "count".equals(aggFunc);
    List<Object> vals;
    if (counting) {
      vals = List.of();
    } else if (valueExpr != null) {
      // Use expression evaluation
      Object val = ExpressionEvaluator.evaluateExpression(valueExpr, map);
      vals = val instanceof Number ? List.of(val) : List.of();
    } else {
      vals = valueTokens == null ? List.of() : PathValues.extractAllValues(map, valueTokens);
    }

    synchronized (groups) {
      for (Object key : keys) {
        GroupAccumulator acc = groupFor(groups, key, aggFunc, keyDisplay);
        if (counting) {
          acc.add(1);
        } else {
          for (Object val : vals) {
            if (val instanceof Number n) {
              acc.add(n.doubleValue());
            }
          }
        }
      }
    }
  }

  /**
   * The group keys one event contributes. A plain or FieldRef-keyed path fans out over arrays it
   * crosses (one group per element, as groupBy always did); a computed key expression evaluates to
   * a single key, and an event where it evaluates to null contributes no group.
   */
  private static List<Object> groupKeys(
      Map<String, Object> map, List<Object> keyTokens, JfrPath.Expr keyExpr, boolean pathKeyed) {
    if (!pathKeyed) {
      Object key = ExpressionEvaluator.evaluateExpression(keyExpr, map);
      return key == null ? List.of() : List.of(key);
    }
    return PathValues.extractAllValues(map, keyTokens);
  }

  /**
   * The distinct-group cap for streaming groupBy, read per call so tests can set and restore it.
   */
  private static int maxGroups() {
    return Math.max(1, Integer.getInteger("jafar.groupby.maxGroups", 10_000));
  }

  /**
   * Creates or fetches the accumulator for one group, enforcing the streaming-only distinct-group
   * cap. The check runs inside the computeIfAbsent mapping function, so existing groups never
   * re-check and insertion order is untouched. The cached path ({@code RowOps.applyGroupBy}) is
   * deliberately cap-free: its input is already materialized in memory.
   */
  private static GroupAccumulator groupFor(
      Map<Object, GroupAccumulator> groups, Object key, String aggFunc, String keyDisplay) {
    return groups.computeIfAbsent(
        key,
        k -> {
          int cap = maxGroups();
          if (groups.size() >= cap) {
            throw new IllegalArgumentException(
                "groupBy: distinct-group cap of "
                    + cap
                    + " reached on key '"
                    + keyDisplay
                    + "' (offending key: "
                    + k
                    + "); narrow the key or raise the cap with -Djafar.groupby.maxGroups=<n>");
          }
          return new GroupAccumulator(aggFunc);
        });
  }

  /**
   * Complains when a groupBy key matched nothing although events were there.
   *
   * <p>Grouping on a field the event does not have used to come back as an empty result, which
   * reads exactly like "this recording has no such events" — so a caller (a person or a model)
   * moves on instead of fixing the name. The two cases are worth telling apart: if events reached
   * the grouping and none of them yielded a key, the key path is wrong, and the field list is the
   * one thing that resolves it.
   *
   * <p>Only reached when the result would have been empty anyway, so no query that returns rows
   * today can start failing because of this.
   */
  private void reportUnmatchedKey(JFRSession session, Query query, String keyDisplay, long seen)
      throws Exception {
    String key = keyDisplay;
    String types = String.join(", ", query.eventTypes);
    StringBuilder msg =
        new StringBuilder("groupBy: key '")
            .append(key)
            .append("' matched nothing in ")
            .append(seen)
            .append(seen == 1 ? " event of " : " events of ")
            .append(types);
    List<String> fields = topLevelFields(session, query.eventTypes);
    if (!fields.isEmpty()) {
      msg.append(". Available: ").append(fields);
    }
    throw new IllegalArgumentException(msg.toString());
  }

  /**
   * The declared field names of the given event types, or an empty list when metadata is out of
   * reach. Reads {@code fieldsByName} — the structured map — because {@code fields} holds rendered
   * display strings.
   */
  private List<String> topLevelFields(JFRSession session, List<String> eventTypes) {
    java.util.TreeSet<String> names = new java.util.TreeSet<>();
    for (String type : eventTypes) {
      try {
        Map<String, Object> meta = MetadataProvider.loadClass(session.getRecordingPath(), type);
        if (meta != null && meta.get("fieldsByName") instanceof Map<?, ?> byName) {
          for (Object k : byName.keySet()) {
            names.add(String.valueOf(k));
          }
        }
      } catch (Exception e) {
        // Metadata is a nicety here; the complaint above stands without it.
      }
    }
    return new ArrayList<>(names);
  }

  List<Map<String, Object>> aggregateSortBy(
      JFRSession session, Query query, List<JfrPath.SortField> sortFields) throws Exception {
    // Materialize all rows first, then sort
    List<Map<String, Object>> rows =
        eval.evaluate(session, new Query(query.root, query.segments, query.predicates));
    return RowOps.applySortBy(rows, sortFields);
  }

  List<Map<String, Object>> aggregateTop(
      JFRSession session, Query query, int n, List<String> byPath, boolean ascending)
      throws Exception {
    // Materialize all rows, then sort and take top N
    List<Map<String, Object>> rows;

    if (query.segments.size() > 1) {
      // Value projection: convert to single-column rows
      List<Object> values = eval.evaluateValues(session, query);
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
      rows = eval.evaluate(session, new Query(query.root, query.segments, query.predicates));
    }

    // Sort by path (resolveSingle: one value per row, tolerates Collection-valued fields)
    rows = new ArrayList<>(rows);
    rows.sort(
        (a, b) -> {
          Object aVal = PathValues.resolveSingle(a, byPath);
          Object bVal = PathValues.resolveSingle(b, byPath);
          int cmp = compareValues(aVal, bVal);
          return ascending ? cmp : -cmp;
        });

    // Take top N
    return rows.subList(0, Math.min(n, rows.size()));
  }

  // Helper class for groupBy accumulation
  static class GroupAccumulator {
    private final String func;
    private long count = 0;
    private double sum = 0.0;
    private double min = Double.MAX_VALUE;
    private double max = Double.NEGATIVE_INFINITY;

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

  /** Lenient ordering delegate; the semantics live in {@link Coerce#compareValues} (move-only). */
  static int compareValues(Object a, Object b) {
    return Coerce.compareValues(a, b);
  }

  List<Map<String, Object>> aggregateLen(
      JFRSession session, Query query, List<String> valuePathOverride) throws Exception {
    List<String> vpath = valuePathOverride;
    if (vpath == null || vpath.isEmpty()) {
      if (query.segments.size() < 2)
        throw new IllegalArgumentException("len() requires projection or a value path");
      vpath = query.segments.subList(1, query.segments.size());
    }
    List<Map<String, Object>> out = new ArrayList<>();
    final List<String> path = vpath;
    Consumer<Object> addLen =
        (val) -> {
          if (val == null) {
            Map<String, Object> row = new HashMap<>();
            row.put("len", null);
            synchronized (out) {
              out.add(row);
            }
            return;
          }
          Integer len = PathValues.valueLength(val);
          if (len == null) {
            throw new IllegalArgumentException(
                "len() expects string or array/list, got " + val.getClass().getName());
          }
          Map<String, Object> row = new HashMap<>();
          row.put("len", len);
          synchronized (out) {
            out.add(row);
          }
        };
    if (query.root == Root.EVENTS) {
      if (query.eventTypes.isEmpty())
        throw new IllegalArgumentException("events root requires type");

      // Validate event types exist
      eval.validateEventTypes(session, query.eventTypes);

      if (query.isMultiType) {
        // Multi-type query: use Set for O(1) lookup
        Set<String> typeSet = new HashSet<>(query.eventTypes);
        eval.forEachMatching(
            session.getRecordingPath(),
            typeSet,
            query.predicates,
            map -> {
              Object val = Values.get(map, path.toArray());
              addLen.accept(val);
            });
      } else {
        // Single-type query: use direct comparison (faster)
        String eventType = query.eventTypes.get(0);
        eval.forEachMatching(
            session.getRecordingPath(),
            List.of(eventType),
            query.predicates,
            map -> {
              Object val = Values.get(map, path.toArray());
              addLen.accept(val);
            });
      }
    } else {
      // For non-events, leverage evaluateValues with the original query to get a list of values
      List<Object> vals =
          eval.evaluateValues(session, new Query(query.root, query.segments, query.predicates));
      for (Object v : vals) addLen.accept(v);
    }
    return out;
  }

  List<Map<String, Object>> aggregateStringTransform(
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
    Consumer<Object> addTransformed =
        (val) -> {
          if (val == null) {
            Map<String, Object> row = new HashMap<>();
            row.put("value", null);
            synchronized (out) {
              out.add(row);
            }
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
          synchronized (out) {
            out.add(row);
          }
        };
    if (query.root == Root.EVENTS) {
      if (query.eventTypes.isEmpty())
        throw new IllegalArgumentException("events root requires type");

      // Validate event types exist
      eval.validateEventTypes(session, query.eventTypes);

      if (query.isMultiType) {
        // Multi-type query: use Set for O(1) lookup
        Set<String> typeSet = new HashSet<>(query.eventTypes);
        eval.forEachMatching(
            session.getRecordingPath(),
            typeSet,
            query.predicates,
            map -> {
              Object val = Values.get(map, path.toArray());
              addTransformed.accept(val);
            });
      } else {
        // Single-type query: use direct comparison (faster)
        String eventType = query.eventTypes.get(0);
        eval.forEachMatching(
            session.getRecordingPath(),
            List.of(eventType),
            query.predicates,
            map -> {
              Object val = Values.get(map, path.toArray());
              addTransformed.accept(val);
            });
      }
    } else {
      List<Object> vals =
          eval.evaluateValues(session, new Query(query.root, query.segments, query.predicates));
      for (Object v : vals) addTransformed.accept(v);
    }
    return out;
  }

  List<Map<String, Object>> aggregateNumberTransform(
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
    Consumer<Object> addTransformed =
        (val) -> {
          if (val == null) {
            Map<String, Object> row = new HashMap<>();
            row.put("value", null);
            synchronized (out) {
              out.add(row);
            }
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
          synchronized (out) {
            out.add(row);
          }
        };
    if (query.root == Root.EVENTS) {
      if (query.eventTypes.isEmpty())
        throw new IllegalArgumentException("events root requires type");

      // Validate event types exist
      eval.validateEventTypes(session, query.eventTypes);

      if (query.isMultiType) {
        // Multi-type query: use Set for O(1) lookup
        Set<String> typeSet = new HashSet<>(query.eventTypes);
        eval.forEachMatching(
            session.getRecordingPath(),
            typeSet,
            query.predicates,
            map -> {
              Object val = Values.get(map, path.toArray());
              addTransformed.accept(val);
            });
      } else {
        // Single-type query: use direct comparison (faster)
        String eventType = query.eventTypes.get(0);
        eval.forEachMatching(
            session.getRecordingPath(),
            List.of(eventType),
            query.predicates,
            map -> {
              Object val = Values.get(map, path.toArray());
              addTransformed.accept(val);
            });
      }
    } else {
      List<Object> vals =
          eval.evaluateValues(session, new Query(query.root, query.segments, query.predicates));
      for (Object v : vals) addTransformed.accept(v);
    }
    return out;
  }

  List<Map<String, Object>> aggregateFormatDurationTransform(
      JFRSession session, Query query, List<String> valuePathOverride) throws Exception {
    List<String> vpath = valuePathOverride;
    if (vpath == null || vpath.isEmpty()) {
      if (query.segments.size() < 2)
        throw new IllegalArgumentException("formatDuration() requires projection or a value path");
      vpath = query.segments.subList(1, query.segments.size());
    }
    List<Map<String, Object>> out = new ArrayList<>();
    final List<String> path = vpath;
    Consumer<Object> addFormatted =
        (val) -> {
          Map<String, Object> row = new HashMap<>();
          row.put(
              "value", val instanceof Number n ? TimeFormats.formatDuration(n.longValue()) : null);
          synchronized (out) {
            out.add(row);
          }
        };
    if (query.root == Root.EVENTS) {
      if (query.eventTypes.isEmpty())
        throw new IllegalArgumentException("events root requires type");
      eval.validateEventTypes(session, query.eventTypes);
      if (query.isMultiType) {
        Set<String> typeSet = new HashSet<>(query.eventTypes);
        eval.forEachMatching(
            session.getRecordingPath(),
            typeSet,
            query.predicates,
            map -> {
              addFormatted.accept(Values.get(map, path.toArray()));
            });
      } else {
        String eventType = query.eventTypes.get(0);
        eval.forEachMatching(
            session.getRecordingPath(),
            List.of(eventType),
            query.predicates,
            map -> {
              addFormatted.accept(Values.get(map, path.toArray()));
            });
      }
    } else {
      List<Object> vals =
          eval.evaluateValues(session, new Query(query.root, query.segments, query.predicates));
      for (Object v : vals) addFormatted.accept(v);
    }
    return out;
  }

  static List<Map<String, Object>> applyFormatDurationTransform(
      List<Map<String, Object>> rows, List<String> path) {
    List<Map<String, Object>> result = new ArrayList<>();
    String key = path.isEmpty() ? null : String.join("/", path);
    for (Map<String, Object> row : rows) {
      if (path.isEmpty() && row.isEmpty()) {
        result.add(new LinkedHashMap<>(row));
        continue;
      }
      Map<String, Object> out = new LinkedHashMap<>(row);
      Object val =
          path.isEmpty() ? row.values().iterator().next() : Values.get(row, path.toArray());
      if (val instanceof Number n) {
        String formatted = TimeFormats.formatDuration(n.longValue());
        if (key != null) {
          out.put(key, formatted);
        } else if (!row.isEmpty()) {
          out.put(row.keySet().iterator().next(), formatted);
        }
      }
      result.add(out);
    }
    return result;
  }

  List<Map<String, Object>> aggregateStringPredicate(
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
    Consumer<Object> add =
        (val) -> {
          Boolean res = null;
          if (val == null) res = null;
          else if (val instanceof CharSequence s) res = s.toString().contains(arg);
          else
            throw new IllegalArgumentException(
                opName + "() expects string, got " + val.getClass().getName());
          Map<String, Object> row = new HashMap<>();
          row.put("value", res);
          synchronized (out) {
            out.add(row);
          }
        };
    if (query.root == Root.EVENTS) {
      String eventType = query.segments.get(0);
      eval.forEachMatching(
          session.getRecordingPath(),
          List.of(eventType),
          query.predicates,
          map -> {
            Object val = Values.get(map, path.toArray());
            add.accept(val);
          });
    } else {
      List<Object> vals =
          eval.evaluateValues(session, new Query(query.root, query.segments, query.predicates));
      for (Object v : vals) add.accept(v);
    }
    return out;
  }

  List<Map<String, Object>> aggregateStringReplace(
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
    Consumer<Object> add =
        (val) -> {
          String res = null;
          if (val == null) res = null;
          else if (val instanceof CharSequence s) res = s.toString().replace(target, repl);
          else
            throw new IllegalArgumentException(
                "replace() expects string, got " + val.getClass().getName());
          Map<String, Object> row = new HashMap<>();
          row.put("value", res);
          synchronized (out) {
            out.add(row);
          }
        };
    if (query.root == Root.EVENTS) {
      String eventType = query.segments.get(0);
      eval.forEachMatching(
          session.getRecordingPath(),
          List.of(eventType),
          query.predicates,
          map -> {
            Object val = Values.get(map, path.toArray());
            add.accept(val);
          });
    } else {
      List<Object> vals =
          eval.evaluateValues(session, new Query(query.root, query.segments, query.predicates));
      for (Object v : vals) add.accept(v);
    }
    return out;
  }

  static String pcol(double q) {
    int pct = (int) Math.round(q * 100);
    return "p" + pct;
  }

  static double quantileNearestRank(List<Double> sortedValues, double q) {
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

  /**
   * Running statistics. {@link #add} is synchronized: the streaming path calls it from several
   * chunk-parsing threads at once. The mean and variance are running values that round at every
   * step, so their last bits depend on the order values arrive in; count, min and max do not.
   */
  /**
   * Running first/last instant and count for {@code timerange()}. The streaming path offers events
   * from several chunk-parsing threads at once: the values are read from the event without a lock
   * and only the update is serialized.
   */
  private static final class TimeRangeAgg {
    private long min = Long.MAX_VALUE;
    private long max = Long.MIN_VALUE;
    private long count;

    void offer(Map<String, Object> map, List<String> valuePath, List<String> durationPath) {
      Object val = Values.get(map, valuePath.toArray());
      if (!(val instanceof Number n)) return;
      long epochNanos = n.longValue();
      // For max, if duration is specified, use startTime + duration
      long endEpochNanos = epochNanos;
      if (!durationPath.isEmpty()) {
        Object durVal = Values.get(map, durationPath.toArray());
        if (durVal instanceof Number dn) {
          endEpochNanos = epochNanos + dn.longValue();
        }
      }
      synchronized (this) {
        if (epochNanos < min) min = epochNanos;
        if (endEpochNanos > max) max = endEpochNanos;
        count++;
      }
    }

    synchronized void snapshot(long[] minMax, long[] count) {
      minMax[0] = min;
      minMax[1] = max;
      count[0] = this.count;
    }
  }

  static final class StatsAgg {
    long count = 0;
    double min = Double.POSITIVE_INFINITY;
    double max = Double.NEGATIVE_INFINITY;
    double mean = 0.0;
    double m2 = 0.0; // for variance via Welford

    synchronized void add(double x) {
      count++;
      if (x < min) min = x;
      if (x > max) max = x;
      double delta = x - mean;
      mean += delta / count;
      double delta2 = x - mean;
      m2 += delta * delta2;
    }

    synchronized Map<String, Object> toRow() {
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
}
