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
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Jafar-specific implementations of Source interfaces. Contains the actual parsing logic using
 * Jafar's internal APIs.
 */
final class JafarSources {

  private JafarSources() {}

  /**
   * One accumulator per chunk, read back in chunk order.
   *
   * <p>{@link StreamingChunkParser} submits every chunk to its own pool thread, so listener
   * callbacks for different chunks run concurrently, while the callbacks for one chunk run in
   * sequence on one thread. State shared by all chunks therefore races: a plain {@code ArrayList}
   * loses rows or throws {@code ArrayIndexOutOfBoundsException}, a single slot reset in {@code
   * onChunkStart} is overwritten by the other chunk mid-flight, and even a synchronized list comes
   * back in whatever order the chunks finished. Each chunk instead gets its own accumulator, keyed
   * by {@link ParserContext#getChunkIndex()} (the same number {@code onChunkStart} receives), so it
   * is only ever touched by one thread. {@code parse} joins every chunk task before returning,
   * which publishes the accumulators to the caller, and {@link #inChunkOrder()} merges them in
   * recording order so results do not depend on scheduling.
   */
  private static final class PerChunk<T> {
    private final ConcurrentSkipListMap<Integer, T> byChunk = new ConcurrentSkipListMap<>();
    private final Supplier<T> factory;

    PerChunk(Supplier<T> factory) {
      this.factory = factory;
    }

    T of(ParserContext context) {
      return byChunk.computeIfAbsent(context.getChunkIndex(), k -> factory.get());
    }

    /** Call only after {@code parse} has returned. */
    Collection<T> inChunkOrder() {
      return byChunk.values();
    }
  }

  // ==================== MetadataSource Implementation ====================

  static final class JafarMetadataSource implements MetadataSource {

