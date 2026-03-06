package io.jafar.shell.backend.impl;

import io.jafar.parser.api.ConstantPool;
import io.jafar.parser.api.ParserContext;
import io.jafar.parser.impl.MapValueBuilder;
import io.jafar.parser.impl.UntypedParserContextFactory;
import io.jafar.parser.internal_api.CheckpointEvent;
import io.jafar.parser.internal_api.ChunkHeader;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.GenericValueReader;
import io.jafar.parser.internal_api.MutableConstantPool;
import io.jafar.parser.internal_api.RecordingStream;
import io.jafar.parser.internal_api.StreamingChunkParser;
import io.jafar.parser.internal_api.ValueProcessor;
import io.jafar.parser.internal_api.metadata.MetadataAnnotation;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.internal_api.metadata.MetadataEvent;
import io.jafar.parser.internal_api.metadata.MetadataField;
import io.jafar.shell.backend.ChunkSource;
import io.jafar.shell.backend.ConstantPoolSource;
import io.jafar.shell.backend.MetadataSource;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * Jafar-specific implementations of Source interfaces. Contains the actual parsing logic using
 * Jafar's internal APIs.
 */
final class JafarSources {

  private JafarSources() {}

  // ==================== MetadataSource Implementation ====================

  static final class JafarMetadataSource implements MetadataSource {

    @Override
    public Map<String, Object> loadClass(Path recording, String typeName) throws Exception {
      final AtomicReference<Map<String, Object>> ref = new AtomicReference<>();
      try (StreamingChunkParser parser =
          new StreamingChunkParser(new UntypedParserContextFactory())) {
        parser.parse(
            recording,
            new ChunkParserListener() {
              @Override
              public boolean onMetadata(ParserContext context, MetadataEvent metadata) {
                Map<String, MetadataClass> byName = new HashMap<>();
                for (MetadataClass mc : metadata.getClasses()) {
                  byName.put(mc.getName(), mc);
                }
                MetadataClass clazz = byName.get(typeName);
                if (clazz != null) {
                  ref.set(convertClass(clazz));
                }
                return false;
              }
            });
      }
      return ref.get();
    }

    @Override
    public Map<String, Object> loadField(Path recording, String typeName, String fieldName)
        throws Exception {
      Map<String, Object> meta = loadClass(recording, typeName);
      if (meta == null) {
        return null;
      }
      Object f = meta.get("fieldsByName");
      if (f instanceof Map<?, ?> map) {
        Object entry = map.get(fieldName);
        if (entry instanceof Map<?, ?>) {
          @SuppressWarnings("unchecked")
          Map<String, Object> ret = (Map<String, Object>) entry;
          return ret;
        }
      }
      return null;
    }

    @Override
    public List<Map<String, Object>> loadAllClasses(Path recording) throws Exception {
      List<Map<String, Object>> result = new ArrayList<>();
      try (StreamingChunkParser parser =
          new StreamingChunkParser(new UntypedParserContextFactory())) {
        parser.parse(
            recording,
            new ChunkParserListener() {
              @Override
              public boolean onMetadata(ParserContext context, MetadataEvent metadata) {
                for (MetadataClass clazz : metadata.getClasses()) {
                  result.add(convertClass(clazz));
                }
                return false;
              }
            });
      }
      return result;
    }

    private static Map<String, Object> convertClass(MetadataClass clazz) {
      Map<String, Object> meta = new LinkedHashMap<>();
      meta.put("id", clazz.getId());
      meta.put("name", clazz.getName());
      meta.put("superType", clazz.getSuperType());

      // Class-level annotations
      try {
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
      } catch (Throwable ignore) {
      }

      // Settings
      try {
        Map<String, Map<String, Object>> settingsByName = new HashMap<>();
        List<String> settingsDisplay = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : clazz.getSettingsByName().entrySet()) {
          String sName = e.getKey();
          @SuppressWarnings("unchecked")
          Map<String, Object> sm = (Map<String, Object>) e.getValue();
          settingsByName.put(sName, sm);
          String st = String.valueOf(sm.get("type"));
          Object dv = sm.get("defaultValue");
          String def = dv == null ? null : String.valueOf(dv);
          settingsDisplay.add(sName + ":" + st + (def != null ? (" (" + def + ")") : ""));
        }
        meta.put("settingsByName", settingsByName);
        meta.put("settings", settingsDisplay);
      } catch (Throwable ignore) {
      }

