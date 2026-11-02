package io.jafar.shell.backend.impl;

import io.jafar.parser.api.ConstantPool;
import io.jafar.parser.api.ParserContext;
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
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
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
   * which publishes the accumulators to the caller ({@code Future.get} happens-after the task), and
   * {@link #inChunkOrder()} merges them in recording order so results do not depend on scheduling.
   *
   * <p>{@link #of} is called on per-event paths, so after the first call for a chunk it is one
   * lookup in the chunk's own {@link ParserContext} storage: no boxing of the chunk index, no
   * lambda, no skip-list search. The skip list is written once per chunk and read once at the end.
   */
  private static final class PerChunk<T> {
    private static final AtomicLong KEYS = new AtomicLong();

    private final ConcurrentSkipListMap<Integer, T> byChunk = new ConcurrentSkipListMap<>();
    private final Supplier<T> factory;
    // Contexts are created per chunk per parse; the key only has to be unique among the PerChunks
    // of one parse, and a counter makes it unique in the JVM.
    private final String key = PerChunk.class.getName() + '#' + KEYS.incrementAndGet();

    PerChunk(Supplier<T> factory) {
      this.factory = factory;
    }

    @SuppressWarnings("unchecked")
    T of(ParserContext context) {
      Object cached = context.get(key, Object.class);
      if (cached != null) {
        return (T) cached;
      }
      T created = factory.get();
      context.put(key, Object.class, created);
      byChunk.put(context.getChunkIndex(), created);
      return created;
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
   * distinct {@code jdk.types.Symbol} ids came back as 28882 rows. Stopping at the first checkpoint
   * instead misses types: that chunk's first checkpoint holds 1 of its 29 types.
   *
   * <p>So every source here reads the pools once, at the chunk's last checkpoint ({@link
   * #isLastCheckpoint}), when they are complete. Sources that do not need events then return {@code
   * false}, which skips the chunk's event section.
   */
  static final class JafarConstantPoolSource implements ConstantPoolSource {

    /**
     * {@code StreamingChunkParser.readConstantPool} follows the chain while {@code nextOffsetDelta
     * != 0} and stops at the checkpoint whose delta is 0, so that checkpoint is visited last and
     * the pools are complete when it is reported.
     */
    private static boolean isLastCheckpoint(CheckpointEvent event) {
      return event.nextOffsetDelta == 0;
    }

    /**
     * Entries indexed in the pool: {@code offsets.size()}. Not {@link ConstantPool#size()}, which
     * counts only the entries deserialized so far. The cast fails loudly if a context factory ever
     * hands out another pool implementation, rather than quietly counting something else.
     */
    private static long entryCount(ConstantPool cp) {
      return ((MutableConstantPool) cp).entryCount();
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
                if (!isLastCheckpoint(event)) {
                  return true;
                }
                Map<String, Long> chunkSizes = perChunk.of(context);
                context
                    .getConstantPools()
                    .pools()
                    .forEach(cp -> chunkSizes.put(cp.getType().getName(), entryCount(cp)));
                return false; // pools complete; the events are not needed
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

    @Override
    public List<Map<String, Object>> loadEntries(Path recording, String typeName) throws Exception {
      return loadEntries(recording, typeName, null);
    }

    @Override
    public List<Map<String, Object>> loadEntries(
        Path recording, String typeName, Predicate<Map<String, Object>> filter) throws Exception {
      // Rows are collected per chunk: callbacks for different chunks run on different threads,
      // and one shared ArrayList lost rows or threw ArrayIndexOutOfBoundsException.
      final PerChunk<List<Map<String, Object>>> perChunk = new PerChunk<>(ArrayList::new);
      try (StreamingChunkParser parser =
          new StreamingChunkParser(new UntypedParserContextFactory())) {
        parser.parse(
            recording,
            new ChunkParserListener() {
              @Override
              public boolean onCheckpoint(ParserContext context, CheckpointEvent event) {
                if (!isLastCheckpoint(event)) {
                  return true;
                }
                List<Map<String, Object>> rows = perChunk.of(context);
                context
                    .getConstantPools()
                    .pools()
                    .forEach(
                        cp -> {
                          if (cp.getType().getName().equals(typeName)) {
                            drain(cp, filter, rows);
                          }
                        });
                return false; // pools complete; the events are not needed
              }
            });
      }
      List<Map<String, Object>> rows = new ArrayList<>();
      perChunk.inChunkOrder().forEach(rows::addAll);
      return rows;
    }

    private static void drain(
        ConstantPool cp, Predicate<Map<String, Object>> filter, List<Map<String, Object>> rows) {
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
              public boolean onCheckpoint(ParserContext context, CheckpointEvent event) {
                if (!isLastCheckpoint(event)) {
                  return true; // stopping at the first checkpoint missed most types
                }
                Set<String> types = perChunk.of(context);
                context.getConstantPools().pools().forEach(cp -> types.add(cp.getType().getName()));
                return false; // pools complete; the events are not needed
              }
            });
      }
      Set<String> types = new HashSet<>();
      perChunk.inChunkOrder().forEach(types::addAll);
      return types;
    }

    @Override
    public Map<String, Object> crossref(Path recording, String typeName) throws Exception {
      // JFR CP IDs are chunk-local, so every statistic is computed per chunk and summed. Chunks
      // also run concurrently, so all per-chunk state lives in that chunk's own CrossrefChunk.
      // Event-type results used to be computed after the parse over one graph and one id set
      // merged from all chunks: an id referenced from two chunks (two different entries) was
      // counted once, and one chunk's ids were resolved through another chunk's edges.
      PerChunk<CrossrefChunk> perChunk = new PerChunk<>(CrossrefChunk::new);

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
                CrossrefChunk chunk = perChunk.of(context);
                context.put(GenericValueReader.class, new GenericValueReader(chunk.eventCollector));
                return true;
              }

              @Override
              public boolean onChunkEnd(ParserContext context, int chunkIndex, boolean skipped) {
                perChunk.of(context).finish(typeName, !skipped);
                context.remove(GenericValueReader.class);
                return true;
              }

              @Override
              public boolean onCheckpoint(ParserContext context, CheckpointEvent cp) {
                if (!isLastCheckpoint(cp)) {
                  // Pools are cumulative (see CHECKPOINTS_ACCUMULATE): reading them here as well
                  // decoded every entry once per later checkpoint, 72703 decodes for 8301 entries.
                  return true;
                }
                CrossrefChunk chunk = perChunk.of(context);
                RecordingStream stream = context.get(RecordingStream.class);
                GenericValueReader edgeReader = new GenericValueReader(chunk.edgeProc);
                context
                    .getConstantPools()
                    .pools()
                    .forEach(
                        pool -> {
                          if (pool.getType().getName().equals(typeName)) {
                            chunk.totalCpCount = entryCount(pool);
                            // Collect the chunk's IDs of the target type for the per-chunk BFS.
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
                MetadataClass eventType = context.getMetadataLookup().getClass(typeId);
                if (eventType == null) return true;
                GenericValueReader reader = context.get(GenericValueReader.class);
                if (reader == null) return true;
                RecordingStream stream = context.get(RecordingStream.class);
                if (stream == null) return true;
                CrossrefChunk chunk = perChunk.of(context);

                Map<String, List<Long>> allRefs = chunk.eventAllRefs;
                allRefs.clear();
                try {
                  reader.readValue(stream, eventType);
                } catch (Throwable ignore) {
                }
                chunk.recordEvent(eventType.getName(), allRefs, typeName);
                return true;
              }
            });
      }

      long total = 0;
      long referenced = 0;
      Map<String, Long> eventCounts = new HashMap<>();
      Map<String, Long> uniqueByEventType = new HashMap<>();
      Map<String, Long> totalRefsByEventType = new HashMap<>();
      for (CrossrefChunk chunk : perChunk.inChunkOrder()) {
        total += chunk.totalCpCount;
        referenced += chunk.referencedCpCount;
        chunk.eventCounts.forEach((k, v) -> eventCounts.merge(k, v, Long::sum));
        chunk.uniqueByEventType.forEach((k, v) -> uniqueByEventType.merge(k, v, Long::sum));
        chunk.totalRefsByEventType.forEach((k, v) -> totalRefsByEventType.merge(k, v, Long::sum));
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
      result.put("uniqueByEventType", uniqueByEventType);
      result.put("totalRefsByEventType", totalRefsByEventType);
      return result;
    }

    /**
     * Everything {@link #crossref} accumulates for one chunk. Touched only by that chunk's thread
     * (see {@link PerChunk}), so plain collections suffice. {@link #finish} turns the chunk's graph
     * and references into counts at the end of the chunk and drops them, so at most one graph per
     * parser thread is alive at a time.
     */
    private static final class CrossrefChunk {
      /** Target-type CP IDs defined in this chunk. */
      Set<Long> allIds = new HashSet<>();

      /** This chunk's CP-to-CP reference edges. */
      Map<String, Map<Long, Map<String, Set<Long>>>> cpEdges = new HashMap<>();

      /** Union of all CP refs made by this chunk's events: seeds for the per-chunk BFS. */
      Map<String, Set<Long>> eventRefs = new HashMap<>();

      /** Event type -> CP type -> ids its events reference: seeds for per-event-type BFS. */
      Map<String, Map<String, Set<Long>>> refsByEventType = new HashMap<>();

      /** Event type -> target ids its events reference directly. */
      Map<String, Set<Long>> directIdsByEventType = new HashMap<>();

      /**
       * Events with no direct target reference but a reference to an ancestor type, per event type;
       * added to {@link #eventCounts} only if the BFS confirms the target is reachable.
       */
      Map<String, Long> candidateTransitiveCounts = new HashMap<>();

      EdgeCollectingProcessor edgeProc = new EdgeCollectingProcessor(cpEdges);

      /** The current event's CP refs, by CP type. Reused across this chunk's events. */
      final Map<String, List<Long>> eventAllRefs = new HashMap<>();

      /** Feeds {@link #eventAllRefs}; one per chunk, so no ThreadLocal is needed. */
      final ValueProcessor eventCollector =
          new ValueProcessor() {
            @Override
            public void onConstantPoolIndex(
                MetadataClass owner, String fld, MetadataClass type, long ptr) {
              eventAllRefs.computeIfAbsent(type.getName(), k -> new ArrayList<>()).add(ptr);
            }
          };

      /** Metadata-derived ancestor types of the target, from this chunk's metadata. */
      Set<String> ancestorTypes = Collections.emptySet();

      // Results, kept after finish().
      long totalCpCount;
      long referencedCpCount;
      final Map<String, Long> eventCounts = new HashMap<>();
      final Map<String, Long> uniqueByEventType = new HashMap<>();
      final Map<String, Long> totalRefsByEventType = new HashMap<>();

      void recordEvent(String eventType, Map<String, List<Long>> allRefs, String typeName) {
        if (allRefs.isEmpty()) return;
        List<Long> directTarget = allRefs.get(typeName);
        if (directTarget != null && !directTarget.isEmpty()) {
          eventCounts.merge(eventType, 1L, Long::sum);
          totalRefsByEventType.merge(eventType, (long) directTarget.size(), Long::sum);
          directIdsByEventType
              .computeIfAbsent(eventType, k -> new HashSet<>())
              .addAll(directTarget);
        } else {
          for (String ancestor : ancestorTypes) {
            if (allRefs.containsKey(ancestor)) {
              candidateTransitiveCounts.merge(eventType, 1L, Long::sum);
              break;
            }
          }
        }
        Map<String, Set<Long>> byRefType =
            refsByEventType.computeIfAbsent(eventType, k -> new HashMap<>());
        allRefs.forEach(
            (refType, refIds) -> {
              eventRefs.computeIfAbsent(refType, k -> new HashSet<>()).addAll(refIds);
              byRefType.computeIfAbsent(refType, k -> new HashSet<>()).addAll(refIds);
            });
      }

      void finish(String typeName, boolean completed) {
        if (completed) {
          // Which target-type IDs are reachable from this chunk's events?
          Set<Long> reachable = bfsCpReachable(eventRefs, cpEdges, typeName);
          referencedCpCount = reachable.stream().filter(allIds::contains).count();
        }
        // Per event type: target ids reachable from its events in this chunk, through this
        // chunk's edges. The BFS starts from the event type's own refs, so it includes the direct
        // ones; the union keeps them if the graph is empty.
        refsByEventType.forEach(
            (eventType, seeds) -> {
              Set<Long> ids = bfsCpReachable(seeds, cpEdges, typeName);
              Set<Long> direct = directIdsByEventType.get(eventType);
              if (direct != null) ids.addAll(direct);
              if (!ids.isEmpty()) {
                uniqueByEventType.put(eventType, (long) ids.size());
                Long candidates = candidateTransitiveCounts.get(eventType);
                if (candidates != null) eventCounts.merge(eventType, candidates, Long::sum);
              }
            });
        allIds = null;
        cpEdges = null;
        edgeProc = null; // holds cpEdges
        eventRefs = null;
        refsByEventType = null;
        directIdsByEventType = null;
        candidateTransitiveCounts = null;
      }
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

  /**
   * Collects CP-to-CP reference edges by recording which CP entries each entry points to.
   *
   * <p>Each instance belongs to one chunk's {@code CrossrefChunk} and is driven only by that
   * chunk's thread (see {@link PerChunk}), so it keeps its cursor in plain fields: no {@code
   * ThreadLocal}, no boxed id per entry, no concurrent maps.
   */
  private static final class EdgeCollectingProcessor implements ValueProcessor {
    private final Map<String, Map<Long, Map<String, Set<Long>>>> cpEdges;
    private String currentType;
    private Map<String, Set<Long>> currentRefs;
    private long currentId;

    EdgeCollectingProcessor(Map<String, Map<Long, Map<String, Set<Long>>>> cpEdges) {
      this.cpEdges = cpEdges;
    }

    void startEntry(String type, long id) {
      currentType = type;
      currentId = id;
      currentRefs = null; // resolved on the first reference, so leaf entries add nothing
    }

    void endEntry() {
      currentType = null;
      currentRefs = null;
    }

    @Override
    public void onConstantPoolIndex(
        MetadataClass owner, String fld, MetadataClass type, long pointer) {
      if (currentType == null) return;
      if (currentRefs == null) {
        currentRefs =
            cpEdges
                .computeIfAbsent(currentType, k -> new HashMap<>())
                .computeIfAbsent(currentId, k -> new HashMap<>());
      }
      currentRefs.computeIfAbsent(type.getName(), k -> new HashSet<>()).add(pointer);
    }
  }
}
