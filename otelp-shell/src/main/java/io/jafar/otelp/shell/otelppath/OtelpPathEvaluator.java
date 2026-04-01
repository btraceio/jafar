package io.jafar.otelp.shell.otelppath;

import io.jafar.otelp.shell.OtelpProfile;
import io.jafar.otelp.shell.OtelpSession;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Pattern;

/**
 * Evaluates {@link OtelpPath.Query} objects against an {@link OtelpSession}.
 *
 * <p>Each OTLP sample is converted to a row map with the following fields:
 *
 * <ul>
 *   <li>One field named after the profile's {@code sampleType.type()} holding the first sample
 *       value (or 0 if {@code values} is empty).
 *   <li>{@code stackTrace}: list of frame maps with {@code name}, {@code filename}, and {@code
 *       line} keys, ordered from leaf to root, resolved through the shared dictionary.
 *   <li>One field per sample attribute, keyed by the attribute's key string.
 * </ul>
 */
public final class OtelpPathEvaluator {

  // Striped weak cache: 16 independent WeakHashMap buckets, each with its own lock.
  // Entries are collected when the Query holding the pattern string is GC'd.
  private static final int STRIPE_COUNT = 16;

  @SuppressWarnings("unchecked")
  private static final WeakHashMap<String, Pattern>[] PATTERN_STRIPES =
      new WeakHashMap[STRIPE_COUNT];

  static {
    for (int i = 0; i < STRIPE_COUNT; i++) PATTERN_STRIPES[i] = new WeakHashMap<>();
  }

  private static Pattern cachedPattern(String pattern) {
    WeakHashMap<String, Pattern> stripe =
        PATTERN_STRIPES[(pattern.hashCode() & 0x7FFF_FFFF) % STRIPE_COUNT];
    synchronized (stripe) {
      return stripe.computeIfAbsent(pattern, Pattern::compile);
    }
  }

  public static List<Map<String, Object>> evaluate(OtelpSession session, OtelpPath.Query query) {
    OtelpProfile.ProfilesData data = session.getData();
    List<Map<String, Object>> rows = buildRows(data);
    rows = applyPredicates(rows, query.predicates());
    String defaultValueField = defaultValueField(data);
    return applyPipeline(rows, query.pipeline(), defaultValueField);
  }

  // ---- Row construction ----

  private static List<Map<String, Object>> buildRows(OtelpProfile.ProfilesData data) {
    OtelpProfile.Dictionary dict = data.dictionary();
    List<OtelpProfile.Stack> stackTable = dict.stackTable();
    List<OtelpProfile.Location> locationTable = dict.locationTable();
    List<OtelpProfile.Function> functionTable = dict.functionTable();
    List<OtelpProfile.Attribute> attributeTable = dict.attributeTable();

    List<Map<String, Object>> rows = new ArrayList<>();

    for (OtelpProfile.Profile profile : data.profiles()) {
      String valueTypeName = profile.sampleType() != null ? profile.sampleType().type() : "";

      for (OtelpProfile.Sample sample : profile.samples()) {
        Map<String, Object> row = new LinkedHashMap<>();

        // Value field + sampleType meta-field for explicit filtering
        long value = sample.values().isEmpty() ? 0L : sample.values().get(0);
        if (!valueTypeName.isEmpty()) {
          row.put(valueTypeName, value);
          row.put("sampleType", valueTypeName);
        }

        // Stack trace
        List<Map<String, Object>> stack =
            buildStack(sample.stackIndex(), stackTable, locationTable, functionTable);
        row.put("stackTrace", stack);

        // Attribute fields (attrIdx is 1-based; index 0 = null sentinel)
        for (int attrIdx : sample.attributeIndices()) {
          if (attrIdx > 0 && attrIdx <= attributeTable.size()) {
            OtelpProfile.Attribute attr = attributeTable.get(attrIdx - 1);
            if (attr.key() != null && !attr.key().isEmpty()) {
              row.put(attr.key(), attr.value() != null ? attr.value() : "");
            }
          }
        }

        rows.add(row);
      }
    }
    return rows;
  }

