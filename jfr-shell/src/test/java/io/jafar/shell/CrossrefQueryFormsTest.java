package io.jafar.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.cli.CommandDispatcher;
import io.jafar.shell.core.Session;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.jfrpath.JfrPathEvaluator;
import io.jafar.shell.jfrpath.JfrPathParser;
import io.jafar.shell.providers.ConstantPoolProvider;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code crossref()} is a JfrPath pipeline function: every way of typing it reaches the same
 * evaluator and yields the same single summary row.
 *
 * <p>It used to work only as a bare {@code constants/<type> | crossref()} line or {@code cp <type>
 * | crossref()} in jfr-shell, both special-cased in {@code CommandDispatcher}. {@code show ... |
 * crossref()}, the unified jafar-shell, scripts and {@code set} reached {@code JfrPathParser} and
 * failed with "Unknown pipeline function: crossref", although the evaluator advertised the operator
 * and the docs list it as a query.
 */
class CrossrefQueryFormsTest {
  private static final String TYPE = "jdk.types.StackTrace";
  private static final String QUERY = "constants/" + TYPE + " | crossref()";

  private CrossrefDecodeFailureTest.BufferIO io;
  private SessionManager<Session> sessions;
  private CommandDispatcher dispatcher;

  private static Path recording() {
    return Paths.get("..", "parser-core", "src", "test", "resources", "test-jfr.jfr")
        .toAbsolutePath()
        .normalize();
  }

  @BeforeEach
  void setUp() {
    ParsingContext ctx = ParsingContext.create();
    sessions = new SessionManager<>((path, c) -> new JFRSession(path, (ParsingContext) c), ctx);
    io = new CrossrefDecodeFailureTest.BufferIO();
    dispatcher = new CommandDispatcher(sessions, io, r -> {});
    dispatcher.dispatch("open " + recording());
    assertEquals("", io.err.toString(), "open failed");
  }

  @AfterEach
  void tearDown() throws Exception {
    sessions.closeAll();
  }

  /** Runs one line and returns its stdout, failing on any stderr. */
  private String run(String line) {
    io.out.setLength(0);
    io.err.setLength(0);
    dispatcher.dispatch(line);
    assertEquals("", io.err.toString(), line + "\nstdout:\n" + io.out);
    return io.out.toString();
  }

  @Test
  void everyFormPrintsTheSameRow() {
    String bare = run(QUERY);
    assertTrue(bare.contains("referencingEventTypes"), bare);
    assertTrue(bare.contains("| " + TYPE + " "), bare);
    assertEquals(bare, run("show " + QUERY));
    assertEquals(bare, run("cp " + TYPE + " | crossref()"));
    assertEquals(bare, run("constants " + TYPE + " | crossref()"));
    // without spaces around the pipe: 'cp T|crossref()' is one word to the command's parser
    assertEquals(bare, run("cp " + TYPE + "|crossref()"));
    assertEquals(bare, run("cp " + TYPE + " |crossref()"));
    assertEquals(bare, run("cp " + TYPE + "| crossref()"));
    assertEquals(bare, run("constants " + TYPE + "|crossref()"));
  }

  @Test
  void rowCarriesTheBackendFigures() throws Exception {
    Map<String, Object> expected = ConstantPoolProvider.crossref(recording(), TYPE);
    JFRSession session = (JFRSession) sessions.current().orElseThrow().session;
    List<Map<String, Object>> rows =
        new JfrPathEvaluator().evaluate(session, JfrPathParser.parse(QUERY));

    assertEquals(1, rows.size(), rows.toString());
    Map<String, Object> row = rows.get(0);
    assertEquals(
        List.of(
            "type",
            "total",
            "referenced",
            "unreferenced",
            "referencedPercent",
            "unreferencedPercent",
            "referencingEventTypes"),
        List.copyOf(row.keySet()));
    assertEquals(TYPE, row.get("type"));
    assertEquals(((Number) expected.get("cpTotal")).longValue(), row.get("total"));
    assertEquals(((Number) expected.get("cpReferenced")).longValue(), row.get("referenced"));
    assertEquals(((Number) expected.get("cpUnused")).longValue(), row.get("unreferenced"));
    assertTrue(((Long) row.get("total")) > 0, "fixture has no " + TYPE + " entries: " + row);
    assertTrue(((Long) row.get("referenced")) > 0, "fixture references none: " + row);

    @SuppressWarnings("unchecked")
    Map<String, Number> events = (Map<String, Number>) expected.get("eventCounts");
    @SuppressWarnings("unchecked")
    Map<String, Number> unique = (Map<String, Number>) expected.get("uniqueRefsByEventType");
    @SuppressWarnings("unchecked")
    Map<String, Number> direct = (Map<String, Number>) expected.get("totalRefsByEventType");
    List<?> referencing = assertInstanceOf(List.class, row.get("referencingEventTypes"));
    Set<String> seen = new HashSet<>();
    long previous = Long.MAX_VALUE;
    for (Object o : referencing) {
      @SuppressWarnings("unchecked")
      Map<String, Object> r = (Map<String, Object>) o;
      String t = (String) r.get("eventType");
      assertTrue(seen.add(t), "duplicate " + t);
      long ev = (Long) r.get("events");
      assertEquals(events.getOrDefault(t, 0L).longValue(), ev, t);
      assertEquals(unique.getOrDefault(t, 0L).longValue(), r.get("uniqueRefs"), t);
      assertEquals(direct.getOrDefault(t, 0L).longValue(), r.get("directRefs"), t);
      assertTrue(ev <= previous, "not sorted by events, most first: " + referencing);
      previous = ev;
    }
    Set<String> all = new HashSet<>(events.keySet());
    all.addAll(unique.keySet());
    all.addAll(direct.keySet());
    assertEquals(all, seen);
  }

  @Test
  void jsonCarriesTheBreakdown() {
    String json = run("show " + QUERY + " --format json");
    assertTrue(json.contains("\"referencingEventTypes\""), json);
    assertTrue(json.contains("\"eventType\""), json);
    assertTrue(json.contains("\"directRefs\""), json);
  }

  @Test
  void rowChainsLikeAnyOther() {
    String out = run("show " + QUERY + " | select(type, total)");
    assertTrue(out.contains("| " + TYPE + " "), out);
    assertFalse(out.contains("referencingEventTypes"), out);
  }

  /** Anything but a bare {@code constants/<type>} right before it is refused, never ignored. */
  @Test
  void misplacedCrossrefIsAnError() {
    for (String line :
        List.of(
            "show constants/" + TYPE + "/frames | crossref()",
            "show constants/" + TYPE + "[truncated=true] | crossref()",
            "show constants/" + TYPE + " | head(1) | crossref()",
            "show constants | crossref()",
            "show events/jdk.ExecutionSample | crossref()")) {
      io.out.setLength(0);
      io.err.setLength(0);
      dispatcher.dispatch(line);
      String ctx = line + "\nstderr:\n" + io.err + "stdout:\n" + io.out;
      assertTrue(
          io.err.toString().contains("crossref() must directly follow constants/<type>"), ctx);
      assertFalse(io.out.toString().contains("referencingEventTypes"), ctx);
    }
  }
}
