package io.jafar.shell;

import io.jafar.parser.api.HandlerRegistration;
import io.jafar.parser.api.JFRHandler;
import io.jafar.parser.api.ParserContext;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.TypedJafarParser;
import io.jafar.parser.impl.UntypedParserContextFactory;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.StreamingChunkParser;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.internal_api.metadata.MetadataEvent;
import io.jafar.shell.core.Session;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Represents an active JFR analysis session with a loaded recording. Manages the parser, context,
 * and provides statistics about the recording.
 */
public final class JFRSession implements Session {

  private final Path recordingPath;
  private final ParsingContext parsingContext;
  private TypedJafarParser parser;
  private final List<HandlerRegistration<?>> registrations = new ArrayList<>();
  // Replaced whole by scanMetadata(); never mutated after publication.
  private volatile TypeIndex types = TypeIndex.EMPTY;
  // Keys are fixed by scanMetadata(); values are incremented from parser threads during run().
  private volatile ConcurrentHashMap<String, Long> eventTypeCounts = new ConcurrentHashMap<>();
  private List<Integer> cachedChunkIds; // lazily discovered, guarded by this
  private Set<String> cpTypes; // lazily discovered, guarded by this
  // A failed lazy scan, rethrown until refreshTypes(): completion asks on every TAB, and re-reading
  // a large broken recording up to the failure point each time would stall every keypress.
  private UncheckedIOException chunkIdsFailure; // guarded by this
  private UncheckedIOException cpTypesFailure; // guarded by this
  private final AtomicLong totalEvents = new AtomicLong(0);
  private final AtomicInteger handlerCount = new AtomicInteger(0);
  private boolean hasRun = false;
  private volatile boolean closed = false;

  public JFRSession(Path recordingPath, ParsingContext parsingContext) throws IOException {
    this.recordingPath = recordingPath;
    this.parsingContext = parsingContext;
    this.parser = parsingContext.newTypedParser(recordingPath);
    // Populate the available types up front. There is deliberately no second collector on the
    // typed parser: it would re-read the same metadata from parser threads during run(), racing
    // with readers, and add nothing this scan has not already found.
    try {
      scanMetadata();
    } catch (IOException | RuntimeException e) {
      // The caller never receives this instance, so nobody else can close the typed parser.
      try {
        parser.close();
      } catch (Exception closeFailure) {
        e.addSuppressed(closeFailure);
      }
      throw e;
    }
  }

  /**
   * Types discovered in the recording, merged from every chunk in recording order. Sets keep the
   * order in which types were first seen. For a name defined by several chunks, {@code
   * metadataTypeIds} holds the id from the last chunk (type ids are chunk-local), which is what a
   * sequential parse overwriting one map would return.
   */
  private record TypeIndex(
      Set<String> eventTypes,
      Set<String> metadataTypes,
      Set<String> allMetadataTypes,
      Set<String> nonPrimitiveMetadataTypes,
      Set<String> primitiveMetadataTypes,
      Map<String, Long> metadataTypeIds) {

    static final TypeIndex EMPTY =
        new TypeIndex(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Map.of());
  }

  /** What one chunk's metadata contributes; only that chunk's parser thread touches it. */
  private static final class ChunkTypes {
    final Set<String> eventTypes = new LinkedHashSet<>();
    final Set<String> metadataTypes = new LinkedHashSet<>();
    final Set<String> allMetadataTypes = new LinkedHashSet<>();
    final Set<String> nonPrimitiveMetadataTypes = new LinkedHashSet<>();
    final Set<String> primitiveMetadataTypes = new LinkedHashSet<>();
    final Map<String, Long> metadataTypeIds = new LinkedHashMap<>();
  }

