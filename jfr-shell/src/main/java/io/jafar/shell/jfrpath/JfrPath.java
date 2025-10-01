package io.jafar.shell.jfrpath;

import java.util.List;

/**
 * JfrPath AST model and utilities.
 */
public final class JfrPath {
    private JfrPath() {}

    public enum Root { EVENTS, METADATA, CHUNKS, CP }

    public enum Op { EQ, NE, GT, GE, LT, LE, REGEX }

    public sealed interface Predicate permits FieldPredicate {
    }

    public static final class FieldPredicate implements Predicate {
        public final List<String> fieldPath; // e.g., ["thread","name"]
        public final Op op;
        public final Object literal; // String or Number

        public FieldPredicate(List<String> fieldPath, Op op, Object literal) {
            this.fieldPath = List.copyOf(fieldPath);
            this.op = op;
            this.literal = literal;
        }
    }

    public static final class Query {
        public final Root root;
        public final List<String> segments; // path segments after root
        public final List<Predicate> predicates;

        public Query(Root root, List<String> segments, List<Predicate> predicates) {
            this.root = root;
            this.segments = List.copyOf(segments);
            this.predicates = List.copyOf(predicates);
        }

        @Override
        public String toString() {
            return "Query{" + root + ":" + String.join("/", segments) + ", filters=" + predicates.size() + "}";
        }
    }
}

