package io.jafar.hdump.shell.hdumppath;

import io.jafar.hdump.shell.hdumppath.HdumpPath.BoolExpr;
import io.jafar.hdump.shell.hdumppath.HdumpPath.CompExpr;
import io.jafar.hdump.shell.hdumppath.HdumpPath.ExprPredicate;
import io.jafar.hdump.shell.hdumppath.HdumpPath.FieldPredicate;
import io.jafar.hdump.shell.hdumppath.HdumpPath.FuncExpr;
import io.jafar.hdump.shell.hdumppath.HdumpPath.LogicalExpr;
import io.jafar.hdump.shell.hdumppath.HdumpPath.NotExpr;
import io.jafar.hdump.shell.hdumppath.HdumpPath.Predicate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * Fails a filter that matched nothing because it compares a field that is not a column of its rows,
 * as {@code top}, {@code sortBy}, {@code sum} and {@code stats} fail on a missing field (#295:
 * {@code classes | filter(retained > 10MB)} returned no rows; classes have no {@code retained}
 * column).
 *
 * <p>A field is a column of the rows when it is one of the {@code known} columns their root (and
 * the operators since that add columns to some rows) can have, whether or not these rows carry it
 * ({@code arrayLength} on objects that are not arrays, {@code retainedSize} before it is computed).
 * For rows a pipeline stage reshaped ({@code known} null) the columns are the keys any row carries,
 * a null value included. Below a column, a path ({@code fields.size}) is not checked: an object's
 * fields depend on its class. Fields compared inside functions are checked too, except those of
 * {@code exists()} and {@code empty()}, which ask about absence.
 *
 * <p>It fails only when rows were filtered and none matched: a filter that matched some rows either
 * read the field or matched without it (an {@code or} branch, {@code !=} against an absent value),
 * and a stream that stopped early stopped because rows matched. When the input rows are an
 * operator's error row ({@code {"error": ...}}, as {@code retainedBreakdown()} gives without
 * dominators, with a {@code help} hint from {@code checkLeaks}), that error is the message. Safe
 * for parallel streams.
 */
final class FilterFieldCheck {

  private static final String ERROR = "error";
  private static final String HELP = "help";

  /** The keys of an operator's error row ({@code checkLeaks} adds a hint). */
  private static final Set<String> ERROR_ROW_KEYS = Set.of(ERROR, HELP);

  private final List<Predicate> predicates;
  private final List<List<String>> paths;

  /** Per path in {@link #paths}: null until a row has its key. */
  private final AtomicReferenceArray<Boolean> seen;

  private final AtomicInteger unseen;
  private final LongAdder rows = new LongAdder();
  private final LongAdder matched = new LongAdder();
  private final AtomicReference<Map<String, Object>> firstRow = new AtomicReference<>();

  private FilterFieldCheck(List<Predicate> predicates, Set<String> known) {
    this.predicates = predicates;
    Set<List<String>> compared = new LinkedHashSet<>();
    for (Predicate p : predicates) {
      switch (p) {
        case FieldPredicate fp -> compared.add(fp.fieldPath());
        case ExprPredicate ep -> collect(ep.expr(), compared);
      }
    }
    // Only fields that are no known column need watching
    List<List<String>> watched = new ArrayList<>();
    for (List<String> path : compared) {
      if (known == null || !isKnown(known, path)) {
        watched.add(path);
      }
    }
    this.paths = watched;
    this.seen = new AtomicReferenceArray<>(paths.size());
    this.unseen = new AtomicInteger(paths.size());
  }

  /**
   * A check of {@code predicates} over rows whose root has the {@code known} columns, or, with
   * {@code known} null, over rows a pipeline stage reshaped (their columns are those they carry).
   */
  static FilterFieldCheck of(List<Predicate> predicates, Set<String> known) {
    return new FilterFieldCheck(predicates, known);
  }

  /** Whether {@code row} matches every predicate; notes which watched fields it has. */
  boolean test(Map<String, Object> row) {
    rows.increment();
    if (unseen.get() > 0) {
      if (firstRow.get() == null) {
        firstRow.compareAndSet(null, row);
      }
      for (int i = 0; i < paths.size(); i++) {
        if (seen.get(i) == null
            && hasKey(row, paths.get(i))
            && seen.compareAndSet(i, null, Boolean.TRUE)) {
          unseen.decrementAndGet();
        }
      }
    }
    boolean match = HdumpPathEvaluator.matchesAll(row, predicates);
    if (match) {
      matched.increment();
    }
    return match;
  }

