package io.jafar.shell.cli;

import io.jafar.shell.JFRSession;
import io.jafar.shell.jfrpath.JfrPath;
import io.jafar.shell.jfrpath.JfrPathEvaluator;
import java.util.List;
import java.util.Map;

/**
 * What {@code show <JfrPath>} prints for a query without a pipeline: whole rows, the values a
 * projected path names, or one field's metadata.
 *
 * <p>{@link JfrPathEvaluator#evaluate} reads only the event type of {@code
 * events/jdk.GarbageCollection/cause} and returns whole events; the path after the type is honoured
 * by {@link JfrPathEvaluator#evaluateValues}. jfr-shell's {@code CommandDispatcher} and
 * jafar-shell's {@code Shell} both route through {@link #of}, so a projected path means the same
 * thing in both shells.
 */
public enum ShowShape {
  /** Whole rows: {@code events/<type>}, {@code metadata/<type>}, {@code chunks}, a pipeline. */
  ROWS,
  /**
   * The values a projected path names: {@code events/<type>/<path>}, {@code
   * constants/<type>/<path>}, {@code metadata/<type>/fields}, ...
   */
  VALUES,
  /** One field's metadata: {@code metadata/<type>/<field>}. */
  FIELD_METADATA;

  /** How {@code show} prints this query. */
  public static ShowShape of(JfrPath.Query query) {
    if (query.pipeline != null && !query.pipeline.isEmpty()) {
      return ROWS;
    }
    if (query.root == JfrPath.Root.METADATA && query.segments.size() == 2) {
      String field = query.segments.get(1);
      return "fields".equals(field) || "fieldsByName".equals(field) ? VALUES : FIELD_METADATA;
    }
    return query.segments.size() > 1 ? VALUES : ROWS;
  }

  /**
   * The values of a {@link #VALUES} query, stopping early at {@code limit} for events; the caller
   * still trims to {@code limit}.
   */
  public static List<Object> values(
      JfrPathEvaluator eval, JFRSession session, JfrPath.Query query, Integer limit)
      throws Exception {
    return query.root == JfrPath.Root.EVENTS
        ? eval.evaluateValuesWithLimit(session, query, limit)
        : eval.evaluateValues(session, query);
  }

  /**
   * The metadata of the field a {@link #FIELD_METADATA} query names, or {@code null} if the type or
   * field does not exist.
   */
  public static Map<String, Object> fieldMetadata(
      JfrPathEvaluator eval, JFRSession session, JfrPath.Query query) throws Exception {
    return eval.loadFieldMetadata(
        session.getRecordingPath(), query.segments.get(0), query.segments.get(1));
  }
}
