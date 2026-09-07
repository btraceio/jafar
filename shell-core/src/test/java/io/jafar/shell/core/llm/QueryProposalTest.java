package io.jafar.shell.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class QueryProposalTest {

  @Test
  void parsesTheRequestedFormat() {
    QueryProposal p =
        QueryProposal.parse(
            """
            QUERY: events/jdk.ExecutionSample | groupBy(sampledThread/javaName) | top(10, by=count)
            WHY: Groups CPU samples by thread and ranks the busiest ten.
            """);

    assertTrue(p.hasQuery());
    assertEquals(
        "events/jdk.ExecutionSample | groupBy(sampledThread/javaName) | top(10, by=count)",
        p.query());
    assertEquals("Groups CPU samples by thread and ranks the busiest ten.", p.rationale());
    assertFalse(p.unanswerable());
  }

  @Test
  void joinsAMultiLineRationale() {
    QueryProposal p =
        QueryProposal.parse(
            """
            QUERY: events/jdk.GCPhasePause | stats(duration)
            WHY: Summarises pause durations,
            which answers how long GC stopped the application.
            """);
    assertEquals(
        "Summarises pause durations, which answers how long GC stopped the application.",
        p.rationale());
  }

  @Test
  void stripsInlineBackticks() {
    QueryProposal p = QueryProposal.parse("QUERY: `events/jdk.FileRead | count()`");
    assertEquals("events/jdk.FileRead | count()", p.query());
  }

  @Test
  void fallsBackToAFencedBlock() {
    QueryProposal p =
        QueryProposal.parse(
            """
            Here is the query you want:

            ```
            events/jdk.JavaMonitorEnter | groupBy(monitorClass) | top(5)
            ```
            """);
    assertTrue(p.hasQuery());
    assertEquals("events/jdk.JavaMonitorEnter | groupBy(monitorClass) | top(5)", p.query());
  }

  @Test
  void skipsCommentLinesInsideAFence() {
    QueryProposal p =
        QueryProposal.parse(
            """
            ```
            # count the reads
            events/jdk.FileRead | count()
            ```
            """);
    assertEquals("events/jdk.FileRead | count()", p.query());
  }

  @Test
  void recognisesAnUnanswerableQuestion() {
    QueryProposal p =
        QueryProposal.parse(
            """
            QUERY: <none>
            WHY: Allocation profiling was not enabled, so allocation cannot be assessed.
            """);
    assertTrue(p.unanswerable());
    assertFalse(p.hasQuery());
    assertTrue(p.rationale().contains("Allocation profiling"));
  }

  @Test
  void returnsNothingRatherThanGuessing() {
    QueryProposal p = QueryProposal.parse("I am not sure what you mean.");
    assertFalse(p.hasQuery());
    assertFalse(p.unanswerable());
    assertNull(p.query());
  }

  @Test
  void toleratesEmptyAndNullReplies() {
    assertFalse(QueryProposal.parse(null).hasQuery());
    assertFalse(QueryProposal.parse("").hasQuery());
    assertFalse(QueryProposal.parse("   ").hasQuery());
  }

  @Test
  void isCaseInsensitiveOnTheLabels() {
    QueryProposal p =
        QueryProposal.parse("query: events/jdk.FileRead | count()\nwhy: counts reads");
    assertEquals("events/jdk.FileRead | count()", p.query());
    assertEquals("counts reads", p.rationale());
  }
}