  /**
   * Fails when rows were filtered, none matched, and a compared field is no column of theirs.
   *
   * @param opName the operator named in the message
   */
  void verify(String opName) {
    if (unseen.get() == 0 || matched.sum() > 0 || rows.sum() == 0) {
      return;
    }
    Map<String, Object> row = firstRow.get();
    if (row != null && row.get(ERROR) != null && ERROR_ROW_KEYS.containsAll(row.keySet())) {
      // The rows are an operator's error: that is the message, not a missing column
      throw new IllegalArgumentException(
          row.get(HELP) == null
              ? String.valueOf(row.get(ERROR))
              : row.get(ERROR) + " (" + row.get(HELP) + ")");
    }
    for (int i = 0; i < paths.size(); i++) {
      if (seen.get(i) == null) {
        String field = String.join(".", paths.get(i));
        Set<String> columns = row == null ? Set.of() : row.keySet();
        if (HdumpPathEvaluator.isRetainedField(field)
            && columns.contains(HdumpPath.ClassFields.NAME)
            && !columns.contains(HdumpPath.ObjectFields.RETAINED_SIZE)) {
          throw new IllegalArgumentException(HdumpPathEvaluator.retainedOnClassesMessage(field));
        }
        throw new IllegalArgumentException(
            opName
                + ": field '"
                + field
                + "' is not present in its input rows (available: "
                + String.join(", ", columns)
                + ")");
      }
    }
  }

  /** Whether every field {@code predicate} compares is one of {@code columns}. */
  static boolean comparesOnly(Predicate predicate, Set<String> columns) {
    Set<List<String>> compared = new LinkedHashSet<>();
    switch (predicate) {
      case FieldPredicate fp -> compared.add(fp.fieldPath());
      case ExprPredicate ep -> collectAll(ep.expr(), compared);
    }
    return compared.stream().allMatch(path -> isKnown(columns, path));
  }

  /** Every field an expression reads, those inside exists() and empty() included. */
  private static void collectAll(BoolExpr expr, Set<List<String>> into) {
    switch (expr) {
      case CompExpr ce -> into.add(ce.fieldPath());
      case LogicalExpr le -> {
        collectAll(le.left(), into);
        collectAll(le.right(), into);
      }
      case NotExpr ne -> collectAll(ne.inner(), into);
      case FuncExpr fe -> {
        if (!fe.args().isEmpty()) {
          into.add(List.of(String.valueOf(fe.args().get(0))));
        }
      }
    }
  }

  /** Whether the path's first segment, or the path as one dotted key, is a known column. */
  private static boolean isKnown(Set<String> known, List<String> path) {
    return known.contains(path.get(0))
        || known.contains(HdumpPathEvaluator.canonicalField(path.get(0)))
        || (path.size() > 1 && known.contains(String.join(".", path)));
  }

  /** Whether {@code row} has the path's key: as one dotted key, or level by level. */
  private static boolean hasKey(Map<String, Object> row, List<String> path) {
    if (path.size() > 1 && row.containsKey(String.join(".", path))) {
      return true;
    }
    Object current = row;
    for (int i = 0; i < path.size(); i++) {
      if (!(current instanceof Map<?, ?> m)) {
        return false;
      }
      // A stage's rows are plain maps keyed by the name the query used (groupBy(class) gives
      // "class"); a root's rows resolve aliases ("class" is "className")
      String key = path.get(i);
      if (!m.containsKey(key)) {
        key = i == 0 ? HdumpPathEvaluator.canonicalField(key) : key;
        if (!m.containsKey(key)) {
          return false;
        }
      }
      current = m.get(key);
    }
    return true;
  }

  private static void collect(BoolExpr expr, Set<List<String>> into) {
    switch (expr) {
      case CompExpr ce -> into.add(ce.fieldPath());
      case LogicalExpr le -> {
        collect(le.left(), into);
        collect(le.right(), into);
      }
      case NotExpr ne -> collect(ne.inner(), into);
      case FuncExpr fe -> {
        // exists()/empty() ask about absence; the others read their first argument's field
        if (!"exists".equals(fe.name()) && !"empty".equals(fe.name()) && !fe.args().isEmpty()) {
          into.add(List.of(String.valueOf(fe.args().get(0))));
        }
      }
    }
  }
}