  private static List<Map<String, Object>> buildStack(
      int stackIndex,
      List<OtelpProfile.Stack> stackTable,
      List<OtelpProfile.Location> locationTable,
      List<OtelpProfile.Function> functionTable) {
    // All table indices are 1-based; index 0 = null sentinel
    if (stackIndex <= 0 || stackIndex > stackTable.size()) {
      return Collections.emptyList();
    }
    OtelpProfile.Stack stack = stackTable.get(stackIndex - 1);
    List<Map<String, Object>> frames = new ArrayList<>();
    for (int locIdx : stack.locationIndices()) {
      if (locIdx <= 0 || locIdx > locationTable.size()) continue;
      OtelpProfile.Location loc = locationTable.get(locIdx - 1);
      for (OtelpProfile.Line line : loc.lines()) {
        int fnIdx = line.functionIndex();
        if (fnIdx <= 0 || fnIdx > functionTable.size()) continue;
        OtelpProfile.Function fn = functionTable.get(fnIdx - 1);
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("name", fn.name().isEmpty() ? fn.systemName() : fn.name());
        frame.put("filename", fn.filename());
        frame.put("line", line.line());
        frames.add(frame);
      }
    }
    return frames;
  }

  // ---- Predicate evaluation ----

  private static List<Map<String, Object>> applyPredicates(
      List<Map<String, Object>> rows, List<OtelpPath.Predicate> predicates) {
    if (predicates.isEmpty()) return rows;
    return rows.stream()
        .filter(row -> predicates.stream().allMatch(p -> matchPredicate(row, p)))
        .toList();
  }

  private static boolean matchPredicate(Map<String, Object> row, OtelpPath.Predicate predicate) {
    return switch (predicate) {
      case OtelpPath.FieldPredicate fp -> matchField(row, fp);
      case OtelpPath.LogicalPredicate lp ->
          lp.and()
              ? matchPredicate(row, lp.left()) && matchPredicate(row, lp.right())
              : matchPredicate(row, lp.left()) || matchPredicate(row, lp.right());
    };
  }

  private static boolean matchField(Map<String, Object> row, OtelpPath.FieldPredicate fp) {
    Object value = resolveField(row, fp.field());
    if (value == null) return false;
    Object literal = fp.literal();

    if (fp.op() == OtelpPath.Op.REGEX) {
      String sv = value.toString();
      String pattern = literal.toString();
      return cachedPattern(pattern).matcher(sv).find();
    }

    // Numeric comparison
    if (value instanceof Number nv && literal instanceof Number nl) {
      double lv = nv.doubleValue();
      double rv = nl.doubleValue();
      return switch (fp.op()) {
        case EQ -> lv == rv;
        case NE -> lv != rv;
        case GT -> lv > rv;
        case GE -> lv >= rv;
        case LT -> lv < rv;
        case LE -> lv <= rv;
        default -> false;
      };
    }

    // String comparison
    String sv = value.toString();
    String lv = literal.toString();
    int cmp = sv.compareTo(lv);
    return switch (fp.op()) {
      case EQ -> sv.equals(lv);
      case NE -> !sv.equals(lv);
      case GT -> cmp > 0;
      case GE -> cmp >= 0;
      case LT -> cmp < 0;
      case LE -> cmp <= 0;
      default -> false;
    };
  }

  /** Resolves a simple field name or a {@code stackTrace/0/name} style path. */
  @SuppressWarnings("unchecked")
  private static Object resolveField(Map<String, Object> row, String field) {
    if (!field.contains("/")) return row.get(field);
    String[] parts = field.split("/", 3);
    Object val = row.get(parts[0]);
    if (val instanceof List<?> list && parts.length >= 2) {
      try {
        int idx = Integer.parseInt(parts[1]);
        if (idx < 0 || idx >= list.size()) return null;
        val = list.get(idx);
      } catch (NumberFormatException e) {
        return null;
      }
    }
    if (val instanceof Map<?, ?> map && parts.length == 3) {
      return ((Map<String, Object>) map).get(parts[2]);
    }
    return val;
  }

