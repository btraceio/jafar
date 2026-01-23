package io.jafar.shell.cli.completion.integration;

import java.util.ArrayList;
import java.util.List;
import org.jline.reader.ParsedLine;
import org.jline.reader.impl.DefaultParser;

/**
 * Test environment for JLine integration testing.
 *
 * <p>This class provides utilities for: - Parsing input with JLine's actual DefaultParser -
 * Comparing SimpleParsedLine behavior with DefaultParser - Testing cursor position accuracy across
 * both implementations
 */
public class JLineTestEnvironment {

  private final DefaultParser parser;

  public JLineTestEnvironment() {
    this.parser = new DefaultParser();
    // Configure parser to match shell behavior
    parser.setEscapeChars(new char[] {}); // No escape chars for testing
    parser.setQuoteChars(new char[] {'"', '\''});
  }

  /** Result of parsing with both SimpleParsedLine and DefaultParser. */
  public record ParseComparison(
      String input,
      int cursor,
      ParsedLine simpleParsed,
      ParsedLine jlineParsed,
      List<String> differences) {

    public boolean isConsistent() {
      return differences.isEmpty();
    }

    public String describe() {
      StringBuilder sb = new StringBuilder();
      sb.append("Input: \"").append(input).append("\" (cursor=").append(cursor).append(")\n");
      sb.append("SimpleParsedLine:\n");
      sb.append("  words: ").append(simpleParsed.words()).append("\n");
      sb.append("  wordIndex: ").append(simpleParsed.wordIndex()).append("\n");
      sb.append("  word: \"").append(simpleParsed.word()).append("\"\n");
      sb.append("  wordCursor: ").append(simpleParsed.wordCursor()).append("\n");
      sb.append("JLine DefaultParser:\n");
      sb.append("  words: ").append(jlineParsed.words()).append("\n");
      sb.append("  wordIndex: ").append(jlineParsed.wordIndex()).append("\n");
      sb.append("  word: \"").append(jlineParsed.word()).append("\"\n");
      sb.append("  wordCursor: ").append(jlineParsed.wordCursor()).append("\n");
      if (!differences.isEmpty()) {
        sb.append("Differences:\n");
        for (String diff : differences) {
          sb.append("  - ").append(diff).append("\n");
        }
      }
      return sb.toString();
    }
  }

  /**
   * Parses the input with both implementations and compares results.
   *
   * @param input the input line
   * @param cursor the cursor position
   * @return the comparison result
   */
  public ParseComparison compare(String input, int cursor) {
    ParsedLine simple = new SimpleParsedLine(input, cursor);
    ParsedLine jline;

    try {
      jline = parser.parse(input, cursor);
    } catch (Exception e) {
      // JLine parser may throw on some inputs
      jline = new FailedParsedLine(input, cursor, e.getMessage());
    }

    List<String> differences = findDifferences(simple, jline);
    return new ParseComparison(input, cursor, simple, jline, differences);
  }

  /** Finds differences between two ParsedLine implementations. */
  private List<String> findDifferences(ParsedLine simple, ParsedLine jline) {
    List<String> diffs = new ArrayList<>();

    if (jline instanceof FailedParsedLine) {
      diffs.add("JLine parser failed: " + ((FailedParsedLine) jline).error);
      return diffs;
    }

    // Compare word count
    if (simple.words().size() != jline.words().size()) {
      diffs.add("Word count: simple=" + simple.words().size() + ", jline=" + jline.words().size());
    }

    // Compare word index
    if (simple.wordIndex() != jline.wordIndex()) {
      diffs.add("Word index: simple=" + simple.wordIndex() + ", jline=" + jline.wordIndex());
    }

    // Compare current word
    if (!simple.word().equals(jline.word())) {
      diffs.add("Current word: simple=\"" + simple.word() + "\", jline=\"" + jline.word() + "\"");
    }

    // Compare word cursor
    if (simple.wordCursor() != jline.wordCursor()) {
      diffs.add("Word cursor: simple=" + simple.wordCursor() + ", jline=" + jline.wordCursor());
    }

    return diffs;
  }

