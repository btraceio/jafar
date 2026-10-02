package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every query example in the server's guidance must parse with the real parser of its language.
 *
 * <p>An agent has no other teacher: the examples in tool descriptions, help topics and help
 * resources are copied verbatim into tool calls. An example that does not parse is worse than no
 * example — it teaches every agent that follows it to fail, and because the text looks plausible,
 * the failure surfaces as an agent flailing, not as a Jafar bug. This is the guidance-level twin of
 * R4 (documentation is code): the docs must run.
 *
 * <p>Extraction mirrors how a reader finds examples: fenced code lines, backtick spans, and bare
 * prose (see {@link QueryExamples}). Anything that looks like a query must parse; placeholders and
 * grammar notation are skipped. The extraction is deliberately generous — a candidate that parses
 * is harmless even if it was English — because the test only fails on look-alikes the parser
 * rejects.
 *
 * <p>This class found real bugs on its first run: {@code pprof_query} and {@code otlp_query}
 * shipped examples with literal backslashes ({@code samples[thread=\'main\']}) from over-escaped
 * Java string literals, the JfrPath filters help documented a nested-bracket {@code any:} form the
 * parser rejects, and the heap help taught {@code checkLeaks()} — which does not parse and, had it
 * parsed, would have silently returned rows unchanged.
 */
class GuidanceExampleQueriesTest {

  @Test
  void everyDocumentedQueryExampleParses() {
    GuidanceSurfaces surfaces = new GuidanceSurfaces();
    List<String> failures = new ArrayList<>();
    int checked = 0;
    int finalChecked;
    for (GuidanceSurfaces.Surface surface : surfaces.surfaces()) {
      if (!surface.hasLanguage()) {
        continue;
      }
      for (QueryExamples.Candidate candidate :
          QueryExamples.extract(surface.name(), surface.text(), surface.language())) {
        checked++;
        try {
          surface.language().parse(candidate.query());
        } catch (Exception | AssertionError e) {
          failures.add(
              String.format(
                  "%s (%s): %s%n    parser said: %s",
                  surface.name(), candidate.origin(), candidate.query(), e.getMessage()));
        }
      }
    }
    finalChecked = checked;
    assertTrue(
        failures.isEmpty(),
        () ->
            failures.size()
                + " of "
                + finalChecked
                + " documented query examples do not parse. An agent copying them gets a"
                + " parse error — fix the example or the parser:%n%n"
                + String.join("%n%n", failures));
    assertTrue(finalChecked > 200, "expected a substantial example corpus, got " + finalChecked);
  }
}
