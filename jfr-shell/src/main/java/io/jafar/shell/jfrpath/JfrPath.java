package io.jafar.shell.jfrpath;

import java.util.List;
import java.util.Objects;

/** JfrPath AST model and utilities. */
public final class JfrPath {
  private JfrPath() {}

  public enum Root {
    EVENTS,
    METADATA,
    CHUNKS,
    CP
  }

  public enum Op {
    EQ,
    NE,
    GT,
    GE,
    LT,
    LE,
    REGEX,
    PLUS,
    MINUS,
    MULT,
    DIV
  }

  public enum MatchMode {
    ANY,
    ALL,
    NONE
  }

  public sealed interface Predicate permits FieldPredicate, ExprPredicate {}

  public static final class FieldPredicate implements Predicate {
    public final List<String> fieldPath; // e.g., ["thread","name"]
    public final Op op;
    public final Object literal; // String or Number
    public final MatchMode matchMode; // list/array matching behavior

    public FieldPredicate(List<String> fieldPath, Op op, Object literal) {
      this.fieldPath = List.copyOf(fieldPath);
      this.op = op;
      this.literal = literal;
      this.matchMode = MatchMode.ANY;
    }

    public FieldPredicate(List<String> fieldPath, Op op, Object literal, MatchMode matchMode) {
      this.fieldPath = List.copyOf(fieldPath);
      this.op = op;
      this.literal = literal;
      this.matchMode = matchMode == null ? MatchMode.ANY : matchMode;
    }
  }

  // Boolean expression-based predicate (for functions, logical ops, comparisons with function/path
  // on LHS)
  public static final class ExprPredicate implements Predicate {
    public final BoolExpr expr;

    public ExprPredicate(BoolExpr expr) {
      this.expr = expr;
    }
  }

  // Boolean expression AST
  public sealed interface BoolExpr permits CompExpr, FuncBoolExpr, LogicalExpr, NotExpr {}

  // Left-hand value expression for comparisons
  public sealed interface ValueExpr permits PathRef, FuncValueExpr {}

  public static final class PathRef implements ValueExpr {
    public final java.util.List<String> path;

    public PathRef(java.util.List<String> path) {
      this.path = java.util.List.copyOf(path);
    }
  }

  public static final class FuncValueExpr implements ValueExpr {
    public final String name;
    public final java.util.List<Arg> args;

    public FuncValueExpr(String name, java.util.List<Arg> args) {
      this.name = name;
      this.args = java.util.List.copyOf(args);
    }
  }

  public static final class CompExpr implements BoolExpr {
    public final ValueExpr lhs;
    public final Op op;
    public final Object literal;

    public CompExpr(ValueExpr lhs, Op op, Object literal) {
      this.lhs = lhs;
      this.op = op;
      this.literal = literal;
    }
  }

  public static final class FuncBoolExpr implements BoolExpr {
    public final String name;
    public final java.util.List<Arg> args;

    public FuncBoolExpr(String name, java.util.List<Arg> args) {
      this.name = name;
      this.args = java.util.List.copyOf(args);
    }
  }

  public static final class LogicalExpr implements BoolExpr {
    public enum Lop {
      AND,
      OR
    }

    public final BoolExpr left;
    public final BoolExpr right;
    public final Lop op;

    public LogicalExpr(BoolExpr left, BoolExpr right, Lop op) {
      this.left = left;
      this.right = right;
      this.op = op;
    }
  }

  public static final class NotExpr implements BoolExpr {
    public final BoolExpr inner;

    public NotExpr(BoolExpr inner) {
      this.inner = inner;
    }
  }

  // Function argument can be a path or a literal
  public sealed interface Arg permits PathArg, LiteralArg {}

  public static final class PathArg implements Arg {
    public final java.util.List<String> path;

    public PathArg(java.util.List<String> path) {
      this.path = java.util.List.copyOf(path);
    }
  }

  public static final class LiteralArg implements Arg {
    public final Object value;

    public LiteralArg(Object value) {
      this.value = value;
    }
  }

  // Expression AST for computed fields in select()
  public sealed interface Expr permits BinExpr, FuncExpr, FieldRef, Literal, StringTemplate {}

  // Binary expression: left op right
  public static final class BinExpr implements Expr {
    public final Op op;
    public final Expr left;
    public final Expr right;

    public BinExpr(Op op, Expr left, Expr right) {
      this.op = op;
      this.left = left;
      this.right = right;
    }
  }

