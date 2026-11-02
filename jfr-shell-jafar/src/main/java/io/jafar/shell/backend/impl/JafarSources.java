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
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
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
                  ref.set(convertClass(clazz));
                } else {
                  if (System.getProperty("jfr.shell.completion.debug") != null) {
                    System.err.println("[DEBUG]   Class NOT found: " + typeName);
                  }
                }
                return false;
              }
            });
      }

      if (System.getProperty("jfr.shell.completion.debug") != null) {
        System.err.println(
            "[DEBUG] JafarMetadataSource.loadClass() returning: "
                + (ref.get() != null ? "non-null" : "NULL"));
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
                  throw CrossrefDecodeFailure.event(
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
        CrossrefDecodeFailure failure = findCause(e, CrossrefDecodeFailure.class);
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
            failure.getCause());
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
   * A value {@code crossref} could not decode. Unchecked because {@link ChunkParserListener}
   * callbacks cannot throw checked exceptions; {@code crossref} unwraps it from the parser's
   * wrapping and rethrows it as an {@link IOException}.
   *
   * <p>The event message has the same shape as {@code io.jafar.parser.api.EventDecodingException}
   * (btraceio/jafar#135). Like that exception it records no stack trace of its own: it never leaves
   * {@code crossref} (only its cause does), and the cause's trace covers the frames where decoding
   * failed.
   */
  private static final class CrossrefDecodeFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private CrossrefDecodeFailure(String message, Exception cause) {
      super(message, cause, false, false);
    }

    /** {@code position} is the event start within the chunk; {@code size} includes its header. */
    static CrossrefDecodeFailure event(
        String typeName, long typeId, int chunkIndex, long position, long size, Exception cause) {
      return new CrossrefDecodeFailure(
          "Cannot decode event of type "
              + typeName
              + " (id "
              + typeId
              + ") in chunk "
              + chunkIndex
              + " at position "
              + position
              + " (size "
              + size
              + ")",
          cause);
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

  private static <T extends Throwable> T findCause(Throwable t, Class<T> type) {
    for (Throwable c = t; c != null; c = c.getCause()) {
      if (type.isInstance(c)) {
        return type.cast(c);
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
