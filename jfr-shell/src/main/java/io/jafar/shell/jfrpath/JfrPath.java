package io.jafar.shell.jfrpath;

import java.util.List;

/**
 * JfrPath AST model and utilities.
 */
public final class JfrPath {
    private JfrPath() {}

    public enum Root { EVENTS, METADATA, CHUNKS, CP }

    public enum Op { EQ, NE, GT, GE, LT, LE, REGEX }

    public enum MatchMode { ANY, ALL, NONE }

    public sealed interface Predicate permits FieldPredicate {
    }

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

    public static final class Query {
        public final Root root;
        public final List<String> segments; // path segments after root
        public final List<Predicate> predicates;
        public final List<PipelineOp> pipeline; // optional aggregation pipeline

        public Query(Root root, List<String> segments, List<Predicate> predicates) {
            this.root = root;
            this.segments = List.copyOf(segments);
            this.predicates = List.copyOf(predicates);
            this.pipeline = List.of();
        }

        public Query(Root root, List<String> segments, List<Predicate> predicates, List<PipelineOp> pipeline) {
            this.root = root;
            this.segments = List.copyOf(segments);
            this.predicates = List.copyOf(predicates);
            this.pipeline = List.copyOf(pipeline);
        }

        @Override
        public String toString() {
            return "Query{" + root + ":" + String.join("/", segments) + ", filters=" + predicates.size() + ", pipe=" + pipeline.size() + "}";
        }
    }

    // Aggregation pipeline
    public sealed interface PipelineOp permits CountOp, StatsOp, QuantilesOp, SketchOp, LenOp { }
    public static final class CountOp implements PipelineOp { }
    public static final class StatsOp implements PipelineOp {
        public final List<String> valuePath; // optional, if empty: use projection path
        public StatsOp(List<String> valuePath) { this.valuePath = valuePath == null ? List.of() : List.copyOf(valuePath); }
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
        public SketchOp(List<String> valuePath) { this.valuePath = valuePath == null ? List.of() : List.copyOf(valuePath); }
    }
    public static final class LenOp implements PipelineOp {
        public final List<String> valuePath; // optional
        public LenOp(List<String> valuePath) { this.valuePath = valuePath == null ? List.of() : List.copyOf(valuePath); }
    }
}