  // Function call expression
  public static final class FuncExpr implements Expr {
    public final String funcName;
    public final List<Expr> args;

    public FuncExpr(String funcName, List<Expr> args) {
      this.funcName = funcName;
      this.args = List.copyOf(args);
    }
  }

  // Field reference expression
  public static final class FieldRef implements Expr {
    public final List<String> fieldPath;

    public FieldRef(List<String> fieldPath) {
      this.fieldPath = List.copyOf(fieldPath);
    }
  }

  // Literal value expression
  public static final class Literal implements Expr {
    public final Object value;

    public Literal(Object value) {
      this.value = value;
    }
  }

  // String template expression with embedded expressions: "prefix ${expr1} middle ${expr2} suffix"
  // Parts contains literal strings, expressions contains the embedded expressions
  // Invariant: parts.size() == expressions.size() + 1
  // Example: "File ${path} has ${bytes} bytes"
  //   parts = ["File ", " has ", " bytes"]
  //   expressions = [FieldRef(path), FieldRef(bytes)]
  public static final class StringTemplate implements Expr {
    public final List<String> parts;
    public final List<Expr> expressions;

    public StringTemplate(List<String> parts, List<Expr> expressions) {
      if (parts.size() != expressions.size() + 1) {
        throw new IllegalArgumentException(
            "StringTemplate must have parts.size() == expressions.size() + 1");
      }
      this.parts = List.copyOf(parts);
      this.expressions = List.copyOf(expressions);
    }
  }

  public static final class Query {
    public final Root root;
    public final List<String> segments; // path segments after root (kept for backward compat)
    public final List<String> eventTypes; // event types for multi-type queries
    public final boolean isMultiType; // true if eventTypes.size() > 1
    public final List<Predicate> predicates;
    public final List<PipelineOp> pipeline; // optional aggregation pipeline

    public Query(Root root, List<String> segments, List<Predicate> predicates) {
      this.root = root;
      this.segments = List.copyOf(segments);
      this.eventTypes = segments.isEmpty() ? List.of() : List.of(segments.get(0));
      this.isMultiType = false;
      this.predicates = List.copyOf(predicates);
      this.pipeline = List.of();
    }

    public Query(
        Root root, List<String> segments, List<Predicate> predicates, List<PipelineOp> pipeline) {
      this.root = root;
      this.segments = List.copyOf(segments);
      this.eventTypes = segments.isEmpty() ? List.of() : List.of(segments.get(0));
      this.isMultiType = false;
      this.predicates = List.copyOf(predicates);
      this.pipeline = List.copyOf(pipeline);
    }

    // Constructor for multi-type queries
    public Query(
        Root root,
        List<String> eventTypes,
        List<Predicate> predicates,
        List<PipelineOp> pipeline,
        boolean isMultiType) {
      this.root = root;
      this.eventTypes = List.copyOf(eventTypes);
      this.isMultiType = eventTypes.size() > 1;
      this.segments = eventTypes.isEmpty() ? List.of() : List.of(eventTypes.get(0));
      this.predicates = List.copyOf(predicates);
      this.pipeline = List.copyOf(pipeline);
    }

    @Override
    public String toString() {
      return "Query{"
          + root
          + ":"
          + (isMultiType ? "(" + String.join("|", eventTypes) + ")" : String.join("/", segments))
          + ", filters="
          + predicates.size()
          + ", pipe="
          + pipeline.size()
          + "}";
    }
  }

  // Aggregation pipeline
  public sealed interface PipelineOp
      permits CountOp,
          SumOp,
          StatsOp,
          QuantilesOp,
          SketchOp,
          GroupByOp,
          TopOp,
          LenOp,
          UppercaseOp,
          LowercaseOp,
          TrimOp,
          AbsOp,
          RoundOp,
          FloorOp,
          CeilOp,
          ContainsOp,
          ReplaceOp,
          DecorateByTimeOp,
          DecorateByKeyOp,
          SelectOp,
          ToMapOp {}

  public static final class CountOp implements PipelineOp {}

  public static final class SumOp implements PipelineOp {
    public final List<String> valuePath; // optional, if empty: use projection path

    public SumOp(List<String> valuePath) {
      this.valuePath = valuePath == null ? List.of() : List.copyOf(valuePath);
    }
  }

  public static final class StatsOp implements PipelineOp {
    public final List<String> valuePath; // optional, if empty: use projection path

