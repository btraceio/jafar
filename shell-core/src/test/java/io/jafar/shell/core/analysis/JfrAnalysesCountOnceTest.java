package io.jafar.shell.core.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.jfrpath.JfrPath;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * An analysis counts the recording's events once. Its detectors and the analyses it runs inside it
 * ({@code diagnose} runs five) each asked for the counts, and each count reads the whole recording.
 */
class JfrAnalysesCountOnceTest {
  private static final Path TCK_RECORDING =
      Path.of("..", "jfr-shell-tck", "src", "main", "resources", "tck-test.jfr")
          .toAbsolutePath()
          .normalize();

  /** The real engine, counting how often it is asked for the event counts. */
  private static final class CountingSource implements JfrQuerySource {
    final JfrQuerySource real = JfrQuerySource.defaultSource();
    final AtomicInteger countCalls = new AtomicInteger();

    @Override
    public List<Map<String, Object>> evaluate(JFRSession session, JfrPath.Query query)
        throws Exception {
      return real.evaluate(session, query);
    }

    @Override
    public void consume(
        JFRSession session, JfrPath.Query query, Consumer<Map<String, Object>> consumer)
        throws Exception {
      real.consume(session, query, consumer);
    }

    @Override
    public Map<String, Long> countAllEventTypes(JFRSession session) throws Exception {
      countCalls.incrementAndGet();
      return real.countAllEventTypes(session);
    }
  }

  @Test
  void diagnoseCountsOnce() throws Exception {
    try (JFRSession session = new JFRSession(TCK_RECORDING, ParsingContext.create())) {
      CountingSource source = new CountingSource();
      JfrAnalyses analyses = new JfrAnalyses(source);
      Map<String, Object> result =
          analyses.diagnose(AnalysisTarget.of(1, session), new HashMap<>(), Progress.NONE);
      assertTrue(result.containsKey("recordingPath"), result.keySet().toString());
      assertEquals(1, source.countCalls.get(), "countAllEventTypes calls in one diagnose");

      // A second analysis counts again: the recording may have changed in between.
      analyses.summary(AnalysisTarget.of(1, session), Progress.NONE);
      assertEquals(2, source.countCalls.get(), "a later analysis reuses nothing");
    }
  }

  @Test
  void theCountsAreTheSameAsCountingDirectly() throws Exception {
    try (JFRSession session = new JFRSession(TCK_RECORDING, ParsingContext.create())) {
      AnalysisTarget target = AnalysisTarget.of(1, session);
      JfrAnalyses analyses = new JfrAnalyses();
      Map<String, Object> summary = analyses.summary(target, Progress.NONE);
      long direct =
          JfrQuerySource.defaultSource().countAllEventTypes(session).values().stream()
              .mapToLong(Long::longValue)
              .sum();
      assertEquals(direct, ((Number) summary.get("totalEvents")).longValue());
    }
  }
}
