package io.jafar.pprof.shell.pprofpath;

import io.jafar.pprof.shell.PprofProfile;
import io.jafar.pprof.shell.PprofSession;
import io.jafar.shell.core.sampling.path.SamplesPath;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Pattern;

/**
 * Evaluates {@link SamplesPath.Query} objects against a {@link PprofSession}.
 *
 * <p>Each pprof sample is converted to a row map with the following fields:
 *
 * <ul>
 *   <li>One field per value type (e.g. {@code cpu}, {@code alloc_objects}), holding the raw sample
 *       value.
 *   <li>{@code stackTrace}: list of frame maps with {@code name}, {@code filename}, and {@code
 *       line} keys, ordered from leaf to root.
 *   <li>One field per label (string labels → String value; numeric labels → Long value).
 * </ul>
 */
public final class PprofPathEvaluator {

  // Striped weak cache: 16 independent WeakHashMap buckets, each with its own lock.
  // Entries are collected when the pattern string key becomes only weakly reachable.
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

  public static List<Map<String, Object>> evaluate(PprofSession session, SamplesPath.Query query) {
    List<Map<String, Object>> rows = buildRows(session.getProfile());
    rows = applyPredicates(rows, query.predicates());
    return applyPipeline(rows, query.pipeline(), session.getProfile());
  }

  // ---- Row construction ----

  private static List<Map<String, Object>> buildRows(PprofProfile.Profile profile) {
    Map<Long, PprofProfile.Location> locationById =
        indexById(profile.locations(), PprofProfile.Location::id);
    Map<Long, PprofProfile.Function> functionById =
        indexById(profile.functions(), PprofProfile.Function::id);

    List<PprofProfile.ValueType> sampleTypes = profile.sampleTypes();
    // Comma-separated list of all value type names in this profile, e.g. "cpu,alloc_space"
    String sampleTypeNames =
        sampleTypes.stream()
            .map(PprofProfile.ValueType::type)
            .collect(java.util.stream.Collectors.joining(","));
    List<Map<String, Object>> rows = new ArrayList<>(profile.samples().size());

    for (PprofProfile.Sample sample : profile.samples()) {
      Map<String, Object> row = new LinkedHashMap<>();

      // Value type fields
      List<Long> values = sample.values();
      for (int i = 0; i < values.size() && i < sampleTypes.size(); i++) {
        row.put(sampleTypes.get(i).type(), values.get(i));
      }

      // sampleType: comma-separated list of value type names present in this profile
      row.put("sampleType", sampleTypeNames);

      // Stack trace
      List<Map<String, Object>> stack =
          buildStack(sample.locationIds(), locationById, functionById);
      row.put("stackTrace", stack);

      // Labels
      for (PprofProfile.Label label : sample.labels()) {
        if (label.str() != null && !label.str().isEmpty()) {
          row.put(label.key(), label.str());
        } else if (label.num() != 0) {
          row.put(label.key(), label.num());
        }
      }

      rows.add(row);
    }
    return rows;
  }

  private static List<Map<String, Object>> buildStack(
      List<Long> locationIds,
      Map<Long, PprofProfile.Location> locationById,
      Map<Long, PprofProfile.Function> functionById) {
    List<Map<String, Object>> stack = new ArrayList<>();
    for (Long locId : locationIds) {
      PprofProfile.Location loc = locationById.get(locId);
      if (loc == null) continue;
      for (PprofProfile.Line line : loc.lines()) {
        PprofProfile.Function fn = functionById.get(line.functionId());
        if (fn == null) continue;
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("name", fn.name().isEmpty() ? fn.systemName() : fn.name());
        frame.put("filename", fn.filename());
        frame.put("line", line.lineNumber());
        stack.add(frame);
      }
    }
    return stack;
  }

  // ---- Predicate evaluation ----

  private static List<Map<String, Object>> applyPredicates(
      List<Map<String, Object>> rows, List<SamplesPath.Predicate> predicates) {
    if (predicates.isEmpty()) return rows;
    return rows.stream()
        .filter(row -> predicates.stream().allMatch(p -> matchPredicate(row, p)))
        .toList();
  }

  private static boolean matchPredicate(Map<String, Object> row, SamplesPath.Predicate predicate) {
    return switch (predicate) {
      case SamplesPath.FieldPredicate fp -> matchField(row, fp);
      case SamplesPath.LogicalPredicate lp ->
          lp.and()
              ? matchPredicate(row, lp.left()) && matchPredicate(row, lp.right())
              : matchPredicate(row, lp.left()) || matchPredicate(row, lp.right());
    };
  }

