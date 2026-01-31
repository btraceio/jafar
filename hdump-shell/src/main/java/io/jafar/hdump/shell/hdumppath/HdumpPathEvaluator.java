package io.jafar.hdump.shell.hdumppath;

import io.jafar.hdump.api.*;
import io.jafar.hdump.shell.HeapSession;
import io.jafar.hdump.shell.hdumppath.HdumpPath.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Evaluates HdumpPath queries against a HeapSession.
 *
 * <p>The evaluator converts queries into heap dump operations, applying filters and pipeline
 * transformations to produce result sets.
 */
public final class HdumpPathEvaluator {

  private HdumpPathEvaluator() {}

  /**
   * Evaluates a query against a heap session.
   *
   * @param session the heap session
   * @param query the parsed query
   * @return list of result maps
   */
  public static List<Map<String, Object>> evaluate(HeapSession session, Query query) {
    HeapDump dump = session.getHeapDump();

    // Get base stream based on root type
    List<Map<String, Object>> results =
        switch (query.root()) {
          case OBJECTS -> evaluateObjects(dump, query);
          case CLASSES -> evaluateClasses(dump, query);
          case GCROOTS -> evaluateGcRoots(dump, query);
        };

    // Apply pipeline operations
    for (PipelineOp op : query.pipeline()) {
      results = applyPipelineOp(results, op);
    }

    return results;
  }

  private static List<Map<String, Object>> evaluateObjects(HeapDump dump, Query query) {
    Stream<HeapObject> stream = dump.getObjects();

    // Filter by type pattern
    if (query.typePattern() != null) {
      String pattern = query.typePattern();
      if (query.instanceof_()) {
        // Include subclasses
        Set<String> matchingClasses = findMatchingClasses(dump, pattern, true);
        stream =
            stream.filter(
                obj -> {
                  HeapClass cls = obj.getHeapClass();
                  return cls != null && matchingClasses.contains(cls.getName());
                });
      } else {
        // Exact match or pattern
        if (pattern.contains("*")) {
          Pattern regex = globToRegex(pattern);
          stream =
              stream.filter(
                  obj -> {
                    HeapClass cls = obj.getHeapClass();
                    return cls != null && regex.matcher(cls.getName()).matches();
                  });
        } else {
          stream =
              stream.filter(
                  obj -> {
                    HeapClass cls = obj.getHeapClass();
                    return cls != null && pattern.equals(cls.getName());
                  });
        }
      }
    }

    // Apply predicates
    if (!query.predicates().isEmpty()) {
      stream = stream.filter(obj -> matchesAllPredicates(objectToMap(obj), query.predicates()));
    }

    return stream.map(HdumpPathEvaluator::objectToMap).collect(Collectors.toList());
  }

  private static List<Map<String, Object>> evaluateClasses(HeapDump dump, Query query) {
    Stream<HeapClass> stream = dump.getClasses().stream();

    // Filter by type pattern
    if (query.typePattern() != null) {
      String pattern = query.typePattern();
      if (pattern.contains("*")) {
        Pattern regex = globToRegex(pattern);
        stream =
            stream.filter(cls -> cls.getName() != null && regex.matcher(cls.getName()).matches());
      } else {
        stream = stream.filter(cls -> pattern.equals(cls.getName()));
      }
    }

    // Apply predicates
    List<Map<String, Object>> results =
        stream.map(HdumpPathEvaluator::classToMap).collect(Collectors.toList());

    if (!query.predicates().isEmpty()) {
      results =
          results.stream()
              .filter(map -> matchesAllPredicates(map, query.predicates()))
              .collect(Collectors.toList());
    }

    return results;
  }

  private static List<Map<String, Object>> evaluateGcRoots(HeapDump dump, Query query) {
    Stream<GcRoot> stream = dump.getGcRoots().stream();

    // Filter by type
    if (query.typePattern() != null) {
      String typeStr = query.typePattern().toUpperCase();
      try {
        GcRoot.Type type = GcRoot.Type.valueOf(typeStr);
        stream = stream.filter(root -> root.getType() == type);
      } catch (IllegalArgumentException e) {
        // Invalid type - return empty
        return List.of();
      }
    }

    // Apply predicates
    List<Map<String, Object>> results =
        stream.map(HdumpPathEvaluator::gcRootToMap).collect(Collectors.toList());

    if (!query.predicates().isEmpty()) {
      results =
          results.stream()
              .filter(map -> matchesAllPredicates(map, query.predicates()))
              .collect(Collectors.toList());
    }

    return results;
  }

  // === Object to Map conversions ===