  /**
   * Scan the recording file to collect metadata and populate available event types. Uses
   * StreamingChunkParser directly and skips each chunk's events once its metadata is read.
   *
   * <p>{@code StreamingChunkParser} parses chunks on a thread pool, so {@code onMetadata} runs
   * concurrently for different chunks. Each chunk collects into its own {@link ChunkTypes}, and the
   * chunks are merged in recording order after {@code parse} returns.
   *
   * @throws IOException if the recording cannot be parsed
   */
  private void scanMetadata() throws IOException {
    ConcurrentSkipListMap<Integer, ChunkTypes> byChunk = new ConcurrentSkipListMap<>();
    try (StreamingChunkParser parser =
        new StreamingChunkParser(new UntypedParserContextFactory())) {
      parser.parse(
          recordingPath,
          new ChunkParserListener() {
            @Override
            public boolean onMetadata(ParserContext context, MetadataEvent metadata) {
              ChunkTypes out =
                  byChunk.computeIfAbsent(context.getChunkIndex(), k -> new ChunkTypes());
              Map<String, MetadataClass> byName = new HashMap<>();
              for (MetadataClass mc : metadata.getClasses()) byName.put(mc.getName(), mc);
              for (MetadataClass clazz : metadata.getClasses()) {
                out.allMetadataTypes.add(clazz.getName());
                out.metadataTypeIds.put(clazz.getName(), clazz.getId());
                if (!clazz.isPrimitive()) {
                  out.nonPrimitiveMetadataTypes.add(clazz.getName());
                } else {
                  out.primitiveMetadataTypes.add(clazz.getName());
                }
                if (clazz.getName() != null && isEventType(clazz, byName)) {
                  out.eventTypes.add(clazz.getName());
                }
                if (isBrowsableMetadataType(clazz)) {
                  out.metadataTypes.add(clazz.getName());
                }
              }
              // Skip this chunk's events; metadata is all this scan needs.
              return false;
            }
          });
    } catch (IOException e) {
      throw e;
    } catch (Exception e) {
      // StreamingChunkParser.close() declares Exception
      throw new IOException("Failed to scan metadata of " + recordingPath, e);
    }

    // parse() has joined every chunk task, so the per-chunk collections are safely published.
    ChunkTypes merged = new ChunkTypes();
    for (ChunkTypes chunk : byChunk.values()) { // ascending chunk index
      merged.eventTypes.addAll(chunk.eventTypes);
      merged.metadataTypes.addAll(chunk.metadataTypes);
      merged.allMetadataTypes.addAll(chunk.allMetadataTypes);
      merged.nonPrimitiveMetadataTypes.addAll(chunk.nonPrimitiveMetadataTypes);
      merged.primitiveMetadataTypes.addAll(chunk.primitiveMetadataTypes);
      merged.metadataTypeIds.putAll(chunk.metadataTypeIds);
    }
    ConcurrentHashMap<String, Long> counts = new ConcurrentHashMap<>();
    for (String t : merged.eventTypes) counts.put(t, 0L);
    types =
        new TypeIndex(
            Collections.unmodifiableSet(merged.eventTypes),
            Collections.unmodifiableSet(merged.metadataTypes),
            Collections.unmodifiableSet(merged.allMetadataTypes),
            Collections.unmodifiableSet(merged.nonPrimitiveMetadataTypes),
            Collections.unmodifiableSet(merged.primitiveMetadataTypes),
            Collections.unmodifiableMap(merged.metadataTypeIds));
    eventTypeCounts = counts;
  }

  /**
   * Refresh discovered event types by rescanning metadata. Also drops the lazily discovered chunk
   * ids and constant-pool types, and any failure recorded while discovering them, so the next call
   * rescans them.
   *
   * @throws IOException if the recording cannot be parsed; the previous types are kept
   */
  public synchronized void refreshTypes() throws IOException {
    cachedChunkIds = null;
    cpTypes = null;
    chunkIdsFailure = null;
    cpTypesFailure = null;
    scanMetadata();
  }

  /**
   * Register a handler for events of the specified type.
   *
   * @param <T> the event type
   * @param eventClass the event class to handle
   * @param handler the handler function
   * @return the handler registration
   */
  public <T> HandlerRegistration<T> handle(Class<T> eventClass, JFRHandler<T> handler) {
    if (hasRun) {
      throw new IllegalStateException("Cannot register handlers after run() has been called");
    }

    final String counted = countedTypeOf(eventClass);

    // Wrap handler to count events
    JFRHandler<T> countingHandler =
        (event, ctl) -> {
          totalEvents.incrementAndGet();
          if (counted != null) {
            // Called from parser threads, one per chunk: computeIfPresent on a ConcurrentHashMap
            // is atomic. Read the field each time: refreshTypes() may have replaced the map.
            eventTypeCounts.computeIfPresent(counted, (k, v) -> v + 1);
          }
          handler.handle(event, ctl);
        };

    HandlerRegistration<T> registration = parser.handle(eventClass, countingHandler);
    registrations.add(registration);
    handlerCount.incrementAndGet();
    return registration;
  }

