package io.jafar.shell.core.llm;

import java.util.Optional;

/**
 * A query the model proposed, parsed out of its reply.
 *
 * <p>Parsing is deliberately forgiving. The prompt asks for {@code QUERY:} / {@code WHY:} lines,
 * but a model may wrap the query in a code fence or add a sentence before it, and failing the whole
 * command over formatting would be a poor trade. What is *not* forgiving: if no query can be found,
 * this returns {@link #none} rather than guessing, because a fabricated query that happens to parse
 * is worse than an honest failure.
 */
public record QueryProposal(String query, String rationale, boolean unanswerable) {

  /** The model said the recording cannot answer the question. {@code rationale} says why. */
  public static QueryProposal unanswerable(String rationale) {
    return new QueryProposal(null, rationale, true);
  }

  public static QueryProposal none() {
    return new QueryProposal(null, null, false);
  }

  public boolean hasQuery() {
    return query != null && !query.isBlank();
  }

  public Optional<String> rationaleText() {
    return rationale == null || rationale.isBlank() ? Optional.empty() : Optional.of(rationale);
  }

  /** Parses a model reply into a proposal. */
  public static QueryProposal parse(String reply) {
    if (reply == null || reply.isBlank()) {
      return none();
    }

    String query = null;
    StringBuilder why = new StringBuilder();
    boolean inWhy = false;

    for (String rawLine : reply.split("\\R")) {
      String line = rawLine.strip();
      if (line.isEmpty()) {
        continue;
      }
      String upper = line.toUpperCase(java.util.Locale.ROOT);
      if (upper.startsWith("QUERY:")) {
        query = stripFences(line.substring("QUERY:".length()).strip());
        inWhy = false;
      } else if (upper.startsWith("WHY:")) {
        why.setLength(0);
        why.append(line.substring("WHY:".length()).strip());
        inWhy = true;
      } else if (inWhy) {
        why.append(' ').append(line);
      }
    }

    // Fall back to a fenced block when the model ignored the line format.
    if (query == null) {
      query = extractFencedQuery(reply);
    }

    String rationale = why.length() == 0 ? null : why.toString().strip();

    if (query == null) {
      return rationale == null ? none() : new QueryProposal(null, rationale, false);
    }
    if (query.isBlank() || "<none>".equalsIgnoreCase(query) || "none".equalsIgnoreCase(query)) {
      return unanswerable(rationale);
    }
    return new QueryProposal(query, rationale, false);
  }

  private static String extractFencedQuery(String reply) {
    int open = reply.indexOf("```");
    if (open < 0) {
      return null;
    }
    int lineEnd = reply.indexOf('\n', open);
    if (lineEnd < 0) {
      return null;
    }
    int close = reply.indexOf("```", lineEnd);
    String body = close < 0 ? reply.substring(lineEnd + 1) : reply.substring(lineEnd + 1, close);
    for (String line : body.split("\\R")) {
      String candidate = line.strip();
      if (!candidate.isEmpty() && !candidate.startsWith("#")) {
        return candidate;
      }
    }
    return null;
  }

  /** Strips inline backticks a model may wrap the query in. */
  private static String stripFences(String value) {
    String out = value.strip();
    if (out.startsWith("`") && out.endsWith("`") && out.length() > 1) {
      out = out.substring(1, out.length() - 1).strip();
    }
    return out;
  }
}
