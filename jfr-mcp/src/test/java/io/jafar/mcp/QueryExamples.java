package io.jafar.mcp;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts query-looking examples from a guidance surface, the way an agent reads them.
 *
 * <p>Examples appear in three shapes: fenced code lines, backtick spans, and bare prose ("e.g.,
 * events/jdk.ExecutionSample | count()"). Anything a reader could reasonably copy as a query must
 * parse — an example that does not is worse than no example, because it teaches every agent that
 * follows it to fail.
 *
 * <p>Extraction is deliberately generous: a candidate that parses is harmless even if it was
 * English ("objects" in a sentence), while the test only fails on candidates that <em>look</em>
 * like queries but are rejected by the parser. Two kinds of look-alikes are skipped: placeholder
 * grammar ({@code events/<eventType>}) and optional-argument notation ({@code
 * checkLeaks([detector="name"])}), which are syntax descriptions, not runnable queries.
 */
final class QueryExamples {

  record Candidate(String surface, String query, String origin) {}

  /**
   * Roots that begin a runnable query per language, for the bare-text scan. JfrPath roots carry
   * their slash so English words like "events" do not anchor; the sampling languages anchor on
   * {@code samples} because that is their only root.
   */
  private static final Map<GuidanceSurfaces.QueryLanguage, List<String>> INLINE_ROOTS =
      Map.of(
          GuidanceSurfaces.QueryLanguage.JFRPATH,
          List.of("events/", "metadata/", "constants/"),
          GuidanceSurfaces.QueryLanguage.HDUMPPATH,
          List.of("objects", "classes", "gcroots", "clusters", "duplicates", "ages", "checkLeaks"),
          GuidanceSurfaces.QueryLanguage.PPROFPATH,
          List.of("samples"),
          GuidanceSurfaces.QueryLanguage.OTLPPATH,
          List.of("samples"));

  /**
   * Roots accepted for fenced lines and backtick spans, where the whole span is the example. Adds
   * bare JfrPath roots (e.g. {@code chunks}) that are too generic to anchor a prose scan for.
   */
  private static final Map<GuidanceSurfaces.QueryLanguage, List<String>> DELIMITED_ROOTS =
      Map.of(
          GuidanceSurfaces.QueryLanguage.JFRPATH,
          List.of("events/", "metadata/", "constants/", "chunks"),
          GuidanceSurfaces.QueryLanguage.HDUMPPATH,
          List.of("objects", "classes", "gcroots", "clusters", "duplicates", "ages", "checkLeaks"),
          GuidanceSurfaces.QueryLanguage.PPROFPATH,
          List.of("samples"),
          GuidanceSurfaces.QueryLanguage.OTLPPATH,
          List.of("samples"));

  /** Placeholder grammar like {@code <eventType>} — a syntax description, not a query. */
  private static final Pattern PLACEHOLDER = Pattern.compile("<[A-Za-z_][A-Za-z0-9_ ]*>");

  /**
   * Grammar notation like {@code samples[predicate]} — a bracket naming what goes there rather than
   * a runnable filter. Real filters always carry an operator or quoted value inside the brackets;
   * type brackets like {@code int[]} are empty and never match this.
   */
  private static final Pattern GRAMMAR_BRACKET = Pattern.compile("\\[[A-Za-z_][A-Za-z0-9_]*\\]");

  private static final Pattern BACKTICK_SPAN = Pattern.compile("`([^`\\n]+)`");

  private QueryExamples() {}

  /** All query candidates in {@code text}, deduplicated, in encounter order. */
  static List<Candidate> extract(
      String surface, String text, GuidanceSurfaces.QueryLanguage language) {
    Set<Candidate> out = new LinkedHashSet<>();
    for (String line : fencedLines(text)) {
      String candidate = trimExample(line);
      if (starts(candidate, DELIMITED_ROOTS.get(language))) {
        addIfRunnable(out, surface, candidate, "fenced", language);
      }
    }
    Matcher spans = BACKTICK_SPAN.matcher(text);
    while (spans.find()) {
      String candidate = trimExample(spans.group(1));
      if (starts(candidate, DELIMITED_ROOTS.get(language))) {
        addIfRunnable(out, surface, candidate, "backtick", language);
      }
    }
    scanInline(out, surface, text, language);
    return List.copyOf(out);
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Fenced blocks
  // ─────────────────────────────────────────────────────────────────────────────

  private static List<String> fencedLines(String text) {
    List<String> lines = new ArrayList<>();
    boolean inFence = false;
    for (String rawLine : text.split("\n", -1)) {
      String line = rawLine.trim();
      if (line.startsWith("```")) {
        inFence = !inFence;
        continue;
      }
      if (inFence) {
        // Annotated example lines end with two-plus spaces of prose ("samples[...]   AND");
        // the queries themselves never contain a double space.
        int annotation = line.indexOf("  ");
        lines.add(annotation > 0 ? line.substring(0, annotation).trim() : line);
      }
    }
    return lines;
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Bare-text scan
  // ─────────────────────────────────────────────────────────────────────────────

  private static void scanInline(
      Set<Candidate> out, String surface, String text, GuidanceSurfaces.QueryLanguage language) {
    List<String> roots = INLINE_ROOTS.get(language);
    int from = 0;
    while (from < text.length()) {
      int start = findRoot(text, from, roots);
      if (start < 0) {
        return;
      }
      int end = scanQueryEnd(text, start, roots);
      if (end < 0) {
        from = start + 1;
        continue;
      }
      String candidate = trimExample(text.substring(start, end));
      // A bare root word in prose is English ("objects and classes"); inline candidates must
      // carry query structure — a path, filter, argument or pipeline — to count as examples.
      boolean hasStructure = candidate.length() > rootLength(candidate, roots);
      if (hasStructure) {
        addIfRunnable(out, surface, candidate, "inline", language);
      }
      from = Math.max(end, start + 1);
    }
  }

  private static int findRoot(String text, int from, List<String> roots) {
    int best = -1;
    for (String root : roots) {
      int idx = text.indexOf(root, from);
      if (idx >= 0 && (best < 0 || idx < best)) {
        best = idx;
      }
    }
    return best;
  }

  private static int rootLength(String candidate, List<String> roots) {
    for (String root : roots) {
      if (candidate.startsWith(root)) {
        return root.length();
      }
    }
    return 0;
  }

  /**
   * Consumes a query-shaped substring starting at {@code start}: root, path segments, filters, root
   * arguments and pipeline stages. Quotes protect their contents; stops at anything a query cannot
   * continue with (a comma, a sentence period, a closing quote, a line break).
   *
   * <p>Returns {@code -1} when what follows the root cannot begin a query (a path segment that is
   * missing after a slash — grammar notation such as {@code events/<eventType>}), because the
   * anchored text is not an example at all.
   */
  private static int scanQueryEnd(String text, int start, List<String> roots) {
    int i = start + rootLength(text.substring(start), roots);
    while (i < text.length()) {
      char c = text.charAt(i);
      if (c == '/') {
        int segment = consumeSegment(text, i + 1);
        if (segment == i + 1) {
          return -1; // "root/" with nothing after: grammar fragment, not an example
        }
        i = segment;
      } else if (c == '[' || c == '(') {
        int balanced = consumeBalanced(text, i);
        if (balanced < 0) {
          break; // unbalanced bracket on this line: prose, not a query
        }
        i = balanced;
      } else if (Character.isWhitespace(c)) {
        int j = i;
        while (j < text.length() && Character.isWhitespace(text.charAt(j))) {
          j++;
        }
        if (j < text.length() && text.charAt(j) == '|' && pipelineOperatorFollows(text, j + 1)) {
          i = j;
        } else {
          break;
        }
      } else if (c == '|' && pipelineOperatorFollows(text, i + 1)) {
        i = consumePipelineStage(text, i + 1);
      } else {
        break;
      }
    }
    return i;
  }

  /**
   * A pipeline stage must start with a bare operator name — otherwise the pipe is a table column
   * separator or prose, and the candidate must end before it.
   */
  private static boolean pipelineOperatorFollows(String text, int i) {
    while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
      i++;
    }
    return i < text.length() && (Character.isLetter(text.charAt(i)) || text.charAt(i) == '_');
  }

  private static int consumeSegment(String text, int i) {
    while (i < text.length() && isSegmentChar(text.charAt(i))) {
      i++;
    }
    return i;
  }

  private static boolean isSegmentChar(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '$' || c == '*' || c == '?';
  }

  private static int consumePipelineStage(String text, int i) {
    while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
      i++;
    }
    i = consumeSegment(text, i);
    if (i < text.length() && text.charAt(i) == '(') {
      i = consumeBalanced(text, i);
    }
    return i;
  }

  /**
   * Consumes balanced brackets or parentheses; quotes protect their contents. Returns -1 when the
   * brackets do not close before the end of the line — a query never spans lines, so the anchor was
   * prose.
   */
  private static int consumeBalanced(String text, int i) {
    char open = text.charAt(i);
    char close = open == '[' ? ']' : ')';
    int depth = 0;
    int endOfLine = text.indexOf('\n', i);
    int limit = endOfLine < 0 ? text.length() : endOfLine;
    while (i < limit) {
      char c = text.charAt(i);
      if (c == '"' || c == '\'') {
        i = consumeQuoted(text, i, c);
        continue;
      }
      if (c == open) {
        depth++;
      } else if (c == close) {
        depth--;
        if (depth == 0) {
          return i + 1;
        }
      }
      i++;
    }
    return -1;
  }

  private static int consumeQuoted(String text, int i, char quote) {
    i++;
    while (i < text.length() && text.charAt(i) != quote) {
      i++;
    }
    return Math.min(i + 1, text.length());
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Candidate admission
  // ─────────────────────────────────────────────────────────────────────────────

  private static boolean starts(String candidate, List<String> roots) {
    if (candidate == null || candidate.isBlank()) {
      return false;
    }
    return roots.stream().anyMatch(candidate::startsWith);
  }

  private static void addIfRunnable(
      Set<Candidate> out,
      String surface,
      String candidate,
      String origin,
      GuidanceSurfaces.QueryLanguage language) {
    if (candidate.isBlank() || PLACEHOLDER.matcher(candidate).find()) {
      return;
    }
    if (GRAMMAR_BRACKET.matcher(candidate).find()) {
      return;
    }
    // Optional-argument notation, e.g. checkLeaks([detector="name"]): "[" directly after "("
    // is grammar, not a runnable query.
    if (candidate.contains("([")) {
      return;
    }
    out.add(new Candidate(surface, candidate, origin));
  }

  /** Strips list markers and trailing sentence punctuation from a delimited example line. */
  private static String trimExample(String line) {
    String s = line.trim();
    while (s.startsWith("-") || s.startsWith("*") || s.startsWith("#")) {
      s = s.substring(1).trim();
    }
    // Trailing sentence punctuation. Closing brackets and parens are always part of a query
    // (objects/int[], count()) and are never stripped.
    while (!s.isEmpty()
        && (s.endsWith(".")
            || s.endsWith(",")
            || s.endsWith(";")
            || s.endsWith(":")
            || s.endsWith("\"")
            || s.endsWith("`"))) {
      s = s.substring(0, s.length() - 1).trim();
    }
    return s;
  }
}
