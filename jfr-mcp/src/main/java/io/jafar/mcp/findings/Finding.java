package io.jafar.mcp.findings;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A single, machine-readable analysis finding.
 *
 * <p>Every analysis tool that makes a judgement emits findings in this shape, so that an agent can
 * merge, rank and de-duplicate results from several tools without re-parsing prose. Before this
 * type existed, {@code jfr_diagnose}, {@code jfr_use} and {@code jfr_tsa} each returned their own
 * ad-hoc list of strings while only {@code hdump_report} carried structured findings.
 *
 * <p>The {@link #id()} is a stable identifier derived from category and subject, which makes
 * findings de-duplicable across tools: {@code jfr_diagnose} running {@code jfr_use} internally
 * produces the same id as a direct {@code jfr_use} call for the same condition.
 *
 * @param id stable identifier, {@code <category>:<subject>}
 * @param severity how strongly this warrants attention
 * @param category broad area, e.g. {@code cpu}, {@code gc}, {@code threads}, {@code memory}
 * @param title one-line statement of the finding
 * @param description optional detail; may be {@code null}
 * @param source name of the tool that produced the finding, e.g. {@code jfr_use}
 * @param evidence the numbers behind the finding; keys are metric names
 * @param action suggested next step; may be {@code null}
 * @param query a follow-up query that drills into the finding; may be {@code null}
 */
public record Finding(
    String id,
    Severity severity,
    String category,
    String title,
    String description,
    String source,
    Map<String, Object> evidence,
    String action,
    String query) {

  /** Severity ranking, ordered most severe first. */
  public enum Severity {
    CRITICAL,
    WARNING,
    INFO;

    /** Returns the more severe of the two values. */
    public Severity max(Severity other) {
      return other == null || this.ordinal() <= other.ordinal() ? this : other;
    }
  }

  public Finding {
    if (category == null || category.isBlank()) {
      throw new IllegalArgumentException("category is required");
    }
    if (title == null || title.isBlank()) {
      throw new IllegalArgumentException("title is required");
    }
    if (severity == null) {
      severity = Severity.INFO;
    }
    evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
  }

  /** Serialises to the map shape returned over MCP. Null members are omitted. */
  public Map<String, Object> toMap() {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", id);
    map.put("severity", severity.name());
    map.put("category", category);
    map.put("title", title);
    if (description != null) {
      map.put("description", description);
    }
    if (source != null) {
      map.put("source", source);
    }
    if (!evidence.isEmpty()) {
      map.put("evidence", evidence);
    }
    if (action != null) {
      map.put("action", action);
    }
    if (query != null) {
      map.put("query", query);
    }
    return map;
  }

  /**
   * Creates a builder for the given category and subject. The subject is only used to derive the
   * {@link #id()} and does not appear in the output.
   */
  public static Builder of(String category, String subject) {
    return new Builder(category, subject);
  }

  /** Fluent builder. */
  public static final class Builder {
    private final String category;
    private final String subject;
    private Severity severity = Severity.INFO;
    private String title;
    private String description;
    private String source;
    private final Map<String, Object> evidence = new LinkedHashMap<>();
    private String action;
    private String query;

    private Builder(String category, String subject) {
      this.category = category;
      this.subject = subject;
    }

    public Builder severity(Severity severity) {
      this.severity = severity;
      return this;
    }

    public Builder critical() {
      return severity(Severity.CRITICAL);
    }

    public Builder warning() {
      return severity(Severity.WARNING);
    }

    public Builder info() {
      return severity(Severity.INFO);
    }

    public Builder title(String title) {
      this.title = title;
      return this;
    }

    public Builder title(String format, Object... args) {
      this.title = String.format(Locale.ROOT, format, args);
      return this;
    }

    public Builder description(String description) {
      this.description = description;
      return this;
    }

    public Builder source(String source) {
      this.source = source;
      return this;
    }

    /** Adds one piece of supporting evidence. Null values are ignored. */
    public Builder evidence(String key, Object value) {
      if (value != null) {
        this.evidence.put(key, value);
      }
      return this;
    }

    public Builder action(String action) {
      this.action = action;
      return this;
    }

    public Builder query(String query) {
      this.query = query;
      return this;
    }

    public Finding build() {
      String id = Findings.id(category, subject);
      return new Finding(
          id, severity, category, title, description, source, evidence, action, query);
    }
  }
}
