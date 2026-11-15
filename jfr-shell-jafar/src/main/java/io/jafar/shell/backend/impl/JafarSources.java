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
import java.lang.reflect.Field;
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
      boolean debug = System.getProperty("jfr.shell.completion.debug") != null;
      if (debug) {
        System.err.println("[DEBUG] JafarMetadataSource.loadClass() called for: " + typeName);
      }

      // Chunks are parsed in parallel; each chunk's onMetadata runs on a chunk thread. Collect
      // matches keyed by chunk index and pick the lowest-numbered chunk afterwards so the
      // result is deterministic regardless of thread scheduling.
      Map<Integer, Map<String, Object>> matches = new ConcurrentHashMap<>();
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
                  matches.put(context.getChunkIndex(), convertClass(clazz));
                } else if (debug) {
                  System.err.println(
                      "[DEBUG]   Chunk " + context.getChunkIndex() + ": NOT found: " + typeName);
                }
                return false;
              }
            });
      }

      Map<String, Object> result =
          matches.entrySet().stream()
              .min(Map.Entry.comparingByKey())
              .map(Map.Entry::getValue)
              .orElse(null);
      if (debug) {
        System.err.println(
            "[DEBUG] JafarMetadataSource.loadClass() returning: "
                + (result != null ? "non-null" : "NULL"));
      }
      return result;
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
      // Chunks are parsed in parallel; collecting into a shared list races and duplicates the
      // repeated per-chunk metadata. Collect each chunk's classes keyed by chunk index, then
      // merge in chunk order, keeping the first occurrence of each (id, name) pair so the
      // output is deterministic and duplicate-free.
      Map<Integer, List<MetadataClass>> perChunk = new ConcurrentHashMap<>();
      try (StreamingChunkParser parser =
          new StreamingChunkParser(new UntypedParserContextFactory())) {
        parser.parse(
            recording,
            new ChunkParserListener() {
              @Override
              public boolean onMetadata(ParserContext context, MetadataEvent metadata) {
                perChunk.put(context.getChunkIndex(), new ArrayList<>(metadata.getClasses()));
                return false;
              }
            });
      }
      List<Map<String, Object>> result = new ArrayList<>();
      Set<String> seen = new HashSet<>();
      perChunk.entrySet().stream()
          .sorted(Map.Entry.comparingByKey())
          .forEach(
              entry -> {
                for (MetadataClass clazz : entry.getValue()) {
                  if (seen.add(clazz.getId() + "\u0000" + clazz.getName())) {
                    result.add(convertClass(clazz));
                  }
                }
              });
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
     * @return list of matching chunk rows, ordered by chunk index
     */
    private List<Map<String, Object>> loadChunksWithLimit(
        Path recording, Predicate<Map<String, Object>> filter, int limit) throws Exception {
      // Chunks are parsed in parallel; collect matching rows keyed by chunk index and merge in
      // chunk order afterwards so the result is deterministic. Rows are cheap to create (header
      // data only), so every chunk reports one; the limit selects the lowest-indexed matches.
      Map<Integer, Map<String, Object>> rows = new ConcurrentHashMap<>();
      try (StreamingChunkParser parser =
          new StreamingChunkParser(new UntypedParserContextFactory())) {
        parser.parse(
            recording,
            new ChunkParserListener() {
              @Override
              public boolean onChunkStart(
                  ParserContext context, int chunkIndex, ChunkHeader header) {
                Map<String, Object> row = createChunkRow(chunkIndex, header);
                if (filter == null || filter.test(row)) {
                  rows.put(chunkIndex, row);
                }
                // Always return true so every chunk reports its row; skipping events below is
                // only an optimization and cannot change which rows are collected.
                return true;
              }

              @Override
              public boolean onMetadata(ParserContext context, MetadataEvent metadata) {
                // Stop parsing this chunk's events once enough chunks matched. Only affects how
                // much of this chunk is parsed, never which rows are collected.
                return limit <= 0 || rows.size() < limit;
              }
            });
      }
      return rows.entrySet().stream()
          .sorted(Map.Entry.comparingByKey())
          .map(Map.Entry::getValue)
          .limit(limit < 0 ? Long.MAX_VALUE : limit)
          .collect(java.util.stream.Collectors.toList());
    }

    @Override
    public Map<String, Object> getChunkSummary(Path recording) throws Exception {
      // Chunks are parsed in parallel; collect per-chunk stats and merge in chunk order so the
      // result is deterministic (and the plain counters are not mutated concurrently).
      Map<Integer, long[]> perChunk = new ConcurrentHashMap<>(); // [size, compressed(0/1)]

      try (StreamingChunkParser parser =
          new StreamingChunkParser(new UntypedParserContextFactory())) {
        parser.parse(
            recording,
            new ChunkParserListener() {
              @Override
              public boolean onChunkStart(
                  ParserContext context, int chunkIndex, ChunkHeader header) {
                perChunk.put(chunkIndex, new long[] {header.size, header.compressed ? 1 : 0});
                return true;
              }

              @Override
              public boolean onMetadata(ParserContext context, MetadataEvent metadata) {
                return true;
              }
            });
      }

      long count = 0, totalSize = 0, minSize = Long.MAX_VALUE, maxSize = Long.MIN_VALUE;
      long compressedCount = 0;
      for (Map.Entry<Integer, long[]> e : new java.util.TreeMap<>(perChunk).entrySet()) {
        long size = e.getValue()[0];
        count++;
        totalSize += size;
        minSize = Math.min(minSize, size);
        maxSize = Math.max(maxSize, size);
        compressedCount += e.getValue()[1];
      }
      long[] stats = {count, totalSize, minSize, maxSize, compressedCount};

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
      final Map<String, Long> sizes = new ConcurrentHashMap<>();
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
                          try {
                            String name = cp.getType().getName();
                            long count = extractConstantPoolCount(cp);
                            sizes.merge(name, count, Long::sum);
                          } catch (Throwable ignore) {
                          }
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
      rows.sort((a, b) -> Long.compare((long) b.get("totalSize"), (long) a.get("totalSize")));
      return rows;
    }

    private static long extractConstantPoolCount(ConstantPool cp) {
      try {
        Field f = cp.getClass().getDeclaredField("offsets");
        f.setAccessible(true);
        Object offsets = f.get(cp);
        try {
          return (int) offsets.getClass().getMethod("size").invoke(offsets);
        } catch (Throwable ignore) {
        }
      } catch (Throwable ignore) {
      }
      return cp.size();
    }

    @Override
    public List<Map<String, Object>> loadEntries(Path recording, String typeName) throws Exception {
      return loadEntries(recording, typeName, null);
    }

    @Override
    public List<Map<String, Object>> loadEntries(
        Path recording, String typeName, Predicate<Map<String, Object>> filter) throws Exception {
      // Chunks are parsed in parallel; drain into per-chunk lists and merge in chunk order so
      // the output is deterministic and race-free. Draining happens once in onChunkEnd, when the
      // chunk's constant pools are fully populated — draining per checkpoint delta would re-emit
      // entries that were already drained.
      final Map<Integer, List<Map<String, Object>>> perChunk = new ConcurrentHashMap<>();

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
                if (!skipped) {
                  List<Map<String, Object>> rows = new ArrayList<>();
                  try {
                    context
                        .getConstantPools()
                        .pools()
                        .forEach(cp -> drainPool(cp, typeName, filter, rows));
                  } catch (Throwable ignore) {
                  }
                  perChunk.put(chunkIndex, rows);
                }
                return ChunkParserListener.super.onChunkEnd(context, chunkIndex, skipped);
              }

              @Override
              public boolean onMetadata(ParserContext context, MetadataEvent metadata) {
                return true;
              }

              @Override
              public boolean onCheckpoint(ParserContext context, CheckpointEvent event) {
                return true;
              }
            });
      }
      List<Map<String, Object>> rows = new ArrayList<>();
      perChunk.entrySet().stream()
          .sorted(Map.Entry.comparingByKey())
          .forEach(entry -> rows.addAll(entry.getValue()));
      return rows;
    }

    private static void drainPool(
        ConstantPool cp,
        String typeName,
        Predicate<Map<String, Object>> filter,
        List<Map<String, Object>> out) {
      if (!cp.getType().getName().equals(typeName)) {
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
          out.add(row);
        }
      }
    }

    @Override
    public Set<String> getAvailableTypes(Path recording) throws Exception {
      Set<String> types = ConcurrentHashMap.newKeySet();
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
      // Per-chunk state, keyed by chunk index: chunks are parsed in parallel, so a single
      // shared slot would be overwritten mid-chunk by neighbouring chunks.
      java.util.concurrent.atomic.AtomicLong totalCpCount =
          new java.util.concurrent.atomic.AtomicLong();
      java.util.concurrent.atomic.AtomicLong referencedCpCount =
          new java.util.concurrent.atomic.AtomicLong();
      // Per-chunk target-type CP IDs
      Map<Integer, Set<Long>> chunkAllIds = new ConcurrentHashMap<>();
      // Per-chunk CP edge graph (merged into cpEdges at chunk end)
      Map<Integer, Map<String, Map<Long, Map<String, Set<Long>>>>> chunkCpEdges =
          new ConcurrentHashMap<>();
      // Per-chunk union of all event CP refs — seed for per-chunk global BFS
      Map<Integer, Map<String, Set<Long>>> chunkEventRefs = new ConcurrentHashMap<>();
      // Per-chunk edge processor
      Map<Integer, EdgeCollectingProcessor> edgeProcs = new ConcurrentHashMap<>();

      // Global merged CP edge graph — accumulated from all chunks for per-event-type BFS
      Map<String, Map<Long, Map<String, Set<Long>>>> cpEdges = new ConcurrentHashMap<>();

      // Per-event-type CP refs (all types) — seeds for per-event-type BFS
      Map<String, Map<String, Set<Long>>> directEventRefsByType = new ConcurrentHashMap<>();
      // Events per event type that directly reference the target CP type
      Map<String, Long> eventCounts = new ConcurrentHashMap<>();
      // Candidate event counts for types with transitive (not direct) refs — only promoted after
      // BFS confirms reachable target IDs
      Map<String, Long> candidateTransitiveCounts = new ConcurrentHashMap<>();
      // Total non-unique direct target-type refs per event type
      Map<String, Long> totalRefsByEventType = new ConcurrentHashMap<>();
      // Per-event-type unique target IDs (direct refs only)
      Map<String, Set<Long>> idsByEventType = new ConcurrentHashMap<>();

      // Metadata-derived ancestor types for transitive crossref counting
      Set<String>[] ancestorTypes = new Set[] {Collections.emptySet()};

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
              public boolean onMetadata(ParserContext context, MetadataEvent metadata) {
                ancestorTypes[0] = buildAncestorTypes(metadata, typeName);
                return true;
              }

              @Override
              public boolean onChunkStart(
                  ParserContext context, int chunkIndex, ChunkHeader header) {
                chunkAllIds.put(chunkIndex, new HashSet<>());
                Map<String, Map<Long, Map<String, Set<Long>>>> edges = new ConcurrentHashMap<>();
                chunkCpEdges.put(chunkIndex, edges);
                chunkEventRefs.put(chunkIndex, new ConcurrentHashMap<>());
                edgeProcs.put(chunkIndex, new EdgeCollectingProcessor(edges));
                context.put(GenericValueReader.class, new GenericValueReader(eventCollector));
                return true;
              }

              @Override
              public boolean onChunkEnd(ParserContext context, int chunkIndex, boolean skipped) {
                Set<Long> allIds = chunkAllIds.remove(chunkIndex);
                Map<String, Map<Long, Map<String, Set<Long>>>> edges =
                    chunkCpEdges.remove(chunkIndex);
                Map<String, Set<Long>> eventRefs = chunkEventRefs.remove(chunkIndex);
                edgeProcs.remove(chunkIndex);
                if (!skipped && edges != null) {
                  // Per-chunk BFS: which target-type IDs are reachable from events in this chunk?
                  Set<Long> chunkReachable =
                      bfsCpReachable(
                          eventRefs != null ? eventRefs : Collections.emptyMap(), edges, typeName);
                  // totalCpCount is accumulated per-checkpoint via extractConstantPoolCount.
                  if (allIds != null) {
                    referencedCpCount.addAndGet(
                        chunkReachable.stream().filter(allIds::contains).count());
                  }
                  // Merge this chunk's CP edges into the global graph for per-event-type BFS.
                  edges.forEach(
                      (type, idMap) ->
                          cpEdges
                              .computeIfAbsent(type, k -> new ConcurrentHashMap<>())
                              .putAll(idMap));
                }
                context.remove(GenericValueReader.class);
                return true;
              }

              @Override
              public boolean onCheckpoint(ParserContext context, CheckpointEvent cp) {
                int chunkIndex = context.getChunkIndex();
                Set<Long> allIds = chunkAllIds.get(chunkIndex);
                EdgeCollectingProcessor edgeProc = edgeProcs.get(chunkIndex);
                RecordingStream stream = context.get(RecordingStream.class);
                GenericValueReader edgeReader =
                    edgeProc != null ? new GenericValueReader(edgeProc) : null;
                context
                    .getConstantPools()
                    .pools()
                    .forEach(
                        pool -> {
                          if (pool.getType().getName().equals(typeName)) {
                            // Accumulate delta count for global total (mirrors loadSummary).
                            totalCpCount.addAndGet(extractConstantPoolCount(pool));
                            // Collect IDs defined in this checkpoint for per-chunk BFS.
                            if (allIds != null) {
                              MutableConstantPool targetMcp = (MutableConstantPool) pool;
                              Iterator<Long> it = targetMcp.ids();
                              while (it.hasNext()) {
                                long id = it.next();
                                if (targetMcp.getOffset(id) != 0) allIds.add(id);
                              }
                            }
                          }
                          // Build per-chunk CP reference edges for ALL types
                          if (stream != null && edgeProc != null && edgeReader != null) {
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

                // Track per-event-type refs and seed per-chunk BFS
                List<Long> directTarget = allRefs.getOrDefault(typeName, Collections.emptyList());
                if (!directTarget.isEmpty()) {
                  String et = eventType.getName();
                  eventCounts.merge(et, 1L, Long::sum);
                  totalRefsByEventType.merge(et, (long) directTarget.size(), Long::sum);
                  idsByEventType
                      .computeIfAbsent(et, k -> ConcurrentHashMap.newKeySet())
                      .addAll(directTarget);
                } else {
                  // Check for transitive refs — count as candidate, confirmed after BFS
                  for (String ancestor : ancestorTypes[0]) {
                    if (allRefs.containsKey(ancestor)) {
                      candidateTransitiveCounts.merge(eventType.getName(), 1L, Long::sum);
                      break;
                    }
                  }
                }
                Map<String, Set<Long>> refs = chunkEventRefs.get(context.getChunkIndex());
                if (refs != null) {
                  allRefs.forEach(
                      (refType, refIds) ->
                          refs.computeIfAbsent(refType, k -> ConcurrentHashMap.newKeySet())
                              .addAll(refIds));
                }
                if (!allRefs.isEmpty()) {
                  String et = eventType.getName();
                  allRefs.forEach(
                      (refType, refIds) ->
                          directEventRefsByType
                              .computeIfAbsent(et, k -> new ConcurrentHashMap<>())
                              .computeIfAbsent(refType, k -> ConcurrentHashMap.newKeySet())
                              .addAll(refIds));
                }
                return true;
              }
            });
      }

      // Per-event-type transitive BFS: merge transitive IDs and promote candidate event counts.
      // Uses the global merged cpEdges accumulated from all chunks.
      for (Map.Entry<String, Map<String, Set<Long>>> e : directEventRefsByType.entrySet()) {
        String et = e.getKey();
        Set<Long> transitiveIds = bfsCpReachable(e.getValue(), cpEdges, typeName);
        if (!transitiveIds.isEmpty()) {
          idsByEventType
              .computeIfAbsent(et, k -> ConcurrentHashMap.newKeySet())
              .addAll(transitiveIds);
          // Promote candidate transitive event counts now that BFS confirmed reachability
          Long candidateCount = candidateTransitiveCounts.get(et);
          if (candidateCount != null) {
            eventCounts.merge(et, candidateCount, Long::sum);
          }
        }
      }

      long total = totalCpCount.get();
      long referenced = referencedCpCount.get();
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
     * Build the transitive set of CP type names that can contain references to {@code targetType},
     * using the recording metadata. For example, if Method references Symbol and StackTrace
     * contains inline StackFrame which references Method, then the ancestor set of Symbol includes
     * Method and StackTrace.
     */
    private static Set<String> buildAncestorTypes(MetadataEvent metadata, String targetType) {
      Map<String, Set<String>> referencedBy = new HashMap<>();
      for (MetadataClass mc : metadata.getClasses()) {
        collectCpRefs(mc, mc.getName(), referencedBy, new HashSet<>());
      }
      Set<String> ancestors = new HashSet<>();
      List<String> worklist = new ArrayList<>();
      worklist.add(targetType);
      while (!worklist.isEmpty()) {
        String current = worklist.remove(worklist.size() - 1);
        Set<String> parents = referencedBy.get(current);
        if (parents == null) continue;
        for (String parent : parents) {
          if (ancestors.add(parent)) {
            worklist.add(parent);
          }
        }
      }
      return ancestors;
    }

    private static void collectCpRefs(
        MetadataClass type,
        String rootTypeName,
        Map<String, Set<String>> referencedBy,
        Set<String> visited) {
      if (!visited.add(type.getName())) return;
      for (MetadataField f : type.getFields()) {
        try {
          if (f.hasConstantPool()) {
            referencedBy
                .computeIfAbsent(f.getType().getName(), k -> new HashSet<>())
                .add(rootTypeName);
          } else if (f.getType() != null && !f.getType().isPrimitive()) {
            collectCpRefs(f.getType(), rootTypeName, referencedBy, visited);
          }
        } catch (Throwable ignore) {
        }
      }
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