  private static boolean matchField(Map<String, Object> row, SamplesPath.FieldPredicate fp) {
    Object value = resolveField(row, fp.field());
    if (value == null) return false;
    Object literal = fp.literal();

    if (fp.op() == SamplesPath.Op.REGEX) {
      String sv = value.toString();
      String pattern = literal.toString();
      return cachedPattern(pattern).matcher(sv).find();
    }

    // Numeric comparison
    if (value instanceof Number nv && literal instanceof Number nl) {
      if (nv instanceof Long && nl instanceof Long) {
        long lv = nv.longValue();
        long rv = nl.longValue();
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
      List<SamplesPath.PipelineOp> pipeline,
      PprofProfile.Profile profile) {
    for (SamplesPath.PipelineOp op : pipeline) {
      rows = applyOp(rows, op, profile);
    }
    return rows;
  }

  private static List<Map<String, Object>> applyOp(
      List<Map<String, Object>> rows, SamplesPath.PipelineOp op, PprofProfile.Profile profile) {
    return switch (op) {
      case SamplesPath.CountOp c -> List.of(Map.of("count", rows.size()));
      case SamplesPath.TopOp top -> applyTop(rows, top, profile);
      case SamplesPath.GroupByOp gb -> applyGroupBy(rows, gb);
      case SamplesPath.StatsOp s -> applyStats(rows, s.valueField());
      case SamplesPath.HeadOp h -> rows.subList(0, Math.min(h.n(), rows.size()));
      case SamplesPath.TailOp t -> rows.subList(Math.max(0, rows.size() - t.n()), rows.size());
      case SamplesPath.FilterOp f -> applyPredicates(rows, f.predicates());
      case SamplesPath.SelectOp s -> applySelect(rows, s.fields());
      case SamplesPath.SortByOp s -> applySort(rows, s.field(), s.ascending());
      case SamplesPath.StackProfileOp sp -> applyStackProfile(rows, sp.valueField(), profile);
      case SamplesPath.DistinctOp d -> applyDistinct(rows, d.field());
    };
  }

  private static List<Map<String, Object>> applyTop(
      List<Map<String, Object>> rows, SamplesPath.TopOp top, PprofProfile.Profile profile) {
    String field = top.byField();
    if (field == null && !profile.sampleTypes().isEmpty()) {
      field = profile.sampleTypes().get(0).type();
    }
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
      List<Map<String, Object>> rows, SamplesPath.GroupByOp gb) {
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
        Comparator.comparingLong(
                (Map<String, Object> r) -> {
                  Object v = r.get(aggLabel);
                  return v instanceof Number n ? n.longValue() : 0L;
                })
            .reversed());
    return result;
  }

  private static List<Map<String, Object>> applyStats(
      List<Map<String, Object>> rows, String field) {
    long count = 0;
    long sum = 0;
    long min = Long.MAX_VALUE;
    long max = Long.MIN_VALUE;
    for (Map<String, Object> row : rows) {
      Object v = resolveField(row, field);
      if (v instanceof Number n) {
        long lv = n.longValue();
        sum += lv;
        if (lv < min) min = lv;
        if (lv > max) max = lv;
        count++;
      }
    }
    if (count == 0) return List.of(Map.of("field", field, "count", 0L));
    Map<String, Object> stats = new LinkedHashMap<>();
    stats.put("field", field);
    stats.put("count", count);
    stats.put("sum", sum);
    stats.put("min", min);
    stats.put("max", max);
    stats.put("avg", Math.round((double) sum / count));
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
      List<Map<String, Object>> rows, String valueField, PprofProfile.Profile profile) {
    String vf = valueField;
    if (vf == null && !profile.sampleTypes().isEmpty()) {
      vf = profile.sampleTypes().get(0).type();
    }
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
    String sortKey = effectiveField != null ? effectiveField : "count";
    result.sort(
        Comparator.comparingLong(
                (Map<String, Object> r) -> r.get(sortKey) instanceof Number n ? n.longValue() : 0L)
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

  private static <T> Map<Long, T> indexById(
      List<T> items, java.util.function.ToLongFunction<T> idFn) {
    Map<Long, T> map = new HashMap<>(items.size() * 2);
    for (T item : items) map.put(idFn.applyAsLong(item), item);
    return map;
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