  private static Map<String, Object> objectToMap(HeapObject obj) {
    Map<String, Object> map = new LinkedHashMap<>();
    HeapClass cls = obj.getHeapClass();

    map.put(ObjectFields.ID, obj.getId());
    map.put(ObjectFields.CLASS, cls != null ? cls.getName() : "unknown");
    map.put(ObjectFields.CLASS_NAME, cls != null ? cls.getName() : "unknown");
    map.put(ObjectFields.SHALLOW, obj.getShallowSize());
    map.put(ObjectFields.SHALLOW_SIZE, obj.getShallowSize());

    long retained = obj.getRetainedSize();
    if (retained >= 0) {
      map.put(ObjectFields.RETAINED, retained);
      map.put(ObjectFields.RETAINED_SIZE, retained);
    }

    if (obj.isArray()) {
      map.put(ObjectFields.ARRAY_LENGTH, obj.getArrayLength());
    }

    // Add string value for String objects
    if (cls != null && "java.lang.String".equals(cls.getName())) {
      String value = obj.getStringValue();
      if (value != null) {
        map.put(ObjectFields.STRING_VALUE, value);
      }
    }

    return map;
  }

  private static Map<String, Object> classToMap(HeapClass cls) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put(ClassFields.ID, cls.getId());
    map.put(ClassFields.NAME, cls.getName());
    map.put(ClassFields.SIMPLE_NAME, cls.getSimpleName());
    map.put(ClassFields.INSTANCE_COUNT, cls.getInstanceCount());
    map.put(ClassFields.INSTANCE_SIZE, cls.getInstanceSize());

    HeapClass superClass = cls.getSuperClass();
    if (superClass != null) {
      map.put(ClassFields.SUPER_CLASS, superClass.getName());
    }

