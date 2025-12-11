package io.jafar.shell.providers;

import io.jafar.parser.api.ConstantPool;
import io.jafar.parser.api.ParserContext;
import io.jafar.parser.impl.MapValueBuilder;
import io.jafar.parser.impl.UntypedParserContextFactory;
import io.jafar.parser.internal_api.GenericValueReader;
import io.jafar.parser.internal_api.CheckpointEvent;
import io.jafar.parser.internal_api.ChunkHeader;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.StreamingChunkParser;
import io.jafar.parser.internal_api.metadata.MetadataEvent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * Provider for constant pool browsing and extraction.
 * Follows the pattern established by MetadataProvider.
 */
public final class ConstantPoolProvider {
    private ConstantPoolProvider() {}

    /**
     * Load summary of all CP types with counts.
     * Returns: name, totalSize (sorted by size desc)
     *
     * @param recording the JFR recording file path
     * @return list of CP type summary rows
     * @throws Exception if parsing fails
     */
    public static List<Map<String, Object>> loadSummary(Path recording) throws Exception {
        final Map<String, Long> sizes = new HashMap<>();
        try (StreamingChunkParser parser = new StreamingChunkParser(new UntypedParserContextFactory())) {
            parser.parse(recording, new ChunkParserListener() {
                @Override
                public boolean onCheckpoint(ParserContext context, CheckpointEvent event) {
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
        // Sort by size desc
        rows.sort((a, b) -> Long.compare((long) b.get("totalSize"), (long) a.get("totalSize")));
        return rows;
    }

    /**
     * Load all entries for a specific CP type.
     * Entries include: id, and all fields from the CP entry.
     *
     * @param recording the JFR recording file path
     * @param typeName the CP type name (e.g., "jdk.types.Symbol")
     * @return list of CP entry rows
     * @throws Exception if parsing fails
     */
    public static List<Map<String, Object>> loadEntries(Path recording, String typeName) throws Exception {
        return loadEntries(recording, typeName, null);
    }

    /**
     * Load CP entries matching a filter predicate.
     *
     * @param recording the JFR recording file path
     * @param typeName the CP type name
     * @param rowFilter predicate to filter entries, or null for all entries
     * @return list of matching CP entry rows
     * @throws Exception if parsing fails
     */
    public static List<Map<String, Object>> loadEntries(Path recording, String typeName, Predicate<Map<String, Object>> rowFilter) throws Exception {
        final List<Map<String, Object>> rows = new ArrayList<>();
        final BiConsumer<ConstantPool, String> drain = (cp, tn) -> {
            if (!cp.getType().getName().equals(tn)) return;
            try { cp.ensureIndexed(); } catch (Throwable ignore) {}
            Iterator<Long> it = cp.ids();
            while (it.hasNext()) {
                long id = it.next();
                Object val = cp.get(id);
                Map<String, Object> row = new HashMap<>();
                row.put("id", id);
                if (val instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked") Map<String, Object> mv = (Map<String, Object>) map;
                    row.putAll(mv);
                } else {
                    row.put("value", val);
                }
                if (rowFilter == null || rowFilter.test(row)) {
                    rows.add(row);
                }
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
                public boolean onCheckpoint(ParserContext context, CheckpointEvent event) {
                    context.getConstantPools().pools().forEach(cp -> drain.accept(cp, typeName));
                    return true;
                }
            });
        }
        return rows;
    }

    /**
     * Get available CP type names in the recording.
     *
     * @param recording the JFR recording file path
     * @return set of CP type names
     * @throws Exception if parsing fails
     */
    public static Set<String> getAvailableTypes(Path recording) throws Exception {
        Set<String> types = new HashSet<>();
        try (StreamingChunkParser parser = new StreamingChunkParser(new UntypedParserContextFactory())) {
            parser.parse(recording, new ChunkParserListener() {
                @Override
                public boolean onMetadata(ParserContext context, MetadataEvent metadata) {
                    return true; // Continue to checkpoint to load CPs
                }

                @Override
                public boolean onCheckpoint(ParserContext context, CheckpointEvent event) {
                    context.getConstantPools().pools().forEach(cp -> types.add(cp.getType().getName()));
                    return false; // Stop after first checkpoint
                }
            });
        } catch (Exception ignore) {
            // Ignore discovery failures; fall back to empty set
        }
        return types;
    }

    /**
     * Extract constant pool entry count using reflection on offsets field.
     * Fallback to cp.size() if reflection fails.
     */
    private static long extractConstantPoolCount(ConstantPool cp) {
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
