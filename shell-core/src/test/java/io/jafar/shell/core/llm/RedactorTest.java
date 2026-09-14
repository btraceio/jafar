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

  @Test
  void theParsersStringWrapperIsUnwrappedRatherThanRedactedWholesale() {
    // The untyped parser delivers a string constant as {string=[B}. That inner key is structure,
    // not a field name — but "string" is in the default redact list, so every wrapped constant was
    // being replaced: class names, symbols, group-by keys. The model saw {string=<redacted>} for
    // data that was never sensitive, and the redaction looked like it was working.
    Redactor redactor = new Redactor(true, java.util.Set.of("string", "path"));

    java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
    row.put("key", java.util.Map.of("string", "[B"));
    row.put("count", 8519);

    java.util.Map<String, Object> out = redactor.redactRows(java.util.List.of(row)).get(0);

    assertEquals("[B", out.get("key"));
    assertEquals(8519, out.get("count"));
  }

  @Test
  void aWrappedValueUnderARedactedFieldIsStillRedacted() {
    // Unwrapping must not become an escape hatch: the decision is taken on the outer field name,
    // which is the one the redact list is about.
    Redactor redactor = new Redactor(true, java.util.Set.of("path"));

    java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
    row.put("path", java.util.Map.of("string", "/secrets/customer.key"));

    java.util.Map<String, Object> out = redactor.redactRows(java.util.List.of(row)).get(0);

    assertEquals(Redactor.PLACEHOLDER, out.get("path"));
  }

  @Test
  void aGenuineMultiFieldMapIsLeftAlone() {
    Redactor redactor = new Redactor(true, java.util.Set.of("path"));

    java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
    row.put("frame", java.util.Map.of("string", "a", "line", 42));

    java.util.Map<String, Object> out = redactor.redactRows(java.util.List.of(row)).get(0);

    assertTrue(
        out.get("frame") instanceof java.util.Map, "only the single-entry wrapper collapses");
  }
}
