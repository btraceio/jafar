package io.jafar.mcp.findings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FindingsTest {

  @Test
  void idIsStableAcrossEquivalentSubjects() {
    assertEquals(
        Findings.id("cpu", "com.example.Foo::bar"), Findings.id("CPU", "com.example.Foo::bar"));
    assertEquals("cpu:hot-method", Findings.id("cpu", "Hot Method"));
    assertEquals("gc:general", Findings.id("gc", null));
  }

  @Test
  void idTrimsLeadingAndTrailingSeparators() {
    assertEquals("cpu:foo-bar", Findings.id("cpu", "  foo  bar  "));
  }

  @Test
  void mergeDeDuplicatesByIdKeepingTheMostSevere() {
    Finding info = Finding.of("gc", "pressure").info().title("moderate").build();
    Finding warning = Finding.of("gc", "pressure").warning().title("high").build();

    List<Finding> merged = Findings.merge(List.of(info), List.of(warning));

    assertEquals(1, merged.size());
    assertEquals(Finding.Severity.WARNING, merged.get(0).severity());
    assertEquals("high", merged.get(0).title());
  }

  @Test
  void mergeKeepsTheFirstDescriptionWhenSeveritiesAreEqual() {
    Finding first = Finding.of("gc", "pressure").warning().title("first").build();
    Finding second = Finding.of("gc", "pressure").warning().title("second").build();

    List<Finding> merged = Findings.merge(List.of(first), List.of(second));

    assertEquals(1, merged.size());
    assertEquals("first", merged.get(0).title());
  }

  @Test
  void mergeOrdersBySeverityMostSevereFirst() {
    List<Finding> merged =
        Findings.merge(
            List.of(
                Finding.of("a", "1").info().title("info").build(),
                Finding.of("b", "2").critical().title("critical").build(),
                Finding.of("c", "3").warning().title("warning").build()));

    assertEquals(
        List.of("critical", "warning", "info"), merged.stream().map(Finding::title).toList());
  }

  @Test
  void mergeToleratesNullListsAndEntries() {
    List<Finding> merged = Findings.merge(null, java.util.Arrays.asList((Finding) null), List.of());
    assertTrue(merged.isEmpty());
  }

  @Test
  void countBySeverityReportsEveryLevel() {
    Map<String, Integer> counts =
        Findings.countBySeverity(
            List.of(
                Finding.of("a", "1").critical().title("x").build(),
                Finding.of("b", "2").info().title("y").build(),
                Finding.of("c", "3").info().title("z").build()));

    assertEquals(1, counts.get("CRITICAL"));
    assertEquals(0, counts.get("WARNING"));
    assertEquals(2, counts.get("INFO"));
  }

  @Test
  void toMapOmitsNullMembersAndEmptyEvidence() {
    Map<String, Object> map = Finding.of("cpu", "x").warning().title("t").build().toMap();

    assertEquals("cpu:x", map.get("id"));
    assertEquals("WARNING", map.get("severity"));
    assertEquals("t", map.get("title"));
    assertFalse(map.containsKey("description"));
    assertFalse(map.containsKey("evidence"));
    assertFalse(map.containsKey("action"));
    assertFalse(map.containsKey("query"));
  }

  @Test
  void builderSkipsNullEvidenceValues() {
    Finding finding =
        Finding.of("cpu", "x").title("t").evidence("present", 1).evidence("absent", null).build();

    assertEquals(Map.of("present", 1), finding.evidence());
  }

  @Test
  void titleAndCategoryAreRequired() {
    assertThrows(
        IllegalArgumentException.class, () -> Finding.of("cpu", "x").title((String) null).build());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Finding("id", Finding.Severity.INFO, "  ", "title", null, null, null, null, null));
  }

  @Test
  void nullSeverityDefaultsToInfo() {
    Finding finding = new Finding("id", null, "cpu", "title", null, null, null, null, null);
    assertEquals(Finding.Severity.INFO, finding.severity());
  }

  @Test
  void evidenceIsDefensivelyCopied() {
    Map<String, Object> mutable = new java.util.HashMap<>();
    mutable.put("a", 1);
    Finding finding =
        new Finding("id", Finding.Severity.INFO, "cpu", "title", null, null, mutable, null, null);
    mutable.put("b", 2);

    assertEquals(1, finding.evidence().size());
    assertThrows(UnsupportedOperationException.class, () -> finding.evidence().put("c", 3));
  }

  @Test
  void severityMaxPrefersTheMoreSevere() {
    assertEquals(
        Finding.Severity.CRITICAL, Finding.Severity.WARNING.max(Finding.Severity.CRITICAL));
    assertEquals(Finding.Severity.WARNING, Finding.Severity.WARNING.max(Finding.Severity.INFO));
    assertEquals(Finding.Severity.INFO, Finding.Severity.INFO.max(null));
  }

  @Test
  void toMapsPreservesOrder() {
    List<Map<String, Object>> maps =
        Findings.toMaps(
            List.of(
                Finding.of("a", "1").critical().title("first").build(),
                Finding.of("b", "2").info().title("second").build()));

    assertEquals(2, maps.size());
    assertEquals("first", maps.get(0).get("title"));
    assertEquals("second", maps.get(1).get("title"));
    assertNull(maps.get(0).get("action"));
  }
}
