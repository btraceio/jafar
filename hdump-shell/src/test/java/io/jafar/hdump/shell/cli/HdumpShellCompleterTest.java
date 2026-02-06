package io.jafar.hdump.shell.cli;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.shell.core.Session;
import io.jafar.shell.core.SessionManager;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jline.reader.Candidate;
import org.jline.reader.ParsedLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for HdumpShellCompleter - verifies the full completion flow from parsed line to
 * candidates.
 */
class HdumpShellCompleterTest {

  private HdumpShellCompleter completer;

  @BeforeEach
  void setUp() {
    // Create a mock session manager with a mock hdump session
    SessionManager sessions =
        new SessionManager(
            (path, ctx) -> new MockHeapSession(path),
            null);
    try {
      sessions.open(Path.of("/test/heap.hprof"), null);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    completer = new HdumpShellCompleter(sessions);
  }

  @Test
  void testGroupByFunctionCompletion() {
    // User types: show classes/java.lang.StringUTF16 | groupBy(
    // and presses Tab - should see class fields
    ParsedLine line = createParsedLine("show classes/java.lang.StringUTF16 | groupBy(", 45);

    List<Candidate> candidates = new ArrayList<>();
    completer.complete(null, line, candidates);

    // Should have candidates for class fields
    assertFalse(candidates.isEmpty(), "Should have completion candidates");

    // Extract candidate values - they include "groupBy(" prefix for JLine matching
    List<String> values = candidates.stream().map(Candidate::value).toList();

    // Should contain class fields with function prefix
    assertTrue(values.contains("groupBy(id"), "Should contain 'groupBy(id' field");
    assertTrue(values.contains("groupBy(name"), "Should contain 'groupBy(name' field");
    assertTrue(values.contains("groupBy(simpleName"), "Should contain 'groupBy(simpleName' field");
    assertTrue(values.contains("groupBy(instanceCount"), "Should contain 'groupBy(instanceCount' field");

    // Display values should show just the field names
    List<String> displays = candidates.stream().map(Candidate::displ).toList();
    assertTrue(displays.contains("id"), "Display should show 'id'");
  }

  @Test
  void testSelectFunctionWithObjectsRoot() {
    // User types: show objects/java.lang.String | select(
    ParsedLine line = createParsedLine("show objects/java.lang.String | select(", 39);

    List<Candidate> candidates = new ArrayList<>();
    completer.complete(null, line, candidates);

    assertFalse(candidates.isEmpty(), "Should have completion candidates");

    List<String> values = candidates.stream().map(Candidate::value).toList();

    // Should contain object fields with function prefix
    assertTrue(values.contains("select(id"), "Should contain 'select(id' field");
    assertTrue(values.contains("select(class"), "Should contain 'select(class' field");
    assertTrue(values.contains("select(shallowSize"), "Should contain 'select(shallowSize' field");
    assertTrue(values.contains("select(retainedSize"), "Should contain 'select(retainedSize' field");
  }

  @Test
  void testGroupBySecondParamShowsAggregations() {
    // User types: show classes/String | groupBy(name,
    // Cursor is after the space, so JLine's word is empty - no prefix needed
    ParsedLine line = createParsedLine("show classes/String | groupBy(name, ", 37);

    List<Candidate> candidates = new ArrayList<>();
    completer.complete(null, line, candidates);

    assertFalse(candidates.isEmpty(), "Should have completion candidates");

    List<String> values = candidates.stream().map(Candidate::value).toList();

    // Second param should include aggregation functions
    // When word is empty (after space), candidates don't need prefix
    assertTrue(values.contains("count"), "Should contain 'count' aggregation");
    assertTrue(values.contains("sum"), "Should contain 'sum' aggregation");
  }

  @Test
  void testPipelineOperatorCompletion() {
    // User types: show classes/String |
    ParsedLine line = createParsedLine("show classes/String | ", 22);

    List<Candidate> candidates = new ArrayList<>();
    completer.complete(null, line, candidates);

    assertFalse(candidates.isEmpty(), "Should have completion candidates");

    List<String> values = candidates.stream().map(Candidate::value).toList();

    // Should contain pipeline operators (with opening paren for seamless param completion)
    assertTrue(values.contains("select("), "Should contain 'select(' operator");
    assertTrue(values.contains("groupBy("), "Should contain 'groupBy(' operator");
    assertTrue(values.contains("top("), "Should contain 'top(' operator");
  }

  @Test
  void testRootCompletion() {
    // User types: show
    ParsedLine line = createParsedLine("show ", 5);

    List<Candidate> candidates = new ArrayList<>();
    completer.complete(null, line, candidates);

    assertFalse(candidates.isEmpty(), "Should have completion candidates");

    List<String> values = candidates.stream().map(Candidate::value).toList();

    // Should contain root types (with trailing / for seamless path completion)
    assertTrue(values.contains("objects/"), "Should contain 'objects/' root");
    assertTrue(values.contains("classes/"), "Should contain 'classes/' root");
    assertTrue(values.contains("gcroots/"), "Should contain 'gcroots/' root");
  }

  /**
   * Create a ParsedLine that simulates JLine's DefaultParser behavior.
   * JLine treats whitespace as word separators and includes an empty word
   * when cursor is after a separator.
   */
  private ParsedLine createParsedLine(String line, int inputCursor) {
    // Ensure cursor is within valid range
    final int cursor = Math.min(inputCursor, line.length());

    // Build words list by walking through the line
    List<String> words = new ArrayList<>();
    List<int[]> wordPositions = new ArrayList<>(); // [start, end] for each word

    int wordStart = -1;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (Character.isWhitespace(c)) {
        if (wordStart >= 0) {
          words.add(line.substring(wordStart, i));
          wordPositions.add(new int[]{wordStart, i});
          wordStart = -1;
        }
      } else {
        if (wordStart < 0) {
          wordStart = i;
        }
      }
    }
    // Add last word if any
    if (wordStart >= 0) {
      words.add(line.substring(wordStart));
      wordPositions.add(new int[]{wordStart, line.length()});
    }

    // If cursor is at end after whitespace, add empty word
    if (cursor > 0 && (words.isEmpty() || cursor > wordPositions.get(words.size() - 1)[1])) {
      if (words.isEmpty() || Character.isWhitespace(line.charAt(cursor - 1)) || cursor == line.length()) {
        words.add("");
        wordPositions.add(new int[]{cursor, cursor});
      }
    }

    // Find which word the cursor is in
    int wordIndex = 0;
    String currentWord = "";

    for (int i = 0; i < wordPositions.size(); i++) {
      int[] pos = wordPositions.get(i);
      if (cursor >= pos[0] && cursor <= pos[1]) {
        wordIndex = i;
        currentWord = words.get(i).substring(0, Math.min(cursor - pos[0], words.get(i).length()));
        break;
      }
    }

    final int finalWordIndex = wordIndex;
    final String finalCurrentWord = currentWord;
    final List<String> finalWords = List.copyOf(words);

    return new ParsedLine() {
      @Override public String line() { return line; }
      @Override public int cursor() { return cursor; }
      @Override public String word() { return finalCurrentWord; }
      @Override public int wordCursor() { return finalCurrentWord.length(); }
      @Override public int wordIndex() { return finalWordIndex; }
      @Override public List<String> words() { return finalWords; }
    };
  }

  /** Mock HeapSession for testing completion without a real heap dump file. */
  private static class MockHeapSession implements Session {
    private final Path path;

    MockHeapSession(Path path) {
      this.path = path;
    }

    @Override
    public String getType() {
      return "hdump";
    }

    @Override
    public Path getFilePath() {
      return path;
    }

    @Override
    public boolean isClosed() {
      return false;
    }

    @Override
    public Set<String> getAvailableTypes() {
      return Set.of("java.lang.String", "java.lang.Object", "java.util.HashMap");
    }

    @Override
    public Map<String, Object> getStatistics() {
      return Map.of();
    }

    @Override
    public void close() {}
  }
}