    @Override
    public Map<String, Object> loadClass(Path recording, String typeName) throws Exception {
      // Last chunk, in recording order, that defines the type wins -- what a sequential parse
      // overwriting one slot would return. The slot itself raced: which chunk's class came back
      // depended on which thread finished last.
      final PerChunk<Map<String, Object>[]> perChunk = new PerChunk<>(() -> new Map[1]);
      if (System.getProperty("jfr.shell.completion.debug") != null) {
        System.err.println("[DEBUG] JafarMetadataSource.loadClass() called for: " + typeName);
      }

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

                if (System.getProperty("jfr.shell.completion.debug") != null) {
                  System.err.println("[DEBUG]   Metadata has " + byName.size() + " classes");
                  // Show matching classes
                  java.util.List<String> matching =
                      byName.keySet().stream()
                          .filter(n -> n.contains("ExecutionSample"))
                          .sorted()
                          .toList();
                  if (!matching.isEmpty()) {
                    System.err.println("[DEBUG]   Classes matching 'ExecutionSample':");
                    matching.forEach(n -> System.err.println("[DEBUG]     - " + n));
                  }
                }

                MetadataClass clazz = byName.get(typeName);
                if (clazz != null) {
                  if (System.getProperty("jfr.shell.completion.debug") != null) {
                    System.err.println("[DEBUG]   Found matching class: " + typeName);
                  }
                  perChunk.of(context)[0] = convertClass(clazz);
                } else {
                  if (System.getProperty("jfr.shell.completion.debug") != null) {
                    System.err.println("[DEBUG]   Class NOT found: " + typeName);
                  }
                }
                return false;
              }
            });
      }

      Map<String, Object> result = null;
      for (Map<String, Object>[] slot : perChunk.inChunkOrder()) {
        if (slot[0] != null) {
          result = slot[0];
        }
      }
      if (System.getProperty("jfr.shell.completion.debug") != null) {
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
      PerChunk<List<Map<String, Object>>> perChunk = new PerChunk<>(ArrayList::new);
      try (StreamingChunkParser parser =
          new StreamingChunkParser(new UntypedParserContextFactory())) {
        parser.parse(
            recording,
            new ChunkParserListener() {
              @Override
              public boolean onMetadata(ParserContext context, MetadataEvent metadata) {
                List<Map<String, Object>> rows = perChunk.of(context);
                for (MetadataClass clazz : metadata.getClasses()) {
                  rows.add(convertClass(clazz));
                }
                return false;
              }
            });
      }
      List<Map<String, Object>> result = new ArrayList<>();
      perChunk.inChunkOrder().forEach(result::addAll);
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
      List<Map<String, Object>> rows = new ArrayList<>();
      for (ChunkHeader header : loadHeaders(recording)) {
        Map<String, Object> row = createChunkRow(header.order, header);
        if (filter == null || filter.test(row)) {
          rows.add(row);
        }
      }
      return rows;
    }

    /**
     * Chunk headers in recording order. Rows used to be appended from {@code onChunkStart} on the
     * parser's pool threads, so they came back in completion order and {@link #loadChunk} picked a
     * chunk by a position that changed from run to run.
     */
    private static List<ChunkHeader> loadHeaders(Path recording) throws Exception {
      PerChunk<ChunkHeader[]> perChunk = new PerChunk<>(() -> new ChunkHeader[1]);
      try (StreamingChunkParser parser =
          new StreamingChunkParser(new UntypedParserContextFactory())) {
        parser.parse(
            recording,
            new ChunkParserListener() {
              @Override
              public boolean onChunkStart(
                  ParserContext context, int chunkIndex, ChunkHeader header) {
                perChunk.of(context)[0] = header;
                return false; // the header is all we need
              }
            });
      }
      List<ChunkHeader> headers = new ArrayList<>();
      for (ChunkHeader[] slot : perChunk.inChunkOrder()) {
        headers.add(slot[0]);
      }
      return headers;
    }

    @Override
    public Map<String, Object> getChunkSummary(Path recording) throws Exception {
      // Aggregated after the parse: the counters used to be updated from every chunk's thread.
      final long[] stats = new long[5]; // count, totalSize, minSize, maxSize, compressedCount
      stats[2] = Long.MAX_VALUE; // minSize
      stats[3] = Long.MIN_VALUE; // maxSize
      for (ChunkHeader header : loadHeaders(recording)) {
        stats[0]++;
        stats[1] += header.size;
        stats[2] = Math.min(stats[2], header.size);
        stats[3] = Math.max(stats[3], header.size);
        if (header.compressed) {
          stats[4]++;
        }
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

  /**
   * CHECKPOINTS_ACCUMULATE: the constant pool seen in {@code onCheckpoint} is not that checkpoint's
   * entries. {@code CheckpointEvent.readConstantPools} adds every checkpoint's entries to one pool
   * per type per chunk ({@code addOrGetConstantPool}), so at the n-th checkpoint the pool holds
   * everything read so far. Draining or counting it at every checkpoint repeats the early entries
   * once per later checkpoint: chunk 1 of {@code tck-test.jfr} has 152 checkpoints, and its 875
   * distinct {@code jdk.types.Symbol} ids came back as 28882 rows.
   */
  static final class JafarConstantPoolSource implements ConstantPoolSource {

    /** One chunk's {@link #loadEntries} output and the ids already emitted for it. */
    private static final class EntryRows {
      final List<Map<String, Object>> rows = new ArrayList<>();
      final Set<Long> seen = new HashSet<>();
    }

    @Override
    public List<Map<String, Object>> loadSummary(Path recording) throws Exception {
      final PerChunk<Map<String, Long>> perChunk = new PerChunk<>(HashMap::new);
      try (StreamingChunkParser parser =
          new StreamingChunkParser(new UntypedParserContextFactory())) {
        parser.parse(
            recording,
            new ChunkParserListener() {
              @Override
              public boolean onCheckpoint(ParserContext context, CheckpointEvent event) {
                Map<String, Long> chunkSizes = perChunk.of(context);
                context
                    .getConstantPools()
                    .pools()
                    .forEach(
                        cp -> {
                          try {
                            String name = cp.getType().getName();
                            long count = extractConstantPoolCount(cp);
                            // The pool is cumulative within a chunk (see CHECKPOINTS_ACCUMULATE):
                            // its size at the last checkpoint is the chunk's total.
                            chunkSizes.merge(name, count, Math::max);
                          } catch (Throwable ignore) {
                          }
                        });
                return true;
              }
            });
      }
      Map<String, Long> sizes = new HashMap<>();
      perChunk
          .inChunkOrder()
          .forEach(chunkSizes -> chunkSizes.forEach((k, v) -> sizes.merge(k, v, Long::sum)));
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
      // Rows are collected per chunk: onMetadata/onCheckpoint for different chunks run on
      // different threads, and one shared ArrayList lost rows or threw
      // ArrayIndexOutOfBoundsException from ArrayList.add.
      final PerChunk<EntryRows> perChunk = new PerChunk<>(EntryRows::new);
      final BiConsumer<ConstantPool, EntryRows> drain =
          (cp, chunk) -> {
            if (!cp.getType().getName().equals(typeName)) {
              return;
            }
            cp.ensureIndexed();
            Iterator<Long> it = cp.ids();
            while (it.hasNext()) {
              long id = it.next();
              // The pool is cumulative within a chunk (see CHECKPOINTS_ACCUMULATE): skip the ids
              // an earlier checkpoint of this chunk already produced.
              if (!chunk.seen.add(id)) {
                continue;
              }
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
                chunk.rows.add(row);
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
                EntryRows chunk = perChunk.of(context);
                context.getConstantPools().pools().forEach(cp -> drain.accept(cp, chunk));
                return true;
              }

              @Override
              public boolean onCheckpoint(ParserContext context, CheckpointEvent event) {
                EntryRows chunk = perChunk.of(context);
                context.getConstantPools().pools().forEach(cp -> drain.accept(cp, chunk));
                return true;
              }
            });
      }
      List<Map<String, Object>> rows = new ArrayList<>();
      perChunk.inChunkOrder().forEach(chunk -> rows.addAll(chunk.rows));
      return rows;
    }

    @Override
    public Set<String> getAvailableTypes(Path recording) throws Exception {
      PerChunk<Set<String>> perChunk = new PerChunk<>(HashSet::new);
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
                Set<String> types = perChunk.of(context);
                context.getConstantPools().pools().forEach(cp -> types.add(cp.getType().getName()));
                return false;
              }
            });
      }
      Set<String> types = new HashSet<>();
      perChunk.inChunkOrder().forEach(types::addAll);
      return types;
    }

    @Override
    public Map<String, Object> crossref(Path recording, String typeName) throws Exception {
      // JFR CP IDs are chunk-local. Global stats (total/referenced/unused CP entries) must be
      // computed per chunk and then summed. Chunks run concurrently, so every piece of per-chunk
      // state lives in that chunk's own CrossrefChunk; a single slot reset in onChunkStart was
      // replaced by the other chunk's onChunkStart while the first was still using it.
      PerChunk<CrossrefChunk> perChunk = new PerChunk<>(CrossrefChunk::new);

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
                perChunk.of(context).ancestorTypes = buildAncestorTypes(metadata, typeName);
                return true;
              }

              @Override
              public boolean onChunkStart(
                  ParserContext context, int chunkIndex, ChunkHeader header) {
                perChunk.of(context);
                context.put(GenericValueReader.class, new GenericValueReader(eventCollector));
                return true;
              }

              @Override
              public boolean onChunkEnd(ParserContext context, int chunkIndex, boolean skipped) {
                CrossrefChunk chunk = perChunk.of(context);
                chunk.completed = !skipped;
                if (!skipped) {
                  // Per-chunk BFS: which target-type IDs are reachable from events in this chunk?
                  // totalCpCount is accumulated per-checkpoint via extractConstantPoolCount.
                  Set<Long> chunkReachable =
                      bfsCpReachable(chunk.eventRefs, chunk.cpEdges, typeName);
                  chunk.referencedCpCount =
                      chunkReachable.stream().filter(chunk.allIds::contains).count();
                }
                context.remove(GenericValueReader.class);
                return true;
              }

              @Override
              public boolean onCheckpoint(ParserContext context, CheckpointEvent cp) {
                CrossrefChunk chunk = perChunk.of(context);
                RecordingStream stream = context.get(RecordingStream.class);
                GenericValueReader edgeReader = new GenericValueReader(chunk.edgeProc);
                context
                    .getConstantPools()
                    .pools()
                    .forEach(
                        pool -> {
                          if (pool.getType().getName().equals(typeName)) {
                            // The pool is cumulative within a chunk (see CHECKPOINTS_ACCUMULATE):
                            // its size at the last checkpoint is the chunk's total.
                            chunk.totalCpCount =
                                Math.max(chunk.totalCpCount, extractConstantPoolCount(pool));
                            // Collect IDs defined in this checkpoint for per-chunk BFS.
                            MutableConstantPool targetMcp = (MutableConstantPool) pool;
                            Iterator<Long> it = targetMcp.ids();
                            while (it.hasNext()) {
                              long id = it.next();
                              if (targetMcp.getOffset(id) != 0) chunk.allIds.add(id);
                            }
                          }
                          // Build per-chunk CP reference edges for ALL types
                          if (stream != null) {
                            MutableConstantPool mcp = (MutableConstantPool) pool;
                            Iterator<Long> it = mcp.ids();
                            while (it.hasNext()) {
                              long id = it.next();
                              long offset = mcp.getOffset(id);
                              if (offset == 0) continue;
                              chunk.edgeProc.startEntry(mcp.getType().getName(), id);
                              long saved = stream.position();
                              try {
                                stream.position(offset);
                                edgeReader.readValue(stream, mcp.getType());
                              } catch (Throwable ignore) {
                              } finally {
                                stream.position(saved);
                                chunk.edgeProc.endEntry();
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
                CrossrefChunk chunk = perChunk.of(context);
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
                  for (String ancestor : chunk.ancestorTypes) {
                    if (allRefs.containsKey(ancestor)) {
                      candidateTransitiveCounts.merge(eventType.getName(), 1L, Long::sum);
                      break;
                    }
                  }
                }
                allRefs.forEach(
                    (refType, refIds) ->
                        chunk
                            .eventRefs
                            .computeIfAbsent(refType, k -> new HashSet<>())
                            .addAll(refIds));
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

      // Sum per-chunk totals and merge per-chunk CP edges in recording order, so that an id
      // defined in several chunks resolves to the same chunk's edges on every run.
      long total = 0;
      long referenced = 0;
      Map<String, Map<Long, Map<String, Set<Long>>>> cpEdges = new HashMap<>();
      for (CrossrefChunk chunk : perChunk.inChunkOrder()) {
        total += chunk.totalCpCount;
        if (chunk.completed) {
          referenced += chunk.referencedCpCount;
          chunk.cpEdges.forEach(
              (type, idMap) -> cpEdges.computeIfAbsent(type, k -> new HashMap<>()).putAll(idMap));
        }
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
     * Everything {@link #crossref} accumulates for one chunk. Touched only by that chunk's thread
     * (see {@link PerChunk}), so plain collections suffice.
     */
    private static final class CrossrefChunk {
      /** Target-type CP IDs defined in this chunk's checkpoints. */
      final Set<Long> allIds = new HashSet<>();

      /** This chunk's CP-to-CP reference edges. Merged into the global graph after the parse. */
      final Map<String, Map<Long, Map<String, Set<Long>>>> cpEdges = new HashMap<>();

      /** Union of all CP refs made by this chunk's events: seeds for the per-chunk BFS. */
      final Map<String, Set<Long>> eventRefs = new HashMap<>();

      final EdgeCollectingProcessor edgeProc = new EdgeCollectingProcessor(cpEdges);

      /** Metadata-derived ancestor types of the target, from this chunk's metadata. */
      Set<String> ancestorTypes = Collections.emptySet();

      long totalCpCount;
      long referencedCpCount;
      boolean completed;
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
