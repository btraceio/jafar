package io.jafar.shell.jfrpath;

import io.jafar.parser.api.ParserContext;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.api.Values;
import io.jafar.parser.impl.UntypedParserContextFactory;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.StreamingChunkParser;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.internal_api.metadata.MetadataEvent;
import io.jafar.parser.internal_api.metadata.MetadataField;
import io.jafar.shell.JFRSession;

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

    public JfrPathEvaluator() {
        this(new DefaultEventSource());
    }

    public JfrPathEvaluator(EventSource source) {
        this.source = Objects.requireNonNull(source);
    }

    public List<Map<String, Object>> evaluate(JFRSession session, Query query) throws Exception {
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
            Map<String, Object> meta = loadMetadataRow(session.getRecordingPath(), typeName);
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
                return loadConstantPoolEntries(session.getRecordingPath(), type);
            } else {
                return loadConstantPoolSummary(session.getRecordingPath());
            }
        } else {
            throw new UnsupportedOperationException("Unsupported root: " + query.root);
        }
    }

    /** Evaluate a query projecting a specific attribute path after the event type. */
    public List<Object> evaluateValues(JFRSession session, Query query) throws Exception {
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
            Map<String, Object> meta = loadMetadataRow(session.getRecordingPath(), typeName);
            if (meta == null) return java.util.Collections.emptyList();
            if (!matchesAll(meta, query.predicates)) return java.util.Collections.emptyList();
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
                List<Map<String, Object>> rows = loadConstantPoolEntries(session.getRecordingPath(), type);
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

    private static boolean matchesAll(Map<String, Object> map, List<Predicate> predicates) {
        for (Predicate p : predicates) {
            if (p instanceof FieldPredicate fp) {
                Object value = Values.get(map, fp.fieldPath.toArray());
                if (!compare(value, fp.op, fp.literal)) return false;
            }
        }
        return true;
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

    // Build a lightweight metadata row for a given type name
    private static Map<String, Object> loadMetadataRow(Path recording, String typeName) throws Exception {
        final AtomicReference<Map<String, Object>> ref = new AtomicReference<>();
        try (StreamingChunkParser parser = new StreamingChunkParser(new UntypedParserContextFactory())) {
            parser.parse(recording, new ChunkParserListener() {
                @Override
                public boolean onMetadata(ParserContext context, MetadataEvent metadata) {
                    Map<String, MetadataClass> byName = new HashMap<>();
                    for (MetadataClass mc : metadata.getClasses()) byName.put(mc.getName(), mc);
                    MetadataClass clazz = byName.get(typeName);
                    if (clazz != null) {
                        Map<String, Object> meta = new HashMap<>();
                        meta.put("name", clazz.getName());
                        meta.put("superType", clazz.getSuperType());

                        // Collect class-level annotations and settings via a visitor
                        java.util.List<String> classAnn = new java.util.ArrayList<>();
                        java.util.List<String> classAnnFull = new java.util.ArrayList<>();
                        java.util.Map<String, java.util.Map<String, Object>> settingsByName = new java.util.HashMap<>();
                        java.util.List<String> settingsDisplay = new java.util.ArrayList<>();
                        final boolean[] inField = new boolean[]{false};
                        clazz.accept(new io.jafar.parser.internal_api.metadata.MetadataVisitor() {
                            @Override
                            public void visitField(MetadataField field) { inField[0] = true; }
                            @Override
                            public void visitEnd(MetadataField field) { inField[0] = false; }
                            @Override
                            public void visitAnnotation(io.jafar.parser.internal_api.metadata.MetadataAnnotation a) {
                                if (inField[0]) return; // ignore field annotations here
                                try {
                                    long id = a.getClassId();
                                    String an = a.getType() != null ? a.getType().getName() : String.valueOf(id);
                                    String simple = an.substring(an.lastIndexOf('.') + 1);
                                    String val = a.getValue();
                                    classAnnFull.add(an + (val != null && !val.isEmpty() ? ("(" + val + ")") : ""));
                                    classAnn.add("@" + simple + (val != null && !val.isEmpty() ? ("(" + val + ")") : ""));
                                } catch (Throwable ignore) {}
                            }
                        });
                        // Reflectively read class-level settings since the type is not public
                        try {
                            java.lang.reflect.Field sf = clazz.getClass().getDeclaredField("settings");
                            sf.setAccessible(true);
                            Object settingsObj = sf.get(clazz);
                            if (settingsObj instanceof java.util.Map<?,?> map) {
                                for (var e : map.entrySet()) {
                                    String sName = String.valueOf(e.getKey());
                                    Object setting = e.getValue();
                                    String st = "";
                                    String def = null;
                                    try {
                                        java.lang.reflect.Method getType = setting.getClass().getMethod("getType");
                                        Object t = getType.invoke(setting);
                                        java.lang.reflect.Method getName = t.getClass().getMethod("getName");
                                        st = String.valueOf(getName.invoke(t));
                                    } catch (Throwable ignore) {}
                                    try {
                                        java.lang.reflect.Method getValue = setting.getClass().getMethod("getValue");
                                        def = (String) getValue.invoke(setting);
                                    } catch (Throwable ignore) {}
                                    java.util.HashMap<String, Object> sm = new java.util.HashMap<>();
                                    sm.put("name", sName);
                                    sm.put("type", st);
                                    sm.put("defaultValue", def);
                                    settingsByName.put(sName, sm);
                                    settingsDisplay.add(sName + ":" + st + (def != null ? (" (" + def + ")") : ""));
                                }
                            }
                        } catch (Throwable ignore) {}
                        meta.put("classAnnotations", classAnn);
                        meta.put("classAnnotationsFull", classAnnFull);
                        meta.put("settingsByName", settingsByName);
                        meta.put("settings", settingsDisplay);

                        // Fields with annotations
                        Map<String, Map<String, Object>> fieldsByName = new HashMap<>();
                        List<String> fieldsDisplay = new ArrayList<>();
                        for (MetadataField f : clazz.getFields()) {
                            Map<String, Object> fm = new HashMap<>();
                            String fName = f.getName();
                            String fType = f.getType() != null ? f.getType().getName() : String.valueOf(f.getTypeId());
                            int dim = Math.max(-1, f.getDimension());
                            String dimSuffix = dim > 0 ? "[]".repeat(dim) : "";
                            fm.put("name", fName);
                            fm.put("type", fType);
                            fm.put("dimension", dim);
                            fm.put("hasConstantPool", f.hasConstantPool());
                            // Collect field annotations via visitor (including nested)
                            java.util.List<String> ann = new java.util.ArrayList<>();
                            java.util.List<String> annFull = new java.util.ArrayList<>();
                            f.accept(new io.jafar.parser.internal_api.metadata.MetadataVisitor() {
                                @Override
                                public void visitAnnotation(io.jafar.parser.internal_api.metadata.MetadataAnnotation a) {
                                    try {
                                        long id = a.getClassId();
                                        String an = a.getType() != null ? a.getType().getName() : String.valueOf(id);
                                        String simple = an.substring(an.lastIndexOf('.') + 1);
                                        String val = a.getValue();
                                        String disp = "@" + simple + (val != null && !val.isEmpty() ? ("(" + val + ")") : "");
                                        ann.add(disp);
                                        annFull.add(an + (val != null && !val.isEmpty() ? ("(" + val + ")") : ""));
                                    } catch (Throwable ignore) {}
                                }
                            });
                            fm.put("annotations", ann);
                            fm.put("annotationsFull", annFull);
                            fieldsByName.put(fName, fm);
                            String disp = fName + ":" + fType + dimSuffix + (ann.isEmpty() ? "" : (" " + String.join(" ", ann)));
                            fieldsDisplay.add(disp);
                        }
                        meta.put("fieldsByName", fieldsByName);
                        meta.put("fields", fieldsDisplay);
                        meta.put("fieldCount", fieldsDisplay.size());
                        ref.set(meta);
                    }
                    return false; // we can abort once metadata processed
                }
            });
        }
        return ref.get();
    }
    
    /** Load metadata for a single field name under a type. */
    public Map<String, Object> loadFieldMetadata(Path recording, String typeName, String fieldName) throws Exception {
        Map<String, Object> meta = loadMetadataRow(recording, typeName);
        if (meta == null) return null;
        Object f = meta.get("fieldsByName");
        if (f instanceof Map<?,?> map) {
            Object entry = map.get(fieldName);
            if (entry instanceof Map<?,?>) {
                @SuppressWarnings("unchecked") Map<String, Object> ret = (Map<String, Object>) entry;
                return ret;
            }
        }
        return null;
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

    private static List<Map<String, Object>> loadConstantPoolEntries(Path recording, String typeName) throws Exception {
        final List<Map<String, Object>> rows = new ArrayList<>();
        try (StreamingChunkParser parser = new StreamingChunkParser(new UntypedParserContextFactory())) {
            parser.parse(recording, new ChunkParserListener() {
                @Override
                public boolean onCheckpoint(ParserContext context, io.jafar.parser.internal_api.CheckpointEvent event) {
                    context.getConstantPools().pools().forEach(cp -> {
                        if (!cp.getType().getName().equals(typeName)) return;
                        // Try to reflect offsets to get all IDs
                        try {
                            Object impl = cp;
                            java.lang.reflect.Field f = impl.getClass().getDeclaredField("offsets");
                            f.setAccessible(true);
                            Object offsets = f.get(impl);
                            // Try long2LongEntrySet -> getLongKey
                            try {
                                Object entrySet = offsets.getClass().getMethod("long2LongEntrySet").invoke(offsets);
                                for (Object entry : (Iterable<?>) entrySet) {
                                    long id = (long) entry.getClass().getMethod("getLongKey").invoke(entry);
                                    Object val = cp.get(id); // lazily deserialize
                                    Map<String, Object> row = new HashMap<>();
                                    row.put("id", id);
                                    row.put("type", typeName);
                                    if (val instanceof Map<?,?> map) {
                                        @SuppressWarnings("unchecked") Map<String,Object> mv = (Map<String,Object>) map;
                                        row.putAll(mv);
                                    } else {
                                        row.put("value", val);
                                    }
                                    rows.add(row);
                                }
                            } catch (NoSuchMethodException nsme) {
                                // Fallback: keySet() with iterator
                                Object keySet = offsets.getClass().getMethod("keySet").invoke(offsets);
                                Object it = keySet.getClass().getMethod("iterator").invoke(keySet);
                                while ((boolean) it.getClass().getMethod("hasNext").invoke(it)) {
                                    Object next = it.getClass().getMethod("next").invoke(it);
                                    long id = (next instanceof Number) ? ((Number) next).longValue() : Long.parseLong(String.valueOf(next));
                                    Object val = cp.get(id);
                                    Map<String, Object> row = new HashMap<>();
                                    row.put("id", id);
                                    row.put("type", typeName);
                                    if (val instanceof Map<?,?> map) {
                                        @SuppressWarnings("unchecked") Map<String,Object> mv = (Map<String,Object>) map;
                                        row.putAll(mv);
                                    } else {
                                        row.put("value", val);
                                    }
                                    rows.add(row);
                                }
                            }
                        } catch (Throwable ignore) {}
                    });
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
