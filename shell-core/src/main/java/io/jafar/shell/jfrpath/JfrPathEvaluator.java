package io.jafar.shell.jfrpath;

import static io.jafar.shell.jfrpath.JfrPath.*;

import io.jafar.parser.TimeRange;
import io.jafar.parser.TypeFilter;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.api.Values;
import io.jafar.shell.JFRSession;
import io.jafar.shell.backend.BackendRegistry;
import io.jafar.shell.backend.JfrBackend;
import io.jafar.shell.providers.ChunkProvider;
import io.jafar.shell.providers.ConstantPoolProvider;
import io.jafar.shell.providers.MetadataProvider;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Minimal evaluator for JfrPath queries.
 *
 * <p>v0 support: events/<type>[field/op/literal] ...
 */
public final class JfrPathEvaluator {
  public interface EventSource {
    /**
     * Streams every event of the recording to {@code consumer}. The consumer may be called from
     * several chunk-parsing threads at once, in no particular order across chunks: it must guard
     * any state it shares (a counter, a list, a map).
     */
    void streamEvents(Path recording, Consumer<Event> consumer) throws Exception;

    /**
     * Streams only the events whose type name is in {@code types}.
     *
     * <p>The default decodes every event and drops the others, which is correct for any source. A
     * source that can skip an event before decoding it should override this: the evaluator calls it
     * for every query that names its event types, which is nearly all of them.
     */
    default void streamEvents(Path recording, Collection<String> types, Consumer<Event> consumer)
        throws Exception {
      Set<String> wanted = new HashSet<>(types);
      streamEvents(
          recording,
          ev -> {
            if (wanted.contains(ev.typeName())) consumer.accept(ev);
          });
    }

    /**
     * Streams the events whose type name is in {@code types}, for a caller that only wants those
     * whose {@code startTime} is in {@code startTime}.
     *
     * <p>The range is a hint: a source may skip parts of the recording that cannot hold such an
     * event, and may deliver events outside the range all the same, so the caller still filters.
     * The default ignores it.
     */
    default void streamEvents(
        Path recording, Collection<String> types, TimeRange startTime, Consumer<Event> consumer)
        throws Exception {
      streamEvents(recording, types, consumer);
    }

    /**
     * The number of events of each type in {@code types}, or of every type when {@code types} is
     * {@code null}. A type with no event may be absent from the map.
     *
     * <p>The default streams the events and counts them, which decodes each one. A source that can
     * count events without decoding them should override this: {@code count()} without a filter,
     * and every analysis that looks for an event type, calls it.
     */
    default Map<String, Long> countEvents(Path recording, Collection<String> types)
        throws Exception {
      java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.LongAdder> counts =
          new java.util.concurrent.ConcurrentHashMap<>();
      Consumer<Event> counter =
          ev ->
              counts
                  .computeIfAbsent(ev.typeName(), k -> new java.util.concurrent.atomic.LongAdder())
                  .increment();
      if (types == null) {
        streamEvents(recording, counter);
      } else {
        streamEvents(recording, types, counter);
      }
      Map<String, Long> result = new java.util.HashMap<>();
      counts.forEach((type, n) -> result.put(type, n.sum()));
      return result;
    }
  }

  /**
   * Test-only switch: {@code false} makes the default event source decode every event and filter
   * afterwards, as it did before type filters existed. It is the reference the pushdown tests
   * compare against, not a user option.
   */
  static final String PUSHDOWN_PROPERTY = "jafar.jfrpath.pushdown";

  static boolean pushdownEnabled() {
    return !"false".equalsIgnoreCase(System.getProperty(PUSHDOWN_PROPERTY));
  }

  /**
   * Registers {@code handler} for the event types named in {@code types}, so that the parser does
   * not decode the others (unless {@link #PUSHDOWN_PROPERTY} turns that off). The handler must
   * still check the type itself only if it is registered for more than it wants.
   */
  static void handleTypes(
      UntypedJafarParser parser,
      Collection<String> types,
      TimeRange startTime,
      UntypedJafarParser.EventHandler handler) {
    // TypeFilter.named, not a lambda on type.getName(): the type handed to a filter is parser
    // internal API, which this module may not reach into (arch-tests rule a).
    TypeFilter wanted = TypeFilter.named(types);
    if (pushdownEnabled()) {
      // The range lets the parser skip chunks; every caller still checks the event's startTime
      // through the query's predicates, because events outside the range are delivered too.
      parser.handle(wanted, startTime, handler);
    } else {
      parser.handle(
          (type, value, ctl) -> {
            if (wanted.test(type)) handler.handle(type, value, ctl);
          });
    }
  }

  @FunctionalInterface
  public interface ProgressListener {
    void onProgress(double progress, double total, String message);
  }

  public record Event(String typeName, Map<String, Object> value) {}

  final EventSource source;
  final JfrPath.MatchMode defaultListMatchMode;

  private final Aggregations aggregations;
  private final Decorators decorators;
  private final Profiles profiles;

  public JfrPathEvaluator() {
    this(new DefaultEventSource(), JfrPath.MatchMode.ANY);
  }

  public JfrPathEvaluator(EventSource source) {
    this(source, JfrPath.MatchMode.ANY);
  }

  public JfrPathEvaluator(EventSource source, JfrPath.MatchMode defaultListMatchMode) {
    this.source = Objects.requireNonNull(source);
    this.defaultListMatchMode =
        defaultListMatchMode == null ? JfrPath.MatchMode.ANY : defaultListMatchMode;
    this.aggregations = new Aggregations(this);
    this.decorators = new Decorators(this);
    this.profiles = new Profiles(this);
  }