    map.put(ClassFields.IS_ARRAY, cls.isArray());
    return map;
  }

  private static Map<String, Object> gcRootToMap(GcRoot root) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put(GcRootFields.TYPE, root.getType().name());
    map.put(GcRootFields.OBJECT_ID, root.getObjectId());

    HeapObject obj = root.getObject();
    if (obj != null) {
      HeapClass cls = obj.getHeapClass();
      map.put(
          GcRootFields.OBJECT,
          cls != null ? cls.getName() + "@" + Long.toHexString(obj.getId()) : "unknown");
    }

    int threadSerial = root.getThreadSerial();
    if (threadSerial >= 0) {
      map.put(GcRootFields.THREAD_SERIAL, threadSerial);
    }

    int frameNum = root.getFrameNumber();
    if (frameNum >= 0) {
      map.put(GcRootFields.FRAME_NUMBER, frameNum);
    }

    return map;
  }

  // === Predicate matching ===

  private static boolean matchesAllPredicates(Map<String, Object> map, List<Predicate> predicates) {
    for (Predicate pred : predicates) {
      if (!matchesPredicate(map, pred)) {
        return false;
      }
    }
    return true;
  }

  private static boolean matchesPredicate(Map<String, Object> map, Predicate pred) {
    return switch (pred) {
      case FieldPredicate fp -> matchesFieldPredicate(map, fp);
      case ExprPredicate ep -> evaluateBoolExpr(map, ep.expr());
    };
  }

  private static boolean matchesFieldPredicate(Map<String, Object> map, FieldPredicate fp) {
    Object value = extractFieldValue(map, fp.fieldPath());
    return compareValues(value, fp.op(), fp.literal());
  }

  private static boolean evaluateBoolExpr(Map<String, Object> map, BoolExpr expr) {
    return switch (expr) {
      case CompExpr ce -> {
        Object value = extractFieldValue(map, ce.fieldPath());
        yield compareValues(value, ce.op(), ce.literal());
      }
      case LogicalExpr le -> {
        boolean left = evaluateBoolExpr(map, le.left());
        if (le.op() == LogicalOp.AND) {
          yield left && evaluateBoolExpr(map, le.right());
        } else {
          yield left || evaluateBoolExpr(map, le.right());
        }
      }
      case NotExpr ne -> !evaluateBoolExpr(map, ne.inner());
    };
  }

  private static Object extractFieldValue(Map<String, Object> map, List<String> path) {
    if (path.isEmpty()) return null;

    Object current = map.get(path.get(0));
    for (int i = 1; i < path.size() && current != null; i++) {
      if (current instanceof Map<?, ?> m) {
        current = m.get(path.get(i));
      } else {
        return null;
      }
    }
    return current;
  }

  private static boolean compareValues(Object value, Op op, Object literal) {
    if (value == null) {
      return op == Op.EQ && literal == null || op == Op.NE && literal != null;
    }

    if (literal == null) {
      return op == Op.NE;
    }

    // Regex matching
    if (op == Op.REGEX) {
      String str = String.valueOf(value);
      String pattern = String.valueOf(literal);
      return Pattern.matches(pattern, str);
    }

    // Numeric comparison
    if (value instanceof Number && literal instanceof Number) {
      double v = ((Number) value).doubleValue();
      double l = ((Number) literal).doubleValue();
      return switch (op) {
        case EQ -> v == l;
        case NE -> v != l;
        case GT -> v > l;
        case GE -> v >= l;
        case LT -> v < l;
        case LE -> v <= l;
        case REGEX -> false;
      };
    }

    // String comparison
    String vStr = String.valueOf(value);
    String lStr = String.valueOf(literal);
    int cmp = vStr.compareTo(lStr);
    return switch (op) {
      case EQ -> cmp == 0;
      case NE -> cmp != 0;
      case GT -> cmp > 0;
      case GE -> cmp >= 0;
      case LT -> cmp < 0;
      case LE -> cmp <= 0;
      case REGEX -> Pattern.matches(lStr, vStr);
    };
  }

  // === Pipeline operations ===

  private static List<Map<String, Object>> applyPipelineOp(
      List<Map<String, Object>> results, PipelineOp op) {
    return switch (op) {
      case SelectOp s -> applySelect(results, s);
      case TopOp t -> applyTop(results, t);
      case GroupByOp g -> applyGroupBy(results, g);
      case CountOp c -> applyCount(results);
      case SumOp s -> applySum(results, s);
      case StatsOp s -> applyStats(results, s);
      case SortByOp s -> applySortBy(results, s);
      case HeadOp h -> results.stream().limit(h.n()).collect(Collectors.toList());
      case TailOp t -> {
        int size = results.size();
        int skip = Math.max(0, size - t.n());
        yield results.stream().skip(skip).collect(Collectors.toList());
      }
      case FilterOp f -> results.stream()
          .filter(map -> matchesPredicate(map, f.predicate()))
          .collect(Collectors.toList());
      case DistinctOp d -> applyDistinct(results, d);
    };
  }

  private static List<Map<String, Object>> applySelect(
      List<Map<String, Object>> results, SelectOp op) {
    return results.stream()
        .map(
            row -> {
              Map<String, Object> selected = new LinkedHashMap<>();
              for (SelectField field : op.fields()) {
                String key = field.alias() != null ? field.alias() : field.field();
                selected.put(key, row.get(field.field()));
              }
              return selected;
            })
        .collect(Collectors.toList());
  }

  private static List<Map<String, Object>> applyTop(List<Map<String, Object>> results, TopOp op) {
    Stream<Map<String, Object>> stream = results.stream();

    if (op.orderBy() != null) {
      Comparator<Map<String, Object>> cmp =
          Comparator.comparing(m -> toComparable(m.get(op.orderBy())));
      if (op.descending()) {
        cmp = cmp.reversed();
      }
      stream = stream.sorted(cmp);
    }

    return stream.limit(op.n()).collect(Collectors.toList());
  }

  @SuppressWarnings("unchecked")
  private static Comparable<Object> toComparable(Object value) {
    if (value == null) return o -> 1;
    if (value instanceof Comparable<?>) {
      return (Comparable<Object>) value;
    }
    return o -> String.valueOf(value).compareTo(String.valueOf(o));
  }

  private static List<Map<String, Object>> applyGroupBy(
      List<Map<String, Object>> results, GroupByOp op) {
    // Group by the specified fields
    Map<List<Object>, List<Map<String, Object>>> groups =
        results.stream()
            .collect(
                Collectors.groupingBy(
                    row -> {
                      List<Object> key = new ArrayList<>();
                      for (String field : op.groupFields()) {
                        key.add(row.get(field));
                      }
                      return key;
                    }));

    // Apply aggregation
    List<Map<String, Object>> aggregated = new ArrayList<>();
    for (Map.Entry<List<Object>, List<Map<String, Object>>> entry : groups.entrySet()) {
      Map<String, Object> row = new LinkedHashMap<>();

      // Add group key fields
      List<Object> keyValues = entry.getKey();
      for (int i = 0; i < op.groupFields().size(); i++) {
        row.put(op.groupFields().get(i), keyValues.get(i));
      }

      // Add aggregation result
      List<Map<String, Object>> group = entry.getValue();
      switch (op.aggregation()) {
        case COUNT -> row.put("count", group.size());
        case SUM, AVG, MIN, MAX -> {
          // For SUM/AVG/MIN/MAX we need a value field - use first numeric field
          String valueField = findFirstNumericField(group);
          if (valueField != null) {
            double sum =
                group.stream()
                    .map(m -> m.get(valueField))
                    .filter(v -> v instanceof Number)
                    .mapToDouble(v -> ((Number) v).doubleValue())
                    .sum();
            switch (op.aggregation()) {
              case SUM -> row.put("sum", sum);
              case AVG -> row.put("avg", sum / group.size());
              case MIN -> row.put(
                  "min",
                  group.stream()
                      .map(m -> m.get(valueField))
                      .filter(v -> v instanceof Number)
                      .mapToDouble(v -> ((Number) v).doubleValue())
                      .min()
                      .orElse(0));
              case MAX -> row.put(
                  "max",
                  group.stream()
                      .map(m -> m.get(valueField))
                      .filter(v -> v instanceof Number)
                      .mapToDouble(v -> ((Number) v).doubleValue())
                      .max()
                      .orElse(0));
              default -> {}
            }
          }
        }
      }

      aggregated.add(row);
    }

    return aggregated;
  }

  private static String findFirstNumericField(List<Map<String, Object>> rows) {
    if (rows.isEmpty()) return null;
    for (Map.Entry<String, Object> entry : rows.get(0).entrySet()) {
      if (entry.getValue() instanceof Number) {
        return entry.getKey();
      }
    }
    return null;
  }

  private static List<Map<String, Object>> applyCount(List<Map<String, Object>> results) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("count", results.size());
    return List.of(row);
  }

  private static List<Map<String, Object>> applySum(List<Map<String, Object>> results, SumOp op) {
    double sum =
        results.stream()
            .map(m -> m.get(op.field()))
            .filter(v -> v instanceof Number)
            .mapToDouble(v -> ((Number) v).doubleValue())
            .sum();

    Map<String, Object> row = new LinkedHashMap<>();
    row.put("sum", sum);
    return List.of(row);
  }

  private static List<Map<String, Object>> applyStats(
      List<Map<String, Object>> results, StatsOp op) {
    DoubleSummaryStatistics stats =
        results.stream()
            .map(m -> m.get(op.field()))
            .filter(v -> v instanceof Number)
            .mapToDouble(v -> ((Number) v).doubleValue())
            .summaryStatistics();

    Map<String, Object> row = new LinkedHashMap<>();
    row.put("count", stats.getCount());
    row.put("sum", stats.getSum());
    row.put("min", stats.getMin());
    row.put("max", stats.getMax());
    row.put("avg", stats.getAverage());
    return List.of(row);
  }

  private static List<Map<String, Object>> applySortBy(
      List<Map<String, Object>> results, SortByOp op) {
    Comparator<Map<String, Object>> cmp = null;

    for (SortField sf : op.fields()) {
      Comparator<Map<String, Object>> fieldCmp =
          Comparator.comparing(m -> toComparable(m.get(sf.field())));
      if (sf.descending()) {
        fieldCmp = fieldCmp.reversed();
      }
      cmp = cmp == null ? fieldCmp : cmp.thenComparing(fieldCmp);
    }

    if (cmp != null) {
      results = new ArrayList<>(results);
      results.sort(cmp);
    }

    return results;
  }

  private static List<Map<String, Object>> applyDistinct(
      List<Map<String, Object>> results, DistinctOp op) {
    Set<Object> seen = new HashSet<>();
    return results.stream()
        .filter(
            row -> {
              Object value = row.get(op.field());
              return seen.add(value);
            })
        .collect(Collectors.toList());
  }

  // === Utility methods ===

  private static Set<String> findMatchingClasses(
      HeapDump dump, String pattern, boolean includeSubclasses) {
    Set<String> result = new HashSet<>();

    // Find the base class
    Optional<HeapClass> baseClass = dump.getClassByName(pattern);
    if (baseClass.isEmpty()) {
      // Try pattern matching
      Pattern regex = globToRegex(pattern);
      for (HeapClass cls : dump.getClasses()) {
        if (cls.getName() != null && regex.matcher(cls.getName()).matches()) {
          result.add(cls.getName());
          if (includeSubclasses) {
            addSubclasses(dump, cls.getName(), result);
          }
        }
      }
    } else {
      result.add(pattern);
      if (includeSubclasses) {
        addSubclasses(dump, pattern, result);
      }
    }

    return result;
  }

  private static void addSubclasses(HeapDump dump, String className, Set<String> result) {
    for (HeapClass cls : dump.getClasses()) {
      HeapClass superCls = cls.getSuperClass();
      while (superCls != null) {
        if (className.equals(superCls.getName())) {
          result.add(cls.getName());
          break;
        }
        superCls = superCls.getSuperClass();
      }
    }
  }

  private static Pattern globToRegex(String glob) {
    StringBuilder regex = new StringBuilder("^");
    for (char c : glob.toCharArray()) {
      switch (c) {
        case '*' -> regex.append(".*");
        case '?' -> regex.append(".");
        case '.' -> regex.append("\\.");
        case '$' -> regex.append("\\$");
        case '[', ']', '(', ')', '{', '}', '|', '^', '+', '\\' -> regex.append("\\").append(c);
        default -> regex.append(c);
      }
    }
    regex.append("$");
    return Pattern.compile(regex.toString());
  }
}