  /**
   * The JFR event type that events of {@code eventClass} are counted under, or {@code null}. The
   * class's {@code @JfrType} name wins when the recording has that type; otherwise the first type,
   * in recording order, whose name loosely matches the class's simple name. Iterating the recording
   * order (not a hash order) keeps an ambiguous match, such as {@code JFRExecutionSample} against
   * both {@code jdk.ExecutionSample} and {@code datadog.ExecutionSample}, the same on every run.
   */
  private String countedTypeOf(Class<?> eventClass) {
    Set<String> eventTypes = types.eventTypes();
    io.jafar.parser.api.JfrType declared =
        eventClass.getAnnotation(io.jafar.parser.api.JfrType.class);
    if (declared != null && eventTypes.contains(declared.value())) {
      return declared.value();
    }
    String typeName = eventClass.getSimpleName().replace("JFR", "").toLowerCase();
    for (String key : eventTypes) {
      if (key.toLowerCase().contains(typeName)
          || typeName.contains(key.toLowerCase().replaceAll(".*\\.", ""))) {
        return key;
      }
    }
    return null;
  }

  /**
   * Run the parser and invoke all registered handlers.
   *
   * @throws IOException if parsing fails
   */
  public void run() throws IOException {
    if (hasRun) {
      throw new IllegalStateException("Session has already been run");
    }

    long startTime = System.currentTimeMillis();
    parser.run();
    long endTime = System.currentTimeMillis();
    hasRun = true;

    System.out.printf("Processed %d events in %d ms%n", totalEvents.get(), (endTime - startTime));
  }

  /**
   * Get available types discovered in this recording.
   *
   * @return set of type names
   */
  public Set<String> getAvailableTypes() {
    return types.eventTypes();
  }

  /** Get available metadata (non-event) types discovered in this recording. */
  public Set<String> getAvailableMetadataTypes() {
    return types.metadataTypes();
  }

  /** Get all metadata classes (events and non-events). */
  public Set<String> getAllMetadataTypes() {
    return types.allMetadataTypes();
  }

  /** Get all non-primitive metadata class names. */
  public Set<String> getNonPrimitiveMetadataTypes() {
    return types.nonPrimitiveMetadataTypes();
  }

  /** Get all primitive metadata class names. */
  public Set<String> getPrimitiveMetadataTypes() {
    return types.primitiveMetadataTypes();
  }

  /**
   * Get map of metadata type names to their type IDs. Type ids are chunk-local; for a name defined
   * by several chunks this is the id from the last chunk in recording order.
   */
  public Map<String, Long> getMetadataTypeIds() {
    return types.metadataTypeIds();
  }

  /**
   * Get available chunk ids (1-based, in recording order) discovered by reading chunk headers.
   *
   * @throws UncheckedIOException if the recording cannot be parsed; the same failure is thrown
   *     again, without re-reading the file, until {@link #refreshTypes()}
   */
  public synchronized List<Integer> getAvailableChunkIds() {
    if (cachedChunkIds != null) return cachedChunkIds;
    if (chunkIdsFailure != null) throw chunkIdsFailure;
    // onChunkStart runs on one pool thread per chunk; the skip-list set orders by index.
    Set<Integer> ids = new ConcurrentSkipListSet<>();
    try (StreamingChunkParser parser =
        new StreamingChunkParser(new UntypedParserContextFactory())) {
      parser.parse(
          recordingPath,
          new ChunkParserListener() {
            @Override
            public boolean onChunkStart(
                ParserContext context,
                int chunkIndex,
                io.jafar.parser.internal_api.ChunkHeader header) {
              ids.add(chunkIndex);
              return false; // the header is all we need; skip metadata and events
            }
          });
    } catch (Exception e) {
      throw chunkIdsFailure = failure("list chunks of", e);
    }
    cachedChunkIds = List.copyOf(ids);
    return cachedChunkIds;
  }

