package io.jafar.shell.cli.completion;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.jline.reader.ParsedLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CompletionContextAnalyzerTest {

  private CompletionContextAnalyzer analyzer;

  @BeforeEach
  void setUp() {
    analyzer = new CompletionContextAnalyzer();
  }

  // Simple ParsedLine implementation for testing
  static class TestParsedLine implements ParsedLine {
    private final String line;
    private final List<String> words;
    private final int wordIndex;
    private final int cursor;

    TestParsedLine(String line) {
      this(line, line.length());
    }

    TestParsedLine(String line, int cursor) {
      this.line = line;
      this.cursor = cursor;
      List<String> w = new java.util.ArrayList<>(Arrays.asList(line.stripLeading().split("\\s+")));
      if (line.endsWith(" ")) {
        w.add("");
      }
      this.words = Collections.unmodifiableList(w);
      this.wordIndex = words.size() - 1;
    }

    @Override
    public String word() {
      return words.isEmpty() ? "" : words.get(wordIndex);
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

  @Nested
  class CommandContext {
    @Test
    void detectsCommandContext_emptyLine() {
      CompletionContext ctx = analyzer.analyze(new TestParsedLine(""));
      assertEquals(CompletionContextType.COMMAND, ctx.type());
    }

    @Test
    void detectsCommandContext_partialCommand() {
      CompletionContext ctx = analyzer.analyze(new TestParsedLine("sho"));
      assertEquals(CompletionContextType.COMMAND, ctx.type());
      assertEquals("sho", ctx.partialInput());
    }

    @Test
    void detectsCommandContext_helpCommand() {
      CompletionContext ctx = analyzer.analyze(new TestParsedLine("help"));
      assertEquals(CompletionContextType.COMMAND, ctx.type());
    }
  }

  @Nested
  class OptionContext {
    @Test
    void detectsOptionContext_doubleDash() {
      CompletionContext ctx = analyzer.analyze(new TestParsedLine("show events/jdk.Test --"));
      assertEquals(CompletionContextType.COMMAND_OPTION, ctx.type());
      assertEquals("--", ctx.partialInput());
    }

    @Test
    void detectsOptionContext_partialOption() {
      CompletionContext ctx = analyzer.analyze(new TestParsedLine("show events/jdk.Test --lim"));
      assertEquals(CompletionContextType.COMMAND_OPTION, ctx.type());
      assertEquals("--lim", ctx.partialInput());
    }
  }

  @Nested
  class OptionValueContext {
    @Test
    void detectsOptionValueContext_listMatch() {
      CompletionContext ctx =
          analyzer.analyze(new TestParsedLine("show events/jdk.Test --list-match "));
      assertEquals(CompletionContextType.OPTION_VALUE, ctx.type());
      assertEquals("--list-match", ctx.extras().get("option"));
    }

    @Test
    void detectsOptionValueContext_format() {
      CompletionContext ctx =
          analyzer.analyze(new TestParsedLine("show events/jdk.Test --format "));
      assertEquals(CompletionContextType.OPTION_VALUE, ctx.type());
      assertEquals("--format", ctx.extras().get("option"));
    }
  }

  @Nested
  class RootContext {
    @Test
    void detectsRootContext_afterShow() {
      CompletionContext ctx = analyzer.analyze(new TestParsedLine("show "));
      assertEquals(CompletionContextType.ROOT, ctx.type());
      assertEquals("show", ctx.command());
    }

    @Test
    void detectsRootContext_partialRoot() {
      CompletionContext ctx = analyzer.analyze(new TestParsedLine("show eve"));
      assertEquals(CompletionContextType.ROOT, ctx.type());
      assertEquals("eve", ctx.partialInput());
    }
  }

  @Nested
  class EventTypeContext {
    @Test
    void detectsEventTypeContext_afterEventsSlash() {
      CompletionContext ctx = analyzer.analyze(new TestParsedLine("show events/"));
      assertEquals(CompletionContextType.EVENT_TYPE, ctx.type());
      assertEquals("events", ctx.rootType());
      assertEquals("", ctx.partialInput());
    }

    @Test
    void detectsEventTypeContext_partialEventType() {
      CompletionContext ctx = analyzer.analyze(new TestParsedLine("show events/jdk.Exec"));
      assertEquals(CompletionContextType.EVENT_TYPE, ctx.type());
      assertEquals("jdk.Exec", ctx.partialInput());
    }

    @Test
    void detectsEventTypeContext_metadataClass() {
      CompletionContext ctx = analyzer.analyze(new TestParsedLine("metadata class "));
      assertEquals(CompletionContextType.EVENT_TYPE, ctx.type());
      assertEquals("metadata", ctx.rootType());
    }
  }

  @Nested
  class FieldPathContext {
    @Test
    void detectsFieldPathContext_afterEventType() {
      CompletionContext ctx =
          analyzer.analyze(new TestParsedLine("show events/jdk.ExecutionSample/"));
      assertEquals(CompletionContextType.FIELD_PATH, ctx.type());
      assertEquals("jdk.ExecutionSample", ctx.eventType());
    }

    @Test
    void detectsFieldPathContext_partialField() {
      CompletionContext ctx =
          analyzer.analyze(new TestParsedLine("show events/jdk.ExecutionSample/sampled"));
      assertEquals(CompletionContextType.FIELD_PATH, ctx.type());
      assertEquals("sampled", ctx.partialInput());
    }

    @Test
    void detectsFieldPathContext_nestedPath() {
      CompletionContext ctx =
          analyzer.analyze(new TestParsedLine("show events/jdk.ExecutionSample/sampledThread/"));
      assertEquals(CompletionContextType.FIELD_PATH, ctx.type());
      assertEquals("jdk.ExecutionSample", ctx.eventType());
      assertEquals(List.of("sampledThread"), ctx.fieldPath());
    }
  }

  @Nested
  class FilterContext {
    @Test
    void detectsFilterFieldContext_afterOpenBracket() {
      CompletionContext ctx =
          analyzer.analyze(new TestParsedLine("show events/jdk.ExecutionSample["));
      assertEquals(CompletionContextType.FILTER_FIELD, ctx.type());
      assertEquals("jdk.ExecutionSample", ctx.eventType());
    }

    @Test
    void detectsFilterFieldContext_partialField() {
      CompletionContext ctx =
          analyzer.analyze(new TestParsedLine("show events/jdk.ExecutionSample[start"));
      assertEquals(CompletionContextType.FILTER_FIELD, ctx.type());
      assertEquals("start", ctx.partialInput());
    }

    @Test
    void detectsFilterOperatorContext_afterField() {
      CompletionContext ctx =
          analyzer.analyze(new TestParsedLine("show events/jdk.ExecutionSample[startTime "));
      assertEquals(CompletionContextType.FILTER_OPERATOR, ctx.type());
    }

    @Test
    void detectsFilterLogicalContext_afterCondition() {
      CompletionContext ctx =
          analyzer.analyze(new TestParsedLine("show events/jdk.ExecutionSample[startTime > 0 "));
      assertEquals(CompletionContextType.FILTER_LOGICAL, ctx.type());
    }

    @Test
    void detectsFilterFieldContext_afterLogicalOperator() {
      CompletionContext ctx =
          analyzer.analyze(new TestParsedLine("show events/jdk.ExecutionSample[startTime > 0 && "));
      assertEquals(CompletionContextType.FILTER_FIELD, ctx.type());
    }

    @Test
    void detectsFilterFieldContext_afterOrOperator() {
      CompletionContext ctx =
          analyzer.analyze(new TestParsedLine("show events/jdk.ExecutionSample[startTime > 0 || "));
      assertEquals(CompletionContextType.FILTER_FIELD, ctx.type());
    }
  }

  @Nested
  class PipelineContext {
    @Test
    void detectsPipelineContext_afterPipeWithSpace() {
      CompletionContext ctx =
          analyzer.analyze(new TestParsedLine("show events/jdk.ExecutionSample | "));
      assertEquals(CompletionContextType.PIPELINE_OPERATOR, ctx.type());
      assertEquals("jdk.ExecutionSample", ctx.eventType());
    }

    @Test
    void detectsPipelineContext_partialOperator() {
      CompletionContext ctx =
          analyzer.analyze(new TestParsedLine("show events/jdk.ExecutionSample | grou"));
      assertEquals(CompletionContextType.PIPELINE_OPERATOR, ctx.type());
      assertEquals("grou", ctx.partialInput());
    }

    @Test
    void detectsPipelineContext_pipeAttachedToWord() {
      CompletionContext ctx =
          analyzer.analyze(new TestParsedLine("show events/jdk.ExecutionSample|"));
      assertEquals(CompletionContextType.PIPELINE_OPERATOR, ctx.type());
    }

    @Test
    void detectsPipelineContext_pipeAtStartOfWord() {
      CompletionContext ctx =
          analyzer.analyze(new TestParsedLine("show events/jdk.ExecutionSample |group"));
      assertEquals(CompletionContextType.PIPELINE_OPERATOR, ctx.type());
    }
  }

  @Nested
  class FunctionParamContext {
    @Test
    void detectsFunctionParamContext_sumOpen() {
      CompletionContext ctx =
          analyzer.analyze(new TestParsedLine("show events/jdk.ExecutionSample | sum("));
      assertEquals(CompletionContextType.FUNCTION_PARAM, ctx.type());
      assertEquals("sum", ctx.functionName());
      assertEquals(0, ctx.parameterIndex());
    }

    @Test
    void detectsFunctionParamContext_groupByOpen() {
      CompletionContext ctx =
          analyzer.analyze(new TestParsedLine("show events/jdk.ExecutionSample | groupBy("));
      assertEquals(CompletionContextType.FUNCTION_PARAM, ctx.type());
      assertEquals("groupBy", ctx.functionName());
    }

    @Test
    void detectsFunctionParamContext_selectOpen() {
      // select() gets special SELECT_EXPRESSION context for richer completion
      CompletionContext ctx =
          analyzer.analyze(new TestParsedLine("show events/jdk.ExecutionSample | select("));
      assertEquals(CompletionContextType.SELECT_EXPRESSION, ctx.type());
      assertEquals("select", ctx.functionName());
    }

    @Test
    void detectsFunctionParamContext_selectAfterComma() {
      // select() gets special SELECT_EXPRESSION context for richer completion
      CompletionContext ctx =
          analyzer.analyze(
              new TestParsedLine("show events/jdk.ExecutionSample | select(startTime, "));
      assertEquals(CompletionContextType.SELECT_EXPRESSION, ctx.type());
      assertEquals("select", ctx.functionName());
      assertEquals(1, ctx.parameterIndex());
    }

    @Test
    void detectsFunctionParamContext_topSecondParam() {
      CompletionContext ctx =
          analyzer.analyze(new TestParsedLine("show events/jdk.ExecutionSample | top(10, "));
      assertEquals(CompletionContextType.FUNCTION_PARAM, ctx.type());
      assertEquals("top", ctx.functionName());
      assertEquals(1, ctx.parameterIndex());
    }

    @Test
    void doesNotCompleteFunctionParam_topFirstParam() {
      // First param of top() is a number, should not suggest fields
      CompletionContext ctx =
          analyzer.analyze(new TestParsedLine("show events/jdk.ExecutionSample | top("));
      assertEquals(CompletionContextType.UNKNOWN, ctx.type());
    }

    @Test
    void detectsFunctionParamContext_noSpacesAttached() {
      // Test case: everything attached without spaces
      CompletionContext ctx =
          analyzer.analyze(new TestParsedLine("show events/jdk.ExecutionSample|sum("));
      assertEquals(CompletionContextType.FUNCTION_PARAM, ctx.type());
      assertEquals("sum", ctx.functionName());
    }

    @Test
    void detectsFunctionParamContext_partialFieldName() {
      // User typing: groupBy(thr
      CompletionContext ctx =
          analyzer.analyze(new TestParsedLine("show events/jdk.ExecutionSample | groupBy(thr"));
      assertEquals(CompletionContextType.FUNCTION_PARAM, ctx.type());
      assertEquals("groupBy", ctx.functionName());
      assertEquals("thr", ctx.partialInput()); // KEY: partial field name
      assertEquals("jdk.ExecutionSample", ctx.eventType()); // KEY: event type extracted
    }

    @Test
    void detectsFunctionParamContext_partialAfterComma() {
      // User typing: select(startTime, dur
      // select() gets special SELECT_EXPRESSION context for richer completion
      CompletionContext ctx =
          analyzer.analyze(
              new TestParsedLine("show events/jdk.ExecutionSample | select(startTime, dur"));
      assertEquals(CompletionContextType.SELECT_EXPRESSION, ctx.type());
      assertEquals("select", ctx.functionName());
      assertEquals(1, ctx.parameterIndex());
      assertEquals("dur", ctx.partialInput());
    }

    @Test
    void detectsFunctionParamContext_nestedFieldPath() {
      // User typing: groupBy(sampledThread/java
      CompletionContext ctx =
          analyzer.analyze(
              new TestParsedLine("show events/jdk.ExecutionSample | groupBy(sampledThread/java"));
      assertEquals(CompletionContextType.FUNCTION_PARAM, ctx.type());
      assertEquals("sampledThread/java", ctx.partialInput());
    }

    @Test
    void detectsFunctionParamContext_emptyPartial() {
      // Just opened paren - partial should be empty
      CompletionContext ctx =
          analyzer.analyze(new TestParsedLine("show events/jdk.ExecutionSample | groupBy("));
      assertEquals(CompletionContextType.FUNCTION_PARAM, ctx.type());
      assertEquals("", ctx.partialInput());
      assertEquals("jdk.ExecutionSample", ctx.eventType());
    }

    @Test
    void detectsFunctionParamContext_withFilterBeforePipe() {
      // User typing with filter: show events/Type[filter] | groupBy(lo
      CompletionContext ctx =
          analyzer.analyze(
              new TestParsedLine(
                  "show events/datadog.ExecutionSample[eventThread/javaThreadId == 59] | groupBy(lo"));
      assertEquals(CompletionContextType.FUNCTION_PARAM, ctx.type());
      assertEquals("groupBy", ctx.functionName());
      assertEquals("lo", ctx.partialInput());
      // BUG: This currently fails - eventType extraction doesn't skip over filter brackets
      assertEquals("datadog.ExecutionSample", ctx.eventType());
    }
  }

  @Nested
  class HelperMethods {
    @Test
    void findFilterPosition_simple() {
      var pos = analyzer.findFilterPosition("events/Type[field", 17);
      assertNotNull(pos);
      assertEquals(11, pos.openBracket());
      assertEquals("field", pos.content());
    }

    @Test
    void findFilterPosition_withCondition() {
      var pos = analyzer.findFilterPosition("events/Type[field > 0", 21);
      assertNotNull(pos);
      assertEquals("field > 0", pos.content());
    }

    @Test
    void findFilterPosition_outsideFilter() {
      var pos = analyzer.findFilterPosition("events/Type[field > 0]", 22);
      assertNull(pos);
    }

    @Test
    void findFunctionPosition_sum() {
      var pos = analyzer.findFunctionPosition("show events/Type | sum(field", 28);
      assertNotNull(pos);
      assertEquals("sum", pos.functionName());
      assertEquals("field", pos.parameters());
    }

    @Test
    void findFunctionPosition_groupBy() {
      var pos = analyzer.findFunctionPosition("show events/Type | groupBy(", 27);
      assertNotNull(pos);
      assertEquals("groupBy", pos.functionName());
    }

    @Test
    void isAfterPipe_withSpace() {
      assertTrue(analyzer.isAfterPipe(new TestParsedLine("show events/Type | ")));
    }

    @Test
    void isAfterPipe_attached() {
      assertTrue(analyzer.isAfterPipe(new TestParsedLine("show events/Type|")));
    }

    @Test
    void isAfterPipe_partialFunction() {
      assertTrue(analyzer.isAfterPipe(new TestParsedLine("show events/Type | grou")));
    }

    @Test
    void extractEventTypeFromLine_simple() {
      String eventType =
          analyzer.extractEventTypeFromLine("show events/jdk.ExecutionSample | sum(");
      assertEquals("jdk.ExecutionSample", eventType);
    }

    @Test
    void extractEventTypeFromLine_withFilter() {
      String eventType =
          analyzer.extractEventTypeFromLine("show events/jdk.ExecutionSample[startTime > 0]");
      assertEquals("jdk.ExecutionSample", eventType);
    }

    @Test
    void hasOperator_equals() {
      assertTrue(analyzer.hasOperator("field == value"));
    }

    @Test
    void hasOperator_notEquals() {
      assertTrue(analyzer.hasOperator("field != value"));
    }

    @Test
    void hasOperator_contains() {
      assertTrue(analyzer.hasOperator("field contains value"));
    }

    @Test
    void hasOperator_noOperator() {
      assertFalse(analyzer.hasOperator("field"));
    }

    @Test
    void countCommas_none() {
      assertEquals(0, analyzer.countCommas("field"));
    }

    @Test
    void countCommas_one() {
      assertEquals(1, analyzer.countCommas("field1, field2"));
    }

    @Test
    void countCommas_multiple() {
      assertEquals(2, analyzer.countCommas("field1, field2, field3"));
    }
  }

  @Nested
  class SetCommandContext {
    @Test
    void detectsRootContext_afterEquals() {
      CompletionContext ctx = analyzer.analyze(new TestParsedLine("set var = "));
      assertEquals(CompletionContextType.ROOT, ctx.type());
      assertEquals("set", ctx.command());
    }

    @Test
    void detectsRootContext_partialRoot() {
      CompletionContext ctx = analyzer.analyze(new TestParsedLine("set var = eve"));
      assertEquals(CompletionContextType.ROOT, ctx.type());
      assertEquals("eve", ctx.partialInput());
    }

    @Test
    void detectsEventTypeContext_afterEventsSlash() {
      CompletionContext ctx = analyzer.analyze(new TestParsedLine("set var = events/"));
      assertEquals(CompletionContextType.EVENT_TYPE, ctx.type());
      assertEquals("events", ctx.rootType());
      assertEquals("set", ctx.command());
    }

    @Test
    void detectsEventTypeContext_partialEventType() {
      CompletionContext ctx = analyzer.analyze(new TestParsedLine("set var = events/jdk.Exec"));
      assertEquals(CompletionContextType.EVENT_TYPE, ctx.type());
      assertEquals("jdk.Exec", ctx.partialInput());
    }

    @Test
    void detectsFieldPathContext_afterEventType() {
      CompletionContext ctx =
          analyzer.analyze(new TestParsedLine("set var = events/jdk.ExecutionSample/"));
      assertEquals(CompletionContextType.FIELD_PATH, ctx.type());
      assertEquals("jdk.ExecutionSample", ctx.eventType());
      assertEquals("set", ctx.command());
    }

    @Test
    void detectsFieldPathContext_partialField() {
      CompletionContext ctx =
          analyzer.analyze(new TestParsedLine("set var = events/jdk.ExecutionSample/sampled"));
      assertEquals(CompletionContextType.FIELD_PATH, ctx.type());
      assertEquals("sampled", ctx.partialInput());
    }

    @Test
    void detectsEventTypeContext_metadata() {
      CompletionContext ctx = analyzer.analyze(new TestParsedLine("set var = metadata/"));
      assertEquals(CompletionContextType.EVENT_TYPE, ctx.type());
      assertEquals("metadata", ctx.rootType());
    }

    @Test
    void detectsEventTypeContext_cp() {
      CompletionContext ctx = analyzer.analyze(new TestParsedLine("set var = cp/"));
      assertEquals(CompletionContextType.EVENT_TYPE, ctx.type());
      assertEquals("cp", ctx.rootType());
    }

    @Test
    void detectsChunkIdContext_chunks() {
      CompletionContext ctx = analyzer.analyze(new TestParsedLine("set var = chunks/"));
      assertEquals(CompletionContextType.CHUNK_ID, ctx.type());
      assertEquals("chunks", ctx.rootType());
    }

    @Test
    void worksWithGlobalFlag() {
      CompletionContext ctx = analyzer.analyze(new TestParsedLine("set --global var = events/"));
      assertEquals(CompletionContextType.EVENT_TYPE, ctx.type());
      assertEquals("events", ctx.rootType());
    }

    @Test
    void worksWithLetAlias() {
      CompletionContext ctx = analyzer.analyze(new TestParsedLine("let var = events/jdk.Exec"));
      assertEquals(CompletionContextType.EVENT_TYPE, ctx.type());
      assertEquals("let", ctx.command());
    }

    @Test
    void detectsUnknownContext_mapLiteral() {
      CompletionContext ctx = analyzer.analyze(new TestParsedLine("set var = {"));
      assertEquals(CompletionContextType.UNKNOWN, ctx.type());
    }

    @Test
    void detectsUnknownContext_mergeFunction() {
      CompletionContext ctx = analyzer.analyze(new TestParsedLine("set var = merge("));
      assertEquals(CompletionContextType.UNKNOWN, ctx.type());
    }

    @Test
    void noCompletionBeforeEquals() {
      CompletionContext ctx = analyzer.analyze(new TestParsedLine("set var"));
      assertEquals(CompletionContextType.COMMAND_OPTION, ctx.type());
    }

    @Test
    void detectsOptionContext_globalFlag() {
      CompletionContext ctx = analyzer.analyze(new TestParsedLine("set --"));
      assertEquals(CompletionContextType.COMMAND_OPTION, ctx.type());
      assertEquals("--", ctx.partialInput());
    }

    @Test
    void detectsPipelineContext_afterPipe() {
      CompletionContext ctx =
          analyzer.analyze(new TestParsedLine("set var = events/jdk.ExecutionSample | "));
      assertEquals(CompletionContextType.PIPELINE_OPERATOR, ctx.type());
    }

    @Test
    void detectsFilterFieldContext_insideBrackets() {
      CompletionContext ctx =
          analyzer.analyze(new TestParsedLine("set var = events/jdk.ExecutionSample["));
      assertEquals(CompletionContextType.FILTER_FIELD, ctx.type());
    }

    @Test
    void detectsFunctionParamContext_setCommand() {
      CompletionContext ctx =
          analyzer.analyze(
              new TestParsedLine("set var = events/jdk.ExecutionSample | groupBy(thr"));
      assertEquals(CompletionContextType.FUNCTION_PARAM, ctx.type());
      assertEquals("thr", ctx.partialInput());
      assertEquals("jdk.ExecutionSample", ctx.eventType());
      assertEquals("set", ctx.command()); // Should preserve "set" not "show"
    }
  }

  @Nested
  class EdgeCases {
    @Test
    void handlesEmptyInput() {
      CompletionContext ctx = analyzer.analyze(new TestParsedLine(""));
      assertNotNull(ctx);
      assertEquals(CompletionContextType.COMMAND, ctx.type());
    }

    @Test
    void handlesSingleSpace() {
      // This is treated as empty command + empty word
      CompletionContext ctx = analyzer.analyze(new TestParsedLine(" "));
      assertNotNull(ctx);
    }

    @Test
    void prioritizesFilterOverOptions() {
      // Inside filter, should not suggest options
      CompletionContext ctx = analyzer.analyze(new TestParsedLine("show events/jdk.Test[--"));
      assertEquals(CompletionContextType.FILTER_FIELD, ctx.type());
    }

    @Test
    void prioritizesFunctionOverPipeline() {
      // Inside function, should complete function params not pipeline
      CompletionContext ctx =
          analyzer.analyze(new TestParsedLine("show events/jdk.Test | groupBy("));
      assertEquals(CompletionContextType.FUNCTION_PARAM, ctx.type());
    }
  }

  /** Tests for new context types added in Phase 2 */
  @Nested
  class NewContextTypes {

    @Test
    void detectsMultiEventTypeContext_afterOpenParen() {
      // events/(Type1| - inside multi-event syntax
      CompletionContext ctx =
          analyzer.analyze(new TestParsedLine("show events/(jdk.ExecutionSample|"));
      assertEquals(CompletionContextType.MULTI_EVENT_TYPE, ctx.type());
      assertEquals("events", ctx.rootType());
    }

    @Test
    void detectsMultiEventTypeContext_afterPipe() {
      // events/(Type1|Type - typing second type
      // The partial is the full event type name being typed (including package prefix)
      CompletionContext ctx =
          analyzer.analyze(new TestParsedLine("show events/(jdk.ExecutionSample|jdk.T"));
      assertEquals(CompletionContextType.MULTI_EVENT_TYPE, ctx.type());
      assertEquals("jdk.T", ctx.partialInput());
    }

    @Test
    void detectsMultiEventTypeContext_empty() {
      // events/( - just opened multi-event
      CompletionContext ctx = analyzer.analyze(new TestParsedLine("show events/("));
      assertEquals(CompletionContextType.MULTI_EVENT_TYPE, ctx.type());
    }

    @Test
    void detectsFilterFunctionArg_containsFirstParam() {
      // [contains( - inside filter function
      CompletionContext ctx =
          analyzer.analyze(new TestParsedLine("show events/jdk.Test[contains("));
      assertEquals(CompletionContextType.FILTER_FUNCTION_ARG, ctx.type());
      assertEquals("contains", ctx.functionName());
      assertEquals(0, ctx.parameterIndex());
    }

    @Test
    void detectsFilterFunctionArg_containsSecondParam() {
      // [contains(field, - waiting for second param
      CompletionContext ctx =
          analyzer.analyze(new TestParsedLine("show events/jdk.Test[contains(path, "));
      assertEquals(CompletionContextType.FILTER_FUNCTION_ARG, ctx.type());
      assertEquals("contains", ctx.functionName());
      assertEquals(1, ctx.parameterIndex());
    }

    @Test
    void detectsFilterFunctionArg_between() {
      // [between(field, - between has 3 params
      CompletionContext ctx =
          analyzer.analyze(new TestParsedLine("show events/jdk.Test[between(bytes, "));
      assertEquals(CompletionContextType.FILTER_FUNCTION_ARG, ctx.type());
      assertEquals("between", ctx.functionName());
      assertEquals(1, ctx.parameterIndex());
    }

    @Test
    void detectsDecoratorFieldContext() {
      // select($decorator. - accessing decorator fields
      CompletionContext ctx =
          analyzer.analyze(new TestParsedLine("show events/jdk.Test | select($decorator."));
      assertEquals(CompletionContextType.DECORATOR_FIELD, ctx.type());
      assertEquals("", ctx.partialInput()); // After the dot, no partial yet
    }

    @Test
    void detectsDecoratorFieldContext_withPartial() {
      // select($decorator.field - typing decorator field name
      CompletionContext ctx =
          analyzer.analyze(new TestParsedLine("show events/jdk.Test | select($decorator.name"));
      assertEquals(CompletionContextType.DECORATOR_FIELD, ctx.type());
      assertEquals("name", ctx.partialInput());
    }

    @Test
    void detectsSelectExpressionContext() {
      // select() uses SELECT_EXPRESSION for richer completion
      CompletionContext ctx =
          analyzer.analyze(new TestParsedLine("show events/jdk.Test | select("));
      assertEquals(CompletionContextType.SELECT_EXPRESSION, ctx.type());
      assertEquals("select", ctx.functionName());
    }

    @Test
    void detectsSelectExpressionContext_withPartial() {
      // select(st - typing expression
      CompletionContext ctx =
          analyzer.analyze(new TestParsedLine("show events/jdk.Test | select(st"));
      assertEquals(CompletionContextType.SELECT_EXPRESSION, ctx.type());
      assertEquals("st", ctx.partialInput());
    }

    @Test
    void detectsVariableReferenceContext() {
      // ${ - inside variable reference
      CompletionContext ctx = analyzer.analyze(new TestParsedLine("show ${"));
      assertEquals(CompletionContextType.VARIABLE_REFERENCE, ctx.type());
    }

    @Test
    void detectsVariableReferenceContext_withPartial() {
      // ${var - typing variable name
      CompletionContext ctx = analyzer.analyze(new TestParsedLine("show ${var"));
      assertEquals(CompletionContextType.VARIABLE_REFERENCE, ctx.type());
      assertEquals("var", ctx.partialInput());
    }
  }
}
