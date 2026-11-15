package io.jafar.shell.jfrpath;

import static io.jafar.shell.jfrpath.JfrPath.*;

import io.jafar.parser.api.Values;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pipeline operators that transform already-materialised row lists (apply* side of the pipeline).
 */
final class RowOps {
  static List<Map<String, Object>> applyTop(
      List<Map<String, Object>> rows, int n, List<String> byPath, boolean ascending) {
    if (rows.isEmpty()) return rows;
    // 'top(n, by=value)' over a groupBy result means the aggregate column, as it does in sortBy.
    // Without this the path resolves to null for every row and the sort silently keeps input order.
    List<String> path = byPath;
    if (byPath.size() == 1) {
      String column = resolveAggregateAlias(rows.get(0), byPath.get(0));
      if (!column.equals(byPath.get(0))) path = List.of(column);
    }
    final List<String> by = path;
    List<Map<String, Object>> sorted = new ArrayList<>(rows);
    sorted.sort(
        (a, b) -> {
          Object aVal = PathValues.resolveSingle(a, by);
          Object bVal = PathValues.resolveSingle(b, by);
          int cmp = Aggregations.compareValues(aVal, bVal);
          return ascending ? cmp : -cmp;
        });
    return sorted.subList(0, Math.min(n, sorted.size()));
  }

  static List<Map<String, Object>> applyCount(List<Map<String, Object>> rows) {
    Map<String, Object> row = new HashMap<>();
    row.put("count", (long) rows.size());
    return List.of(row);
  }