      // Fields
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
          try {
            hasCp = f.hasConstantPool();
          } catch (Throwable t) {
            hasCp = false;
          }
          fm.put("hasConstantPool", hasCp);

          // Field annotations
          List<String> ann = new ArrayList<>();
          List<String> annFull = new ArrayList<>();
          try {
            for (MetadataAnnotation a : f.getAnnotations()) {
              String an =
                  a.getType() != null ? a.getType().getName() : String.valueOf(a.getClassId());
              String simple = an.substring(an.lastIndexOf('.') + 1);
              String val = a.getValue();
              ann.add("@" + simple + (val != null && !val.isEmpty() ? ("(" + val + ")") : ""));
              annFull.add(an + (val != null && !val.isEmpty() ? ("(" + val + ")") : ""));
            }
          } catch (Throwable ignoreFieldAnn) {
          }
          fm.put("annotations", ann);
          fm.put("annotationsFull", annFull);
          fieldsByName.put(fName, fm);
          String disp =
              fName
                  + ":"
                  + fType
                  + dimSuffix
                  + (ann.isEmpty() ? "" : (" " + String.join(" ", ann)));
          fieldsDisplay.add(disp);
        } catch (Throwable ignoreField) {
          // skip problematic field
        }
      }
      meta.put("fieldsByName", fieldsByName);
      meta.put("fields", fieldsDisplay);
      meta.put("fieldCount", fieldsDisplay.size());
      return meta;
    }
  }

  // ==================== ChunkSource Implementation ====================

  static final class JafarChunkSource implements ChunkSource {

    @Override
    public List<Map<String, Object>> loadAllChunks(Path recording) throws Exception {
      return loadChunks(recording, null);
    }

    @Override
    public Map<String, Object> loadChunk(Path recording, int chunkIndex) throws Exception {
      // Simple: load all chunks and return by position (index is 0-based position)
      List<Map<String, Object>> allChunks = loadAllChunks(recording);
      if (chunkIndex >= 0 && chunkIndex < allChunks.size()) {
        return allChunks.get(chunkIndex);
      }
      return null;
    }

    @Override
    public List<Map<String, Object>> loadChunks(
        Path recording, Predicate<Map<String, Object>> filter) throws Exception {
      return loadChunksWithLimit(recording, filter, -1);
    }

    /**
     * Load chunks with optional filter and limit. Supports early termination when limit is reached.
     *
     * @param recording path to the JFR recording
     * @param filter optional predicate to filter chunks, null for all
     * @param limit maximum number of chunks to collect (-1 for unlimited)
     * @return list of matching chunk rows
     */
    private List<Map<String, Object>> loadChunksWithLimit(
        Path recording, Predicate<Map<String, Object>> filter, int limit) throws Exception {
      // Synchronized because chunks may be processed in parallel
      final List<Map<String, Object>> rows = Collections.synchronizedList(new ArrayList<>());
      try (StreamingChunkParser parser =
          new StreamingChunkParser(new UntypedParserContextFactory())) {
        parser.parse(
            recording,
            new ChunkParserListener() {
              @Override
              public boolean onChunkStart(
                  ParserContext context, int chunkIndex, ChunkHeader header) {
                // Check if we've already collected enough chunks from previous iterations
                if (limit > 0 && rows.size() >= limit) {
                  return false; // Stop processing - we already have enough
                }
                Map<String, Object> row = createChunkRow(chunkIndex, header);
                if (filter == null || filter.test(row)) {
                  rows.add(row);
                }
                // Always return true to complete processing of this chunk
                // The limit check at the start of next onChunkStart will stop if needed
                return true;
              }

              @Override
              public boolean onMetadata(ParserContext context, MetadataEvent metadata) {
                // Stop processing metadata if we already have enough chunks
                return limit <= 0 || rows.size() < limit;
              }
            });
      }
      return rows;
    }

    @Override
    public Map<String, Object> getChunkSummary(Path recording) throws Exception {
      final long[] stats = new long[5]; // count, totalSize, minSize, maxSize, compressedCount
      stats[2] = Long.MAX_VALUE; // minSize
      stats[3] = Long.MIN_VALUE; // maxSize

      try (StreamingChunkParser parser =
          new StreamingChunkParser(new UntypedParserContextFactory())) {
        parser.parse(
            recording,
            new ChunkParserListener() {
              @Override
              public boolean onChunkStart(
                  ParserContext context, int chunkIndex, ChunkHeader header) {
                stats[0]++;
                stats[1] += header.size;
                stats[2] = Math.min(stats[2], header.size);
                stats[3] = Math.max(stats[3], header.size);
                if (header.compressed) {
                  stats[4]++;
                }
                return true;
              }

              @Override
              public boolean onMetadata(ParserContext context, MetadataEvent metadata) {
                return true;
              }
            });
      }

      Map<String, Object> summary = new HashMap<>();
      summary.put("totalChunks", stats[0]);
      summary.put("totalSize", stats[1]);
      summary.put("avgSize", stats[0] > 0 ? stats[1] / stats[0] : 0);
      summary.put("minSize", stats[0] > 0 ? stats[2] : 0);
      summary.put("maxSize", stats[0] > 0 ? stats[3] : 0);
      summary.put("compressedCount", stats[4]);
      return summary;
    }

    private static Map<String, Object> createChunkRow(int chunkIndex, ChunkHeader header) {
      Map<String, Object> m = new HashMap<>();
      m.put("index", chunkIndex);
      m.put("offset", header.offset);
      m.put("size", header.size);
      m.put("startNanos", header.startNanos);
      m.put("duration", header.duration);
      m.put("startTicks", header.startTicks);
      m.put("frequency", header.frequency);
      m.put("compressed", header.compressed);
      return m;
    }
  }

  // ==================== ConstantPoolSource Implementation ====================

  static final class JafarConstantPoolSource implements ConstantPoolSource {

    @Override
    public List<Map<String, Object>> loadSummary(Path recording) throws Exception {
      final Map<String, Set<Long>> idSets = new ConcurrentHashMap<>();
      try (StreamingChunkParser parser =
          new StreamingChunkParser(new UntypedParserContextFactory())) {
        parser.parse(
            recording,
            new ChunkParserListener() {
              @Override
              public boolean onCheckpoint(ParserContext context, CheckpointEvent event) {
                context
                    .getConstantPools()
                    .pools()
                    .forEach(
                        cp -> {
                          String name = cp.getType().getName();
                          Set<Long> ids =
                              idSets.computeIfAbsent(name, k -> ConcurrentHashMap.newKeySet());
                          try {
                            cp.ensureIndexed();
                          } catch (Throwable ignore) {
                          }
                          Iterator<Long> it = cp.ids();
                          while (it.hasNext()) ids.add(it.next());
                        });
                return true;
              }
            });
      }
      List<Map<String, Object>> rows = new ArrayList<>();
      for (Map.Entry<String, Set<Long>> e : idSets.entrySet()) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", e.getKey());
        m.put("totalSize", (long) e.getValue().size());
        rows.add(m);
      }
      rows.sort((a, b) -> Long.compare((long) b.get("totalSize"), (long) a.get("totalSize")));
      return rows;
    }

    @Override
    public List<Map<String, Object>> loadEntries(Path recording, String typeName) throws Exception {
      return loadEntries(recording, typeName, null);
    }

    @Override
    public List<Map<String, Object>> loadEntries(
        Path recording, String typeName, Predicate<Map<String, Object>> filter) throws Exception {
      final List<Map<String, Object>> rows = new ArrayList<>();
      final BiConsumer<ConstantPool, String> drain =
          (cp, tn) -> {
            if (!cp.getType().getName().equals(tn)) {
              return;
            }
            try {
              cp.ensureIndexed();
            } catch (Throwable ignore) {
            }
            Iterator<Long> it = cp.ids();
            while (it.hasNext()) {
              long id = it.next();
              Object val = cp.get(id);
              Map<String, Object> row = new HashMap<>();
              row.put("id", id);
              if (val instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> mv = (Map<String, Object>) map;
                row.putAll(mv);
              } else {
                row.put("value", val);
              }
              if (filter == null || filter.test(row)) {
                rows.add(row);
              }
            }
          };

      try (StreamingChunkParser parser =
          new StreamingChunkParser(new UntypedParserContextFactory())) {
        parser.parse(
            recording,
            new ChunkParserListener() {
              @Override
              public boolean onChunkStart(
                  ParserContext context, int chunkIndex, ChunkHeader header) {
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
                try {
                  context.getConstantPools().pools().forEach(cp -> drain.accept(cp, typeName));
                } catch (Throwable ignore) {
                }
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

    @Override
    public Set<String> getAvailableTypes(Path recording) throws Exception {
      Set<String> types = new HashSet<>();
      try (StreamingChunkParser parser =
          new StreamingChunkParser(new UntypedParserContextFactory())) {
        parser.parse(
            recording,
            new ChunkParserListener() {
              @Override
              public boolean onMetadata(ParserContext context, MetadataEvent metadata) {
                return true;
              }

              @Override
              public boolean onCheckpoint(ParserContext context, CheckpointEvent event) {
                context.getConstantPools().pools().forEach(cp -> types.add(cp.getType().getName()));
                return false;
              }
            });
      } catch (Exception ignore) {
      }
      return types;
    }

    @Override
    public Map<String, Object> crossref(Path recording, String typeName) throws Exception {
      // All CP entry IDs for the target type across all chunks
      Set<Long> allIds = ConcurrentHashMap.newKeySet();

      // CP reference graph: cpType -> entryId -> refType -> Set<refId>
      Map<String, Map<Long, Map<String, Set<Long>>>> cpEdges = new ConcurrentHashMap<>();

      // Union of direct CP refs across all events (all ref types) — for global BFS seed
      Map<String, Set<Long>> directEventRefs = new ConcurrentHashMap<>();
      // Events per event type that directly reference the target CP type
      Map<String, Long> eventCounts = new ConcurrentHashMap<>();
      // Total non-unique direct target-type refs per event type
      Map<String, Long> totalRefsByEventType = new ConcurrentHashMap<>();
      // Per-event-type unique target IDs (direct refs only)
      Map<String, Set<Long>> idsByEventType = new ConcurrentHashMap<>();

      EdgeCollectingProcessor edgeProc = new EdgeCollectingProcessor(cpEdges);

      // ThreadLocal: accumulates all CP refs (all types) seen in the current event
      ThreadLocal<Map<String, List<Long>>> currentEventAllRefs = new ThreadLocal<>();

      ValueProcessor eventCollector =
          new ValueProcessor() {
            @Override
            public void onConstantPoolIndex(
                MetadataClass owner, String fld, MetadataClass type, long ptr) {
              Map<String, List<Long>> allRefs = currentEventAllRefs.get();
              if (allRefs != null) {
                allRefs.computeIfAbsent(type.getName(), k -> new ArrayList<>()).add(ptr);
              }
            }
          };

      try (StreamingChunkParser parser =
          new StreamingChunkParser(new UntypedParserContextFactory())) {
        parser.parse(
            recording,
            new ChunkParserListener() {
              @Override
              public boolean onChunkStart(
                  ParserContext context, int chunkIndex, ChunkHeader header) {
                context.put(GenericValueReader.class, new GenericValueReader(eventCollector));
                return true;
              }

              @Override
              public boolean onChunkEnd(ParserContext context, int chunkIndex, boolean skipped) {
                context.remove(GenericValueReader.class);
                return true;
              }

              @Override
              public boolean onCheckpoint(ParserContext context, CheckpointEvent cp) {
                RecordingStream stream = context.get(RecordingStream.class);
                GenericValueReader edgeReader = new GenericValueReader(edgeProc);
                context
                    .getConstantPools()
                    .pools()
                    .forEach(
                        pool -> {
                          // Collect allIds for the target type
                          if (pool.getType().getName().equals(typeName)) {
                            try {
                              pool.ensureIndexed();
                            } catch (Throwable ignore) {
                            }
                            Iterator<Long> it = pool.ids();
                            while (it.hasNext()) allIds.add(it.next());
                          }
                          // Build CP reference edges for ALL types
                          if (stream != null) {
                            MutableConstantPool mcp = (MutableConstantPool) pool;
                            Iterator<Long> it = mcp.ids();
                            while (it.hasNext()) {
                              long id = it.next();
                              long offset = mcp.getOffset(id);
                              if (offset == 0) continue;
                              edgeProc.startEntry(mcp.getType().getName(), id);
                              long saved = stream.position();
                              try {
                                stream.position(offset);
                                edgeReader.readValue(stream, mcp.getType());
                              } catch (Throwable ignore) {
                              } finally {
                                stream.position(saved);
                                edgeProc.endEntry();
                              }
                            }
                          }
                        });
                return true;
              }

              @Override
              public boolean onEvent(
                  ParserContext context,
                  long typeId,
                  long eventStartPos,
                  long rawSize,
                  long payloadSize) {
                MetadataClass eventType = context.getMetadataLookup().getClass(typeId);
                if (eventType == null) return true;
                GenericValueReader reader = context.get(GenericValueReader.class);
                if (reader == null) return true;
                RecordingStream stream = context.get(RecordingStream.class);
                if (stream == null) return true;

                Map<String, List<Long>> allRefs = new HashMap<>();
                currentEventAllRefs.set(allRefs);
                try {
                  reader.readValue(stream, eventType);
                } catch (Throwable ignore) {
                } finally {
                  currentEventAllRefs.remove();
                }

                // Seed global BFS and track per-event-type direct refs
                List<Long> directTarget = allRefs.getOrDefault(typeName, Collections.emptyList());
                if (!directTarget.isEmpty()) {
                  String et = eventType.getName();
                  eventCounts.merge(et, 1L, Long::sum);
                  totalRefsByEventType.merge(et, (long) directTarget.size(), Long::sum);
                  idsByEventType
                      .computeIfAbsent(et, k -> ConcurrentHashMap.newKeySet())
                      .addAll(directTarget);
                }
                allRefs.forEach(
                    (refType, refIds) ->
                        directEventRefs
                            .computeIfAbsent(refType, k -> ConcurrentHashMap.newKeySet())
                            .addAll(refIds));
                return true;
              }
            });
      }

      // Post-parse BFS: compute transitive reachability of target type from all event refs
      Set<Long> referencedIds = bfsCpReachable(directEventRefs, cpEdges, typeName);
      long total = allIds.size();
      long referenced = referencedIds.stream().filter(allIds::contains).count();
      long unused = total - referenced;

      Map<String, Object> result = new LinkedHashMap<>();
      result.put("type", typeName);
      result.put("cpTotal", total);
      result.put("cpReferenced", referenced);
      result.put("cpUnused", unused);
      result.put("usedPercent", total == 0 ? 0.0 : 100.0 * referenced / total);
      result.put("unusedPercent", total == 0 ? 0.0 : 100.0 * unused / total);
      result.put("eventCounts", eventCounts);
      result.put("idsByEventType", idsByEventType);
      result.put("totalRefsByEventType", totalRefsByEventType);
      return result;
    }

    /**
     * BFS over the CP reference graph starting from {@code seeds}, returning the set of target-type
     * IDs reachable.
     */
    private static Set<Long> bfsCpReachable(
        Map<String, Set<Long>> seeds,
        Map<String, Map<Long, Map<String, Set<Long>>>> cpEdges,
        String targetType) {
      Map<String, Set<Long>> reachable = new HashMap<>();
      seeds.forEach(
          (type, ids) -> reachable.computeIfAbsent(type, k -> new HashSet<>()).addAll(ids));

      boolean changed;
      do {
        changed = false;
        for (Map.Entry<String, Set<Long>> e : new ArrayList<>(reachable.entrySet())) {
          Map<Long, Map<String, Set<Long>>> typeEdges = cpEdges.get(e.getKey());
          if (typeEdges == null) continue;
          for (Long id : new ArrayList<>(e.getValue())) {
            Map<String, Set<Long>> refs = typeEdges.get(id);
            if (refs == null) continue;
            for (Map.Entry<String, Set<Long>> ref : refs.entrySet()) {
              if (reachable
                  .computeIfAbsent(ref.getKey(), k -> new HashSet<>())
                  .addAll(ref.getValue())) changed = true;
            }
          }
        }
      } while (changed);

      return new HashSet<>(reachable.getOrDefault(targetType, Collections.emptySet()));
    }
  }

  /** Collects CP-to-CP reference edges by recording which CP entries each entry points to. */
  private static final class EdgeCollectingProcessor implements ValueProcessor {
    private final ThreadLocal<String> currentType = new ThreadLocal<>();
    private final ThreadLocal<Long> currentId = new ThreadLocal<>();
    private final Map<String, Map<Long, Map<String, Set<Long>>>> cpEdges;

    EdgeCollectingProcessor(Map<String, Map<Long, Map<String, Set<Long>>>> cpEdges) {
      this.cpEdges = cpEdges;
    }

    void startEntry(String type, long id) {
      currentType.set(type);
      currentId.set(id);
    }

    void endEntry() {
      currentType.remove();
      currentId.remove();
    }

    @Override
    public void onConstantPoolIndex(
        MetadataClass owner, String fld, MetadataClass type, long pointer) {
      String src = currentType.get();
      Long sid = currentId.get();
      if (src == null || sid == null) return;
      cpEdges
          .computeIfAbsent(src, k -> new ConcurrentHashMap<>())
          .computeIfAbsent(sid, k -> new ConcurrentHashMap<>())
          .computeIfAbsent(type.getName(), k -> ConcurrentHashMap.newKeySet())
          .add(pointer);
    }
  }
}
