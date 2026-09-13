package io.jafar.shell.core.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One move the model makes during an investigation.
 *
 * <p>The loop speaks the same line-prefixed protocol as {@code ask} rather than a provider's
 * tool-calling API. That is a deliberate departure from the handoff document's §3.1, which expected
 * {@code completeWithTools} on {@link LlmBackend}: native tool use exists on the hosted providers
 * and not on a small local model served through an OpenAI-compatible endpoint, so building on it
 * would have made the investigation loop a hosted-only feature and split the backend SPI in two.
 * The {@code FIELDS:} exchange already demonstrated a text protocol carrying a multi-round
 * conversation through every backend unchanged.
 *
 * <p>Parsing is forgiving in the same way {@link QueryProposal} is, and refuses in the same way: an
 * unrecognisable reply becomes {@link Kind#UNKNOWN} rather than a guess, because a fabricated step
 * spends the user's tokens and their patience.
 */
public record AnalysisStep(Kind kind, String query, List<String> types, String text) {

  public enum Kind {
    /** Run this query and show me the result. */
    QUERY,
    /** Tell me what fields these types have. */
    FIELDS,
    /** The investigation is finished; {@code text} is the answer. */
    ANSWER,
    /** Nothing usable in the reply. */
    UNKNOWN
  }

  public AnalysisStep {
    types = types == null ? List.of() : List.copyOf(types);
  }

  public static AnalysisStep query(String query) {
    return new AnalysisStep(Kind.QUERY, query, List.of(), null);
  }

  public static AnalysisStep fields(List<String> types) {
    return new AnalysisStep(Kind.FIELDS, null, types, null);
  }

  public static AnalysisStep answer(String text) {
    return new AnalysisStep(Kind.ANSWER, null, List.of(), text);
  }

  public static AnalysisStep unknown() {
    return new AnalysisStep(Kind.UNKNOWN, null, List.of(), null);
  }

  /**
   * Reads a reply into a step.
   *
   * <p>{@code ANSWER:} wins over the others. A model that has concluded and also suggests a further
   * query has finished; taking the query instead would spend another round to reach the same place.
   */
  public static AnalysisStep parse(String reply) {
    if (reply == null || reply.isBlank()) {
      return unknown();
    }

    StringBuilder answer = new StringBuilder();
    boolean inAnswer = false;
    String query = null;
    List<String> types = new ArrayList<>();

    for (String rawLine : reply.split("\\R")) {
      String line = rawLine.strip();
      String upper = line.toUpperCase(Locale.ROOT);
      if (upper.startsWith("ANSWER:")) {
        inAnswer = true;
        String rest = line.substring("ANSWER:".length()).strip();
        if (!rest.isEmpty()) {
          answer.append(rest);
        }
      } else if (inAnswer) {
        // Everything after ANSWER: is prose, blank lines included — it is meant to be read.
        answer.append(answer.isEmpty() ? "" : "\n").append(rawLine.stripTrailing());
      } else if (upper.startsWith("QUERY:") && query == null) {
        query = stripFences(line.substring("QUERY:".length()).strip());
      } else if (upper.startsWith("FIELDS:")) {
        for (String name : line.substring("FIELDS:".length()).split("[,\\s]+")) {
          String cleaned = name.trim().replaceAll("^[`'\"]+|[`'\"]+$", "");
          if (!cleaned.isEmpty() && types.size() < PromptBuilder.MAX_FIELD_REQUEST) {
            types.add(cleaned);
          }
        }
      }
    }

    String prose = answer.toString().strip();
    if (!prose.isEmpty()) {
      return answer(prose);
    }
    if (query != null && !query.isBlank()) {
      return query(query);
    }
    if (!types.isEmpty()) {
      return fields(types);
    }
    return unknown();
  }

  private static String stripFences(String value) {
    String trimmed = value.strip();
    if (trimmed.startsWith("`") && trimmed.endsWith("`") && trimmed.length() > 1) {
      trimmed = trimmed.substring(1, trimmed.length() - 1).strip();
    }
    return trimmed;
  }
}