    public StatsOp(List<String> valuePath) {
      this.valuePath = valuePath == null ? List.of() : List.copyOf(valuePath);
    }
  }

  public static final class QuantilesOp implements PipelineOp {
    public final List<Double> qs; // quantiles in 0..1
    public final List<String> valuePath; // optional

    public QuantilesOp(List<Double> qs, List<String> valuePath) {
      this.qs = (qs == null || qs.isEmpty()) ? List.of(0.5, 0.9, 0.99) : List.copyOf(qs);
      this.valuePath = valuePath == null ? List.of() : List.copyOf(valuePath);
    }
  }

  public static final class SketchOp implements PipelineOp {
    public final List<String> valuePath; // optional

    public SketchOp(List<String> valuePath) {
      this.valuePath = valuePath == null ? List.of() : List.copyOf(valuePath);
    }
  }

  public static final class GroupByOp implements PipelineOp {
    public final List<String> keyPath; // path to group by
    public final String aggFunc; // "count", "sum", "avg", "min", "max"
    public final List<String> valuePath; // for sum/avg/min/max

    public GroupByOp(List<String> keyPath, String aggFunc, List<String> valuePath) {
      this.keyPath = List.copyOf(keyPath);
      this.aggFunc = aggFunc == null ? "count" : aggFunc;
      this.valuePath = valuePath == null ? List.of() : List.copyOf(valuePath);
    }
  }

  public static final class TopOp implements PipelineOp {
    public final int n; // number of results to return
    public final List<String> byPath; // sort key path
    public final boolean ascending; // sort order

    public TopOp(int n, List<String> byPath, boolean ascending) {
      this.n = n;
      this.byPath = byPath == null ? List.of("value") : List.copyOf(byPath);
      this.ascending = ascending;
    }
  }

  public static final class LenOp implements PipelineOp {
    public final List<String> valuePath; // optional

    public LenOp(List<String> valuePath) {
      this.valuePath = valuePath == null ? List.of() : List.copyOf(valuePath);
    }
  }

  public static final class UppercaseOp implements PipelineOp {
    public final List<String> valuePath;

    public UppercaseOp(List<String> valuePath) {
      this.valuePath = valuePath == null ? List.of() : List.copyOf(valuePath);
    }
  }

  public static final class LowercaseOp implements PipelineOp {
    public final List<String> valuePath;

    public LowercaseOp(List<String> valuePath) {
      this.valuePath = valuePath == null ? List.of() : List.copyOf(valuePath);
    }
  }

  public static final class TrimOp implements PipelineOp {
    public final List<String> valuePath;

    public TrimOp(List<String> valuePath) {
      this.valuePath = valuePath == null ? List.of() : List.copyOf(valuePath);
    }
  }

  public static final class AbsOp implements PipelineOp {
    public final List<String> valuePath;

    public AbsOp(List<String> valuePath) {
      this.valuePath = valuePath == null ? List.of() : List.copyOf(valuePath);
    }
  }

  public static final class RoundOp implements PipelineOp {
    public final List<String> valuePath;

    public RoundOp(List<String> valuePath) {
      this.valuePath = valuePath == null ? List.of() : List.copyOf(valuePath);
    }
  }

  public static final class FloorOp implements PipelineOp {
    public final List<String> valuePath;

    public FloorOp(List<String> valuePath) {
      this.valuePath = valuePath == null ? List.of() : List.copyOf(valuePath);
    }
  }

  public static final class CeilOp implements PipelineOp {
    public final List<String> valuePath;

    public CeilOp(List<String> valuePath) {
      this.valuePath = valuePath == null ? List.of() : List.copyOf(valuePath);
    }
  }

  public static final class ContainsOp implements PipelineOp {
    public final List<String> valuePath;
    public final String substr;

    public ContainsOp(List<String> valuePath, String substr) {
      this.valuePath = valuePath == null ? List.of() : List.copyOf(valuePath);
      this.substr = substr;
    }
  }

  public static final class ReplaceOp implements PipelineOp {
    public final List<String> valuePath;
    public final String target;
    public final String replacement;

    public ReplaceOp(List<String> valuePath, String target, String replacement) {
      this.valuePath = valuePath == null ? List.of() : List.copyOf(valuePath);
      this.target = target;
      this.replacement = replacement;
    }
  }

  // Key expression for correlation-based decoration
  public sealed interface KeyExpr permits PathKeyExpr {}

