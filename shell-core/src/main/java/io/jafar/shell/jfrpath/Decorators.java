package io.jafar.shell.jfrpath;

import static io.jafar.shell.jfrpath.JfrPath.*;

import io.jafar.parser.api.Values;
import io.jafar.shell.JFRSession;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Event decoration: temporal joins (decorateByTime) and correlation-key joins (decorateByKey). */
final class Decorators {
  private final JfrPathEvaluator eval;

  Decorators(JfrPathEvaluator eval) {
    this.eval = eval;
  }

  private boolean matchesAll(Map<String, Object> map, List<Predicate> predicates) {
    return MatchEngine.matchesAll(map, predicates, eval.defaultListMatchMode);
  }

  List<Map<String, Object>> evaluateDecorateByTime(
      JFRSession session, Query query, JfrPath.DecorateByTimeOp op) throws Exception {
    if (query.root != Root.EVENTS) {
      throw new UnsupportedOperationException("decorateByTime only supports events root");
    }
    if (query.segments.isEmpty()) {
      throw new IllegalArgumentException("decorateByTime requires primary event type");
    }

    String primaryType = query.segments.get(0);

    // PASS 1: Collect decorator events with time ranges
    List<DecoratorTimeRange> decorators =
        collectTimeRangeDecorators(session.getRecordingPath(), op);

    // PASS 2: Stream primary events and decorate
    List<Map<String, Object>> result = new ArrayList<>();
    eval.source.streamEvents(
        session.getRecordingPath(),
        ev -> {
          if (!primaryType.equals(ev.typeName())) return;
          if (!matchesAll(ev.value(), query.predicates)) return;

          // Find matching decorators for this event
          List<Map<String, Object>> matchingDecorators =
              findTimeRangeMatches(ev.value(), decorators, op.threadPathPrimary);

          // Wrap event with decorators
          Map<String, Object> decorated =
              new DecoratedEventMap(ev.value(), matchingDecorators, op.decoratorFields);

          result.add(decorated);
        });

    return result;
  }

  List<DecoratorTimeRange> collectTimeRangeDecorators(Path recording, JfrPath.DecorateByTimeOp op)
      throws Exception {
    List<DecoratorTimeRange> decorators = new ArrayList<>();

    eval.source.streamEvents(
        recording,
        ev -> {
          if (!op.decoratorEventType.equals(ev.typeName())) return;

          Map<String, Object> event = ev.value();

          // Extract thread ID
          Object threadId = Values.get(event, op.threadPathDecorator.toArray());
          if (threadId == null) return;

          // Extract startTime and duration
          Object startTimeObj = event.get("startTime");
          Object durationObj = event.get("duration");

          if (startTimeObj == null) return;

          long startTime = Coerce.toLong(startTimeObj);
          long duration = durationObj != null ? Coerce.toLong(durationObj) : 0;
          long endTime = startTime + duration;

          decorators.add(new DecoratorTimeRange(threadId, startTime, endTime, event));
        });

    // Sort by thread ID and start time for efficient lookup
    decorators.sort(
        java.util.Comparator.comparing((DecoratorTimeRange d) -> String.valueOf(d.threadId))
            .thenComparingLong(d -> d.startTimeNanos));

    return decorators;
  }

  List<Map<String, Object>> findTimeRangeMatches(
      Map<String, Object> primaryEvent,
      List<DecoratorTimeRange> decorators,
      List<String> threadPathPrimary) {

    // Extract primary event's thread ID and time range
    Object primaryThreadId = Values.get(primaryEvent, threadPathPrimary.toArray());
    if (primaryThreadId == null) return List.of();

    Object startTimeObj = primaryEvent.get("startTime");
    if (startTimeObj == null) return List.of();

    long primaryStartTime = Coerce.toLong(startTimeObj);
    Object durationObj = primaryEvent.get("duration");
    long primaryDuration = durationObj != null ? Coerce.toLong(durationObj) : 0;
    long primaryEndTime = primaryStartTime + primaryDuration;

    // Find overlapping decorators on same thread
    List<Map<String, Object>> matches = new ArrayList<>();

    for (DecoratorTimeRange dec : decorators) {
      if (!Objects.equals(dec.threadId, primaryThreadId)) continue;

      // Check time overlap: primaryStart < decoratorEnd && primaryEnd > decoratorStart
      if (primaryStartTime < dec.endTimeNanos && primaryEndTime > dec.startTimeNanos) {
        matches.add(dec.decoratorEvent);
      }
    }

    return matches;
  }

  List<Map<String, Object>> evaluateDecorateByKey(
      JFRSession session, Query query, JfrPath.DecorateByKeyOp op) throws Exception {
    if (query.root != Root.EVENTS) {
      throw new UnsupportedOperationException("decorateByKey only supports events root");
    }
    if (query.segments.isEmpty()) {
      throw new IllegalArgumentException("decorateByKey requires primary event type");
    }

    String primaryType = query.segments.get(0);

    // PASS 1: Collect decorator events indexed by correlation key
    Map<Object, List<Map<String, Object>>> decoratorIndex =
        collectKeyedDecorators(session.getRecordingPath(), op);

    // PASS 2: Stream primary events and decorate
    List<Map<String, Object>> result = new ArrayList<>();
    eval.source.streamEvents(
        session.getRecordingPath(),
        ev -> {
          if (!primaryType.equals(ev.typeName())) return;
          if (!matchesAll(ev.value(), query.predicates)) return;

          // Compute correlation key for primary event
          Object primaryKey = evaluateKeyExpr(ev.value(), op.primaryKey);
          List<Map<String, Object>> matchingDecorators =
              primaryKey == null ? List.of() : decoratorIndex.getOrDefault(primaryKey, List.of());

          // Wrap event with decorators
          Map<String, Object> decorated =
              new DecoratedEventMap(ev.value(), matchingDecorators, op.decoratorFields);

          result.add(decorated);
        });

    return result;
  }

