package io.jafar.shell.jfrpath;

import io.jafar.parser.api.ParserContext;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.api.Values;
import io.jafar.parser.impl.MapValueBuilder;
import io.jafar.parser.impl.UntypedParserContextFactory;
import io.jafar.parser.internal_api.ChunkHeader;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.GenericValueReader;
import io.jafar.parser.internal_api.StreamingChunkParser;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.internal_api.metadata.MetadataEvent;
import io.jafar.parser.internal_api.metadata.MetadataField;
import io.jafar.shell.JFRSession;
import io.jafar.shell.providers.MetadataProvider;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static io.jafar.shell.jfrpath.JfrPath.*;

/**
 * Minimal evaluator for JfrPath queries.
 *
 * v0 support: events/<type>[field/op/literal] ...
 */
public final class JfrPathEvaluator {
    public interface EventSource {
        void streamEvents(Path recording, Consumer<Event> consumer) throws Exception;
    }

    public record Event(String typeName, Map<String, Object> value) {}

    private final EventSource source;
    private final JfrPath.MatchMode defaultListMatchMode;

    public JfrPathEvaluator() {
        this(new DefaultEventSource(), JfrPath.MatchMode.ANY);
    }

    public JfrPathEvaluator(EventSource source) {
        this(source, JfrPath.MatchMode.ANY);
    }

    public JfrPathEvaluator(EventSource source, JfrPath.MatchMode defaultListMatchMode) {
        this.source = Objects.requireNonNull(source);
        this.defaultListMatchMode = defaultListMatchMode == null ? JfrPath.MatchMode.ANY : defaultListMatchMode;
    }

    public JfrPathEvaluator(JfrPath.MatchMode defaultListMatchMode) {
        this(new DefaultEventSource(), defaultListMatchMode);
    }

    public List<Map<String, Object>> evaluate(JFRSession session, Query query) throws Exception {
        if (query.pipeline != null && !query.pipeline.isEmpty()) {
            return evaluateAggregate(session, query);
        }
        if (query.root == Root.EVENTS) {
            if (query.segments.isEmpty()) {
                throw new IllegalArgumentException("Expected event type segment after 'events/'");
            }
            String eventType = query.segments.get(0);
            List<Map<String, Object>> out = new ArrayList<>();
            source.streamEvents(session.getRecordingPath(), ev -> {
                if (!eventType.equals(ev.typeName)) return; // filter type
                Map<String, Object> map = ev.value;
                if (matchesAll(map, query.predicates)) {
                    out.add(Values.resolvedShallow(map));
                }
            });
            return out;
        } else if (query.root == Root.METADATA) {
            if (query.segments.isEmpty()) {
                throw new IllegalArgumentException("Expected type segment after 'metadata/'");
            }
            String typeName = query.segments.get(0);
            Map<String, Object> meta = MetadataProvider.loadClass(session.getRecordingPath(), typeName);
            if (meta == null) return java.util.Collections.emptyList();
            if (matchesAll(meta, query.predicates)) {
                return java.util.List.of(meta);
            } else {
                return java.util.Collections.emptyList();
            }
        } else if (query.root == Root.CHUNKS) {
            return loadChunkRows(session.getRecordingPath());
        } else if (query.root == Root.CP) {
            if (!query.segments.isEmpty()) {
                String type = query.segments.get(0);
                return loadConstantPoolEntries(session.getRecordingPath(), type, row -> matchesAll(row, query.predicates));
            } else {
                return loadConstantPoolSummary(session.getRecordingPath());
            }
        } else {
            throw new UnsupportedOperationException("Unsupported root: " + query.root);
        }
    }

