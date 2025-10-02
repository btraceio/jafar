package io.jafar.shell.providers;

import io.jafar.parser.api.ParserContext;
import io.jafar.parser.impl.UntypedParserContextFactory;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.StreamingChunkParser;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.internal_api.metadata.MetadataEvent;
import io.jafar.parser.internal_api.metadata.MetadataField;
import io.jafar.parser.internal_api.metadata.MetadataAnnotation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Provider for building summarized metadata views (classes, fields, annotations, settings).
 *
 * Note: This mirrors the logic used by JfrPathEvaluator for metadata queries,
 * but centralizes it for reuse by commands (e.g., 'metadata class').
 */
public final class MetadataProvider {
    private MetadataProvider() {}

    /** Build a summarized metadata map for a given class name in the recording. */
    public static Map<String, Object> loadClass(Path recording, String typeName) throws Exception {
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
                        try {
                            // Class-level annotations via getters
                            List<String> classAnn = new ArrayList<>();
                            List<String> classAnnFull = new ArrayList<>();
                            for (MetadataAnnotation a : clazz.getAnnotations()) {
                                String an = a.getType() != null ? a.getType().getName() : String.valueOf(a.getClassId());
                                String simple = an.substring(an.lastIndexOf('.') + 1);
                                String val = a.getValue();
                                classAnnFull.add(an + (val != null && !val.isEmpty() ? ("(" + val + ")") : ""));
                                classAnn.add("@" + simple + (val != null && !val.isEmpty() ? ("(" + val + ")") : ""));
                            }
                            meta.put("classAnnotations", classAnn);
                            meta.put("classAnnotationsFull", classAnnFull);
                        } catch (Throwable ignore) {}
                        try {
                            // Settings via getters
                            Map<String, Map<String, Object>> settingsByName = new HashMap<>();
                            List<String> settingsDisplay = new ArrayList<>();
                            for (Map.Entry<String, Map<String, Object>> e : clazz.getSettingsByName().entrySet()) {
                                String sName = e.getKey();
                                @SuppressWarnings("unchecked") Map<String, Object> sm = (Map<String, Object>) e.getValue();
                                settingsByName.put(sName, sm);
                                String st = String.valueOf(sm.get("type"));
                                Object dv = sm.get("defaultValue");
                                String def = dv == null ? null : String.valueOf(dv);
                                settingsDisplay.add(sName + ":" + st + (def != null ? (" (" + def + ")") : ""));
                            }
                            meta.put("settingsByName", settingsByName);
                            meta.put("settings", settingsDisplay);
                        } catch (Throwable ignore) {}
                        // Fields (+ annotations) — collect per-field with individual guards
                        Map<String, Map<String, Object>> fieldsByName = new HashMap<>();
                        List<String> fieldsDisplay = new ArrayList<>();
                        for (MetadataField f : clazz.getFields()) {
                            try {
                                Map<String, Object> fm = new HashMap<>();
                                String fName = f.getName();
                                String fType;
                                try {
                                    fType = f.getType() != null ? f.getType().getName() : String.valueOf(f.getTypeId());
                                } catch (Throwable t) {
                                    fType = String.valueOf(f.getTypeId());
                                }
                                int dim;
                                try {
                                    dim = Math.max(-1, f.getDimension());
                                } catch (Throwable t) {
                                    dim = -1;
                                }
                                String dimSuffix = dim > 0 ? "[]".repeat(dim) : "";
                                fm.put("name", fName);
                                fm.put("type", fType);
                                fm.put("dimension", dim);
                                boolean hasCp;
                                try { hasCp = f.hasConstantPool(); } catch (Throwable t) { hasCp = false; }
                                fm.put("hasConstantPool", hasCp);
                                List<String> ann = new ArrayList<>();
                                List<String> annFull = new ArrayList<>();
                                try {
                                    for (MetadataAnnotation a : f.getAnnotations()) {
                                        String an = a.getType() != null ? a.getType().getName() : String.valueOf(a.getClassId());
                                        String simple = an.substring(an.lastIndexOf('.') + 1);
                                        String val = a.getValue();
                                        ann.add("@" + simple + (val != null && !val.isEmpty() ? ("(" + val + ")") : ""));
                                        annFull.add(an + (val != null && !val.isEmpty() ? ("(" + val + ")") : ""));
                                    }
                                } catch (Throwable ignoreFieldAnn) {}
                                fm.put("annotations", ann);
                                fm.put("annotationsFull", annFull);
                                fieldsByName.put(fName, fm);
                                String disp = fName + ":" + fType + dimSuffix + (ann.isEmpty() ? "" : (" " + String.join(" ", ann)));
                                fieldsDisplay.add(disp);
                            } catch (Throwable ignoreField) {
                                // skip problematic field, continue with others
                            }
                        }
                        meta.put("fieldsByName", fieldsByName);
                        meta.put("fields", fieldsDisplay);
                        meta.put("fieldCount", fieldsDisplay.size());
                        ref.set(meta);
                    }
                    return false; // abort once metadata processed
                }
            });
        }
        return ref.get();
    }

    /** Load metadata for a single field under a class name. */
    public static Map<String, Object> loadField(Path recording, String typeName, String fieldName) throws Exception {
        Map<String, Object> meta = loadClass(recording, typeName);
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
}
