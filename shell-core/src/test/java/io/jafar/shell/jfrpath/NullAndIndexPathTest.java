package io.jafar.shell.jfrpath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import io.jafar.shell.JFRSession;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * #309: {@code [x == null]} matched nothing for a missing x while {@code filter(x == null)} matched
 * every row, and {@code filter(frames/0/line >= 0)} read frames/0 as a map key and found nothing
 * while {@code [frames/0/line >= 0]} matched.
 */
class NullAndIndexPathTest {

  /** Three events: one with tag and frames, one with a null tag, one with neither. */
  private static long count(String query) throws Exception {
    JFRSession session = Mockito.mock(JFRSession.class);
    when(session.getRecordingPath()).thenReturn(Path.of("/tmp/x.jfr"));
    JfrPathEvaluator.EventSource src =
        (rec, consumer) -> {
          Map<String, Object> full = new java.util.HashMap<>();
          full.put("tag", "a");
          full.put("frames", new Object[] {Map.of("line", 7L), Map.of("line", 9L)});
          full.put("nested", Map.of("x", 1L));
          Map<String, Object> nullTag = new java.util.HashMap<>();
          nullTag.put("tag", null);
          consumer.accept(new JfrPathEvaluator.Event("test.Event", full));
          consumer.accept(new JfrPathEvaluator.Event("test.Event", nullTag));
          consumer.accept(new JfrPathEvaluator.Event("test.Event", Map.of()));
          if (MODES.get()) {
            // A frame without line, and a list holding a frame whose line is null
            Map<String, Object> noLine = new java.util.HashMap<>();
            noLine.put("frames", new Object[] {Map.of("other", 1L)});
            Map<String, Object> nullLine = new java.util.HashMap<>();
            Map<String, Object> frame = new java.util.HashMap<>();
            frame.put("line", null);
            nullLine.put("frames", List.of(frame));
            consumer.accept(new JfrPathEvaluator.Event("test.Event", noLine));
            consumer.accept(new JfrPathEvaluator.Event("test.Event", nullLine));
          }
        };
    return ((Number)
            new JfrPathEvaluator(src)
                .evaluate(session, JfrPathParser.parse(query))
                .get(0)
                .get("count"))
        .longValue();
  }

  @Test
  void bothFormsAgree() throws Exception {
    Map<String, Long> expected =
        Map.ofEntries(
            // Presence checks: missing and null are absent
            Map.entry("tag == null", 2L),
            Map.entry("tag = null", 2L),
            Map.entry("tag != null", 1L),
            Map.entry("nosuch == null", 3L),
            // A missing step on a nested path is absent too
            Map.entry("nested/x == null", 2L),
            Map.entry("nested/x != null", 1L),
            // Array indexes on a nested path
            Map.entry("frames/0/line >= 0", 1L),
            Map.entry("frames/1/line = 9", 1L),
            Map.entry("frames/0/line = 9", 0L),
            Map.entry("frames/5/line == null", 3L),
            // A null literal with an ordering operator matches nothing
            Map.entry("tag > null", 0L));
    List<String> mismatches = new ArrayList<>();
    for (Map.Entry<String, Long> e : expected.entrySet()) {
      // Per condition, so one that throws does not hide the others
      String bracket = attempt("events/test.Event[" + e.getKey() + "] | count()");
      String filter = attempt("events/test.Event | filter(" + e.getKey() + ") | count()");
      String want = String.valueOf(e.getValue());
      if (!want.equals(bracket) || !want.equals(filter)) {
        mismatches.add(
            e.getKey() + ": expected " + want + ", [] " + bracket + ", filter " + filter);
      }
    }
    assertEquals(List.of(), mismatches);
  }

  private static final ThreadLocal<Boolean> MODES = ThreadLocal.withInitial(() -> false);

  @Test
  void matchModesKeepTheirRules() throws Exception {
    // any:/all:/none: are unchanged: a missing list, an element without the field, or one holding
    // null matches nothing, = null included; frames/0 there is a key of each element, not an index.
    // Five events here: the three above, a frame without line, a frame whose line is null
    MODES.set(true);
    try {
      checkModes();
    } finally {
      MODES.set(false);
    }
  }

  private static void checkModes() throws Exception {
    Map<String, Long> expected =
        Map.of(
            "any:frames/line == null", 0L,
            "all:frames/line == null", 0L,
            // the event with lines, the frame without line, the null line
            "none:frames/line == null", 3L,
            "any:frames == null", 0L,
            "any:frames[0]/line == null", 0L,
            "any:frames/line = 9", 1L,
            "any:frames/0/line == null", 0L);
    List<String> mismatches = new ArrayList<>();
    for (Map.Entry<String, Long> e : expected.entrySet()) {
      String got = attempt("events/test.Event[" + e.getKey() + "] | count()");
      if (!String.valueOf(e.getValue()).equals(got)) {
        mismatches.add(e.getKey() + ": expected " + e.getValue() + ", got " + got);
      }
    }
    assertEquals(List.of(), mismatches);
  }

  private static String attempt(String query) {
    try {
      return String.valueOf(count(query));
    } catch (Exception e) {
      return e.getClass().getSimpleName() + ": " + e.getMessage();
    }
  }
}