  static List<Map<String, Object>> applySum(List<Map<String, Object>> rows, List<String> path) {
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

  static List<Map<String, Object>> applyStats(List<Map<String, Object>> rows, List<String> path) {
    Aggregations.StatsAgg agg = new Aggregations.StatsAgg();
    for (Map<String, Object> row : rows) {
      Object val =
          path.isEmpty() ? row.values().iterator().next() : Values.get(row, path.toArray());
      if (val instanceof Number num) {
        agg.add(num.doubleValue());
      }
    }
    return List.of(agg.toRow());
  }

  static List<Map<String, Object>> applySelect(
      List<Map<String, Object>> rows, JfrPath.SelectOp op) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      Map<String, Object> selected = new LinkedHashMap<>();

      for (JfrPath.SelectItem item : op.items) {
        String columnName = item.outputName(); // Use consistent naming with evaluateSelect
        Object value;

        if (item instanceof JfrPath.FieldSelection fieldSel) {
          value = PathValues.resolveSingle(row, fieldSel.fieldPath);
        } else if (item instanceof JfrPath.ExpressionSelection exprSel) {
          // Evaluate expression
          value = ExpressionEvaluator.evaluateExpression(exprSel.expression, row);
        } else {
          throw new IllegalStateException("Unknown SelectItem type");
        }

        selected.put(columnName, value);
      }

      result.add(selected);
    }
    return result;
  }

  static List<Map<String, Object>> applyGroupBy(
      List<Map<String, Object>> rows,
      List<String> keyPath,
      JfrPath.Expr keyExpr,
      String aggFunc,
      List<String> valuePath,
      JfrPath.Expr valueExpr,
      String sortBy,
      boolean ascending) {
    Map<Object, Aggregations.GroupAccumulator> groups = new LinkedHashMap<>();
    boolean pathKeyed = keyExpr == null || keyExpr instanceof JfrPath.FieldRef;

    // Same key semantics as the streaming groupBy (Aggregations.groupKeys): a plain or
    // FieldRef-keyed path resolves through extractAllValues — fanning out over arrays crossed
    // by the path, one group per element, never emitting nulls, tolerating Collections — while a
    // computed key expression yields one key, and a row where it evaluates to null contributes
    // no group.
    List<Object> keyTokens = pathKeyed ? PathValues.buildPathTokens(keyPath) : null;

    for (Map<String, Object> row : rows) {
      List<Object> keys;
      if (pathKeyed) {
        keys = PathValues.extractAllValues(row, keyTokens);
      } else {
        Object keyVal = ExpressionEvaluator.evaluateExpression(keyExpr, row);
        keys = keyVal == null ? List.of() : List.of(keyVal);
      }
      for (Object keyVal : keys) {
        Aggregations.GroupAccumulator acc =
            groups.computeIfAbsent(keyVal, k -> new Aggregations.GroupAccumulator(aggFunc));

        if ("count".equals(aggFunc)) {
          acc.add(0); // Just increment count — one per fan-out key, as streaming counts one per key
        } else if (valueExpr != null) {
          // Use expression evaluation
          Object val = ExpressionEvaluator.evaluateExpression(valueExpr, row);
          if (val instanceof Number n) {
            acc.add(n.doubleValue());
          }
        } else {
          // resolveSingle: one value per row (never fans out — see design assumption A-5),
          // tolerating Collection-valued indexed fields
          Object val =
              valuePath.isEmpty()
                  ? row.values().iterator().next()
                  : PathValues.resolveSingle(row, valuePath);
          if (val instanceof Number num) {
            acc.add(num.doubleValue());
          }
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

    // Sort results if sortBy is specified
    if (sortBy != null) {
      sortGroupByResults(result, sortBy, aggFunc, ascending);
    }

    return result;
  }

  /**
   * Sort groupBy results by key or aggregated value.
   *
   * @param result the list of grouped results to sort in place
   * @param sortBy "key" to sort by group key, "value" to sort by aggregated value
   * @param aggFunc the aggregation function name (used to find the value column)
   * @param ascending true for ascending order, false for descending
   */
  @SuppressWarnings("unchecked")
  static void sortGroupByResults(
      List<Map<String, Object>> result, String sortBy, String aggFunc, boolean ascending) {
    Comparator<Map<String, Object>> comparator;
    if ("key".equals(sortBy)) {
      comparator =
          (a, b) -> {
            Object ka = a.get("key");
            Object kb = b.get("key");
            return Aggregations.compareValues(ka, kb);
          };
    } else {
      // sortBy "value" - compare by the aggregation result
      comparator =
          (a, b) -> {
            Object va = a.get(aggFunc);
            Object vb = b.get(aggFunc);
            return Aggregations.compareValues(va, vb);
          };
    }
    if (!ascending) {
      comparator = comparator.reversed();
    }
    result.sort(comparator);
  }

  static List<Map<String, Object>> applySortBy(
      List<Map<String, Object>> rows, List<JfrPath.SortField> sortFields) {
    if (rows.isEmpty() || sortFields.isEmpty()) return rows;

    // Validate all fields exist in first row, resolving the aggregate alias first
    List<String> columns = new ArrayList<>(sortFields.size());
    for (JfrPath.SortField sf : sortFields) {
      String column = resolveAggregateAlias(rows.get(0), sf.field());
      if (!rows.get(0).containsKey(column)) {
        throw new IllegalArgumentException(
            "sortBy: field '" + sf.field() + "' not found. Available: " + rows.get(0).keySet());
      }
      columns.add(column);
    }

    List<Map<String, Object>> result = new ArrayList<>(rows);
    Comparator<Map<String, Object>> comparator =
        (a, b) -> {
          for (int i = 0; i < sortFields.size(); i++) {
            String column = columns.get(i);
            int cmp = Aggregations.compareValues(a.get(column), b.get(column));
            if (sortFields.get(i).descending()) cmp = -cmp;
            if (cmp != 0) return cmp;
          }
          return 0;
        };
    result.sort(comparator);
    return result;
  }

  /**
   * Reads {@code value} as the aggregate column of a groupBy result.
   *
   * <p>{@code groupBy} names its output {@code key} and the aggregate after the function, so {@code
   * groupBy(name, agg=sum, value=sumOfPauses)} yields {@code sum} — but its own {@code sortBy=}
   * argument already spells that column {@code value}, and the pipeline stage {@code |
   * sortBy(value)} is the same thought written the other way round. It used to be rejected. The
   * alias only applies when there is no real column of that name and the rows are shaped the way
   * groupBy shapes them, so it cannot shadow a field a recording actually has.
   */
  static String resolveAggregateAlias(Map<String, Object> firstRow, String field) {
    if (!"value".equals(field) || firstRow.containsKey("value")) return field;
    if (firstRow.size() != 2 || !firstRow.containsKey("key")) return field;
    for (String column : firstRow.keySet()) {
      if (!"key".equals(column)) return column;
    }
    return field;
  }

  static List<Map<String, Object>> applyQuantiles(
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
      result.put(Aggregations.pcol(q), Aggregations.quantileNearestRank(values, q));
    }
    return List.of(result);
  }

  static List<Map<String, Object>> applyLen(List<Map<String, Object>> rows, List<String> path) {
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

  static List<Map<String, Object>> applyStringTransform(
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

  static List<Map<String, Object>> applyAsDateTime(
      List<Map<String, Object>> rows, List<String> path, String format) {
    DateTimeFormatter formatter = TimeFormats.getFormatter(format);
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
        Instant instant = Instant.ofEpochSecond(0, n.longValue());
        String formatted = formatter.format(instant.atZone(TimeFormats.SYSTEM_ZONE));
        if (key != null) {
          out.put(key, formatted);
        } else if (!row.isEmpty()) {
          String firstKey = row.keySet().iterator().next();
          out.put(firstKey, formatted);
        }
      }
      result.add(out);
    }
    return result;
  }

  static List<Map<String, Object>> applyNumberTransform(
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

  static List<Map<String, Object>> applyContains(
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

  static List<Map<String, Object>> applyReplace(
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

  static List<Map<String, Object>> applyFilter(
      List<Map<String, Object>> rows, JfrPath.Predicate predicate) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      if (MatchEngine.matchesPredicate(row, predicate)) {
        result.add(row);
      }
    }
    return result;
  }

  static List<Map<String, Object>> applyDistinct(List<Map<String, Object>> rows, String field) {
    Set<Object> seen = new LinkedHashSet<>();
    List<Map<String, Object>> result = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      Object val = row.get(field);
      if (seen.add(val)) {
        result.add(row);
      }
    }
    return result;
  }

  static List<Map<String, Object>> applyToMap(
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

  static List<Map<String, Object>> applyTimeRange(
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

  // ---- Per-event pipeline subset for live streaming (tail) ----

  /**
   * Applies the per-event-capable subset of the pipeline to ONE event map: {@code filter}, {@code
   * select}, {@code len}, string/number transforms ({@code uppercase}, {@code lowercase}, {@code
   * trim}, {@code abs}, {@code round}, {@code floor}, {@code ceil}), {@code formatduration}, {@code
   * contains}, {@code replace}, {@code asdatetime}. Returns the transformed map, or {@code null}
   * when a filter op excluded the event.
   *
   * <p>Row-set operators (aggregations, ordering, windowing) throw {@link
   * UnsupportedPipelineOperationException} naming the operator: they need the full row set, which
   * does not exist in a live stream. The per-event-capable subset is declared per operator via
   * {@link JfrPath.PipelineOp#isPerEventApplicable()} — a row-set op reaching this path is rejected
   * by that check.
   *
   * <p>The input map is MUTATED IN PLACE by the transform operators ({@code len}, {@code
   * uppercase}, {@code lowercase}, {@code trim}, {@code abs}, {@code round}, {@code floor}, {@code
   * ceil}, {@code formatduration}, {@code replace}, {@code asdatetime}); callers must pass a
   * per-event map they exclusively own (the live tail path passes a freshly decoded map). {@code
   * select} still returns a new map (projection semantics).
   */
  static Map<String, Object> applyEventOps(
      List<JfrPath.PipelineOp> pipeline, Map<String, Object> event) {
    Map<String, Object> current = event;
    if (pipeline == null) return current;
    for (JfrPath.PipelineOp op : pipeline) {
      current = applyEventOp(current, op);
      if (current == null) return null;
    }
    return current;
  }

  private static Map<String, Object> applyEventOp(Map<String, Object> row, JfrPath.PipelineOp op) {
    if (!op.isPerEventApplicable()) {
      throw new UnsupportedPipelineOperationException(opName(op));
    }
    return switch (op) {
      case JfrPath.FilterOp f -> MatchEngine.matchesPredicate(row, f.predicate) ? row : null;
      case JfrPath.ContainsOp co -> {
        Object val = eventValue(row, co.valuePath);
        yield val instanceof String s && s.contains(co.substr) ? row : null;
      }
      case JfrPath.SelectOp sel -> applySelectEvent(row, sel);
      case JfrPath.LenOp len -> {
        Object val = eventValue(row, len.valuePath);
        int l = 0;
        if (val instanceof String s) {
          l = s.length();
        } else if (val instanceof Collection<?> c) {
          l = c.size();
        } else if (val != null && val.getClass().isArray()) {
          l = java.lang.reflect.Array.getLength(val);
        }
        row.put("len", l);
        yield row;
      }
      case JfrPath.UppercaseOp up ->
          stringTransformEvent(row, up.valuePath, s -> s.toUpperCase(java.util.Locale.ROOT));
      case JfrPath.LowercaseOp lo ->
          stringTransformEvent(row, lo.valuePath, s -> s.toLowerCase(java.util.Locale.ROOT));
      case JfrPath.TrimOp tr -> stringTransformEvent(row, tr.valuePath, String::trim);
      case JfrPath.AbsOp ab -> numberTransformEvent(row, ab.valuePath, Math::abs);
      case JfrPath.RoundOp ro -> numberTransformEvent(row, ro.valuePath, Math::round);
      case JfrPath.FloorOp fl -> numberTransformEvent(row, fl.valuePath, Math::floor);
      case JfrPath.CeilOp ce -> numberTransformEvent(row, ce.valuePath, Math::ceil);
      case JfrPath.FormatDurationOp fd -> {
        Object val = eventValue(row, fd.valuePath);
        if (val instanceof Number n) {
          String formatted = TimeFormats.formatDuration(n.longValue());
          putTransformed(row, fd.valuePath, formatted);
        }
        yield row;
      }
      case JfrPath.ReplaceOp rp -> {
        Object val = eventValue(row, rp.valuePath);
        if (val instanceof String s) {
          putTransformed(row, rp.valuePath, s.replace(rp.target, rp.replacement));
        }
        yield row;
      }
      case JfrPath.AsDateTimeOp ft -> {
        Object val = eventValue(row, ft.valuePath);
        if (val instanceof Number n) {
          Instant instant = Instant.ofEpochSecond(0, n.longValue());
          putTransformed(
              row,
              ft.valuePath,
              TimeFormats.getFormatter(ft.format).format(instant.atZone(TimeFormats.SYSTEM_ZONE)));
        }
        yield row;
      }
      default -> throw new AssertionError("per-event drift: " + opName(op));
    };
  }

  private static Object eventValue(Map<String, Object> row, List<String> path) {
    return path.isEmpty() ? row.values().iterator().next() : Values.get(row, path.toArray());
  }

  private static void putTransformed(
      Map<String, Object> row, List<String> path, String transformed) {
    String key = path.isEmpty() ? null : String.join("/", path);
    if (key != null) {
      row.put(key, transformed);
    } else if (!row.isEmpty()) {
      row.put(row.keySet().iterator().next(), transformed);
    }
  }

  private static Map<String, Object> stringTransformEvent(
      Map<String, Object> row, List<String> path, java.util.function.Function<String, String> fn) {
    Object val = eventValue(row, path);
    if (!(val instanceof String s)) {
      return row;
    }
    putTransformed(row, path, fn.apply(s));
    return row;
  }

  private static Map<String, Object> numberTransformEvent(
      Map<String, Object> row, List<String> path, java.util.function.DoubleUnaryOperator fn) {
    Object val = eventValue(row, path);
    if (!(val instanceof Number n)) {
      return row;
    }
    double transformed = fn.applyAsDouble(n.doubleValue());
    // Same value type as the row-based applyNumberTransform: a double, not a string
    String key = path.isEmpty() ? null : String.join("/", path);
    if (key != null) {
      row.put(key, transformed);
    } else if (!row.isEmpty()) {
      row.put(row.keySet().iterator().next(), transformed);
    }
    return row;
  }

  private static Map<String, Object> applySelectEvent(
      Map<String, Object> row, JfrPath.SelectOp op) {
    Map<String, Object> selected = new LinkedHashMap<>();
    for (JfrPath.SelectItem item : op.items) {
      String columnName = item.outputName();
      Object value;
      if (item instanceof JfrPath.FieldSelection fieldSel) {
        value = PathValues.resolveSingle(row, fieldSel.fieldPath);
      } else if (item instanceof JfrPath.ExpressionSelection exprSel) {
        value = ExpressionEvaluator.evaluateExpression(exprSel.expression, row);
      } else {
        throw new IllegalStateException("Unknown SelectItem type");
      }
      selected.put(columnName, value);
    }
    return selected;
  }

  private static String opName(JfrPath.PipelineOp op) {
    String simple = op.getClass().getSimpleName();
    return simple.endsWith("Op")
        ? simple.substring(0, simple.length() - 2).toLowerCase(java.util.Locale.ROOT)
        : simple.toLowerCase(java.util.Locale.ROOT);
  }
}
