package io.jafar.hdump.shell.hdumppath;

import io.jafar.hdump.api.*;
import io.jafar.hdump.shell.HeapSession;
import io.jafar.hdump.shell.hdumppath.HdumpPath.*;
import io.jafar.shell.core.RowSorter;
import io.jafar.shell.core.expr.ValueExpr;
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

    // Ensure approximate retained sizes are computed (with progress display if first time)
    if (!dump.hasDominators()) {
      session.computeApproximateRetainedSizes();
    }

    // Get base stream based on root type
    List<Map<String, Object>> results =
        switch (query.root()) {
          case OBJECTS -> evaluateObjects(dump, query);
          case CLASSES -> evaluateClasses(dump, query);
          case GCROOTS -> evaluateGcRoots(dump, query);
        };

    // Apply pipeline operations
    for (PipelineOp op : query.pipeline()) {
      results = applyPipelineOp(session, results, op);
    }

    return results;
  }

  private static List<Map<String, Object>> evaluateObjects(HeapDump dump, Query query) {
    Stream<HeapObject> stream = dump.getObjects();

    // Filter by type pattern
    if (query.typePattern() != null) {
      String pattern = normalizeClassName(query.typePattern());
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
      String pattern = normalizeClassName(query.typePattern());
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

      // Add size fields from the rooted object
      map.put(GcRootFields.SHALLOW, obj.getShallowSize());
      map.put(GcRootFields.SHALLOW_SIZE, obj.getShallowSize());

      long retained = obj.getRetainedSize();
      if (retained >= 0) {
        map.put(GcRootFields.RETAINED, retained);
        map.put(GcRootFields.RETAINED_SIZE, retained);
      }
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
      HeapSession session, List<Map<String, Object>> results, PipelineOp op) {
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
      case PathToRootOp p -> applyPathToRoot(session.getHeapDump(), results);
      case CheckLeaksOp c -> applyCheckLeaks(session, results, c);
      case DominatorsOp d -> applyDominators(session, session.getHeapDump(), results, d);
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
      // Create comparator that compares actual values with null-safe handling
      Comparator<Map<String, Object>> cmp = (m1, m2) -> {
        Object v1 = m1.get(op.orderBy());
        Object v2 = m2.get(op.orderBy());
        return compareValues(v1, v2);
      };

      // top() defaults to descending (largest first) - reverse for ascending if needed
      if (op.descending()) {
        cmp = cmp.reversed();
      }
      stream = stream.sorted(cmp);
    }

    return stream.limit(op.n()).collect(Collectors.toList());
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static int compareValues(Object v1, Object v2) {
    if (v1 == null && v2 == null) return 0;
    if (v1 == null) return 1; // nulls sort last
    if (v2 == null) return -1;

    if (v1 instanceof Comparable && v2 instanceof Comparable) {
      try {
        return ((Comparable) v1).compareTo(v2);
      } catch (ClassCastException e) {
        // Fall back to string comparison for incompatible types
        return String.valueOf(v1).compareTo(String.valueOf(v2));
      }
    }
    return String.valueOf(v1).compareTo(String.valueOf(v2));
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static Comparable<Object> toComparable(Object value) {
    if (value == null) return o -> 1; // nulls sort last
    if (value instanceof Comparable comp) {
      // Wrap to handle type mismatch gracefully (e.g., comparing Integer with String)
      return o -> {
        if (o == null) return -1;
        if (value.getClass().isInstance(o)) {
          return comp.compareTo(o);
        }
        // Fall back to string comparison for incompatible types
        return String.valueOf(value).compareTo(String.valueOf(o));
      };
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
          ValueExpr valueExpr = op.valueExpr();
          if (valueExpr != null) {
            // Use provided expression
            double[] values =
                group.stream()
                    .mapToDouble(valueExpr::evaluate)
                    .filter(v -> !Double.isNaN(v))
                    .toArray();
            if (values.length > 0) {
              applyAggregation(row, op.aggregation(), values);
            }
          } else {
            // Fall back to first numeric field
            String valueField = findFirstNumericField(group);
            if (valueField != null) {
              double[] values =
                  group.stream()
                      .map(m -> m.get(valueField))
                      .filter(v -> v instanceof Number)
                      .mapToDouble(v -> ((Number) v).doubleValue())
                      .toArray();
              if (values.length > 0) {
                applyAggregation(row, op.aggregation(), values);
              }
            }
          }
        }
      }

      aggregated.add(row);
    }

    // Apply sorting if requested
    if (op.sortBy() != null) {
      String keyField = op.groupFields().isEmpty() ? "key" : op.groupFields().get(0);
      String valueField = getAggregationFieldName(op.aggregation());
      RowSorter.sortGroupByResults(aggregated, op.sortBy(), keyField, valueField, op.ascending());
    }

    return aggregated;
  }

  /** Returns the field name for the given aggregation operation. */
  private static String getAggregationFieldName(AggOp aggOp) {
    return switch (aggOp) {
      case COUNT -> "count";
      case SUM -> "sum";
      case AVG -> "avg";
      case MIN -> "min";
      case MAX -> "max";
    };
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

  private static void applyAggregation(Map<String, Object> row, AggOp aggOp, double[] values) {
    switch (aggOp) {
      case SUM -> {
        double sum = 0;
        for (double v : values) sum += v;
        row.put("sum", sum);
      }
      case AVG -> {
        double sum = 0;
        for (double v : values) sum += v;
        row.put("avg", sum / values.length);
      }
      case MIN -> {
        double min = Double.MAX_VALUE;
        for (double v : values) if (v < min) min = v;
        row.put("min", min);
      }
      case MAX -> {
        double max = Double.MIN_VALUE;
        for (double v : values) if (v > max) max = v;
        row.put("max", max);
      }
      default -> {}
    }
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

  /**
   * Normalizes a class name from Java format (java.util.HashMap) to internal JVM format (java/util/HashMap).
   * Heap dumps store class names with slashes, but users naturally type them with dots.
   */
  private static String normalizeClassName(String className) {
    return className.replace('.', '/');
  }

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

  private static List<Map<String, Object>> applyPathToRoot(
      HeapDump dump, List<Map<String, Object>> results) {
    List<Map<String, Object>> paths = new ArrayList<>();

    for (Map<String, Object> row : results) {
      Object idObj = row.get("id");
      if (idObj == null) continue;

      long id = idObj instanceof Number ? ((Number) idObj).longValue() : Long.parseLong(idObj.toString());
      HeapObject obj = dump.getObjectById(id).orElse(null);
      if (obj == null) continue;

      // Find path to GC root
      List<HeapObject> path = dump.findPathToGcRoot(obj);
      if (path.isEmpty()) continue;

      // Convert path to result format - one row per object in path
      for (int i = 0; i < path.size(); i++) {
        HeapObject pathObj = path.get(i);
        Map<String, Object> pathRow = new LinkedHashMap<>();
        pathRow.put("step", i);
        pathRow.put("id", pathObj.getId());
        pathRow.put("class", pathObj.getHeapClass() != null ? pathObj.getHeapClass().getName() : "unknown");
        pathRow.put("shallow", pathObj.getShallowSize());
        pathRow.put("retained", pathObj.getRetainedSize());
        pathRow.put("description", pathObj.getDescription());

        // Add reference type for steps after the root
        if (i > 0) {
          pathRow.put("referenceType", i == 0 ? "GC Root" : "field/array reference");
        } else {
          pathRow.put("referenceType", "GC Root");
        }

        paths.add(pathRow);
      }
    }

    return paths;
  }

  private static List<Map<String, Object>> applyCheckLeaks(
      HeapSession session, List<Map<String, Object>> results, CheckLeaksOp op) {

    if (op.detector() != null) {
      // Use built-in detector
      io.jafar.hdump.shell.leaks.LeakDetector detector =
          io.jafar.hdump.shell.leaks.LeakDetectorRegistry.getDetector(op.detector());

      if (detector == null) {
        // Invalid detector name
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("error", "Unknown detector: " + op.detector());
        error.put("availableDetectors",
            io.jafar.hdump.shell.leaks.LeakDetectorRegistry.getDetectorNames());
        return List.of(error);
      }

      // Run the detector
      return detector.detect(session.getHeapDump(), op.threshold(), op.minSize());

    } else if (op.filter() != null) {
      // Apply custom filter - this would reference a saved query variable
      // For now, return placeholder
      Map<String, Object> placeholder = new LinkedHashMap<>();
      placeholder.put("message", "Custom filter '" + op.filter() + "' requires variable resolution");
      placeholder.put("filter", op.filter());
      return List.of(placeholder);
    }

    return results;
  }

  private static List<Map<String, Object>> applyDominators(
      HeapSession session, HeapDump dump, List<Map<String, Object>> results, DominatorsOp op) {

    // Check if full dominator tree is computed
    if (dump instanceof io.jafar.hdump.impl.HeapDumpImpl heapDumpImpl) {
      if (!heapDumpImpl.hasFullDominatorTree()) {
        // Auto-compute dominator tree
        if (!session.promptAndComputeDominatorTree()) {
          return List.of();
        }
      }
    }

    String mode = op.mode() != null ? op.mode() : "objects";
    return switch (mode) {
      case "objects" -> applyDominatorsObjects(dump, results);
      case "byClass" -> applyDominatorsByClass(dump, results);
      case "tree" -> applyDominatorsTree(dump, results);
      default -> throw new IllegalArgumentException("Unknown dominators mode: " + mode);
    };
  }

  private static List<Map<String, Object>> applyDominatorsObjects(
      HeapDump dump, List<Map<String, Object>> results) {
    // Original behavior: return individual dominated objects
    List<Map<String, Object>> dominated = new ArrayList<>();

    for (Map<String, Object> row : results) {
      Object idObj = row.get("id");
      if (idObj == null) continue;

      long id = idObj instanceof Number ? ((Number) idObj).longValue() : Long.parseLong(idObj.toString());
      HeapObject dominator = dump.getObjectById(id).orElse(null);
      if (dominator == null) continue;

      // Get all objects dominated by this object
      if (dump instanceof io.jafar.hdump.impl.HeapDumpImpl heapDumpImpl) {
        List<HeapObject> dominatedObjects = heapDumpImpl.getDominatedObjects(dominator);

        for (HeapObject obj : dominatedObjects) {
          Map<String, Object> result = objectToMap(obj);
          result.put("dominator", dominator.getId());
          result.put(
              "dominatorClass",
              dominator.getHeapClass() != null ? dominator.getHeapClass().getName() : "unknown");
          dominated.add(result);
        }
      }
    }

    return dominated;
  }

  private static List<Map<String, Object>> applyDominatorsByClass(
      HeapDump dump, List<Map<String, Object>> results) {
    // Group dominated objects by class and aggregate retained sizes
    List<Map<String, Object>> aggregated = new ArrayList<>();

    for (Map<String, Object> row : results) {
      Object idObj = row.get("id");
      if (idObj == null) continue;

      long id = idObj instanceof Number ? ((Number) idObj).longValue() : Long.parseLong(idObj.toString());
      HeapObject dominator = dump.getObjectById(id).orElse(null);
      if (dominator == null) continue;

      // Build class-level aggregation
      if (dump instanceof io.jafar.hdump.impl.HeapDumpImpl heapDumpImpl) {
        List<HeapObject> dominatedObjects = heapDumpImpl.getDominatedObjects(dominator);

        // Group by class name
        Map<String, ClassAggregation> byClass = new LinkedHashMap<>();
        for (HeapObject obj : dominatedObjects) {
          String className =
              obj.getHeapClass() != null
                  ? obj.getHeapClass().getName().replace('/', '.')
                  : "unknown";
          ClassAggregation agg =
              byClass.computeIfAbsent(className, k -> new ClassAggregation(className));
          agg.count++;
          agg.shallowSize += obj.getShallowSize();
          agg.retainedSize += obj.getRetainedSize();
        }

        // Convert to result maps
        for (ClassAggregation agg : byClass.values()) {
          Map<String, Object> result = new LinkedHashMap<>();
          result.put("dominator", dominator.getId());
          result.put(
              "dominatorClass",
              dominator.getHeapClass() != null
                  ? dominator.getHeapClass().getName().replace('/', '.')
                  : "unknown");
          result.put("className", agg.className);
          result.put("count", agg.count);
          result.put("shallowSize", agg.shallowSize);
          result.put("retainedSize", agg.retainedSize);
          aggregated.add(result);
        }
      }
    }

    return aggregated;
  }

  private static List<Map<String, Object>> applyDominatorsTree(
      HeapDump dump, List<Map<String, Object>> results) {
    // Build ASCII tree visualization of dominator relationships
    List<Map<String, Object>> treeResults = new ArrayList<>();

    for (Map<String, Object> row : results) {
      Object idObj = row.get("id");
      if (idObj == null) continue;

      long id =
          idObj instanceof Number
              ? ((Number) idObj).longValue()
              : Long.parseLong(idObj.toString());
      HeapObject dominator = dump.getObjectById(id).orElse(null);
      if (dominator == null) continue;

      if (dump instanceof io.jafar.hdump.impl.HeapDumpImpl heapDumpImpl) {
        // Build recursive dominator tree
        DominatorNode root = buildRecursiveDominatorTree(heapDumpImpl, dominator, 0, 3);

        // Format as ASCII tree
        StringBuilder tree = new StringBuilder();
        String dominatorClassName =
            dominator.getHeapClass() != null
                ? dominator.getHeapClass().getName().replace('/', '.')
                : "unknown";
        tree.append(
            String.format(
                "%s (retained: %,d bytes)\n", dominatorClassName, dominator.getRetainedSize()));
        formatDominatorTree(root, tree, "", true);

        // Count total dominated objects recursively
        int totalDominated = countDominatedObjects(root);

        // Print tree to stderr to avoid table truncation
        System.err.println();
        System.err.println("=== Dominator Tree ===");
        System.err.print(tree.toString());
        System.err.println();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dominator", dominator.getId());
        result.put("dominatorClass", dominatorClassName);
        result.put("dominatedCount", totalDominated);
        result.put("retainedSize", dominator.getRetainedSize());
        treeResults.add(result);
      }
    }

    return treeResults;
  }

  private static DominatorNode buildRecursiveDominatorTree(
      io.jafar.hdump.impl.HeapDumpImpl dump, HeapObject root, int depth, int maxDepth) {
    // Stop recursion at max depth to avoid stack overflow
    if (depth >= maxDepth) {
      return new DominatorNode("...");
    }

    List<HeapObject> children = dump.getDominatedObjects(root);
    if (children.isEmpty()) {
      return new DominatorNode("(no children)");
    }

    // Group immediate children by class to avoid per-object recursion
    Map<String, ClassNode> classToDominatorNode = new LinkedHashMap<>();

    for (HeapObject child : children) {
      String className =
          child.getHeapClass() != null
              ? child.getHeapClass().getName().replace('/', '.')
              : "unknown";

      ClassNode classNode =
          classToDominatorNode.computeIfAbsent(className, k -> new ClassNode(k, child));
      classNode.count++;
      classNode.retainedSize += child.getRetainedSize();
    }

    // Convert to DominatorNode with limited recursion
    // Only recurse for top 5 classes to prevent OOM/stack overflow
    DominatorNode rootNode =
        new DominatorNode(
            root.getHeapClass() != null
                ? root.getHeapClass().getName().replace('/', '.')
                : "unknown");

    List<ClassNode> sortedClasses = new ArrayList<>(classToDominatorNode.values());
    sortedClasses.sort((a, b) -> Long.compare(b.retainedSize, a.retainedSize));

    int shown = 0;
    for (ClassNode classNode : sortedClasses) {
      DominatorNode node = new DominatorNode(classNode.className);
      node.count = classNode.count;
      node.retainedSize = classNode.retainedSize;

      // Only recurse for top 5 classes and not beyond depth limit
      if (shown < 5 && depth + 1 < maxDepth && classNode.sampleObject != null) {
        DominatorNode childTree =
            buildRecursiveDominatorTree(dump, classNode.sampleObject, depth + 1, maxDepth);
        if (childTree != null && !childTree.children.isEmpty()) {
          node.children.addAll(childTree.children);
        }
      }

      rootNode.children.add(node);
      shown++;
      if (shown >= 10) {
        // Show at most 10 classes at this level
        if (sortedClasses.size() > 10) {
          DominatorNode more = new DominatorNode("... (" + (sortedClasses.size() - 10) + " more classes)");
          rootNode.children.add(more);
        }
        break;
      }
    }

    return rootNode;
  }

  private static class ClassNode {
    final String className;
    final HeapObject sampleObject; // One object from this class for recursion
    int count = 0;
    long retainedSize = 0;

    ClassNode(String className, HeapObject sampleObject) {
      this.className = className;
      this.sampleObject = sampleObject;
    }
  }

  private static int countDominatedObjects(DominatorNode node) {
    int count = node.count;
    for (DominatorNode child : node.children) {
      count += countDominatedObjects(child);
    }
    return count;
  }

  private static void formatDominatorTree(
      DominatorNode node, StringBuilder sb, String prefix, boolean isLast) {
    if (node.children.isEmpty()) return;

    for (int i = 0; i < node.children.size(); i++) {
      DominatorNode child = node.children.get(i);
      boolean last = i == node.children.size() - 1;

      sb.append(prefix);
      sb.append(last ? "└── " : "├── ");
      sb.append(
          String.format(
              "%s (%,d objects, retained: %,d bytes)\n",
              child.className, child.count, child.retainedSize));

      String childPrefix = prefix + (last ? "    " : "│   ");
      formatDominatorTree(child, sb, childPrefix, last);
    }
  }

  private static class ClassAggregation {
    final String className;
    int count = 0;
    long shallowSize = 0;
    long retainedSize = 0;

    ClassAggregation(String className) {
      this.className = className;
    }
  }

  private static class DominatorNode {
    final String className;
    int count = 0;
    long retainedSize = 0;
    final List<DominatorNode> children = new ArrayList<>();

    DominatorNode(String className) {
      this.className = className;
    }
  }
}
