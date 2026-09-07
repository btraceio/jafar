package io.jafar.shell.core.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Removes sensitive values from query results before they leave the process.
 *
 * <p>A production recording is not neutral data: file paths leak deployment layout, socket
 * addresses leak topology, and exception messages and heap string values leak whatever the
 * application was handling. Sending any of that to a third-party API is the user's decision, so the
 * shell redacts a conservative default set and shows exactly what would be sent.
 *
 * <p>Redaction is by field name, matching the existing scrubber in {@code tools/} ({@code
 * io.jafar.tools.Scrubber}), which redacts named event fields in a recording. The same mental model
 * applies here, one layer further out: that scrubber rewrites a file, this rewrites a prompt.
 *
 * <p>What is deliberately *not* redacted: class names, method names, thread names, event type names
 * and numeric values. Without them there is no performance question left to ask. Users who need
 * them redacted too can extend the list, at the cost of answer quality.
 */
public final class Redactor {

  /** Marker substituted for a redacted value. Recognisable in dry-run output. */
  public static final String PLACEHOLDER = "<redacted>";

  private final boolean enabled;
  private final Set<String> fields;

  public Redactor(boolean enabled, Set<String> fields) {
    this.enabled = enabled;
    this.fields = fields;
  }

  public static Redactor from(LlmConfig config) {
    return new Redactor(config.redactionEnabled(), config.redactFields());
  }

  /** Redacts a list of result rows, leaving the originals untouched. */
  public List<Map<String, Object>> redactRows(List<Map<String, Object>> rows) {
    if (!enabled || rows == null) {
      return rows == null ? List.of() : rows;
    }
    List<Map<String, Object>> out = new ArrayList<>(rows.size());
    for (Map<String, Object> row : rows) {
      out.add(redactRow(row));
    }
    return out;
  }

  private Map<String, Object> redactRow(Map<String, Object> row) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : row.entrySet()) {
      String key = entry.getKey();
      out.put(key, shouldRedact(key) ? PLACEHOLDER : redactValue(entry.getValue()));
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private Object redactValue(Object value) {
    // Rows can nest: a decorated event carries $decorator.* fields, and heap rows carry paths.
    // Redaction has to follow the structure or it only protects the top level.
    if (value instanceof Map<?, ?> map) {
      return redactRow((Map<String, Object>) map);
    }
    if (value instanceof List<?> list) {
      List<Object> out = new ArrayList<>(list.size());
      for (Object element : list) {
        out.add(redactValue(element));
      }
      return out;
    }
    return value;
  }

  /**
   * Whether a field name is redacted. Matches the last path segment too, so {@code $decorator.path}
   * and {@code source/path} are caught along with {@code path}.
   */
  boolean shouldRedact(String key) {
    if (!enabled || key == null) {
      return false;
    }
    String normalised = key.toLowerCase(Locale.ROOT);
    if (fields.contains(normalised)) {
      return true;
    }
    int lastSeparator = Math.max(normalised.lastIndexOf('.'), normalised.lastIndexOf('/'));
    return lastSeparator >= 0 && fields.contains(normalised.substring(lastSeparator + 1));
  }

  public boolean enabled() {
    return enabled;
  }
}