    /** Evaluate events with early-stop when limit is reached; other roots fall back to {@link #evaluate}. */
    public List<Map<String, Object>> evaluateWithLimit(JFRSession session, Query query, Integer limit) throws Exception {
        if (query.root != Root.EVENTS || limit == null) {
            return evaluate(session, query);
        }
        if (query.segments.isEmpty()) {
            throw new IllegalArgumentException("Expected event type segment after 'events/'");
        }
        String eventType = query.segments.get(0);
        List<Map<String, Object>> out = new ArrayList<>();
        try (UntypedJafarParser p = io.jafar.parser.api.ParsingContext.create().newUntypedParser(session.getRecordingPath())) {
            p.handle((type, value, ctl) -> {
                if (!eventType.equals(type.getName())) return;
                @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) value;
                if (matchesAll(map, query.predicates)) {
                    out.add(Values.resolvedShallow(map));
                    if (out.size() >= limit) {
                        ctl.abort();
                    }
                }
            });
            p.run();
        }
        return out;
    }

    /** Evaluate a query projecting a specific attribute path after the event type. */
    public List<Object> evaluateValues(JFRSession session, Query query) throws Exception {
        if (query.pipeline != null && !query.pipeline.isEmpty()) {
            // For projections, aggregations handled by evaluateAggregate returning rows
            throw new UnsupportedOperationException("Use evaluate(...) for aggregation pipelines");
        }
        if (query.root == Root.EVENTS) {
            if (query.segments.isEmpty()) {
                throw new IllegalArgumentException("Expected event type segment after 'events/'");
            }
            if (query.segments.size() < 2) {
                throw new IllegalArgumentException("No projection path provided after event type");
            }
            String eventType = query.segments.get(0);
            List<String> proj = query.segments.subList(1, query.segments.size());
            List<Object> out = new ArrayList<>();
            source.streamEvents(session.getRecordingPath(), ev -> {
                if (!eventType.equals(ev.typeName)) return; // filter type
                Map<String, Object> map = ev.value;
                if (matchesAll(map, query.predicates)) {
                    Object val = Values.get(map, proj.toArray());
                    if (val != null) out.add(val);
                }
            });
            return out;
        } else if (query.root == Root.METADATA) {
            if (query.segments.isEmpty()) {
                throw new IllegalArgumentException("Expected type segment after 'metadata/'");
            }
            if (query.segments.size() < 2) {
                throw new IllegalArgumentException("No projection path provided after metadata type");
            }
            String typeName = query.segments.get(0);
            List<String> proj = query.segments.subList(1, query.segments.size());
            Map<String, Object> meta = MetadataProvider.loadClass(session.getRecordingPath(), typeName);
            if (meta == null) return java.util.Collections.emptyList();
            if (!matchesAll(meta, query.predicates)) return java.util.Collections.emptyList();
            // Special handling: 'fields[/<name>[/...]]' maps to fieldsByName
            if (!proj.isEmpty() && ("fields".equals(proj.get(0)) || proj.get(0).startsWith("fields.")) ) {
                // Support alias: fields.<name>[/...]
                if (proj.get(0).startsWith("fields.")) {
                    String rest = proj.get(0).substring("fields.".length());
                    java.util.List<String> np = new java.util.ArrayList<>();
                    np.add("fields");
                    if (!rest.isEmpty()) np.add(rest);
                    if (proj.size() > 1) np.addAll(proj.subList(1, proj.size()));
                    proj = np;
                }
                Object fbn = meta.get("fieldsByName");
                if (!(fbn instanceof java.util.Map<?,?> m)) {
                    return java.util.Collections.emptyList();
                }
                if (proj.size() == 1) {
                    java.util.List<String> names = new java.util.ArrayList<>();
                    for (Object k : m.keySet()) names.add(String.valueOf(k));
                    java.util.Collections.sort(names);
                    return new java.util.ArrayList<>(names);
                } else {
                    String fname = proj.get(1);
                    Object entry = m.get(fname);
                    if (!(entry instanceof java.util.Map<?,?> em)) return java.util.Collections.emptyList();
                    if (proj.size() == 2) {
                        @SuppressWarnings("unchecked") java.util.Map<String, Object> val = (java.util.Map<String, Object>) em;
                        return java.util.List.of(val);
                    } else {
                        java.util.List<String> rest = proj.subList(2, proj.size());
                        Object v = Values.get((java.util.Map<String, Object>) em, rest.toArray());
                        return v == null ? java.util.Collections.emptyList() : java.util.List.of(v);
                    }
                }
            }

            // Support alias: fieldsByName.<name>[/...]
            if (!proj.isEmpty() && ("fieldsByName".equals(proj.get(0)) || proj.get(0).startsWith("fieldsByName.")) ) {
                if (proj.get(0).startsWith("fieldsByName.")) {
                    String rest = proj.get(0).substring("fieldsByName.".length());
                    java.util.List<String> np = new java.util.ArrayList<>();
                    np.add("fieldsByName");
                    if (!rest.isEmpty()) np.add(rest);
                    if (proj.size() > 1) np.addAll(proj.subList(1, proj.size()));
                    proj = np;
                }
                Object fbn2 = meta.get("fieldsByName");
                if (!(fbn2 instanceof java.util.Map<?,?> m2)) return java.util.Collections.emptyList();
                if (proj.size() == 1) {
                    java.util.List<String> names = new java.util.ArrayList<>();
                    for (Object k : m2.keySet()) names.add(String.valueOf(k));
                    java.util.Collections.sort(names);
                    return new java.util.ArrayList<>(names);
                }
                String fname2 = proj.get(1);
                Object entry2 = m2.get(fname2);
                if (!(entry2 instanceof java.util.Map<?,?> em2)) return java.util.Collections.emptyList();
                if (proj.size() == 2) {
                    @SuppressWarnings("unchecked") java.util.Map<String, Object> val2 = (java.util.Map<String, Object>) em2;
                    return java.util.List.of(val2);
                }
                java.util.List<String> rest2 = proj.subList(2, proj.size());
                Object v2 = Values.get((java.util.Map<String, Object>) em2, rest2.toArray());
                return v2 == null ? java.util.Collections.emptyList() : java.util.List.of(v2);
            }

            Object v = Values.get(meta, proj.toArray());
            return v == null ? java.util.Collections.emptyList() : java.util.List.of(v);
        } else if (query.root == Root.CHUNKS) {
            if (query.segments.isEmpty()) {
                throw new IllegalArgumentException("No projection path provided after 'chunks'");
            }
            List<Map<String, Object>> rows = loadChunkRows(session.getRecordingPath());
            List<Object> out = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Object v = Values.get(row, query.segments.toArray());
                if (v != null) out.add(v);
            }
            return out;
        } else if (query.root == Root.CP) {
            if (query.segments.isEmpty()) {
                // projection from summary rows
                List<Map<String, Object>> rows = loadConstantPoolSummary(session.getRecordingPath());
                List<Object> out = new ArrayList<>();
                for (Map<String, Object> r : rows) {
                    Object v = Values.get(r, query.segments.toArray());
                    if (v != null) out.add(v);
                }
                return out;
            } else {
                // projection from entries of specific type
                String type = query.segments.get(0);
                List<Map<String, Object>> rows = loadConstantPoolEntries(session.getRecordingPath(), type, r -> matchesAll(r, query.predicates));
                List<String> proj = query.segments.subList(1, query.segments.size());
                List<Object> out = new ArrayList<>();
                for (Map<String, Object> r : rows) {
                    Object v = Values.get(r, proj.toArray());
                    if (v != null) out.add(v);
                }
                return out;
            }
        } else {
            throw new UnsupportedOperationException("Unsupported root: " + query.root);
        }
    }

    // Aggregations
    private List<Map<String, Object>> evaluateAggregate(JFRSession session, Query query) throws Exception {
        // Only a single op supported for now; chain could be added later
        JfrPath.PipelineOp op = query.pipeline.get(0);
        return switch (op) {
            case JfrPath.CountOp c -> aggregateCount(session, query);
            case JfrPath.StatsOp s -> aggregateStats(session, query, s.valuePath);
            case JfrPath.QuantilesOp q -> aggregateQuantiles(session, query, q.valuePath, q.qs);
            case JfrPath.SketchOp sk -> aggregateSketch(session, query, sk.valuePath);
        };
    }

    private List<Map<String, Object>> aggregateCount(JFRSession session, Query query) throws Exception {
        long count = 0;
        if (query.root == Root.EVENTS) {
            if (query.segments.isEmpty()) throw new IllegalArgumentException("events root requires type");
            String eventType = query.segments.get(0);
            final long[] c = new long[1];
            source.streamEvents(session.getRecordingPath(), ev -> {
                if (!eventType.equals(ev.typeName())) return;
                if (matchesAll(ev.value(), query.predicates)) c[0]++;
            });
            count = c[0];
        } else if (query.root == Root.METADATA) {
            if (query.segments.isEmpty()) throw new IllegalArgumentException("metadata root requires type");
            // base row presence as count=1 if matches, else 0; projection lists use evaluateValues
            if (query.segments.size() > 1) {
                List<Object> vals = evaluateValues(session, new Query(query.root, query.segments, query.predicates));
                count = vals.size();
            } else {
                List<Map<String, Object>> rows = evaluate(session, new Query(query.root, query.segments, query.predicates));
                count = rows.size();
            }
        } else if (query.root == Root.CHUNKS || query.root == Root.CP) {
            List<Map<String, Object>> rows = evaluate(session, new Query(query.root, query.segments, query.predicates));
            count = rows.size();
        } else {
            throw new UnsupportedOperationException("Unsupported root: " + query.root);
        }
        Map<String, Object> out = new HashMap<>();
        out.put("count", count);
        return List.of(out);
    }

    private List<Map<String, Object>> aggregateStats(JFRSession session, Query query, List<String> valuePathOverride) throws Exception {
        List<String> vpath = valuePathOverride;
        if (vpath == null || vpath.isEmpty()) {
            if (query.segments.size() < 2) throw new IllegalArgumentException("stats() requires projection or a value path");
            vpath = query.segments.subList(1, query.segments.size());
        }
        StatsAgg agg = new StatsAgg();
        if (query.root == Root.EVENTS) {
            String eventType = query.segments.get(0);
            List<String> path = vpath;
            source.streamEvents(session.getRecordingPath(), ev -> {
                if (!eventType.equals(ev.typeName())) return;
                Map<String, Object> map = ev.value();
                if (!matchesAll(map, query.predicates)) return;
                Object val = Values.get(map, path.toArray());
                if (val instanceof Number n) agg.add(n.doubleValue());
            });
        } else {
            // For non-events: derive rows or values then apply
            List<Object> vals;
            if (query.segments.size() >= 1) {
                vals = evaluateValues(session, new Query(query.root, new ArrayList<>(List.of(query.segments.get(0))).subList(0, 1), query.predicates));
                // Fallback: get values by explicitly evaluating the original query and extracting
                vals = evaluateValues(session, new Query(query.root, query.segments, query.predicates));
            } else {
                throw new IllegalArgumentException("stats() not applicable");
            }
            for (Object v : vals) if (v instanceof Number n) agg.add(n.doubleValue());
        }
        return List.of(agg.toRow());
    }

    private List<Map<String, Object>> aggregateQuantiles(JFRSession session, Query query, List<String> valuePathOverride, List<Double> qs) throws Exception {
        List<String> vpath = valuePathOverride;
        if (vpath == null || vpath.isEmpty()) {
            if (query.segments.size() < 2) throw new IllegalArgumentException("quantiles() requires projection or a value path");
            vpath = query.segments.subList(1, query.segments.size());
        }
        List<Double> values = new ArrayList<>();
        if (query.root == Root.EVENTS) {
            String eventType = query.segments.get(0);
            List<String> path = vpath;
            source.streamEvents(session.getRecordingPath(), ev -> {
                if (!eventType.equals(ev.typeName())) return;
                Map<String, Object> map = ev.value();
                if (!matchesAll(map, query.predicates)) return;
                Object val = Values.get(map, path.toArray());
                if (val instanceof Number n) values.add(n.doubleValue());
            });
        } else {
            List<Object> vals = evaluateValues(session, new Query(query.root, query.segments, query.predicates));
            for (Object v : vals) if (v instanceof Number n) values.add(n.doubleValue());
        }
        java.util.Collections.sort(values);
        Map<String, Object> row = new HashMap<>();
        row.put("count", values.size());
        for (Double q : qs) {
            if (values.isEmpty()) { row.put(pcol(q), null); continue; }
            double qv = quantileNearestRank(values, q);
            row.put(pcol(q), qv);
        }
        return List.of(row);
    }

    private List<Map<String, Object>> aggregateSketch(JFRSession session, Query query, List<String> valuePath) throws Exception {
        // Combine stats + default quantiles
        Map<String, Object> stats = aggregateStats(session, query, valuePath).get(0);
        Map<String, Object> quants = aggregateQuantiles(session, query, valuePath, List.of(0.5, 0.9, 0.99)).get(0);
        Map<String, Object> out = new HashMap<>();
        out.putAll(stats);
        out.putAll(quants);
        return List.of(out);
    }

    private static String pcol(double q) {
        int pct = (int) Math.round(q * 100);
        return "p" + pct;
    }

    private static double quantileNearestRank(List<Double> sortedValues, double q) {
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

    private static final class StatsAgg {
        long count = 0;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        double mean = 0.0;
        double m2 = 0.0; // for variance via Welford

        void add(double x) {
            count++;
            if (x < min) min = x;
            if (x > max) max = x;
            double delta = x - mean;
            mean += delta / count;
            double delta2 = x - mean;
            m2 += delta * delta2;
        }

        Map<String, Object> toRow() {
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

    /** Evaluate event projection with early-stop when limit is reached; other roots fall back to {@link #evaluateValues}. */
    public List<Object> evaluateValuesWithLimit(JFRSession session, Query query, Integer limit) throws Exception {
        if (query.root != Root.EVENTS || limit == null) {
            return evaluateValues(session, query);
        }
        if (query.segments.isEmpty()) {
            throw new IllegalArgumentException("Expected event type segment after 'events/'");
        }
        if (query.segments.size() < 2) {
            throw new IllegalArgumentException("No projection path provided after event type");
        }
        String eventType = query.segments.get(0);
        List<String> proj = query.segments.subList(1, query.segments.size());
        List<Object> out = new ArrayList<>();
        try (UntypedJafarParser p = io.jafar.parser.api.ParsingContext.create().newUntypedParser(session.getRecordingPath())) {
            p.handle((type, value, ctl) -> {
                if (!eventType.equals(type.getName())) return;
                @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) value;
                if (matchesAll(map, query.predicates)) {
                    Object val = Values.get(map, proj.toArray());
                    if (val != null) out.add(val);
                    if (out.size() >= limit) {
                        ctl.abort();
                    }
                }
            });
            p.run();
        }
        return out;
    }

    private boolean matchesAll(Map<String, Object> map, List<Predicate> predicates) {
        for (Predicate p : predicates) {
            if (p instanceof FieldPredicate fp) {
                if (!deepMatch(map, fp.fieldPath, 0, fp.op, fp.literal, fp.matchMode != null ? fp.matchMode : defaultListMatchMode)) {
                    return false;
                }
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private boolean deepMatch(Object current, List<String> path, int idx, Op op, Object lit, JfrPath.MatchMode mode) {
        if (current instanceof io.jafar.parser.api.ComplexType ct) {
            current = ct.getValue();
        }
        if (current instanceof io.jafar.parser.api.ArrayType at) {
            current = at.getArray();
        }
        if (idx >= path.size()) {
            // At leaf; apply comparison to current
            return compare(current, op, lit);
        }
        String seg = path.get(idx);
        // If current is a map-like structure
        if (current instanceof java.util.Map<?,?> m) {
            Object next = ((java.util.Map<String, Object>) m).get(seg);
            return deepMatch(next, path, idx + 1, op, lit, mode);
        }
        // If current is an array/list, apply match mode across elements without consuming seg
        if (current != null && current.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(current);
            int matches = 0;
            for (int i = 0; i < len; i++) {
                Object el = java.lang.reflect.Array.get(current, i);
                if (deepMatch(el, path, idx, op, lit, mode)) matches++;
            }
            return applyMode(matches, len, mode);
        }
        if (current instanceof java.util.Collection<?> coll) {
            int len = 0, matches = 0;
            for (Object el : coll) { len++; if (deepMatch(el, path, idx, op, lit, mode)) matches++; }
            return applyMode(matches, len, mode);
        }
        // No further navigation possible
        return false;
    }

    private static boolean applyMode(int matches, int total, JfrPath.MatchMode mode) {
        return switch (mode) {
            case ANY -> matches > 0;
            case ALL -> total > 0 && matches == total;
            case NONE -> matches == 0;
        };
    }

    private static boolean compare(Object actual, Op op, Object lit) {
        if (actual == null) return false;
        return switch (op) {
            case EQ -> Objects.equals(coerce(actual, lit), lit);
            case NE -> !Objects.equals(coerce(actual, lit), lit);
            case GT -> compareNum(actual, lit) > 0;
            case GE -> compareNum(actual, lit) >= 0;
            case LT -> compareNum(actual, lit) < 0;
            case LE -> compareNum(actual, lit) <= 0;
            case REGEX -> String.valueOf(actual).matches(String.valueOf(lit));
        };
    }

    private static Object coerce(Object actual, Object lit) {
        if (lit instanceof Number) {
            if (actual instanceof Number) return actual;
            try { return Double.parseDouble(String.valueOf(actual)); } catch (Exception ignore) {}
        }
        if (lit instanceof String) return String.valueOf(actual);
        return actual;
    }

    private static int compareNum(Object a, Object b) {
        double da = (a instanceof Number) ? ((Number) a).doubleValue() : Double.parseDouble(String.valueOf(a));
        double db = (b instanceof Number) ? ((Number) b).doubleValue() : Double.parseDouble(String.valueOf(b));
        return Double.compare(da, db);
    }

    /** Default EventSource that streams all events untyped from a recording. */
    static final class DefaultEventSource implements EventSource {
        @Override
        public void streamEvents(Path recording, Consumer<Event> consumer) throws Exception {
            try (UntypedJafarParser p = io.jafar.parser.api.ParsingContext.create().newUntypedParser(recording)) {
                p.handle((type, value, ctl) -> consumer.accept(new Event(type.getName(), value)));
                p.run();
            }
        }
    }

    // Metadata is provided by MetadataProvider; keep a public wrapper for compatibility
    
    /** Load metadata for a single field name under a type. */
    public Map<String, Object> loadFieldMetadata(Path recording, String typeName, String fieldName) throws Exception {
        return MetadataProvider.loadField(recording, typeName, fieldName);
    }

    private static List<Map<String, Object>> loadChunkRows(Path recording) throws Exception {
        final List<Map<String, Object>> rows = java.util.Collections.synchronizedList(new ArrayList<>());
        try (StreamingChunkParser parser = new StreamingChunkParser(new UntypedParserContextFactory())) {
            parser.parse(recording, new ChunkParserListener() {
                @Override
                public boolean onChunkStart(ParserContext context, int chunkIndex, io.jafar.parser.internal_api.ChunkHeader header) {
                    Map<String, Object> m = new HashMap<>();
                    m.put("index", chunkIndex);
                    m.put("offset", header.offset);
                    m.put("size", header.size);
                    m.put("startNanos", header.startNanos);
                    m.put("duration", header.duration);
                    m.put("compressed", header.compressed);
                    rows.add(m);
                    return true;
                }

                @Override
                public boolean onMetadata(ParserContext context, MetadataEvent metadata) { return true; }
            });
        }
        return rows;
    }

    private static List<Map<String, Object>> loadConstantPoolSummary(Path recording) throws Exception {
        final Map<String, Long> sizes = new HashMap<>();
        try (StreamingChunkParser parser = new StreamingChunkParser(new UntypedParserContextFactory())) {
            parser.parse(recording, new ChunkParserListener() {
                @Override
                public boolean onCheckpoint(ParserContext context, io.jafar.parser.internal_api.CheckpointEvent event) {
                    context.getConstantPools().pools().forEach(cp -> {
                        String name = cp.getType().getName();
                        long count = extractConstantPoolCount(cp);
                        sizes.merge(name, count, Long::sum);
                    });
                    return true;
                }
            });
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, Long> e : sizes.entrySet()) {
            Map<String, Object> m = new HashMap<>();
            m.put("name", e.getKey());
            m.put("totalSize", e.getValue());
            rows.add(m);
        }
        // sort by size desc
        rows.sort((a,b) -> Long.compare((long)b.get("totalSize"), (long)a.get("totalSize")));
        return rows;
    }

    private static List<Map<String, Object>> loadConstantPoolEntries(Path recording, String typeName,
                                                                     java.util.function.Predicate<Map<String,Object>> rowFilter) throws Exception {
        final List<Map<String, Object>> rows = new ArrayList<>();
        final java.util.function.BiConsumer<io.jafar.parser.api.ConstantPool, String> drain = (cp, tn) -> {
            if (!cp.getType().getName().equals(tn)) return;
            try { cp.ensureIndexed(); } catch (Throwable ignore) {}
            java.util.Iterator<Long> it = cp.ids();
            while (it.hasNext()) {
                long id = it.next();
                Object val = cp.get(id);
                Map<String, Object> row = new HashMap<>();
                row.put("id", id);
                if (val instanceof Map<?,?> map) {
                    @SuppressWarnings("unchecked") Map<String,Object> mv = (Map<String,Object>) map;
                    row.putAll(mv);
                } else {
                    row.put("value", val);
                }
                if (rowFilter == null || rowFilter.test(row)) rows.add(row);
            }
        };

        try (StreamingChunkParser parser = new StreamingChunkParser(new UntypedParserContextFactory())) {
            parser.parse(recording, new ChunkParserListener() {
                @Override
                public boolean onChunkStart(ParserContext context, int chunkIndex, ChunkHeader header) {
                    MapValueBuilder mvb = new MapValueBuilder(context);
                    GenericValueReader reader = new GenericValueReader(mvb);
                    context.put(GenericValueReader.class, reader);
                    return ChunkParserListener.super.onChunkStart(context, chunkIndex, header);
                }

                @Override
                public boolean onChunkEnd(ParserContext context, int chunkIndex, boolean skipped) {
                    context.remove(GenericValueReader.class);
                    return ChunkParserListener.super.onChunkEnd(context, chunkIndex, skipped);
                }

                @Override
                public boolean onMetadata(ParserContext context, MetadataEvent metadata) {
                    try { context.getConstantPools().pools().forEach(cp -> drain.accept(cp, typeName)); } catch (Throwable ignore) {}
                    return true;
                }

                @Override
                public boolean onCheckpoint(ParserContext context, io.jafar.parser.internal_api.CheckpointEvent event) {
                    context.getConstantPools().pools().forEach(cp -> drain.accept(cp, typeName));
                    return true;
                }
            });
        }
        return rows;
    }

    private static long extractConstantPoolCount(io.jafar.parser.api.ConstantPool cp) {
        try {
            Object impl = cp;
            java.lang.reflect.Field f = impl.getClass().getDeclaredField("offsets");
            f.setAccessible(true);
            Object offsets = f.get(impl);
            try { return (int) offsets.getClass().getMethod("size").invoke(offsets); } catch (Throwable ignore) {}
        } catch (Throwable ignore) {}
        // Fallback to size() (may be zero if not materialized yet)
        return cp.size();
    }
}
