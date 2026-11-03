package io.jafar.shell.backend.impl;

import io.jafar.parser.api.ConstantPool;
import io.jafar.parser.api.EventDecodingException;
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
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
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

    /** Chunk index -> accumulator, in chunk order. Call only after {@code parse} has returned. */
    SortedMap<Integer, T> byChunkIndex() {
      return Collections.unmodifiableSortedMap(byChunk);
    }
  }

  // ==================== MetadataSource Implementation ====================

  static final class JafarMetadataSource implements MetadataSource {

    @Override
    public Map<String, Object> loadClass(Path recording, String typeName) throws Exception {
      // Every chunk that defines the type contributes; see mergeClassDefinitions.
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

      SortedMap<Integer, Map<String, Object>> defsByChunk = new TreeMap<>();
      perChunk
          .byChunkIndex()
          .forEach(
              (chunk, slot) -> {
                if (slot[0] != null) defsByChunk.put(chunk, slot[0]);
              });
      Map<String, Object> result =
          defsByChunk.isEmpty() ? null : mergeClassDefinitions(defsByChunk);
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
      // One row per class name, not per chunk: name -> chunk -> definition, names in first-seen
      // order. Rows used to repeat for every chunk (340 rows for 304 classes in tck-test.jfr).
      Map<String, SortedMap<Integer, Map<String, Object>>> byName = new LinkedHashMap<>();
      perChunk
          .byChunkIndex()
          .forEach(
              (chunk, defs) -> {
                for (Map<String, Object> def : defs) {
                  byName
                      .computeIfAbsent((String) def.get("name"), k -> new TreeMap<>())
                      .put(chunk, def);
                }
              });
      List<Map<String, Object>> result = new ArrayList<>(byName.size());
      byName.values().forEach(defsByChunk -> result.add(mergeClassDefinitions(defsByChunk)));
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
        Map<String, Map<String, Object>> settingsByName = new LinkedHashMap<>();
        List<String> settingsDisplay = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : clazz.getSettingsByName().entrySet()) {
          String sName = e.getKey();
          @SuppressWarnings("unchecked")
          Map<String, Object> sm = (Map<String, Object>) e.getValue();
          settingsByName.put(sName, sm);
          settingsDisplay.add(settingDisplay(sName, sm));
        }
        meta.put("settingsByName", settingsByName);
        meta.put("settings", settingsDisplay);
      } catch (Throwable ignore) {
      }

      // Fields
      // Declaration order: the merge across chunks keeps it, and appends fields later chunks add.
      Map<String, Map<String, Object>> fieldsByName = new LinkedHashMap<>();
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
          fieldsDisplay.add(fieldDisplay(fm));
        } catch (Throwable ignoreField) {
          // skip problematic field
        }
      }
      meta.put("fieldsByName", fieldsByName);
      meta.put("fields", fieldsDisplay);
      meta.put("fieldCount", fieldsDisplay.size());
      return meta;
    }

    private static String settingDisplay(String name, Map<String, Object> setting) {
      Object dv = setting.get("defaultValue");
      return name + ":" + setting.get("type") + (dv != null ? (" (" + dv + ")") : "");
    }

    /** {@code name:type[]... @Ann...}, as listed under {@code fields}. */
    private static String fieldDisplay(Map<String, Object> field) {
      List<?> ann = (List<?>) field.get("annotations");
      return field.get("name")
          + ":"
          + typeWithDimensions(field)
          + (ann == null || ann.isEmpty()
              ? ""
              : (" " + String.join(" ", ann.stream().map(String::valueOf).toList())));
    }

    /**
     * What chunks must agree on for a field: type, dimensions and whether values are constant pool
     * references (which changes how they decode). Shown in {@code typesByChunk} on a conflict.
     */
    private static String typeSignature(Map<String, Object> field) {
      return typeWithDimensions(field)
          + (Boolean.TRUE.equals(field.get("hasConstantPool")) ? " (constant pool)" : "");
    }

    private static String typeWithDimensions(Map<String, Object> field) {
      Object dim = field.get("dimension");
      int d = dim instanceof Number n ? n.intValue() : -1;
      return field.get("type") + (d > 0 ? "[]".repeat(d) : "");
    }

    /**
     * Merges one class's definitions from several chunks, given as chunk index -> {@link
     * #convertClass} output, into one class.
     *
     * <p>Chunks can disagree. Metadata is written per chunk, and a recording can concatenate chunks
     * from different writers: in {@code tck-test.jfr}, 36 classes appear in both chunks with
     * different ids, and 4 of them have fields only chunk 1 declares ({@code java.lang.Thread} has
     * {@code group} and {@code virtual}). Picking one chunk's definition loses fields that exist in
     * the others, so the merge keeps everything and records where it came from:
     *
     * <ul>
     *   <li>{@code chunks}: the chunks that define the class, and on every field the chunks that
     *       define that field. A field missing from some chunks is why events from those chunks
     *       show {@code null} for it; the {@code fields} display marks it {@code [chunks: ...]}.
     *   <li>Fields: union in declaration order, later chunks' new fields appended. Annotations and
     *       settings: union, first chunk wins for a setting both define.
     *   <li>Conflicts are reported, not resolved: if chunks give a field a different type,
     *       dimension or constant-pool flag, it keeps the first chunk's and gets {@code
     *       typesByChunk} (chunk -> signature); a class whose chunks disagree on the super type
     *       gets {@code superTypesByChunk}.
     *   <li>{@code id} is the first chunk's. Class ids are chunk-local; the other chunks' ids
     *       differ and are not kept.
     * </ul>
     *
     * A class defined by one chunk comes out as that definition plus the {@code chunks} keys.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> mergeClassDefinitions(
        SortedMap<Integer, Map<String, Object>> defsByChunk) {
      List<Integer> classChunks = new ArrayList<>(defsByChunk.keySet());
      Map<String, Object> first = defsByChunk.get(classChunks.get(0));

      Map<String, Object> out = new LinkedHashMap<>();
      out.put("id", first.get("id"));
      out.put("name", first.get("name"));
      out.put("superType", first.get("superType"));

      List<String> classAnn = null;
      List<String> classAnnFull = null;
      Map<String, Map<String, Object>> settingsByName = null;
      Map<String, Map<String, Object>> fields = new LinkedHashMap<>();
      Map<String, Map<Integer, String>> typesByField = new HashMap<>();
      Map<Integer, Object> superTypes = new LinkedHashMap<>();

      for (Map.Entry<Integer, Map<String, Object>> e : defsByChunk.entrySet()) {
        Integer chunk = e.getKey();
        Map<String, Object> def = e.getValue();
        superTypes.put(chunk, def.get("superType"));
        if (def.get("classAnnotations") instanceof List<?> l) {
          classAnn = union(classAnn, (List<String>) l);
        }
        if (def.get("classAnnotationsFull") instanceof List<?> l) {
          classAnnFull = union(classAnnFull, (List<String>) l);
        }
        if (def.get("settingsByName") instanceof Map<?, ?> m) {
          if (settingsByName == null) settingsByName = new LinkedHashMap<>();
          for (Map.Entry<?, ?> se : m.entrySet()) {
            settingsByName.putIfAbsent((String) se.getKey(), (Map<String, Object>) se.getValue());
          }
        }
        if (def.get("fieldsByName") instanceof Map<?, ?> fbn) {
          for (Map.Entry<?, ?> fe : fbn.entrySet()) {
            String name = (String) fe.getKey();
            Map<String, Object> fm = (Map<String, Object>) fe.getValue();
            typesByField
                .computeIfAbsent(name, k -> new LinkedHashMap<>())
                .put(chunk, typeSignature(fm));
            Map<String, Object> merged = fields.get(name);
            if (merged == null) {
              merged = new HashMap<>(fm);
              merged.put("chunks", new ArrayList<>(List.of(chunk)));
              fields.put(name, merged);
            } else {
              ((List<Integer>) merged.get("chunks")).add(chunk);
              merged.put(
                  "annotations",
                  union(
                      (List<String>) merged.get("annotations"),
                      (List<String>) fm.get("annotations")));
              merged.put(
                  "annotationsFull",
                  union(
                      (List<String>) merged.get("annotationsFull"),
                      (List<String>) fm.get("annotationsFull")));
            }
          }
        }
      }

      if (classAnn != null) out.put("classAnnotations", classAnn);
      if (classAnnFull != null) out.put("classAnnotationsFull", classAnnFull);
      if (settingsByName != null) {
        List<String> settings = new ArrayList<>(settingsByName.size());
        settingsByName.forEach((name, sm) -> settings.add(settingDisplay(name, sm)));
        out.put("settingsByName", settingsByName);
        out.put("settings", settings);
      }

      List<String> display = new ArrayList<>(fields.size());
      for (Map.Entry<String, Map<String, Object>> fe : fields.entrySet()) {
        Map<String, Object> fm = fe.getValue();
        StringBuilder line = new StringBuilder(fieldDisplay(fm));
        List<Integer> fieldChunks = (List<Integer>) fm.get("chunks");
        if (!fieldChunks.equals(classChunks)) {
          line.append(" [chunks: ").append(joinChunks(fieldChunks)).append(']');
        }
        Map<Integer, String> types = typesByField.get(fe.getKey());
        if (new HashSet<>(types.values()).size() > 1) {
          fm.put("typesByChunk", types);
          StringBuilder t = new StringBuilder();
          types.forEach(
              (c, type) ->
                  t.append(t.length() == 0 ? "" : ", ").append(c).append('=').append(type));
          line.append(" [types by chunk: ").append(t).append(']');
        }
        display.add(line.toString());
      }
      out.put("fieldsByName", fields);
      out.put("fields", display);
      out.put("fieldCount", display.size());
      out.put("chunks", classChunks);
      if (new HashSet<>(superTypes.values()).size() > 1) {
        out.put("superTypesByChunk", superTypes);
      }
      return out;
    }

    private static List<String> union(List<String> acc, List<String> more) {
      if (acc == null) return more == null ? null : new ArrayList<>(more);
      if (more == null) return acc;
      List<String> out = new ArrayList<>(acc);
      for (String s : more) {
        if (!out.contains(s)) out.add(s);
      }
      return out;
    }

    private static String joinChunks(List<Integer> chunks) {
      StringBuilder sb = new StringBuilder();
      for (Integer c : chunks) sb.append(sb.length() == 0 ? "" : ", ").append(c);
      return sb.toString();
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
      // By the index the rows carry (1-based, file order), which is what `chunks` lists and
      // completion offers. This used to be a 0-based position, so `chunks/1` showed chunk 2.
      for (ChunkHeader header : loadHeaders(recording)) {
        if (header.order == chunkIndex) {
          return createChunkRow(header.order, header);
        }
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

  static final class JafarConstantPoolSource implements ConstantPoolSource {

    /**
     * Returns {@code false} at a chunk's last checkpoint, for listeners that need its pools but not
     * its events. {@code StreamingChunkParser.readConstantPool} follows the checkpoint chain until
     * {@code nextOffsetDelta == 0} and reports that checkpoint last, so the chunk's pools are
     * complete there; returning {@code false} skips the event section, and {@code onChunkEnd} still
     * runs. Without it these listeners walked every event of the chunk for nothing.
     */
    private static boolean continueUnlessLastCheckpoint(CheckpointEvent event) {
      return event.nextOffsetDelta != 0;
    }

    /**
     * Entries per pool type, summed over chunks.
     *
     * <p>Counted once per chunk, at chunk end. A chunk's pools accumulate across its checkpoints
     * ({@code MutableConstantPools.addOrGetConstantPool}), so counting them at every checkpoint
     * counts an entry once per checkpoint it survives. Chunks are parsed in parallel; each chunk's
     * counts are merged under a lock.
     */
    @Override
    public List<Map<String, Object>> loadSummary(Path recording) throws Exception {
      final Map<String, Long> sizes = new HashMap<>(); // guarded by itself
      try (StreamingChunkParser parser =
          new StreamingChunkParser(new UntypedParserContextFactory())) {
        parser.parse(
            recording,
            new ChunkParserListener() {
              @Override
              public boolean onCheckpoint(ParserContext context, CheckpointEvent event) {
                return continueUnlessLastCheckpoint(event);
              }

              @Override
              public boolean onChunkEnd(ParserContext context, int chunkIndex, boolean skipped) {
                Map<String, Long> chunk = new HashMap<>();
                context
                    .getConstantPools()
                    .pools()
                    .forEach(
                        cp ->
                            chunk.merge(cp.getType().getName(), (long) cp.entryCount(), Long::sum));
                synchronized (sizes) {
                  chunk.forEach((name, count) -> sizes.merge(name, count, Long::sum));
                }
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
      rows.sort(
          (a, b) -> {
            int bySize = Long.compare((long) b.get("totalSize"), (long) a.get("totalSize"));
            return bySize != 0
                ? bySize
                : ((String) a.get("name")).compareTo((String) b.get("name"));
          });
      return rows;
    }

    @Override
    public List<Map<String, Object>> loadEntries(Path recording, String typeName) throws Exception {
      return loadEntries(recording, typeName, null);
    }

    /**
     * Every entry of {@code typeName}, in chunk order.
     *
     * <p>Each chunk's entries are read once, at chunk end, when its pools hold every checkpoint's
     * entries. They used to be drained at every checkpoint from the accumulated pools, so an entry
     * appeared once per checkpoint it survived. Ids are chunk-local: rows from different chunks are
     * different entries even when their ids are equal. Chunks are parsed in parallel; {@code
     * filter} runs on the parser's worker threads and must not depend on shared mutable state.
     */
    @Override
    public List<Map<String, Object>> loadEntries(
        Path recording, String typeName, Predicate<Map<String, Object>> filter) throws Exception {
      final Map<Integer, List<Map<String, Object>>> rowsByChunk = new TreeMap<>(); // guarded
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
                return true;
              }

              @Override
              public boolean onCheckpoint(ParserContext context, CheckpointEvent event) {
                return continueUnlessLastCheckpoint(event);
              }

              @Override
              public boolean onChunkEnd(ParserContext context, int chunkIndex, boolean skipped) {
                List<Map<String, Object>> rows = new ArrayList<>();
                Iterator<? extends ConstantPool> pools =
                    context.getConstantPools().pools().iterator();
                while (pools.hasNext()) {
                  ConstantPool cp = pools.next();
                  if (cp.getType().getName().equals(typeName)) {
                    drain(cp, filter, rows);
                  }
                }
                context.remove(GenericValueReader.class);
                synchronized (rowsByChunk) {
                  rowsByChunk.put(chunkIndex, rows);
                }
                return true;
              }
            });
      }
      List<Map<String, Object>> rows = new ArrayList<>();
      rowsByChunk.values().forEach(rows::addAll);
      return rows;
    }

    private static void drain(
        ConstantPool cp, Predicate<Map<String, Object>> filter, List<Map<String, Object>> rows) {
      cp.ensureIndexed();
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
      // Read at chunk end, when the chunk's pools hold every checkpoint's types. This used to read
      // them in the first onCheckpoint and return false, which skipped the rest of the checkpoint
      // chain: only the pools of the first checkpoint read (the chunk's last) were seen.
      Set<String> types = new HashSet<>(); // guarded by itself
      try (StreamingChunkParser parser =
          new StreamingChunkParser(new UntypedParserContextFactory())) {
        parser.parse(
            recording,
            new ChunkParserListener() {
              @Override
              public boolean onEvent(
                  ParserContext context,
                  long typeId,
                  long eventStartPos,
                  long rawSize,
                  long payloadSize) {
                // The whole checkpoint chain is read before the first event; the events are not
                // needed. Returning false skips them, and onChunkEnd still runs.
                return false;
              }

              @Override
              public boolean onChunkEnd(ParserContext context, int chunkIndex, boolean skipped) {
                Set<String> chunk = new HashSet<>();
                context.getConstantPools().pools().forEach(cp -> chunk.add(cp.getType().getName()));
                synchronized (types) {
                  types.addAll(chunk);
                }
                return true;
              }
            });
      }
      return types;
    }

    /**
     * Crosschecks the {@code typeName} pool against event references.
     *
     * <p>Constant pool ids are chunk-local: id 5 in chunk 1 and id 5 in chunk 2 are different
     * entries. So everything is computed per chunk, on that chunk's ids, and only numbers are added
     * up across chunks:
     *
     * <ul>
     *   <li>{@code cpTotal}: entries of the type, counted once per chunk at chunk end. A pool
     *       accumulates across the chunk's checkpoints, so counting it at every checkpoint counts
     *       an entry once per checkpoint it survives.
     *   <li>{@code cpReferenced}: entries reachable from the chunk's events, directly or through
     *       other pool entries.
     *   <li>{@code uniqueRefsByEventType}: per event type, entries reachable from that type's
     *       events, summed over chunks.
     * </ul>
     *
     * <p>Chunks are parsed in parallel, but each chunk by one thread from {@code onChunkStart} to
     * {@code onChunkEnd}; per-chunk state lives in that chunk's {@link ParserContext} and is merged
     * into the totals under a lock at chunk end. Every pool entry is decoded once, at chunk end,
     * when the chunk's pools are complete: at its first event ({@code StreamingChunkParser} reads
     * the whole checkpoint chain before any event), or at chunk end if it has none. Decoding before
     * the events also means a corrupted pool entry is reported as itself, not as the first event
     * that points at it.
     */
    @Override
    public Map<String, Object> crossref(Path recording, String typeName) throws Exception {
      CrossrefTotals totals = new CrossrefTotals();
      try (StreamingChunkParser parser =
          new StreamingChunkParser(new UntypedParserContextFactory())) {
        parser.parse(
            recording,
            new ChunkParserListener() {
              @Override
              public boolean onChunkStart(
                  ParserContext context, int chunkIndex, ChunkHeader header) {
                context.put(ChunkState.class, new ChunkState(typeName));
                return true;
              }

              @Override
              public boolean onMetadata(ParserContext context, MetadataEvent metadata) {
                chunkState(context).ancestorTypes = buildAncestorTypes(metadata, typeName);
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
                ChunkState state = chunkState(context);
                RecordingStream stream = recordingStream(context);
                state.decodePoolsOnce(context, stream);
                try {
                  state.reader.readValue(stream, eventType);
                } catch (Exception e) {
                  throw new EventDecodingException(
                      eventType.getName(),
                      typeId,
                      context.getChunkIndex(),
                      eventStartPos,
                      rawSize,
                      e);
                }
                state.endEvent(eventType.getName());
                return true;
              }

              @Override
              public boolean onChunkEnd(ParserContext context, int chunkIndex, boolean skipped) {
                ChunkState state = context.remove(ChunkState.class);
                if (state == null) {
                  throw new IllegalStateException("no crossref state for chunk " + chunkIndex);
                }
                state.decodePoolsOnce(context, recordingStream(context));
                totals.add(state.finish());
                return true;
              }
            });
      } catch (IOException | RuntimeException e) {
        RuntimeException failure = findDecodeFailure(e);
        if (failure == null) {
          throw e;
        }
        // The parser wraps a listener failure in several layers with generic messages; the shell
        // prints only the top message, so rethrow with the one that says what went wrong.
        throw new IOException(
            "crossref("
                + typeName
                + ") aborted: "
                + failure.getMessage()
                + ": "
                + failure.getCause()
                + "; counts from a partial read would be wrong, so none are reported",
            failure); // keeps EventDecodingException's typed fields reachable for callers
      }
      return totals.toResult(typeName);
    }

    private static ChunkState chunkState(ParserContext context) {
      ChunkState state = context.get(ChunkState.class);
      if (state == null) {
        throw new IllegalStateException(
            "no crossref state for chunk " + context.getChunkIndex() + " (onChunkStart not seen)");
      }
      return state;
    }

    private static RecordingStream recordingStream(ParserContext context) {
      RecordingStream stream = context.get(RecordingStream.class);
      if (stream == null) {
        throw new IllegalStateException(
            "no RecordingStream in the parser context of chunk " + context.getChunkIndex());
      }
      return stream;
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
     * Target-type ids reachable from {@code seeds} over the pool-to-pool {@code edges}, seeds
     * included. Worklist traversal: each (type, id) is expanded once.
     */
    private static Set<Long> reachableTargets(
        Map<String, Set<Long>> seeds,
        Map<String, Map<Long, Map<String, Set<Long>>>> edges,
        String targetType) {
      Map<String, Set<Long>> visited = new HashMap<>();
      List<String> workTypes = new ArrayList<>();
      List<Long> workIds = new ArrayList<>();
      for (Map.Entry<String, Set<Long>> e : seeds.entrySet()) {
        Set<Long> seen = visited.computeIfAbsent(e.getKey(), k -> new HashSet<>());
        for (Long id : e.getValue()) {
          if (seen.add(id)) {
            workTypes.add(e.getKey());
            workIds.add(id);
          }
        }
      }
      while (!workTypes.isEmpty()) {
        int last = workTypes.size() - 1;
        String type = workTypes.remove(last);
        Long id = workIds.remove(last);
        Map<Long, Map<String, Set<Long>>> typeEdges = edges.get(type);
        if (typeEdges == null) continue;
        Map<String, Set<Long>> out = typeEdges.get(id);
        if (out == null) continue;
        for (Map.Entry<String, Set<Long>> ref : out.entrySet()) {
          Set<Long> seen = visited.computeIfAbsent(ref.getKey(), k -> new HashSet<>());
          for (Long r : ref.getValue()) {
            if (seen.add(r)) {
              workTypes.add(ref.getKey());
              workIds.add(r);
            }
          }
        }
      }
      Set<Long> reached = visited.get(targetType);
      return reached != null ? reached : Collections.emptySet();
    }
  }

  /**
   * A constant pool entry {@code crossref} could not decode; an event it could not decode is an
   * {@link EventDecodingException}, whose message shape this follows. Unchecked because {@link
   * ChunkParserListener} callbacks cannot throw checked exceptions; {@code crossref} unwraps it
   * from the parser's wrapping and rethrows it as an {@link IOException}. Like {@code
   * EventDecodingException} it records no stack trace of its own: it never leaves {@code crossref}
   * (only its cause does), and the cause's trace covers the frames where decoding failed.
   */
  private static final class CrossrefDecodeFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private CrossrefDecodeFailure(String message, Exception cause) {
      super(message, cause, false, false);
    }

    /** {@code id} is chunk-local; {@code position} is the entry's offset within the chunk. */
    static CrossrefDecodeFailure poolEntry(
        String typeName, long id, int chunkIndex, long position, Exception cause) {
      return new CrossrefDecodeFailure(
          "Cannot decode constant pool entry "
              + typeName
              + "#"
              + id
              + " in chunk "
              + chunkIndex
              + " at position "
              + position,
          cause);
    }
  }

  /** The first {@code crossref} decode failure in {@code t}'s cause chain, or {@code null}. */
  private static RuntimeException findDecodeFailure(Throwable t) {
    for (Throwable c = t; c != null; c = c.getCause()) {
      if (c instanceof CrossrefDecodeFailure || c instanceof EventDecodingException) {
        return (RuntimeException) c;
      }
    }
    return null;
  }

  /**
   * Everything {@code crossref} collects for one chunk. Only the thread parsing that chunk touches
   * it, from {@code onChunkStart} to {@code onChunkEnd}, so it needs no synchronisation.
   */
  private static final class ChunkState implements ValueProcessor {
    private final String targetType;
    final GenericValueReader reader = new GenericValueReader(this);

    /** Pool types that can reach {@code targetType}, from this chunk's metadata. */
    Set<String> ancestorTypes = Collections.emptySet();

    /**
     * Per event type: {@code [events with a direct ref, events with only ancestor-type refs
     * (counted once a search confirms the type reaches the target), direct refs]}.
     */
    private final Map<String, long[]> eventStats = new HashMap<>();

    /** Per event type: referenced pool type to ids. Seeds for the searches. */
    private final Map<String, Map<String, Set<Long>>> refsByEventType = new HashMap<>();

    /** Pool-to-pool edges: source type, source id, referenced type, referenced ids. */
    private final Map<String, Map<Long, Map<String, Set<Long>>>> edges = new HashMap<>();

    /** Ids of {@code targetType} entries defined in this chunk. */
    private final Set<Long> targetIds = new HashSet<>();

    // References of the event being decoded; the lists are reused from event to event.
    private final Map<String, LongList> eventRefs = new HashMap<>();
    private final List<LongList> touched = new ArrayList<>();

    // The pool entry being decoded; edgeType is null while events are decoded.
    private String edgeType;
    private Long edgeId;
    private Map<String, Set<Long>> edgeRefs;

    ChunkState(String targetType) {
      this.targetType = targetType;
    }

    @Override
    public void onConstantPoolIndex(
        MetadataClass owner, String fld, MetadataClass type, long pointer) {
      if (edgeType != null) {
        if (edgeRefs == null) {
          edgeRefs =
              edges
                  .computeIfAbsent(edgeType, k -> new HashMap<>())
                  .computeIfAbsent(edgeId, k -> new HashMap<>());
        }
        edgeRefs.computeIfAbsent(type.getName(), k -> new HashSet<>()).add(pointer);
        return;
      }
      String name = type.getName();
      LongList refs = eventRefs.get(name);
      if (refs == null) {
        refs = new LongList(name);
        eventRefs.put(name, refs);
      }
      if (refs.size == 0) {
        touched.add(refs);
      }
      refs.add(pointer);
    }

    /** Folds the references of the event just decoded into the chunk's totals. */
    void endEvent(String eventType) {
      if (touched.isEmpty()) {
        return;
      }
      LongList direct = eventRefs.get(targetType);
      if (direct != null && direct.size > 0) {
        long[] stats = eventStats.computeIfAbsent(eventType, k -> new long[3]);
        stats[0]++;
        stats[2] += direct.size;
      } else {
        for (LongList refs : touched) {
          if (ancestorTypes.contains(refs.type)) {
            eventStats.computeIfAbsent(eventType, k -> new long[3])[1]++;
            break;
          }
        }
      }
      Map<String, Set<Long>> byType =
          refsByEventType.computeIfAbsent(eventType, k -> new HashMap<>());
      for (LongList refs : touched) {
        Set<Long> ids = byType.computeIfAbsent(refs.type, k -> new HashSet<>());
        for (int i = 0; i < refs.size; i++) {
          ids.add(refs.values[i]);
        }
        refs.size = 0;
      }
      touched.clear();
    }

    private boolean poolsDecoded;

    /**
     * Decodes every pool entry of the chunk, once, collecting pool-to-pool edges and the target
     * type's ids. Must be called after the chunk's last checkpoint has been read.
     */
    void decodePoolsOnce(ParserContext context, RecordingStream stream) {
      if (poolsDecoded) {
        return;
      }
      poolsDecoded = true;
      long saved = stream.position();
      try {
        Iterator<? extends ConstantPool> pools = context.getConstantPools().pools().iterator();
        while (pools.hasNext()) {
          MutableConstantPool pool = (MutableConstantPool) pools.next();
          MetadataClass type = pool.getType();
          String name = type.getName();
          boolean target = name.equals(targetType);
          Iterator<Long> ids = pool.ids();
          while (ids.hasNext()) {
            Long id = ids.next();
            long offset = pool.getOffset(id);
            if (offset == 0) continue;
            if (target) {
              targetIds.add(id);
            }
            edgeType = name;
            edgeId = id;
            edgeRefs = null;
            try {
              stream.position(offset);
              reader.readValue(stream, type);
            } catch (Exception e) {
              throw CrossrefDecodeFailure.poolEntry(name, id, context.getChunkIndex(), offset, e);
            } finally {
              edgeType = null;
              edgeId = null;
              edgeRefs = null;
            }
          }
        }
      } finally {
        stream.position(saved);
      }
    }

    /** Runs the chunk's searches; the result holds only counts, so ids never leave the chunk. */
    ChunkResult finish() {
      ChunkResult result = new ChunkResult();
      result.cpTotal = targetIds.size();
      Map<String, Set<Long>> allRefs = new HashMap<>();
      for (Map.Entry<String, Map<String, Set<Long>>> e : refsByEventType.entrySet()) {
        String eventType = e.getKey();
        Map<String, Set<Long>> refs = e.getValue();
        refs.forEach(
            (type, ids) -> allRefs.computeIfAbsent(type, k -> new HashSet<>()).addAll(ids));
        long[] stats = eventStats.get(eventType);
        long events = stats != null ? stats[0] : 0;
        long directRefs = stats != null ? stats[2] : 0;
        long unique = JafarConstantPoolSource.reachableTargets(refs, edges, targetType).size();
        if (unique > 0 && stats != null) {
          events += stats[1];
        }
        if (events > 0 || directRefs > 0 || unique > 0) {
          result.perEventType.put(eventType, new long[] {events, directRefs, unique});
        }
      }
      for (Long id : JafarConstantPoolSource.reachableTargets(allRefs, edges, targetType)) {
        if (targetIds.contains(id)) {
          result.cpReferenced++;
        }
      }
      return result;
    }
  }

  /** One chunk's counts. Per event type: {@code [events, direct refs, unique entries]}. */
  private static final class ChunkResult {
    long cpTotal;
    long cpReferenced;
    final Map<String, long[]> perEventType = new HashMap<>();
  }

  /** Sums of {@link ChunkResult}s, added from the parser's worker threads. */
  private static final class CrossrefTotals {
    private long cpTotal;
    private long cpReferenced;
    private final Map<String, long[]> perEventType = new HashMap<>();

    synchronized void add(ChunkResult chunk) {
      cpTotal += chunk.cpTotal;
      cpReferenced += chunk.cpReferenced;
      chunk.perEventType.forEach(
          (type, counts) -> {
            long[] sum = perEventType.computeIfAbsent(type, k -> new long[3]);
            for (int i = 0; i < 3; i++) {
              sum[i] += counts[i];
            }
          });
    }

    synchronized Map<String, Object> toResult(String typeName) {
      Map<String, Long> eventCounts = new HashMap<>();
      Map<String, Long> totalRefsByEventType = new HashMap<>();
      Map<String, Long> uniqueRefsByEventType = new HashMap<>();
      perEventType.forEach(
          (type, sum) -> {
            if (sum[0] > 0) eventCounts.put(type, sum[0]);
            if (sum[1] > 0) totalRefsByEventType.put(type, sum[1]);
            if (sum[2] > 0) uniqueRefsByEventType.put(type, sum[2]);
          });
      long unused = cpTotal - cpReferenced;
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("type", typeName);
      result.put("cpTotal", cpTotal);
      result.put("cpReferenced", cpReferenced);
      result.put("cpUnused", unused);
      result.put("usedPercent", cpTotal == 0 ? 0.0 : 100.0 * cpReferenced / cpTotal);
      result.put("unusedPercent", cpTotal == 0 ? 0.0 : 100.0 * unused / cpTotal);
      result.put("eventCounts", eventCounts);
      result.put("uniqueRefsByEventType", uniqueRefsByEventType);
      result.put("totalRefsByEventType", totalRefsByEventType);
      return result;
    }
  }

  /** A growable {@code long[]} tagged with the pool type its values point into. */
  private static final class LongList {
    final String type;
    long[] values = new long[4];
    int size;

    LongList(String type) {
      this.type = type;
    }

    void add(long value) {
      if (size == values.length) {
        values = Arrays.copyOf(values, size * 2);
      }
      values[size++] = value;
    }
  }
}