  // ---- Pipeline evaluation ----

  private static List<Map<String, Object>> applyPipeline(
      List<Map<String, Object>> rows,
      List<OtelpPath.PipelineOp> pipeline,
      String defaultValueField) {
    for (OtelpPath.PipelineOp op : pipeline) {
      rows = applyOp(rows, op, defaultValueField);
    }
    return rows;
  }

  private static List<Map<String, Object>> applyOp(
      List<Map<String, Object>> rows, OtelpPath.PipelineOp op, String defaultValueField) {
    return switch (op) {
      case OtelpPath.CountOp c -> List.of(Map.of("count", rows.size()));
      case OtelpPath.TopOp top -> applyTop(rows, top, defaultValueField);
      case OtelpPath.GroupByOp gb -> applyGroupBy(rows, gb);
      case OtelpPath.StatsOp s -> applyStats(rows, s.valueField());
      case OtelpPath.HeadOp h -> rows.subList(0, Math.min(h.n(), rows.size()));
      case OtelpPath.TailOp t -> rows.subList(Math.max(0, rows.size() - t.n()), rows.size());
      case OtelpPath.FilterOp f -> applyPredicates(rows, f.predicates());
      case OtelpPath.SelectOp s -> applySelect(rows, s.fields());
      case OtelpPath.SortByOp s -> applySort(rows, s.field(), s.ascending());
      case OtelpPath.StackProfileOp sp ->
          applyStackProfile(rows, sp.valueField(), defaultValueField);
      case OtelpPath.DistinctOp d -> applyDistinct(rows, d.field());
    };
  }

  private static List<Map<String, Object>> applyTop(
      List<Map<String, Object>> rows, OtelpPath.TopOp top, String defaultValueField) {
    String field = top.byField() != null ? top.byField() : defaultValueField;
    List<Map<String, Object>> sorted = applySort(rows, field, top.ascending());
    return sorted.subList(0, Math.min(top.n(), sorted.size()));
  }

  private static List<Map<String, Object>> applySort(
      List<Map<String, Object>> rows, String field, boolean ascending) {
    if (field == null) return rows;
    Comparator<Map<String, Object>> cmp =
        Comparator.comparing(row -> toDouble(resolveField(row, field)));
    if (!ascending) cmp = cmp.reversed();
    List<Map<String, Object>> sorted = new ArrayList<>(rows);
    sorted.sort(cmp);
    return sorted;
  }

  private static List<Map<String, Object>> applyGroupBy(
      List<Map<String, Object>> rows, OtelpPath.GroupByOp gb) {
    Map<String, Long> groups = new LinkedHashMap<>();
    for (Map<String, Object> row : rows) {
      Object key = resolveField(row, gb.keyField());
      String keyStr = key != null ? key.toString() : "<null>";
      long val;
      if ("count".equals(gb.aggFunc())) {
        val = groups.getOrDefault(keyStr, 0L) + 1;
      } else if ("sum".equals(gb.aggFunc()) && gb.valueField() != null) {
        Object v = resolveField(row, gb.valueField());
        val = groups.getOrDefault(keyStr, 0L) + (v instanceof Number n ? n.longValue() : 0L);
      } else {
        val = groups.getOrDefault(keyStr, 0L) + 1;
      }
      groups.put(keyStr, val);
    }
    String aggLabel =
        "count".equals(gb.aggFunc())
            ? "count"
            : (gb.valueField() != null ? gb.aggFunc() + "_" + gb.valueField() : "count");
    List<Map<String, Object>> result = new ArrayList<>(groups.size());
    for (Map.Entry<String, Long> entry : groups.entrySet()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put(gb.keyField(), entry.getKey());
      row.put(aggLabel, entry.getValue());
      result.add(row);
    }
    result.sort(
        Comparator.comparingLong((Map<String, Object> r) -> (Long) r.get(aggLabel)).reversed());
    return result;
  }