  public JfrPathEvaluator(JfrPath.MatchMode defaultListMatchMode) {
    this(new DefaultEventSource(), defaultListMatchMode);
  }

  /**
   * Count all events by type in a single pass. Equivalent to running {@code events/T | count()} for
   * every type T, but O(file_size) instead of O(N × file_size).
   */
  public Map<String, Long> countAllEventTypes(JFRSession session) throws Exception {
    // ConcurrentHashMap: StreamingChunkParser processes chunks in parallel, so the consumer
    // lambda is called from multiple threads concurrently.
    return source.countEvents(session.getRecordingPath(), null);
  }

  /**
   * Streams matching events to {@code consumer} without materialising them into a list. Use this
   * instead of {@link #evaluate} for large event sets to avoid OOM.
   *
   * <p><strong>Thread safety:</strong> the consumer may be invoked concurrently from multiple
   * chunk-worker threads (StreamingChunkParser uses a fixed thread pool). Provide a thread-safe
   * consumer — prefer {@link java.util.concurrent.ConcurrentHashMap#merge}, {@link
   * java.util.concurrent.atomic.LongAdder}, or {@link java.util.concurrent.atomic.AtomicLong} for
   * accumulation state. No internal locking is applied; lock-free concurrent structures give better
   * throughput than a synchronized wrapper.
   *
   * <p>If the consumer throws a {@link RuntimeException}, the stream is aborted (subsequent events
   * are skipped) and the exception propagates to the caller.
   */
  public void consume(JFRSession session, Query query, Consumer<Map<String, Object>> consumer)
      throws Exception {
    if (query.root != Root.EVENTS) {
      throw new UnsupportedOperationException("consume() only supports 'events/' queries");
    }
    if (query.eventTypes.isEmpty()) {
      throw new IllegalArgumentException("Expected event type segment after 'events/'");
    }
    validateEventTypes(session, query.eventTypes);

    java.util.concurrent.atomic.AtomicBoolean aborted =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    Consumer<Map<String, Object>> guarded =
        map -> {
          if (aborted.get()) return;
          try {
            consumer.accept(map);
          } catch (RuntimeException e) {
            aborted.set(true);
            throw e;
          }
        };
    forEachMatching(
        session.getRecordingPath(),
        query.eventTypes,
        query.predicates,
        map -> guarded.accept(Values.resolvedShallow(map)));
  }

  /**
   * Streams the events of {@code types} that match {@code predicates} to {@code consumer}.
   *
   * <p>Every operator that reads events goes through here, because this is where a query's event
   * types and its start-time range reach the source: the parser then decodes no other type and
   * skips the chunks that end before the range. A caller that asks the source for every event
   * instead makes it decode the whole recording.
   *
   * <p><strong>Thread safety:</strong> chunks are parsed in parallel and the consumer runs on the
   * chunk's thread, so it is called from several threads at once and in no particular order across
   * chunks. It must guard any state it shares; see {@link #consume}. The predicates are checked on
   * the chunk's thread too, before the consumer is called.
   */
  void forEachMatching(
      Path recording,
      Collection<String> types,
      List<JfrPath.Predicate> predicates,
      Consumer<Map<String, Object>> consumer)
      throws Exception {
    source.streamEvents(
        recording,
        types,
        StartTimeBounds.of(predicates),
        ev -> {
          Map<String, Object> map = ev.value();
          if (predicates.isEmpty()
              || MatchEngine.matchesAll(map, predicates, defaultListMatchMode)) {
            consumer.accept(map);
          }
        });
  }

  public List<Map<String, Object>> evaluate(JFRSession session, Query query) throws Exception {
    return evaluate(session, query, null);
  }