  Map<Object, List<Map<String, Object>>> collectKeyedDecorators(
      Path recording, JfrPath.DecorateByKeyOp op) throws Exception {
    Map<Object, List<Map<String, Object>>> index = new HashMap<>();

    eval.source.streamEvents(
        recording,
        ev -> {
          if (!op.decoratorEventType.equals(ev.typeName())) return;

          Map<String, Object> event = ev.value();

          // Compute correlation key
          Object key = evaluateKeyExpr(event, op.decoratorKey);
          if (key == null) return;

          index.computeIfAbsent(key, k -> new ArrayList<>()).add(event);
        });

    return index;
  }

  Object evaluateKeyExpr(Map<String, Object> event, JfrPath.KeyExpr keyExpr) {
    if (keyExpr instanceof JfrPath.PathKeyExpr pke) {
      // resolveSingle: decorator key paths can carry array indexing, as everywhere else
      // (tolerates Collection-valued fields on Integer tokens — the JDK backend's array shape)
      return PathValues.resolveSingle(event, pke.path);
    }
    return null;
  }

  // Helper class for time-range decorators
  static class DecoratorTimeRange {
    final Object threadId;
    final long startTimeNanos;
    final long endTimeNanos;
    final Map<String, Object> decoratorEvent;

    DecoratorTimeRange(
        Object threadId,
        long startTimeNanos,
        long endTimeNanos,
        Map<String, Object> decoratorEvent) {
      this.threadId = threadId;
      this.startTimeNanos = startTimeNanos;
      this.endTimeNanos = endTimeNanos;
      this.decoratorEvent = decoratorEvent;
    }
  }

  // Lazy decorator wrapper for decorated events
  static class DecoratedEventMap extends java.util.AbstractMap<String, Object> {
    private static final String DECORATOR_PREFIX = "$decorator.";

    private final Map<String, Object> primaryEvent;
    private final List<Map<String, Object>> decorators;
    private final List<String> decoratorFields;

    DecoratedEventMap(
        Map<String, Object> primaryEvent,
        List<Map<String, Object>> decorators,
        List<String> decoratorFields) {
      this.primaryEvent = primaryEvent;
      this.decorators = decorators;
      this.decoratorFields = decoratorFields;
    }

    @Override
    public Object get(Object key) {
      String keyStr = String.valueOf(key);

      // Handle bare "$decorator" key (from path navigation: "$decorator" / "field")
      if ("$decorator".equals(keyStr)) {
        if (decorators.isEmpty()) return null;
        return decorators.get(0);
      }

      // Check if accessing decorator field
      if (keyStr.startsWith(DECORATOR_PREFIX)) {
        String decoratorField = keyStr.substring(DECORATOR_PREFIX.length());

        // Only expose requested fields (if specified)
        if (!decoratorFields.isEmpty() && !decoratorFields.contains(decoratorField)) {
          return null;
        }

        // Return from first matching decorator
        if (!decorators.isEmpty()) {
          return decorators.get(0).get(decoratorField);
        }
        return null;
      }

      // Otherwise delegate to primary event
      return primaryEvent.get(key);
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
      // Lazily compute entry set combining primary + decorator fields
      Set<Entry<String, Object>> entries = new HashSet<>(primaryEvent.entrySet());

      if (!decorators.isEmpty()) {
        Map<String, Object> firstDecorator = decorators.get(0);
        Iterable<String> fieldsToExpose =
            decoratorFields.isEmpty() ? firstDecorator.keySet() : decoratorFields;

        for (String field : fieldsToExpose) {
          Object value = firstDecorator.get(field);
          if (value != null) {
            entries.add(Map.entry(DECORATOR_PREFIX + field, value));
          }
        }
      }

      return entries;
    }

    @Override
    public boolean containsKey(Object key) {
      String keyStr = String.valueOf(key);
      if (keyStr.startsWith(DECORATOR_PREFIX)) {
        String decoratorField = keyStr.substring(DECORATOR_PREFIX.length());
        if (!decoratorFields.isEmpty() && !decoratorFields.contains(decoratorField)) {
          return false;
        }
        return !decorators.isEmpty() && decorators.get(0).containsKey(decoratorField);
      }
      return primaryEvent.containsKey(key);
    }

    @Override
    public int size() {
      int size = primaryEvent.size();
      if (!decorators.isEmpty()) {
        Map<String, Object> firstDecorator = decorators.get(0);
        size += decoratorFields.isEmpty() ? firstDecorator.size() : decoratorFields.size();
      }
      return size;
    }
  }
}