  /**
   * Tests a set of edge case inputs and returns all comparisons.
   *
   * @return list of comparisons with any differences
   */
  public List<ParseComparison> testEdgeCases() {
    List<ParseComparison> results = new ArrayList<>();

    // Filter-related edge cases
    results.add(compare("show events/jdk.ExecutionSample[", 31));
    results.add(compare("show events/jdk.ExecutionSample[startTime >", 43));
    results.add(compare("show events/jdk.ExecutionSample[startTime > 0", 45));
    results.add(compare("show events/jdk.ExecutionSample[startTime > 0 && ", 49));

    // Pipeline-related edge cases
    results.add(compare("show events/jdk.ExecutionSample | ", 34));
    results.add(compare("show events/jdk.ExecutionSample | count(", 40));
    results.add(compare("show events/jdk.ExecutionSample | groupBy(", 42));

    // Nested path edge cases
    results.add(compare("show events/jdk.ExecutionSample/", 32));
    results.add(compare("show events/jdk.ExecutionSample/sampledThread/", 46));
    results.add(compare("show events/jdk.ExecutionSample//", 33));

    // Whitespace edge cases
    results.add(compare("show  events/jdk.ExecutionSample", 32));
    results.add(compare("show events/jdk.ExecutionSample ", 33));
    results.add(compare("  show events/jdk.ExecutionSample", 34));

    // Quote edge cases
    results.add(compare("show events/jdk.ExecutionSample[name == \"test", 44));
    results.add(compare("show events/jdk.ExecutionSample[name == \"test\"", 46));

    // Special character edge cases
    results.add(compare("show events/jdk.ExecutionSample[$decorator.", 44));
    results.add(compare("echo ${var", 10));
    results.add(compare("echo ${var.nested", 17));

    return results;
  }

  /** Generates a corpus of test inputs from patterns. */
  public List<String> generateTestCorpus() {
    List<String> corpus = new ArrayList<>();

    String[] roots = {"events/", "metadata/", "cp/", "chunks/"};
    String[] types = {"jdk.ExecutionSample", "jdk.CPUInformation", "jdk.JavaMonitorEnter"};
    String[] fields = {"startTime", "duration", "sampledThread", "name"};
    String[] operators = {"==", "!=", ">", "<", ">=", "<=", "~"};
    String[] functions = {"count()", "sum(", "groupBy(", "top(", "select("};

    // Generate combinations
    for (String root : roots) {
      corpus.add("show " + root);

      for (String type : types) {
        corpus.add("show " + root + type);
        corpus.add("show " + root + type + "/");

        for (String field : fields) {
          corpus.add("show " + root + type + "/" + field);
          corpus.add("show " + root + type + "[" + field);
          corpus.add("show " + root + type + "[" + field + " ");

          for (String op : operators) {
            corpus.add("show " + root + type + "[" + field + " " + op + " ");
          }
        }

        for (String func : functions) {
          corpus.add("show " + root + type + " | " + func);
        }
      }
    }

    return corpus;
  }

  // ==================== Simple ParsedLine Implementation ====================

  /** Simple ParsedLine implementation matching SimpleParsedLine from mutation tests. */
  static class SimpleParsedLine implements ParsedLine {
    private final String line;
    private final int cursor;
    private final List<String> words;
    private final int wordIndex;

    SimpleParsedLine(String line, int cursor) {
      this.line = line;
      this.cursor = Math.min(cursor, line.length());

      List<String> w = new ArrayList<>();
      String stripped = line.stripLeading();
      if (!stripped.isEmpty()) {
        for (String word : stripped.split("\\s+")) {
          w.add(word);
        }
      }
      if (line.endsWith(" ") || w.isEmpty()) {
        w.add("");
      }
      this.words = List.copyOf(w);
      this.wordIndex = Math.max(0, words.size() - 1);
    }

    @Override
    public String word() {
      return words.get(wordIndex);
    }

    @Override
    public int wordCursor() {
      return word().length();
    }

    @Override
    public int wordIndex() {
      return wordIndex;
    }

    @Override
    public List<String> words() {
      return words;
    }

    @Override
    public String line() {
      return line;
    }

    @Override
    public int cursor() {
      return cursor;
    }
  }

  /** Placeholder for failed parsing. */
  static class FailedParsedLine implements ParsedLine {
    final String line;
    final int cursor;
    final String error;

    FailedParsedLine(String line, int cursor, String error) {
      this.line = line;
      this.cursor = cursor;
      this.error = error;
    }

    @Override
    public String word() {
      return "";
    }

    @Override
    public int wordCursor() {
      return 0;
    }

    @Override
    public int wordIndex() {
      return 0;
    }

    @Override
    public List<String> words() {
      return List.of("");
    }

    @Override
    public String line() {
      return line;
    }

    @Override
    public int cursor() {
      return cursor;
    }
  }
}