  public List<Map<String, Object>> evaluate(
      JFRSession session, Query query, ProgressListener progress) throws Exception {
    if (query.pipeline != null && !query.pipeline.isEmpty()) {
      return evaluateAggregate(session, query, progress);
    }
    if (query.root == Root.EVENTS) {
      if (query.eventTypes.isEmpty()) {
        throw new IllegalArgumentException("Expected event type segment after 'events/'");
      }

      // Validate event types exist
      validateEventTypes(session, query.eventTypes);

      List<Map<String, Object>> out = new ArrayList<>();
      // Rows arrive from several chunk-parsing threads at once (see consume()).
      forEachMatching(
          session.getRecordingPath(),
          query.eventTypes,
          query.predicates,
          map -> {
            Map<String, Object> row = Values.resolvedShallow(map);
            synchronized (out) {
              out.add(row);
            }
          });
      return out;
    } else if (query.root == Root.METADATA) {
      // Handle metadata queries: metadata/Type or metadata/[filter] or metadata
      if (query.segments.isEmpty()) {
        // No type specified: load all metadata types and filter
        // This handles: metadata or metadata/[filter]
        List<Map<String, Object>> allMetadata =
            MetadataProvider.loadAllClasses(session.getRecordingPath());
        if (query.predicates.isEmpty()) {
          return allMetadata;
        }
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> meta : allMetadata) {
          if (MatchEngine.matchesAll(meta, query.predicates, defaultListMatchMode)) {
            filtered.add(meta);
          }
        }
        return filtered;
      }
      // Specific type: metadata/TypeName or metadata/TypeName[filter]
      String typeName = query.segments.get(0);
      Map<String, Object> meta = MetadataProvider.loadClass(session.getRecordingPath(), typeName);
      if (meta == null) return java.util.Collections.emptyList();
      if (MatchEngine.matchesAll(meta, query.predicates, defaultListMatchMode)) {
        return java.util.List.of(meta);
      } else {
        return java.util.Collections.emptyList();
      }
    } else if (query.root == Root.CHUNKS) {
      if (!ChunkProvider.isSupported()) {
        JfrBackend backend = BackendRegistry.getInstance().getCurrent();
        throw new UnsupportedOperationException(
            "Backend '" + backend.getId() + "' does not support chunk queries");
      }
      if (!query.segments.isEmpty()) {
        // show chunks/1 - specific chunk by the index `chunks` lists (1-based)
        try {
          int chunkId = Integer.parseInt(query.segments.get(0));
          Map<String, Object> chunk = ChunkProvider.loadChunk(session.getRecordingPath(), chunkId);
          if (chunk == null) return java.util.Collections.emptyList();
          return MatchEngine.matchesAll(chunk, query.predicates, defaultListMatchMode)
              ? java.util.List.of(chunk)
              : java.util.Collections.emptyList();
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException("Invalid chunk index: " + query.segments.get(0));
        }
      }
      // show chunks or show chunks[filter]
      if (query.predicates.isEmpty()) {
        return ChunkProvider.loadAllChunks(session.getRecordingPath());
      } else {
        return ChunkProvider.loadChunks(
            session.getRecordingPath(),
            row -> MatchEngine.matchesAll(row, query.predicates, defaultListMatchMode));
      }
    } else if (query.root == Root.CONSTANTS) {
      if (!ConstantPoolProvider.isSupported()) {
        JfrBackend backend = BackendRegistry.getInstance().getCurrent();
        throw new UnsupportedOperationException(
            "Backend '" + backend.getId() + "' does not support constant pool queries");
      }
      if (!query.segments.isEmpty()) {
        String type = query.segments.get(0);
        return ConstantPoolProvider.loadEntries(
            session.getRecordingPath(),
            type,
            row -> MatchEngine.matchesAll(row, query.predicates, defaultListMatchMode));
      } else {
        return ConstantPoolProvider.loadSummary(session.getRecordingPath());
      }
    } else {
      throw new UnsupportedOperationException("Unsupported root: " + query.root);
    }
  }

  /**
   * Evaluate events with early-stop when limit is reached; other roots fall back to {@link
   * #evaluate}.
   */
  public List<Map<String, Object>> evaluateWithLimit(JFRSession session, Query query, Integer limit)
      throws Exception {
    if (query.root != Root.EVENTS || limit == null) {
      return evaluate(session, query);
    }
    if (query.eventTypes.isEmpty()) {
      throw new IllegalArgumentException("Expected event type segment after 'events/'");
    }

    // Validate event types exist
    validateEventTypes(session, query.eventTypes);

    List<Map<String, Object>> out = new ArrayList<>();
    try (UntypedJafarParser p =
        io.jafar.parser.api.ParsingContext.create().newUntypedParser(session.getRecordingPath())) {
      handleTypes(
          p,
          query.eventTypes,
          StartTimeBounds.of(query.predicates),
          (type, value, ctl) -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) value;
            if (MatchEngine.matchesAll(map, query.predicates, defaultListMatchMode)) {
              // Handlers run on several chunk-parsing threads at once (see consume()).
              synchronized (out) {
                if (out.size() < limit) {
                  out.add(Values.resolvedShallow(map));
                }
                if (out.size() >= limit) {
                  ctl.abort();
                }
              }
            }
          });
      p.run();
    }
    return out;
  }

  /** Evaluate a query projecting a specific attribute path after the event type. */
  public List<Object> evaluateValues(JFRSession session, Query query) throws Exception {
    if (query.pipeline != null && !query.pipeline.isEmpty()) {
      // For projections, aggregations handled by evaluateAggregate returning rows
      throw new UnsupportedOperationException("Use evaluate(...) for aggregation pipelines");
    }
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
      forEachMatching(
          session.getRecordingPath(),
          List.of(eventType),
          query.predicates,
          map -> {
            // Called from several chunk-parsing threads: extract first, then append as one.
            List<Object> vals = new ArrayList<>();
            PathValues.extractWithIndexing(map, proj, vals);
            synchronized (out) {
              out.addAll(vals);
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
      Map<String, Object> meta = MetadataProvider.loadClass(session.getRecordingPath(), typeName);
      if (meta == null) return java.util.Collections.emptyList();
      if (!MatchEngine.matchesAll(meta, query.predicates, defaultListMatchMode))
        return java.util.Collections.emptyList();
      // Special handling: 'fields[/<name>[/...]]' maps to fieldsByName
      if (!proj.isEmpty() && ("fields".equals(proj.get(0)) || proj.get(0).startsWith("fields."))) {
        // Support alias: fields.<name>[/...]
        if (proj.get(0).startsWith("fields.")) {
          String rest = proj.get(0).substring("fields.".length());
          java.util.List<String> np = new java.util.ArrayList<>();
          np.add("fields");
          if (!rest.isEmpty()) np.add(rest);
          if (proj.size() > 1) np.addAll(proj.subList(1, proj.size()));
          proj = np;
        }
        Object fbn = meta.get("fieldsByName");
        if (!(fbn instanceof java.util.Map<?, ?> m)) {
          return java.util.Collections.emptyList();
        }
        if (proj.size() == 1) {
          java.util.List<String> names = new java.util.ArrayList<>();
          for (Object k : m.keySet()) names.add(String.valueOf(k));
          java.util.Collections.sort(names);
          return new java.util.ArrayList<>(names);
        } else {
          String fname = proj.get(1);
          Object entry = m.get(fname);
          if (!(entry instanceof java.util.Map<?, ?> em)) return java.util.Collections.emptyList();
          if (proj.size() == 2) {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> val = (java.util.Map<String, Object>) em;
            return java.util.List.of(val);
          } else {
            java.util.List<String> rest = proj.subList(2, proj.size());
            Object v = Values.get((java.util.Map<String, Object>) em, rest.toArray());
            return v == null ? java.util.Collections.emptyList() : java.util.List.of(v);
          }
        }
      }

      // Support alias: fieldsByName.<name>[/...]
      if (!proj.isEmpty()
          && ("fieldsByName".equals(proj.get(0)) || proj.get(0).startsWith("fieldsByName."))) {
        if (proj.get(0).startsWith("fieldsByName.")) {
          String rest = proj.get(0).substring("fieldsByName.".length());
          java.util.List<String> np = new java.util.ArrayList<>();
          np.add("fieldsByName");
          if (!rest.isEmpty()) np.add(rest);
          if (proj.size() > 1) np.addAll(proj.subList(1, proj.size()));
          proj = np;
        }
        Object fbn2 = meta.get("fieldsByName");
        if (!(fbn2 instanceof java.util.Map<?, ?> m2)) return java.util.Collections.emptyList();
        if (proj.size() == 1) {
          java.util.List<String> names = new java.util.ArrayList<>();
          for (Object k : m2.keySet()) names.add(String.valueOf(k));
          java.util.Collections.sort(names);
          return new java.util.ArrayList<>(names);
        }
        String fname2 = proj.get(1);
        Object entry2 = m2.get(fname2);
        if (!(entry2 instanceof java.util.Map<?, ?> em2)) return java.util.Collections.emptyList();
        if (proj.size() == 2) {
          @SuppressWarnings("unchecked")
          java.util.Map<String, Object> val2 = (java.util.Map<String, Object>) em2;
          return java.util.List.of(val2);
        }
        java.util.List<String> rest2 = proj.subList(2, proj.size());
        Object v2 = Values.get((java.util.Map<String, Object>) em2, rest2.toArray());
        return v2 == null ? java.util.Collections.emptyList() : java.util.List.of(v2);
      }

      List<Object> out = new ArrayList<>();
      PathValues.extractWithIndexing(meta, proj, out);
      return out;
    } else if (query.root == Root.CHUNKS) {
      if (!ChunkProvider.isSupported()) {
        JfrBackend backend = BackendRegistry.getInstance().getCurrent();
        throw new UnsupportedOperationException(
            "Backend '" + backend.getId() + "' does not support chunk queries");
      }
      if (query.segments.isEmpty()) {
        throw new IllegalArgumentException("No projection path provided after 'chunks'");
      }
      // chunks/<n>/<path> projects from chunk n, the chunk evaluate() returns for chunks/<n>;
      // chunks/<path> projects from every chunk
      List<Map<String, Object>> rows;
      List<String> proj = query.segments;
      Integer chunkId = parseChunkIndex(query.segments.get(0));
      if (chunkId != null && query.segments.size() > 1) {
        Map<String, Object> chunk = ChunkProvider.loadChunk(session.getRecordingPath(), chunkId);
        rows = chunk == null ? java.util.Collections.emptyList() : java.util.List.of(chunk);
        proj = query.segments.subList(1, query.segments.size());
      } else {
        rows = ChunkProvider.loadAllChunks(session.getRecordingPath());
      }
      List<Object> out = new ArrayList<>();
      for (Map<String, Object> row : rows) {
        if (MatchEngine.matchesAll(row, query.predicates, defaultListMatchMode)) {
          PathValues.extractWithIndexing(row, proj, out);
        }
      }
      return out;
    } else if (query.root == Root.CONSTANTS) {
      if (!ConstantPoolProvider.isSupported()) {
        JfrBackend backend = BackendRegistry.getInstance().getCurrent();
        throw new UnsupportedOperationException(
            "Backend '" + backend.getId() + "' does not support constant pool queries");
      }
      if (query.segments.isEmpty()) {
        // projection from summary rows
        List<Map<String, Object>> rows =
            ConstantPoolProvider.loadSummary(session.getRecordingPath());
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
          Object v = Values.get(r, query.segments.toArray());
          if (v != null) out.add(v);
        }
        return out;
      } else {
        // projection from entries of specific type
        String type = query.segments.get(0);
        List<Map<String, Object>> rows =
            ConstantPoolProvider.loadEntries(
                session.getRecordingPath(),
                type,
                r -> MatchEngine.matchesAll(r, query.predicates, defaultListMatchMode));
        List<String> proj = query.segments.subList(1, query.segments.size());
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
          PathValues.extractWithIndexing(r, proj, out);
        }
        return out;
      }
    } else {
      throw new UnsupportedOperationException("Unsupported root: " + query.root);
    }
  }

  /** The chunk index a {@code chunks/<n>} segment names, or {@code null} if it is not a number. */
  private static Integer parseChunkIndex(String segment) {
    try {
      return Integer.parseInt(segment);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  // Aggregations
  private List<Map<String, Object>> evaluateAggregate(
      JFRSession session, Query query, ProgressListener progress) throws Exception {
    // Execute the first operator
    JfrPath.PipelineOp op = query.pipeline.get(0);
    List<Map<String, Object>> result =
        switch (op) {
          case JfrPath.CountOp _ -> aggregations.aggregateCount(session, query);
          case JfrPath.StatsOp s -> aggregations.aggregateStats(session, query, s.valuePath);
          case JfrPath.QuantilesOp q ->
              aggregations.aggregateQuantiles(session, query, q.valuePath, q.qs);
          case JfrPath.SketchOp sk -> aggregations.aggregateSketch(session, query, sk.valuePath);
          case JfrPath.SumOp sm -> aggregations.aggregateSum(session, query, sm.valuePath);
          case JfrPath.GroupByOp gb ->
              aggregations.aggregateGroupBy(
                  session,
                  query,
                  gb.keyPath,
                  gb.keyExpr,
                  gb.aggFunc,
                  gb.valuePath,
                  gb.valueExpr,
                  gb.sortBy,
                  gb.ascending);
          case JfrPath.SortByOp sort -> aggregations.aggregateSortBy(session, query, sort.fields);
          case JfrPath.TopOp tp ->
              aggregations.aggregateTop(session, query, tp.n, tp.byPath, tp.ascending);
          case JfrPath.LenOp ln -> aggregations.aggregateLen(session, query, ln.valuePath);
          case JfrPath.UppercaseOp up ->
              aggregations.aggregateStringTransform(session, query, up.valuePath, "uppercase");
          case JfrPath.LowercaseOp lo ->
              aggregations.aggregateStringTransform(session, query, lo.valuePath, "lowercase");
          case JfrPath.TrimOp tr ->
              aggregations.aggregateStringTransform(session, query, tr.valuePath, "trim");
          case JfrPath.AbsOp ab ->
              aggregations.aggregateNumberTransform(session, query, ab.valuePath, "abs");
          case JfrPath.RoundOp ro ->
              aggregations.aggregateNumberTransform(session, query, ro.valuePath, "round");
          case JfrPath.FloorOp flo ->
              aggregations.aggregateNumberTransform(session, query, flo.valuePath, "floor");
          case JfrPath.CeilOp cei ->
              aggregations.aggregateNumberTransform(session, query, cei.valuePath, "ceil");
          case JfrPath.FormatDurationOp fd ->
              aggregations.aggregateFormatDurationTransform(session, query, fd.valuePath);
          case JfrPath.ContainsOp co ->
              aggregations.aggregateStringPredicate(
                  session, query, co.valuePath, "contains", co.substr);
          case JfrPath.ReplaceOp rp ->
              aggregations.aggregateStringReplace(
                  session, query, rp.valuePath, rp.target, rp.replacement);
          case JfrPath.DecorateByTimeOp dt -> decorators.evaluateDecorateByTime(session, query, dt);
          case JfrPath.DecorateByKeyOp dk -> decorators.evaluateDecorateByKey(session, query, dk);
          case JfrPath.SelectOp so -> aggregations.evaluateSelect(session, query, so);
          case JfrPath.ToMapOp tm -> aggregations.evaluateToMap(session, query, tm);
          case JfrPath.TimeRangeOp tr -> aggregations.aggregateTimeRange(session, query, tr);
          case JfrPath.HeadOp head -> {
            List<Map<String, Object>> rows = collectAllRows(session, query);
            yield rows.subList(0, Math.min(head.n, rows.size()));
          }
          case JfrPath.TailOp tail -> {
            List<Map<String, Object>> rows = collectAllRows(session, query);
            yield rows.subList(Math.max(0, rows.size() - tail.n), rows.size());
          }
          case JfrPath.FilterOp filter -> {
            List<Map<String, Object>> rows = collectAllRows(session, query);
            yield RowOps.applyFilter(rows, filter.predicate);
          }
          case JfrPath.DistinctOp dist -> {
            List<Map<String, Object>> rows = collectAllRows(session, query);
            yield RowOps.applyDistinct(rows, dist.field);
          }
          case JfrPath.StackProfileOp sp ->
              profiles.aggregateStackProfile(
                  session, query, sp.direction, sp.buckets, sp.minPct, progress);
          case JfrPath.FlameGraphOp fg ->
              profiles.aggregateFlameGraph(session, query, fg.direction);
          case JfrPath.AsDateTimeOp ft ->
              aggregations.aggregateAsDateTime(session, query, ft.valuePath, ft.format);
          case JfrPath.CrossrefOp _ -> aggregations.aggregateCrossref(session, query);
        };

    // Apply remaining operators in sequence
    if (query.pipeline.size() > 1) {
      List<JfrPath.PipelineOp> remainingOps = query.pipeline.subList(1, query.pipeline.size());
      result = applyToRows(result, remainingOps);
    }

    return result;
  }

  /**
   * {@code v} without its wrappers: a ComplexType's value, and a one-entry map's single value,
   * repeatedly. The untyped parser gives string constants as one-entry maps ({@code {string=...}},
   * a ThreadState's {@code {name=...}}); spread as fields they made a {@code string} column, and
   * {@code filter(value = ...)} matched nothing.
   */
  private static Object collapsed(Object v) {
    Object value = v;
    while (true) {
      if (value instanceof io.jafar.parser.api.ComplexType ct) {
        value = ct.getValue();
      } else if (value instanceof Map<?, ?> m && m.size() == 1) {
        value = m.values().iterator().next();
      } else {
        return value;
      }
    }
  }

  /**
   * The rows a row operator (head, tail, filter, distinct) starts from. With a projection ({@code
   * events/T/heapUsed}) each projected value is a row: a scalar as {@code {value: v}}, the column a
   * plain projection and the transforms show; an object (a map) as its own fields. A string
   * constant ({@code {string=...}}, {@code {name=...}}) is a scalar. Whole events came back, so
   * {@code /heapUsed | head(2)} showed whole events and a later {@code stats()} read the events'
   * first column (#305).
   */
  List<Map<String, Object>> collectAllRows(JFRSession session, Query query) throws Exception {
    if (query.root == Root.EVENTS && query.segments.size() >= 2) {
      validateEventTypes(session, query.eventTypes);
      List<String> proj = query.segments.subList(1, query.segments.size());
      List<Map<String, Object>> out = new ArrayList<>();
      forEachMatching(
          session.getRecordingPath(),
          query.eventTypes,
          query.predicates,
          map -> {
            // Called from several chunk-parsing threads: extract first, then append as one
            List<Map<String, Object>> rows = projectedRows(map, proj);
            synchronized (out) {
              out.addAll(rows);
            }
          });
      return out;
    }
    return evaluate(session, new Query(query.root, query.segments, query.predicates));
  }

  /** The rows one event gives for the projection {@code proj}, as {@link #collectAllRows} makes. */
  private static List<Map<String, Object>> projectedRows(
      Map<String, Object> event, List<String> proj) {
    List<Object> vals = new ArrayList<>();
    PathValues.extractWithIndexing(event, proj, vals);
    List<Map<String, Object>> rows = new ArrayList<>(vals.size());
    for (Object v : vals) {
      Object value = collapsed(v);
      Map<String, Object> row = new java.util.LinkedHashMap<>();
      if (value instanceof Map<?, ?> fields) {
        fields.forEach((k, fv) -> row.put(String.valueOf(k), fv));
      } else {
        row.put("value", value);
      }
      rows.add(row);
    }
    return rows;
  }

  /**
   * The rows {@code event} gives for {@code query}'s projection ({@code events/T/a/b}): a scalar
   * value as {@code {value: v}}, an object as its own fields, as a file query's row operators see
   * them. Without a projection, the event itself. For a live {@code tail} that aggregates (#185).
   */
  public static List<Map<String, Object>> projectEvent(Query query, Map<String, Object> event) {
    if (query.root != Root.EVENTS || query.segments.size() < 2) {
      return List.of(event);
    }
    return projectedRows(event, query.segments.subList(1, query.segments.size()));
  }

  /**
   * Evaluate event projection with early-stop when limit is reached; other roots fall back to
   * {@link #evaluateValues}.
   */
  public List<Object> evaluateValuesWithLimit(JFRSession session, Query query, Integer limit)
      throws Exception {
    if (query.root != Root.EVENTS || limit == null) {
      return evaluateValues(session, query);
    }
    if (query.segments.isEmpty()) {
      throw new IllegalArgumentException("Expected event type segment after 'events/'");
    }
    if (query.segments.size() < 2) {
      throw new IllegalArgumentException("No projection path provided after event type");
    }
    String eventType = query.segments.get(0);
    List<String> proj = query.segments.subList(1, query.segments.size());
    List<Object> out = new ArrayList<>();
    try (UntypedJafarParser p =
        io.jafar.parser.api.ParsingContext.create().newUntypedParser(session.getRecordingPath())) {
      handleTypes(
          p,
          List.of(eventType),
          StartTimeBounds.of(query.predicates),
          (type, value, ctl) -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) value;
            if (MatchEngine.matchesAll(map, query.predicates, defaultListMatchMode)) {
              // Handlers run on several chunk-parsing threads at once (see consume()).
              synchronized (out) {
                if (out.size() < limit) {
                  PathValues.extractWithIndexing(map, proj, out);
                }
                if (out.size() >= limit) {
                  ctl.abort();
                }
              }
            }
          });
      p.run();
    }
    return out;
  }

  void validateEventTypes(JFRSession session, List<String> requestedTypes) throws Exception {
    Set<String> availableTypes = session.getAvailableTypes();

    // Skip validation if event type information is not available
    if (availableTypes == null || availableTypes.isEmpty()) {
      return;
    }

    for (String requestedType : requestedTypes) {
      if (!availableTypes.contains(requestedType)) {
        String suggestion = findClosestMatch(requestedType, availableTypes);
        if (suggestion != null) {
          throw new IllegalArgumentException(
              "Event type '" + requestedType + "' not found. Did you mean '" + suggestion + "'?");
        }
        throw new IllegalArgumentException("Event type '" + requestedType + "' not found");
      }
    }
  }

  /**
   * Finds the closest matching event type using prefix matching and Levenshtein distance.
   *
   * @return closest match or null if no good match found
   */
  String findClosestMatch(String requested, Set<String> available) {
    // Prefix matching first
    for (String avail : available) {
      if (avail.startsWith(requested) || requested.startsWith(avail)) {
        return avail;
      }
    }

    // Levenshtein distance fallback (max 3 edits)
    int minDist = Integer.MAX_VALUE;
    String closest = null;
    for (String avail : available) {
      int dist = levenshteinDistance(requested, avail);
      if (dist < minDist && dist <= 3) {
        minDist = dist;
        closest = avail;
      }
    }
    return closest;
  }

  /** Calculates Levenshtein distance between two strings. */
  int levenshteinDistance(String s1, String s2) {
    int[][] dp = new int[s1.length() + 1][s2.length() + 1];
    for (int i = 0; i <= s1.length(); i++) dp[i][0] = i;
    for (int j = 0; j <= s2.length(); j++) dp[0][j] = j;

    for (int i = 1; i <= s1.length(); i++) {
      for (int j = 1; j <= s2.length(); j++) {
        int cost = s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1;
        dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
      }
    }
    return dp[s1.length()][s2.length()];
  }

  /**
   * Default EventSource that streams events untyped from a recording: all of them, or only the
   * types it was created for, which the parser then does not decode the others for.
   */
  static final class DefaultEventSource implements EventSource {
    /** The event types to read, or {@code null} for all of them. */
    private final Collection<String> types;

    /** The start times the caller wants; only meaningful with {@link #types}. */
    private final TimeRange startTime;

    DefaultEventSource() {
      this(null, TimeRange.all());
    }

    private DefaultEventSource(Collection<String> types, TimeRange startTime) {
      this.types = types;
      this.startTime = startTime;
    }

    @Override
    public void streamEvents(Path recording, Consumer<Event> consumer) throws Exception {
      try (UntypedJafarParser p =
          io.jafar.parser.api.ParsingContext.create().newUntypedParser(recording)) {
        // The one place this class names an event's type (see handleTypes on why that matters).
        UntypedJafarParser.EventHandler handler =
            (type, value, ctl) -> consumer.accept(new Event(type.getName(), value));
        if (types == null) {
          p.handle(handler);
        } else {
          handleTypes(p, types, startTime, handler);
        }
        p.run();
      }
    }

    @Override
    public void streamEvents(Path recording, Collection<String> types, Consumer<Event> consumer)
        throws Exception {
      streamEvents(recording, types, TimeRange.all(), consumer);
    }

    /**
     * Counts from the event headers, without decoding an event ({@link
     * UntypedJafarParser#countEvents}). An event whose payload would fail to decode is counted all
     * the same, where streaming it would have failed the query.
     */
    @Override
    public Map<String, Long> countEvents(Path recording, Collection<String> types)
        throws Exception {
      if (!pushdownEnabled()) {
        return EventSource.super.countEvents(recording, types);
      }
      try (UntypedJafarParser p =
          io.jafar.parser.api.ParsingContext.create().newUntypedParser(recording)) {
        return types == null ? p.countEvents() : p.countEvents(TypeFilter.named(types));
      }
    }

    @Override
    public void streamEvents(
        Path recording, Collection<String> types, TimeRange startTime, Consumer<Event> consumer)
        throws Exception {
      new DefaultEventSource(types, startTime).streamEvents(recording, consumer);
    }
  }

  // Metadata is provided by MetadataProvider; keep a public wrapper for compatibility

  /** Load metadata for a single field name under a type. */
  public Map<String, Object> loadFieldMetadata(Path recording, String typeName, String fieldName)
      throws Exception {
    return MetadataProvider.loadField(recording, typeName, fieldName);
  }

  /**
   * Applies pipeline operators to an existing list of rows. This allows post-processing cached
   * results from lazy variables without re-evaluating the original query.
   *
   * @param rows the input rows
   * @param pipeline the pipeline operators to apply
   * @return the processed result
   */
  @SuppressWarnings("unchecked")
  public List<Map<String, Object>> applyToRows(
      List<Map<String, Object>> rows, List<JfrPath.PipelineOp> pipeline) {
    if (pipeline == null || pipeline.isEmpty()) {
      return rows;
    }

    List<Map<String, Object>> result = new ArrayList<>(rows);

    for (JfrPath.PipelineOp op : pipeline) {
      result = applySingleOp(result, op);
    }

    return result;
  }

  private List<Map<String, Object>> applySingleOp(
      List<Map<String, Object>> rows, JfrPath.PipelineOp op) {
    return switch (op) {
      case JfrPath.TopOp top -> RowOps.applyTop(rows, top.n, top.byPath, top.ascending);
      case JfrPath.CountOp _ -> RowOps.applyCount(rows);
      case JfrPath.SumOp sum -> RowOps.applySum(rows, sum.valuePath);
      case JfrPath.StatsOp stats -> RowOps.applyStats(rows, stats.valuePath);
      // Was ignored here: head(n) | sketch() printed the rows (#305)
      case JfrPath.SketchOp sk -> RowOps.applySketch(rows, sk.valuePath);
      case JfrPath.SelectOp sel -> RowOps.applySelect(rows, sel);
      case JfrPath.GroupByOp gb ->
          RowOps.applyGroupBy(
              rows,
              gb.keyPath,
              gb.keyExpr,
              gb.aggFunc,
              gb.valuePath,
              gb.valueExpr,
              gb.sortBy,
              gb.ascending);
      case JfrPath.SortByOp sort -> RowOps.applySortBy(rows, sort.fields);
      case JfrPath.QuantilesOp q -> RowOps.applyQuantiles(rows, q.valuePath, q.qs);
      case JfrPath.LenOp len -> RowOps.applyLen(rows, len.valuePath);
      case JfrPath.UppercaseOp up ->
          RowOps.applyStringTransform(rows, up.valuePath, s -> s.toUpperCase(Locale.ROOT));
      case JfrPath.LowercaseOp lo ->
          RowOps.applyStringTransform(rows, lo.valuePath, s -> s.toLowerCase(Locale.ROOT));
      case JfrPath.TrimOp tr -> RowOps.applyStringTransform(rows, tr.valuePath, String::trim);
      case JfrPath.AbsOp ab -> RowOps.applyNumberTransform(rows, ab.valuePath, Math::abs);
      case JfrPath.RoundOp ro -> RowOps.applyNumberTransform(rows, ro.valuePath, Math::round);
      case JfrPath.FloorOp fl -> RowOps.applyNumberTransform(rows, fl.valuePath, Math::floor);
      case JfrPath.CeilOp ce -> RowOps.applyNumberTransform(rows, ce.valuePath, Math::ceil);
      case JfrPath.FormatDurationOp fd ->
          Aggregations.applyFormatDurationTransform(rows, fd.valuePath);
      case JfrPath.ContainsOp co -> RowOps.applyContains(rows, co.valuePath, co.substr);
      case JfrPath.ReplaceOp rp ->
          RowOps.applyReplace(rows, rp.valuePath, rp.target, rp.replacement);
      case JfrPath.HeadOp head -> rows.subList(0, Math.min(head.n, rows.size()));
      case JfrPath.TailOp tail -> rows.subList(Math.max(0, rows.size() - tail.n), rows.size());
      case JfrPath.FilterOp filter -> RowOps.applyFilter(rows, filter.predicate);
      case JfrPath.DistinctOp dist -> RowOps.applyDistinct(rows, dist.field);
      case JfrPath.ToMapOp tm -> RowOps.applyToMap(rows, tm.keyField, tm.valueField);
      case JfrPath.TimeRangeOp tr -> RowOps.applyTimeRange(rows, tr.valuePath, tr.durationPath);
      case JfrPath.StackProfileOp sp ->
          profiles.applyStackProfile(rows, sp.direction, sp.buckets, sp.minPct);
      case JfrPath.FlameGraphOp fg -> profiles.applyFlameGraph(rows, fg.direction);
      case JfrPath.AsDateTimeOp ft -> RowOps.applyAsDateTime(rows, ft.valuePath, ft.format);
      // Not a row transform: it re-reads the whole recording, so on rows it would silently
      // ignore what came before it.
      case JfrPath.CrossrefOp _ -> throw new IllegalArgumentException(Aggregations.CROSSREF_USAGE);
      // They read the recording for their partner events: on rows they were silently skipped
      case JfrPath.DecorateByTimeOp _, JfrPath.DecorateByKeyOp _ ->
          throw new IllegalArgumentException(
              "decorateByTime()/decorateByKey() read the recording: they must be the first"
                  + " operator of an events/ query, not follow another operator or run on rows"
                  + " (a variable's)");
    };
  }

  /**
   * Per-event predicate evaluation for live streaming ({@code tail}): evaluates the query's event
   * type membership (single or multi-type) and its bracket predicates against ONE event map — the
   * same predicate semantics {@link #consume} applies per event over a file.
   *
   * <p>Pipeline operators are NOT evaluated here; use {@link #applyEventOps} for the per-event
   * subset.
   */
  public boolean matchesEvent(Query query, String typeName, Map<String, Object> event) {
    return MatchEngine.matchesEvent(query, typeName, event, defaultListMatchMode);
  }

  /**
   * Shared event-type membership rule for per-event matching: empty (or absent) eventTypes match
   * all types; a multi-type query matches when the type name is listed; a single-type query matches
   * on exact equality.
   */
  public static boolean matchesType(Query query, String typeName) {
    return MatchEngine.matchesType(query, typeName);
  }

  /**
   * Applies the per-event-capable subset of the pipeline to ONE event map (see {@link
   * RowOps#applyEventOps} for the exact contract, including the in-place mutation of transform
   * operators and the {@link UnsupportedPipelineOperationException} for row-set ops).
   */
  public Map<String, Object> applyEventOps(
      List<JfrPath.PipelineOp> pipeline, Map<String, Object> event) {
    return RowOps.applyEventOps(pipeline, event);
  }

  /**
   * Where the per-event part of {@code pipeline} ends: the index of the first operator that needs
   * rows rather than one event, or the pipeline's size. A live {@code tail} runs the part before it
   * on each event and the rest on the rows it has collected (#185).
   */
  public static int firstRowSetOp(List<JfrPath.PipelineOp> pipeline) {
    return RowOps.firstRowSetOp(pipeline);
  }

  /**
   * Throws an {@link UnsupportedPipelineOperationException} for the first operator of {@code
   * pipeline} that cannot run on a live stream's collected rows, so that a live filter is refused
   * when it is given rather than later (#185).
   */
  public static void requireLiveOps(List<JfrPath.PipelineOp> pipeline) {
    RowOps.requireLiveOps(pipeline);
  }

  /**
   * As {@link #requireLiveOps(List)} for {@code query}, refusing too what a file query refuses: a
   * value operator ({@code stats()}, {@code sum()}, {@code quantiles()}, {@code sketch()}) with no
   * value path straight over whole events, where rows would make it read their first column.
   */
  public static void requireLiveOps(Query query) {
    RowOps.requireLiveOps(query.pipeline);
    if (query.pipeline == null || query.segments.size() >= 2) {
      return;
    }
    int first = RowOps.firstRowSetOp(query.pipeline);
    if (first >= query.pipeline.size()) {
      return;
    }
    String name =
        switch (query.pipeline.get(first)) {
          case JfrPath.StatsOp s when s.valuePath.isEmpty() -> "stats";
          case JfrPath.SumOp s when s.valuePath.isEmpty() -> "sum";
          case JfrPath.QuantilesOp q when q.valuePath.isEmpty() -> "quantiles";
          case JfrPath.SketchOp s when s.valuePath.isEmpty() -> "sketch";
          default -> null;
        };
    if (name != null) {
      throw new IllegalArgumentException(name + "() requires projection or a value path");
    }
  }
}
