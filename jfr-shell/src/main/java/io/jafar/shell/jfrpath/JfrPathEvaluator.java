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
import io.jafar.shell.providers.ChunkProvider;
import io.jafar.shell.providers.ConstantPoolProvider;
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
            if (!query.segments.isEmpty()) {
                // show chunks/0 - specific chunk by ID
                try {
                    int chunkId = Integer.parseInt(query.segments.get(0));
                    Map<String, Object> chunk = ChunkProvider.loadChunk(session.getRecordingPath(), chunkId);
                    if (chunk == null) return java.util.Collections.emptyList();
                    return matchesAll(chunk, query.predicates) ? java.util.List.of(chunk) : java.util.Collections.emptyList();
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid chunk index: " + query.segments.get(0));
                }
            }
            // show chunks or show chunks[filter]
            if (query.predicates.isEmpty()) {
                return ChunkProvider.loadAllChunks(session.getRecordingPath());
            } else {
                return ChunkProvider.loadChunks(session.getRecordingPath(), row -> matchesAll(row, query.predicates));
            }
        } else if (query.root == Root.CP) {
            if (!query.segments.isEmpty()) {
                String type = query.segments.get(0);
                return ConstantPoolProvider.loadEntries(session.getRecordingPath(), type, row -> matchesAll(row, query.predicates));
            } else {
                return ConstantPoolProvider.loadSummary(session.getRecordingPath());
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
                    extractWithIndexing(map, proj, out);
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

            List<Object> out = new ArrayList<>();
            extractWithIndexing(meta, proj, out);
            return out;
        } else if (query.root == Root.CHUNKS) {
            if (query.segments.isEmpty()) {
                throw new IllegalArgumentException("No projection path provided after 'chunks'");
            }
            List<Map<String, Object>> rows = ChunkProvider.loadAllChunks(session.getRecordingPath());
            List<Object> out = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                extractWithIndexing(row, query.segments, out);
            }
            return out;
        } else if (query.root == Root.CP) {
            if (query.segments.isEmpty()) {
                // projection from summary rows
                List<Map<String, Object>> rows = ConstantPoolProvider.loadSummary(session.getRecordingPath());
                List<Object> out = new ArrayList<>();
                for (Map<String, Object> r : rows) {
                    Object v = Values.get(r, query.segments.toArray());
                    if (v != null) out.add(v);
                }
                return out;
            } else {
                // projection from entries of specific type
                String type = query.segments.get(0);
                List<Map<String, Object>> rows = ConstantPoolProvider.loadEntries(session.getRecordingPath(), type, r -> matchesAll(r, query.predicates));
                List<String> proj = query.segments.subList(1, query.segments.size());
                List<Object> out = new ArrayList<>();
                for (Map<String, Object> r : rows) {
                    extractWithIndexing(r, proj, out);
                }
                return out;
            }
        } else {
            throw new UnsupportedOperationException("Unsupported root: " + query.root);
        }
    }

    // Indexing and tail-slice support for projection. Slice [start:end] supported only on last segment.
    private void extractWithIndexing(Map<String, Object> root, List<String> proj, List<Object> out) {
        if (proj.isEmpty()) return;
        String last = proj.get(proj.size() - 1);
        Slice sl = parseSlice(last);
        if (sl != null) {
            // Resolve up to base of last segment
            String base = baseName(last);
            List<String> head = new java.util.ArrayList<>(proj);
            head.set(head.size() - 1, base);
            Object arrVal = Values.get(root, buildPathTokens(head).toArray());
            Object arr = unwrapArrayLike(arrVal);
            if (arr == null) return;
            int len = java.lang.reflect.Array.getLength(arr);
            int from = Math.max(0, sl.start());
            int to = sl.end() < 0 ? len : Math.min(len, sl.end());
            for (int i = from; i < to; i++) {
                out.add(java.lang.reflect.Array.get(arr, i));
            }
            return;
        }
        // If the parent of the last segment resolves to an array/list and the last is a simple
        // field name, project that field from each element.
        if (proj.size() >= 2) {
            String baseName = baseName(last);
            boolean hasIndexOrSlice = last.contains("[");
            if (!hasIndexOrSlice) {
                List<String> head = new java.util.ArrayList<>(proj.subList(0, proj.size() - 1));
                Object parent = Values.get(root, buildPathTokens(head).toArray());
                Object arr = unwrapArrayLike(parent);
                if (arr != null) {
                    int len = java.lang.reflect.Array.getLength(arr);
                    for (int i = 0; i < len; i++) {
                        Object el = java.lang.reflect.Array.get(arr, i);
                        if (el instanceof io.jafar.parser.api.ComplexType ct) el = ct.getValue();
                        if (el instanceof java.util.Map<?,?> m) {
                            @SuppressWarnings("unchecked") java.util.Map<String, Object> mm = (java.util.Map<String, Object>) m;
                            Object v = mm.get(baseName);
                            if (v != null) out.add(v);
                        }
                    }
                    return;
                }
            }
        }
        Object v = Values.get(root, buildPathTokens(proj).toArray());
        if (v != null) out.add(v);
    }

    private static java.util.List<Object> buildPathTokens(List<String> segs) {
        java.util.List<Object> tokens = new java.util.ArrayList<>();
        for (String seg : segs) {
            int b = seg.indexOf('[');
            if (b < 0) { tokens.add(seg); continue; }
            String name = seg.substring(0, b);
            if (!name.isEmpty()) tokens.add(name);
            int e = seg.lastIndexOf(']');
            if (e > b) {
                String inside = seg.substring(b + 1, e);
                if (!inside.contains(":")) {
                    try { tokens.add(Integer.parseInt(inside.trim())); } catch (Exception ignore) {}
                } else {
                    // slice only supported at tail; ignore here
                }
            }
        }
        return tokens;
    }

    private static String baseName(String seg) {
        int b = seg.indexOf('[');
        return b < 0 ? seg : seg.substring(0, b);
    }

    private static Slice parseSlice(String seg) {
        int b = seg.indexOf('[');
        int e = seg.endsWith("]") ? seg.lastIndexOf(']') : -1;
        if (b < 0 || e < b) return null;
        String inside = seg.substring(b + 1, e);
        int c = inside.indexOf(':');
        if (c < 0) return null;
        int from = 0, to = -1;
        String a = inside.substring(0, c).trim();
        String bb = inside.substring(c + 1).trim();
        if (!a.isEmpty()) try { from = Integer.parseInt(a); } catch (Exception ignore) {}
        if (!bb.isEmpty()) try { to = Integer.parseInt(bb); } catch (Exception ignore) {}
        return new Slice(from, to);
    }

    private static Object unwrapArrayLike(Object v) {
        if (v == null) return null;
        if (v.getClass().isArray()) return v;
        if (v instanceof java.util.Collection<?> c) return c.toArray();
        if (v instanceof io.jafar.parser.api.ArrayType at) return at.getArray();
        return null;
    }

    private record Slice(int start, int end) {}

    // Aggregations
    private List<Map<String, Object>> evaluateAggregate(JFRSession session, Query query) throws Exception {
        // Only a single op supported for now; chain could be added later
        JfrPath.PipelineOp op = query.pipeline.get(0);
        return switch (op) {
            case JfrPath.CountOp c -> aggregateCount(session, query);
            case JfrPath.StatsOp s -> aggregateStats(session, query, s.valuePath);
            case JfrPath.QuantilesOp q -> aggregateQuantiles(session, query, q.valuePath, q.qs);
            case JfrPath.SketchOp sk -> aggregateSketch(session, query, sk.valuePath);
            case JfrPath.LenOp ln -> aggregateLen(session, query, ln.valuePath);
            case JfrPath.UppercaseOp up -> aggregateStringTransform(session, query, up.valuePath, "uppercase");
            case JfrPath.LowercaseOp lo -> aggregateStringTransform(session, query, lo.valuePath, "lowercase");
            case JfrPath.TrimOp tr -> aggregateStringTransform(session, query, tr.valuePath, "trim");
            case JfrPath.AbsOp ab -> aggregateNumberTransform(session, query, ab.valuePath, "abs");
            case JfrPath.RoundOp ro -> aggregateNumberTransform(session, query, ro.valuePath, "round");
            case JfrPath.FloorOp flo -> aggregateNumberTransform(session, query, flo.valuePath, "floor");
            case JfrPath.CeilOp cei -> aggregateNumberTransform(session, query, cei.valuePath, "ceil");
            case JfrPath.ContainsOp co -> aggregateStringPredicate(session, query, co.valuePath, "contains", co.substr);
            case JfrPath.ReplaceOp rp -> aggregateStringReplace(session, query, rp.valuePath, rp.target, rp.replacement);
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

    private List<Map<String, Object>> aggregateLen(JFRSession session, Query query, List<String> valuePathOverride) throws Exception {
        List<String> vpath = valuePathOverride;
        if (vpath == null || vpath.isEmpty()) {
            if (query.segments.size() < 2) throw new IllegalArgumentException("len() requires projection or a value path");
            vpath = query.segments.subList(1, query.segments.size());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        final List<String> path = vpath;
        java.util.function.Consumer<Object> addLen = (val) -> {
            if (val == null) {
                Map<String, Object> row = new HashMap<>();
                row.put("len", null);
                out.add(row);
                return;
            }
            Integer len = valueLength(val);
            if (len == null) {
                throw new IllegalArgumentException("len() expects string or array/list, got " + val.getClass().getName());
            }
            Map<String, Object> row = new HashMap<>();
            row.put("len", len);
            out.add(row);
        };
        if (query.root == Root.EVENTS) {
            if (query.segments.isEmpty()) throw new IllegalArgumentException("events root requires type");
            String eventType = query.segments.get(0);
            source.streamEvents(session.getRecordingPath(), ev -> {
                if (!eventType.equals(ev.typeName())) return;
                Map<String, Object> map = ev.value();
                if (!matchesAll(map, query.predicates)) return;
                Object val = Values.get(map, path.toArray());
                addLen.accept(val);
            });
        } else {
            // For non-events, leverage evaluateValues with the original query to get a list of values
            List<Object> vals = evaluateValues(session, new Query(query.root, query.segments, query.predicates));
            for (Object v : vals) addLen.accept(v);
        }
        return out;
    }

    private static Integer valueLength(Object val) {
        if (val == null) return null;
        if (val instanceof CharSequence s) return s.length();
        if (val instanceof java.util.Collection<?> c) return c.size();
        Object arr = val;
        if (arr.getClass().isArray()) return java.lang.reflect.Array.getLength(arr);
        return null;
    }

    private List<Map<String, Object>> aggregateStringTransform(JFRSession session, Query query, List<String> valuePathOverride, String opName) throws Exception {
        List<String> vpath = valuePathOverride;
        if (vpath == null || vpath.isEmpty()) {
            if (query.segments.size() < 2) throw new IllegalArgumentException(opName + "() requires projection or a value path");
            vpath = query.segments.subList(1, query.segments.size());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        final List<String> path = vpath;
        java.util.function.Consumer<Object> addTransformed = (val) -> {
            if (val == null) {
                Map<String, Object> row = new HashMap<>(); row.put("value", null); out.add(row); return;
            }
            if (!(val instanceof CharSequence s)) {
                throw new IllegalArgumentException(opName + "() expects string, got " + val.getClass().getName());
            }
            String res = switch (opName) {
                case "uppercase" -> s.toString().toUpperCase(java.util.Locale.ROOT);
                case "lowercase" -> s.toString().toLowerCase(java.util.Locale.ROOT);
                case "trim" -> s.toString().trim();
                default -> throw new IllegalArgumentException("Unsupported op: " + opName);
            };
            Map<String, Object> row = new HashMap<>(); row.put("value", res); out.add(row);
        };
        if (query.root == Root.EVENTS) {
            if (query.segments.isEmpty()) throw new IllegalArgumentException("events root requires type");
            String eventType = query.segments.get(0);
            source.streamEvents(session.getRecordingPath(), ev -> {
                if (!eventType.equals(ev.typeName())) return;
                Map<String, Object> map = ev.value();
                if (!matchesAll(map, query.predicates)) return;
                Object val = Values.get(map, path.toArray());
                addTransformed.accept(val);
            });
        } else {
            List<Object> vals = evaluateValues(session, new Query(query.root, query.segments, query.predicates));
            for (Object v : vals) addTransformed.accept(v);
        }
        return out;
    }

    private List<Map<String, Object>> aggregateNumberTransform(JFRSession session, Query query, List<String> valuePathOverride, String opName) throws Exception {
        List<String> vpath = valuePathOverride;
        if (vpath == null || vpath.isEmpty()) {
            if (query.segments.size() < 2) throw new IllegalArgumentException(opName + "() requires projection or a value path");
            vpath = query.segments.subList(1, query.segments.size());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        final List<String> path = vpath;
        java.util.function.Consumer<Object> addTransformed = (val) -> {
            if (val == null) { Map<String, Object> row = new HashMap<>(); row.put("value", null); out.add(row); return; }
            if (!(val instanceof Number n)) {
                throw new IllegalArgumentException(opName + "() expects number, got " + val.getClass().getName());
            }
            Number res;
            switch (opName) {
                case "abs" -> {
                    if (n instanceof Integer) res = Math.abs(n.intValue());
                    else if (n instanceof Long) res = Math.abs(n.longValue());
                    else if (n instanceof Short) res = (short) Math.abs(n.shortValue());
                    else if (n instanceof Byte) res = (byte) Math.abs(n.byteValue());
                    else if (n instanceof Float) res = Math.abs(n.floatValue());
                    else res = Math.abs(n.doubleValue());
                }
                case "round" -> {
                    if (n instanceof Float || n instanceof Double) res = Math.round(n.doubleValue());
                    else res = n.longValue();
                }
                case "floor" -> res = Math.floor(n.doubleValue());
                case "ceil" -> res = Math.ceil(n.doubleValue());
                default -> throw new IllegalArgumentException("Unsupported op: " + opName);
            }
            Map<String, Object> row = new HashMap<>(); row.put("value", res); out.add(row);
        };
        if (query.root == Root.EVENTS) {
            if (query.segments.isEmpty()) throw new IllegalArgumentException("events root requires type");
            String eventType = query.segments.get(0);
            source.streamEvents(session.getRecordingPath(), ev -> {
                if (!eventType.equals(ev.typeName())) return;
                Map<String, Object> map = ev.value();
                if (!matchesAll(map, query.predicates)) return;
                Object val = Values.get(map, path.toArray());
                addTransformed.accept(val);
            });
        } else {
            List<Object> vals = evaluateValues(session, new Query(query.root, query.segments, query.predicates));
            for (Object v : vals) addTransformed.accept(v);
        }
        return out;
    }

    private List<Map<String, Object>> aggregateStringPredicate(JFRSession session, Query query, List<String> valuePathOverride, String opName, String arg) throws Exception {
        if (arg == null) throw new IllegalArgumentException(opName + "() requires a string argument");
        List<String> vpath = valuePathOverride;
        if (vpath == null || vpath.isEmpty()) {
            if (query.segments.size() < 2) throw new IllegalArgumentException(opName + "() requires projection or a value path");
            vpath = query.segments.subList(1, query.segments.size());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        final List<String> path = vpath;
        java.util.function.Consumer<Object> add = (val) -> {
            Boolean res = null;
            if (val == null) res = null;
            else if (val instanceof CharSequence s) res = s.toString().contains(arg);
            else throw new IllegalArgumentException(opName + "() expects string, got " + val.getClass().getName());
            Map<String, Object> row = new HashMap<>(); row.put("value", res); out.add(row);
        };
        if (query.root == Root.EVENTS) {
            String eventType = query.segments.get(0);
            source.streamEvents(session.getRecordingPath(), ev -> {
                if (!eventType.equals(ev.typeName())) return;
                Map<String, Object> map = ev.value();
                if (!matchesAll(map, query.predicates)) return;
                Object val = Values.get(map, path.toArray());
                add.accept(val);
            });
        } else {
            List<Object> vals = evaluateValues(session, new Query(query.root, query.segments, query.predicates));
            for (Object v : vals) add.accept(v);
        }
        return out;
    }

    private List<Map<String, Object>> aggregateStringReplace(JFRSession session, Query query, List<String> valuePathOverride, String target, String repl) throws Exception {
        if (target == null || repl == null) throw new IllegalArgumentException("replace() requires target and replacement strings");
        List<String> vpath = valuePathOverride;
        if (vpath == null || vpath.isEmpty()) {
            if (query.segments.size() < 2) throw new IllegalArgumentException("replace() requires projection or a value path");
            vpath = query.segments.subList(1, query.segments.size());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        final List<String> path = vpath;
        java.util.function.Consumer<Object> add = (val) -> {
            String res = null;
            if (val == null) res = null;
            else if (val instanceof CharSequence s) res = s.toString().replace(target, repl);
            else throw new IllegalArgumentException("replace() expects string, got " + val.getClass().getName());
            Map<String, Object> row = new HashMap<>(); row.put("value", res); out.add(row);
        };
        if (query.root == Root.EVENTS) {
            String eventType = query.segments.get(0);
            source.streamEvents(session.getRecordingPath(), ev -> {
                if (!eventType.equals(ev.typeName())) return;
                Map<String, Object> map = ev.value();
                if (!matchesAll(map, query.predicates)) return;
                Object val = Values.get(map, path.toArray());
                add.accept(val);
            });
        } else {
            List<Object> vals = evaluateValues(session, new Query(query.root, query.segments, query.predicates));
            for (Object v : vals) add.accept(v);
        }
        return out;
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
            } else if (p instanceof io.jafar.shell.jfrpath.JfrPath.ExprPredicate ep) {
                if (!evalBoolExpr(map, ep.expr)) return false;
            }
        }
        return true;
    }

    private boolean evalBoolExpr(Map<String, Object> root, io.jafar.shell.jfrpath.JfrPath.BoolExpr expr) {
        if (expr instanceof io.jafar.shell.jfrpath.JfrPath.CompExpr ce) {
            Object val = evalValueExpr(root, ce.lhs);
            return compare(val, ce.op, ce.literal);
        } else if (expr instanceof io.jafar.shell.jfrpath.JfrPath.FuncBoolExpr fb) {
            String n = fb.name.toLowerCase(java.util.Locale.ROOT);
            java.util.List<io.jafar.shell.jfrpath.JfrPath.Arg> args = fb.args;
            switch (n) {
                case "contains" -> { ensureArgs(args,2); Object s = resolveArg(root,args.get(0)); String sub = String.valueOf(resolveArg(root,args.get(1))); return s != null && String.valueOf(s).contains(sub); }
                case "starts_with" -> { ensureArgs(args,2); Object s = resolveArg(root,args.get(0)); String pre = String.valueOf(resolveArg(root,args.get(1))); return s != null && String.valueOf(s).startsWith(pre); }
                case "ends_with" -> { ensureArgs(args,2); Object s = resolveArg(root,args.get(0)); String suf = String.valueOf(resolveArg(root,args.get(1))); return s != null && String.valueOf(s).endsWith(suf); }
                case "matches" -> { ensureArgsMin(args,2); Object s = resolveArg(root,args.get(0)); String re = String.valueOf(resolveArg(root,args.get(1))); int flags = 0; if (args.size()>=3 && "i".equalsIgnoreCase(String.valueOf(resolveArg(root,args.get(2))))) flags = java.util.regex.Pattern.CASE_INSENSITIVE; return s != null && java.util.regex.Pattern.compile(re, flags).matcher(String.valueOf(s)).find(); }
                case "exists" -> { ensureArgs(args,1); return resolveArg(root,args.get(0)) != null; }
                case "empty" -> { ensureArgs(args,1); Object v = resolveArg(root,args.get(0)); if (v==null) return true; if (v instanceof CharSequence cs) return cs.length()==0; Object arr = unwrapArrayLike(v); if (arr != null) return java.lang.reflect.Array.getLength(arr)==0; return false; }
                case "between" -> { ensureArgs(args,3); Object v = resolveArg(root,args.get(0)); if (!(v instanceof Number)) return false; double x=((Number)v).doubleValue(); double a = toDouble(resolveArg(root,args.get(1))); double b = toDouble(resolveArg(root,args.get(2))); return x>=a && x<=b; }
                default -> throw new IllegalArgumentException("Unknown function in filter: " + fb.name);
            }
        } else if (expr instanceof io.jafar.shell.jfrpath.JfrPath.LogicalExpr le) {
            boolean l = evalBoolExpr(root, le.left);
            if (le.op == io.jafar.shell.jfrpath.JfrPath.LogicalExpr.Lop.AND) return l && evalBoolExpr(root, le.right);
            else return l || evalBoolExpr(root, le.right);
        } else if (expr instanceof io.jafar.shell.jfrpath.JfrPath.NotExpr ne) {
            return !evalBoolExpr(root, ne.inner);
        }
        return false;
    }

    private static void ensureArgs(java.util.List<io.jafar.shell.jfrpath.JfrPath.Arg> args, int n) { if (args.size() != n) throw new IllegalArgumentException("Function expects " + n + " args"); }
    private static void ensureArgsMin(java.util.List<io.jafar.shell.jfrpath.JfrPath.Arg> args, int n) { if (args.size() < n) throw new IllegalArgumentException("Function expects at least " + n + " args"); }
    private static double toDouble(Object o) { return (o instanceof Number) ? ((Number) o).doubleValue() : Double.parseDouble(String.valueOf(o)); }

    private Object evalValueExpr(Map<String, Object> root, io.jafar.shell.jfrpath.JfrPath.ValueExpr vexpr) {
        if (vexpr instanceof io.jafar.shell.jfrpath.JfrPath.PathRef pr) {
            return Values.get(root, buildPathTokens(pr.path).toArray());
        } else if (vexpr instanceof io.jafar.shell.jfrpath.JfrPath.FuncValueExpr fv) {
            String n = fv.name.toLowerCase(java.util.Locale.ROOT);
            java.util.List<io.jafar.shell.jfrpath.JfrPath.Arg> args = fv.args;
            switch (n) {
                case "len" -> { ensureArgs(args,1); Object v = resolveArg(root,args.get(0)); Integer L = valueLength(v); return L; }
                default -> throw new IllegalArgumentException("Unknown value function: " + fv.name);
            }
        }
        return null;
    }

    private Object resolveArg(Map<String, Object> root, io.jafar.shell.jfrpath.JfrPath.Arg arg) {
        if (arg instanceof io.jafar.shell.jfrpath.JfrPath.LiteralArg la) return la.value;
        if (arg instanceof io.jafar.shell.jfrpath.JfrPath.PathArg pa) return Values.get(root, buildPathTokens(pa.path).toArray());
        return null;
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
        // Parse possible index/slice syntax in segment
        String segName = seg;
        Integer segIndex = null;
        int b = seg.indexOf('[');
        int e = seg.endsWith("]") ? seg.lastIndexOf(']') : -1;
        Integer sliceFrom = null, sliceTo = null;
        if (b >= 0 && e > b) {
            segName = seg.substring(0, b);
            String inside = seg.substring(b + 1, e);
            int c = inside.indexOf(':');
            if (c >= 0) {
                try {
                    String a = inside.substring(0, c).trim();
                    String bb = inside.substring(c + 1).trim();
                    sliceFrom = a.isEmpty() ? 0 : Integer.parseInt(a);
                    sliceTo = bb.isEmpty() ? Integer.MAX_VALUE : Integer.parseInt(bb);
                } catch (Exception ignore) {}
            } else {
                try { segIndex = Integer.parseInt(inside.trim()); } catch (Exception ignore) {}
            }
        }
        // If current is a map-like structure
        if (current instanceof java.util.Map<?,?> m) {
            Object next = ((java.util.Map<String, Object>) m).get(segName);
            if (segIndex != null) {
                Object arr = unwrapArrayLike(next);
                if (arr == null) return false;
                int len = java.lang.reflect.Array.getLength(arr);
                if (segIndex < 0 || segIndex >= len) return false;
                next = java.lang.reflect.Array.get(arr, segIndex);
            } else if (sliceFrom != null) {
                Object arr = unwrapArrayLike(next);
                if (arr == null) return false;
                int len = java.lang.reflect.Array.getLength(arr);
                int from = Math.max(0, sliceFrom);
                int to = Math.min(len, sliceTo == null ? len : sliceTo);
                int matches = 0, total = Math.max(0, to - from);
                for (int i = from; i < to; i++) {
                    Object el = java.lang.reflect.Array.get(arr, i);
                    if (deepMatch(el, path, idx + 1, op, lit, mode)) matches++;
                }
                return applyMode(matches, total, mode);
            }
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

}