  public static final class PathKeyExpr implements KeyExpr {
    public final List<String> path;

    public PathKeyExpr(List<String> path) {
      this.path = List.copyOf(path);
    }
  }

  // Time-range decoration: decorate events that overlap temporally on same thread
  public static final class DecorateByTimeOp implements PipelineOp {
    public final String decoratorEventType;
    public final List<String> decoratorFields;
    public final List<String> threadPathPrimary;
    public final List<String> threadPathDecorator;

    public DecorateByTimeOp(
        String decoratorEventType,
        List<String> decoratorFields,
        List<String> threadPathPrimary,
        List<String> threadPathDecorator) {
      this.decoratorEventType = decoratorEventType;
      this.decoratorFields = decoratorFields == null ? List.of() : List.copyOf(decoratorFields);
      this.threadPathPrimary =
          threadPathPrimary == null
              ? List.of("eventThread", "javaThreadId")
              : List.copyOf(threadPathPrimary);
      this.threadPathDecorator =
          threadPathDecorator == null
              ? List.of("eventThread", "javaThreadId")
              : List.copyOf(threadPathDecorator);
    }
  }

  // Correlation-based decoration: decorate events with matching correlation keys
  public static final class DecorateByKeyOp implements PipelineOp {
    public final String decoratorEventType;
    public final KeyExpr primaryKey;
    public final KeyExpr decoratorKey;
    public final List<String> decoratorFields;

    public DecorateByKeyOp(
        String decoratorEventType,
        KeyExpr primaryKey,
        KeyExpr decoratorKey,
        List<String> decoratorFields) {
      this.decoratorEventType = decoratorEventType;
      this.primaryKey = primaryKey;
      this.decoratorKey = decoratorKey;
      this.decoratorFields = decoratorFields == null ? List.of() : List.copyOf(decoratorFields);
    }
  }

  // SelectItem: represents a single item in select projection
  public sealed interface SelectItem permits FieldSelection, ExpressionSelection {
    String outputName(); // Column name in output
  }

  // Simple field path selection
  public static final class FieldSelection implements SelectItem {
    public final List<String> fieldPath;
    public final String alias; // Optional alias

    public FieldSelection(List<String> fieldPath, String alias) {
      this.fieldPath = List.copyOf(fieldPath);
      this.alias = alias;
    }

    @Override
    public String outputName() {
      // Use alias if provided, otherwise leaf segment
      return alias != null ? alias : fieldPath.get(fieldPath.size() - 1);
    }
  }

  // Computed expression selection
  public static final class ExpressionSelection implements SelectItem {
    public final Expr expression;
    public final String alias; // Required for expressions

    public ExpressionSelection(Expr expression, String alias) {
      if (alias == null || alias.isEmpty()) {
        throw new IllegalArgumentException("Expression selections require an alias");
      }
      this.expression = expression;
      this.alias = alias;
    }

    @Override
    public String outputName() {
      return alias;
    }
  }

  /** select(field1,field2,...) - Project only specified fields from events */
  public static final class SelectOp implements PipelineOp {
    public final List<SelectItem> items;

    public SelectOp(List<SelectItem> items) {
      this.items = items == null ? List.of() : List.copyOf(items);
    }

    // Backward compatibility helper
    @Deprecated
    public List<List<String>> fieldPaths() {
      return items.stream()
          .filter(item -> item instanceof FieldSelection)
          .map(item -> ((FieldSelection) item).fieldPath)
          .toList();
    }
  }

  /** toMap(keyField, valueField) - Convert rows to map using specified key and value fields */
  public static final class ToMapOp implements PipelineOp {
    public final List<String> keyField;
    public final List<String> valueField;

    public ToMapOp(List<String> keyField, List<String> valueField) {
      if (keyField == null || keyField.isEmpty()) {
        throw new IllegalArgumentException("toMap requires non-empty keyField");
      }
      if (valueField == null || valueField.isEmpty()) {
        throw new IllegalArgumentException("toMap requires non-empty valueField");
      }
      this.keyField = List.copyOf(keyField);
      this.valueField = List.copyOf(valueField);
    }

    @Override
    public String toString() {
      return "toMap(" + String.join("/", keyField) + ", " + String.join("/", valueField) + ")";
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof ToMapOp that)) return false;
      return keyField.equals(that.keyField) && valueField.equals(that.valueField);
    }

    @Override
    public int hashCode() {
      return Objects.hash(keyField, valueField);
    }
  }
}
