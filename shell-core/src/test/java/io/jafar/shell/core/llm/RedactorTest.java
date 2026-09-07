package io.jafar.shell.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RedactorTest {

  private static Redactor defaultRedactor() {
    return new Redactor(true, Set.copyOf(LlmConfig.DEFAULT_REDACT_FIELDS));
  }

  @Test
  void redactsSensitiveFieldsAndKeepsTheRest() {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("path", "/srv/app/secrets/config.yml");
    row.put("bytes", 4096);
    row.put("class", "com.example.Service");

    Map<String, Object> out = defaultRedactor().redactRows(List.of(row)).get(0);

    assertEquals(Redactor.PLACEHOLDER, out.get("path"));
    assertEquals(4096, out.get("bytes"));
    // Class and method names survive: without them there is no performance question left to ask.
    assertEquals("com.example.Service", out.get("class"));
  }

  @Test
  void redactsNestedRowsAndLists() {
    Map<String, Object> nested = new LinkedHashMap<>();
    nested.put("address", "10.0.0.7:5432");
    nested.put("count", 3);

    Map<String, Object> row = new LinkedHashMap<>();
    row.put("peer", nested);
    row.put("samples", List.of(Map.of("message", "boom", "n", 1)));

    Map<String, Object> out = defaultRedactor().redactRows(List.of(row)).get(0);

    @SuppressWarnings("unchecked")
    Map<String, Object> peer = (Map<String, Object>) out.get("peer");
    assertEquals(Redactor.PLACEHOLDER, peer.get("address"));
    assertEquals(3, peer.get("count"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> samples = (List<Map<String, Object>>) out.get("samples");
    assertEquals(Redactor.PLACEHOLDER, samples.get(0).get("message"));
    assertEquals(1, samples.get(0).get("n"));
  }

  @Test
  void matchesTheLastSegmentOfAPath() {
    Redactor redactor = defaultRedactor();
    assertTrue(redactor.shouldRedact("path"));
    assertTrue(redactor.shouldRedact("$decorator.path"));
    assertTrue(redactor.shouldRedact("source/path"));
    assertTrue(redactor.shouldRedact("PATH"));
    assertFalse(redactor.shouldRedact("pathological"));
  }

  @Test
  void disabledRedactorIsAPassThrough() {
    Map<String, Object> row = Map.of("path", "/etc/passwd");
    Redactor redactor = new Redactor(false, Set.of("path"));
    assertEquals("/etc/passwd", redactor.redactRows(List.of(row)).get(0).get("path"));
    assertFalse(redactor.shouldRedact("path"));
  }

  @Test
  void doesNotMutateTheInputRows() {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("path", "/secret");
    defaultRedactor().redactRows(List.of(row));
    assertEquals("/secret", row.get("path"), "the caller's rows must be untouched");
  }

  @Test
  void handlesNullRowList() {
    assertTrue(defaultRedactor().redactRows(null).isEmpty());
  }
}