  /**
   * Get available constant-pool types discovered in this recording (lazy scan).
   *
   * @throws UncheckedIOException if the recording cannot be parsed; the same failure is thrown
   *     again, without re-reading the file, until {@link #refreshTypes()}
   */
  public synchronized Set<String> getAvailableConstantPoolTypes() {
    if (cpTypes != null) return cpTypes;
    if (cpTypesFailure != null) throw cpTypesFailure;
    // onCheckpoint runs concurrently for different chunks: collect per chunk, merge in order.
    ConcurrentSkipListMap<Integer, Set<String>> byChunk = new ConcurrentSkipListMap<>();
    try (StreamingChunkParser parser =
        new StreamingChunkParser(new UntypedParserContextFactory())) {
      parser.parse(
          recordingPath,
          new ChunkParserListener() {
            @Override
            public boolean onMetadata(ParserContext context, MetadataEvent metadata) {
              return true; // continue to checkpoint to load CPs
            }

            @Override
            public boolean onCheckpoint(
                ParserContext context, io.jafar.parser.internal_api.CheckpointEvent event) {
              Set<String> out =
                  byChunk.computeIfAbsent(context.getChunkIndex(), k -> new LinkedHashSet<>());
              context.getConstantPools().pools().forEach(cp -> out.add(cp.getType().getName()));
              return true; // continue to ALL checkpoints to discover all CP types
            }

            @Override
            public boolean onEvent(
                ParserContext context,
                long typeId,
                long eventStartPos,
                long rawSize,
                long payloadSize) {
              return false; // every checkpoint of this chunk is read; skip its events
            }
          });
    } catch (Exception e) {
      throw cpTypesFailure = failure("discover constant-pool types of", e);
    }
    Set<String> merged = new LinkedHashSet<>();
    byChunk.values().forEach(merged::addAll); // ascending chunk index
    cpTypes = Collections.unmodifiableSet(merged);
    return cpTypes;
  }

  private UncheckedIOException failure(String what, Exception e) {
    String message = "Failed to " + what + " " + recordingPath;
    return new UncheckedIOException(
        message, e instanceof IOException io ? io : new IOException(message, e));
  }

  /**
   * Get the count of events processed for each type.
   *
   * @return map of event type to count
   */
  public Map<String, Long> getEventTypeCounts() {
    return new HashMap<>(eventTypeCounts);
  }

  /**
   * Get total number of events processed.
   *
   * @return total event count
   */
  public long getTotalEvents() {
    return totalEvents.get();
  }

  /**
   * Get number of registered handlers.
   *
   * @return handler count
   */
  public int getHandlerCount() {
    return handlerCount.get();
  }

  /**
   * Get the recording file path.
   *
   * @return recording path
   */
  public Path getRecordingPath() {
    return recordingPath;
  }

  @Override
  public String getType() {
    return "jfr";
  }

  @Override
  public Path getFilePath() {
    return recordingPath;
  }

  @Override
  public Map<String, Object> getStatistics() {
    Map<String, Object> stats = new HashMap<>();
    stats.put("recordingPath", recordingPath.toString());
    stats.put("totalEvents", totalEvents.get());
    stats.put("eventTypeCount", eventTypeCounts.size());
    stats.put("handlerCount", handlerCount.get());
    stats.put("hasRun", hasRun);
    return stats;
  }

  /**
   * Get cumulative parsing time from the context.
   *
   * @return uptime in nanoseconds
   */
  public long getUptime() {
    return parsingContext.uptime();
  }

  /**
   * Check if the session has been run.
   *
   * @return true if run() has been called
   */
  public boolean hasRun() {
    return hasRun;
  }

  /**
   * Check if a metadata class represents a JFR event type. Uses same logic as TypeDiscovery for
   * consistency.
   *
   * @param clazz metadata class to check
   * @return true if this is an event type
   */
  private boolean isEventType(MetadataClass clazz, Map<String, MetadataClass> resolver) {
    // Identify JFR events by following super type chain recursively
    HashSet<String> seen = new HashSet<>();
    String st = clazz.getSuperType();
    while (st != null && seen.add(st)) {
      if ("jdk.jfr.Event".equals(st)) return true;
      MetadataClass sup = resolver != null ? resolver.get(st) : null;
      st = (sup != null) ? sup.getSuperType() : null;
    }
    return false;
  }

  private boolean isBrowsableMetadataType(MetadataClass clazz) {
    if (clazz.isPrimitive()) return false;
    String st = clazz.getSuperType();
    if (st == null) return false;
    if ("java.lang.annotation.Annotation".equals(st)) return false;
    if ("jdk.jfr.SettingControl".equals(st)) return false;
    return true;
  }

  @Override
  public String toString() {
    return "JFRSession[" + recordingPath + "]";
  }

  /** Returns whether this session has been closed. */
  public boolean isClosed() {
    return closed;
  }

  @Override
  public void close() throws Exception {
    closed = true;
    // Clean up all registrations
    for (HandlerRegistration<?> registration : registrations) {
      registration.close();
    }
    registrations.clear();

    if (parser != null) {
      parser.close();
      parser = null;
    }
  }
}