  private static List<Map<String, Object>> applyStats(
      List<Map<String, Object>> rows, String field) {
    long count = 0;
    double sum = 0;
    double min = Double.MAX_VALUE;
    double max = -Double.MAX_VALUE;
    for (Map<String, Object> row : rows) {
      Object v = resolveField(row, field);
      if (v instanceof Number n) {
        double d = n.doubleValue();
        sum += d;
        if (d < min) min = d;
        if (d > max) max = d;
        count++;
      }
    }
    if (count == 0) return List.of(Map.of("field", field, "count", 0L));
    Map<String, Object> stats = new LinkedHashMap<>();
    stats.put("field", field);
    stats.put("count", count);
    stats.put("sum", (long) sum);
    stats.put("min", (long) min);
    stats.put("max", (long) max);
    stats.put("avg", (long) (sum / count));
    return List.of(stats);
  }

  private static List<Map<String, Object>> applySelect(
      List<Map<String, Object>> rows, List<String> fields) {
    List<Map<String, Object>> result = new ArrayList<>(rows.size());
    for (Map<String, Object> row : rows) {
      Map<String, Object> projected = new LinkedHashMap<>();
      for (String f : fields) {
        Object v = resolveField(row, f);
        if (v != null) projected.put(f, v);
      }
      result.add(projected);
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> applyStackProfile(
      List<Map<String, Object>> rows, String valueField, String defaultValueField) {
    String vf = valueField != null ? valueField : defaultValueField;
    final String effectiveField = vf;

    Map<String, Long> stackCounts = new LinkedHashMap<>();
    for (Map<String, Object> row : rows) {
      Object stackObj = row.get("stackTrace");
      if (!(stackObj instanceof List<?> stackList)) continue;

      List<String> frameNames = new ArrayList<>();
      for (Object frame : stackList) {
        if (frame instanceof Map<?, ?> frameMap) {
          Object name = ((Map<String, Object>) frameMap).get("name");
          if (name != null && !name.toString().isEmpty()) {
            frameNames.add(name.toString());
          }
        }
      }
      if (frameNames.isEmpty()) continue;

      // Reverse: root first (flame graph convention)
      List<String> reversed = new ArrayList<>(frameNames);
      java.util.Collections.reverse(reversed);
      String stack = String.join(";", reversed);

      long value = 1L;
      if (effectiveField != null) {
        Object v = row.get(effectiveField);
        if (v instanceof Number n) value = n.longValue();
      }
      stackCounts.merge(stack, value, Long::sum);
    }

    List<Map<String, Object>> result = new ArrayList<>(stackCounts.size());
    for (Map.Entry<String, Long> entry : stackCounts.entrySet()) {
      Map<String, Object> r = new LinkedHashMap<>();
      r.put("stack", entry.getKey());
      r.put(effectiveField != null ? effectiveField : "count", entry.getValue());
      result.add(r);
    }
    result.sort(
        Comparator.comparingLong(
                (Map<String, Object> r) ->
                    (Long) r.get(effectiveField != null ? effectiveField : "count"))
            .reversed());
    return result;
  }

  private static List<Map<String, Object>> applyDistinct(
      List<Map<String, Object>> rows, String field) {
    Set<Object> seen = new LinkedHashSet<>();
    List<Map<String, Object>> result = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      Object v = resolveField(row, field);
      if (seen.add(v)) {
        result.add(Map.of(field, v != null ? v : ""));
      }
    }
    return result;
  }

  // ---- Utilities ----

  /** Returns the value type name of the first profile, or null if none. */
  private static String defaultValueField(OtelpProfile.ProfilesData data) {
    for (OtelpProfile.Profile profile : data.profiles()) {
      if (profile.sampleType() != null && !profile.sampleType().type().isEmpty()) {
        return profile.sampleType().type();
      }
    }
    return null;
  }

  private static double toDouble(Object v) {
    if (v instanceof Number n) return n.doubleValue();
    if (v instanceof String s) {
      try {
        return Double.parseDouble(s);
      } catch (NumberFormatException ignore) {
        return 0.0;
      }
    }
    return 0.0;
  }
}
