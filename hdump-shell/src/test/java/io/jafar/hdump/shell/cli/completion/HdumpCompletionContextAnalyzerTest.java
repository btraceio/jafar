package io.jafar.hdump.shell.cli.completion;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.jafar.shell.core.completion.CompletionContext;
import io.jafar.shell.core.completion.CompletionContextType;
import java.util.List;
import org.jline.reader.ParsedLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for HdumpCompletionContextAnalyzer. */
class HdumpCompletionContextAnalyzerTest {

  private HdumpCompletionContextAnalyzer analyzer;

  @BeforeEach
  void setUp() {
    analyzer = new HdumpCompletionContextAnalyzer();
  }

  @Test
  void testCommandCompletion() {
    ParsedLine line = createParsedLine("sh", 2, 0);
    CompletionContext ctx = analyzer.analyze(line);
    assertEquals(CompletionContextType.COMMAND, ctx.type());
    assertEquals("sh", ctx.partialInput());
  }

  @Test
  void testRootCompletion() {
    ParsedLine line = createParsedLine("show ", 5, 1);
    CompletionContext ctx = analyzer.analyze(line);
    assertEquals(CompletionContextType.ROOT, ctx.type());
  }

  @Test
  void testRootPartialCompletion() {
    ParsedLine line = createParsedLine("show obj", 8, 1);
    CompletionContext ctx = analyzer.analyze(line);
    assertEquals(CompletionContextType.ROOT, ctx.type());
    assertEquals("obj", ctx.partialInput());
  }

  @Test
  void testTypePatternCompletion() {
    ParsedLine line = createParsedLine("show objects/java.lang.S", 24, 1);
    CompletionContext ctx = analyzer.analyze(line);
    assertEquals(CompletionContextType.TYPE_PATTERN, ctx.type());
    assertEquals("objects", ctx.rootType());
    assertEquals("java.lang.S", ctx.partialInput());
  }

  @Test
  void testClassesTypePatternCompletion() {
    ParsedLine line = createParsedLine("show classes/java.", 18, 1);
    CompletionContext ctx = analyzer.analyze(line);
    assertEquals(CompletionContextType.TYPE_PATTERN, ctx.type());
    assertEquals("classes", ctx.rootType());
    assertEquals("java.", ctx.partialInput());
  }

  @Test
  void testFilterFieldCompletion() {
    ParsedLine line = createParsedLine("show objects/java.lang.String[", 30, 1);
    CompletionContext ctx = analyzer.analyze(line);
    assertEquals(CompletionContextType.FILTER_FIELD, ctx.type());
    assertEquals("objects", ctx.rootType());
  }

  @Test
  void testFilterFieldPartialCompletion() {
    ParsedLine line = createParsedLine("show objects/java.lang.String[shal", 34, 1);
    CompletionContext ctx = analyzer.analyze(line);
    assertEquals(CompletionContextType.FILTER_FIELD, ctx.type());
    assertEquals("shal", ctx.partialInput());
  }

  @Test
  void testPipelineOperatorCompletion() {
    ParsedLine line = createParsedLine("show objects/java.lang.String | ", 32, 1);
    CompletionContext ctx = analyzer.analyze(line);
    assertEquals(CompletionContextType.PIPELINE_OPERATOR, ctx.type());
    assertEquals("objects", ctx.rootType());
  }

  @Test
  void testPipelineOperatorPartialCompletion() {
    ParsedLine line = createParsedLine("show objects/java.lang.String | top", 35, 1);
    CompletionContext ctx = analyzer.analyze(line);
    assertEquals(CompletionContextType.PIPELINE_OPERATOR, ctx.type());
    assertEquals("top", ctx.partialInput());
  }

  @Test
  void testFunctionParamCompletion() {
    ParsedLine line = createParsedLine("show objects/java.lang.String | select(", 39, 1);
    CompletionContext ctx = analyzer.analyze(line);
    assertEquals(CompletionContextType.FUNCTION_PARAM, ctx.type());
    assertEquals("select", ctx.functionName());
    assertEquals(0, ctx.parameterIndex());
  }

  @Test
  void testFunctionSecondParamCompletion() {
    ParsedLine line = createParsedLine("show objects/java.lang.String | select(id, ", 43, 1);
    CompletionContext ctx = analyzer.analyze(line);
    assertEquals(CompletionContextType.FUNCTION_PARAM, ctx.type());
    assertEquals("select", ctx.functionName());
    assertEquals(1, ctx.parameterIndex());
  }

  @Test
  void testClassesGroupByCompletion() {
    // This is the user's scenario: show classes/java.lang.StringUTF16 | groupBy(<tab>
    ParsedLine line =
        createParsedLine("show classes/java.lang.StringUTF16 | groupBy(", 45, 1);
    CompletionContext ctx = analyzer.analyze(line);
    assertEquals(CompletionContextType.FUNCTION_PARAM, ctx.type());
    assertEquals("classes", ctx.rootType());
    assertEquals("groupBy", ctx.functionName());
    assertEquals(0, ctx.parameterIndex());
  }

  @Test
  void testFunctionParamWithRootType() {
    // Verify root type is preserved in function param context
    ParsedLine line = createParsedLine("show objects/String | select(", 29, 1);
    CompletionContext ctx = analyzer.analyze(line);
    assertEquals(CompletionContextType.FUNCTION_PARAM, ctx.type());
    assertEquals("objects", ctx.rootType());
    assertEquals("select", ctx.functionName());
  }

  /** Creates a mock ParsedLine for testing. */
  private ParsedLine createParsedLine(String line, int cursor, int wordIndex) {
    // Parse words by splitting on whitespace
    String beforeCursor = line.substring(0, cursor);
    List<String> words = List.of(line.split("\\s+"));
    String currentWord = wordIndex < words.size() ? words.get(wordIndex) : "";

    // For word at cursor, extract from line
    int wordStart = 0;
    for (int i = cursor - 1; i >= 0; i--) {
      if (Character.isWhitespace(line.charAt(i))) {
        wordStart = i + 1;
        break;
      }
    }
    String wordAtCursor = line.substring(wordStart, cursor);

    return new TestParsedLine(line, cursor, wordAtCursor, wordIndex, words);
  }

  /** Simple ParsedLine implementation for testing. */
  private record TestParsedLine(
      String line, int cursor, String word, int wordIndex, List<String> words)
      implements ParsedLine {

    @Override
    public String line() {
      return line;
    }

    @Override
    public int cursor() {
      return cursor;
    }

    @Override
    public String word() {
      return word;
    }

    @Override
    public int wordCursor() {
      return word.length();
    }

    @Override
    public int wordIndex() {
      return wordIndex;
    }

    @Override
    public List<String> words() {
      return words;
    }
  }
}
