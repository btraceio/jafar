package io.jafar.shell.cli.completion;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.shell.cli.completion.FunctionSpec.FunctionCategory;
import io.jafar.shell.cli.completion.ParamSpec.ParamType;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for the FunctionRegistry to ensure all JFR query functions are properly defined. */
class FunctionRegistryTest {

  @Nested
  class PipelineOperators {

    @Test
    void allPipelineOperatorsRegistered() {
      // Expected pipeline operators from JfrPathParser
      List<String> expectedOperators =
          List.of(
              "count",
              "sum",
              "stats",
              "quantiles",
              "sketch",
              "groupBy",
              "top",
              "select",
              "toMap",
              "timerange",
              "len",
              "uppercase",
              "lowercase",
              "trim",
              "abs",
              "round",
              "floor",
              "ceil",
              "contains",
              "replace",
              "decorateByTime",
              "decorateByKey");

      for (String op : expectedOperators) {
        FunctionSpec spec = FunctionRegistry.getPipelineOperator(op);
        assertNotNull(spec, "Missing pipeline operator: " + op);
        assertTrue(spec.isPipeline(), op + " should be a pipeline operator");
      }
    }

    @Test
    void countHasNoParameters() {
      FunctionSpec count = FunctionRegistry.getPipelineOperator("count");
      assertNotNull(count);
      assertTrue(count.positionalParams().isEmpty());
      assertTrue(count.keywordParams().isEmpty());
    }

    @Test
    void sumHasOptionalFieldPath() {
      FunctionSpec sum = FunctionRegistry.getPipelineOperator("sum");
      assertNotNull(sum);

      ParamSpec param = sum.getPositionalParam(0);
      assertNotNull(param);
      assertEquals(ParamType.FIELD_PATH, param.type());
      assertFalse(param.required());
    }

    @Test
    void groupByHasCorrectParameters() {
      FunctionSpec groupBy = FunctionRegistry.getPipelineOperator("groupBy");
      assertNotNull(groupBy);

      // First param: required field path (key)
      ParamSpec keyParam = groupBy.getPositionalParam(0);
      assertNotNull(keyParam);
      assertEquals(ParamType.FIELD_PATH, keyParam.type());
      assertTrue(keyParam.required());

      // Keyword params: agg (enum), value (field path)
      ParamSpec aggParam = groupBy.getKeywordParam("agg");
      assertNotNull(aggParam);
      assertEquals(ParamType.ENUM, aggParam.type());
      assertTrue(aggParam.enumValues().contains("count"));
      assertTrue(aggParam.enumValues().contains("sum"));
      assertTrue(aggParam.enumValues().contains("avg"));
      assertTrue(aggParam.enumValues().contains("min"));
      assertTrue(aggParam.enumValues().contains("max"));

      ParamSpec valueParam = groupBy.getKeywordParam("value");
      assertNotNull(valueParam);
      assertEquals(ParamType.FIELD_PATH, valueParam.type());
    }

    @Test
    void topHasCorrectParameters() {
      FunctionSpec top = FunctionRegistry.getPipelineOperator("top");
      assertNotNull(top);

      // First param: required number (count)
      ParamSpec countParam = top.getPositionalParam(0);
      assertNotNull(countParam);
      assertEquals(ParamType.NUMBER, countParam.type());
      assertTrue(countParam.required());

      // Keyword params: by (field path), asc (boolean)
      ParamSpec byParam = top.getKeywordParam("by");
      assertNotNull(byParam);
      assertEquals(ParamType.FIELD_PATH, byParam.type());

      ParamSpec ascParam = top.getKeywordParam("asc");
      assertNotNull(ascParam);
      assertEquals(ParamType.BOOLEAN, ascParam.type());
    }

    @Test
    void decorateByTimeHasCorrectParameters() {
      FunctionSpec decorator = FunctionRegistry.getPipelineOperator("decorateByTime");
      assertNotNull(decorator);

      // First param: event type
      ParamSpec eventTypeParam = decorator.getPositionalParam(0);
      assertNotNull(eventTypeParam);
      assertEquals(ParamType.EVENT_TYPE, eventTypeParam.type());

      // Keywords: fields (multi), threadPath, decoratorThreadPath
      assertTrue(decorator.hasKeyword("fields"));
      assertTrue(decorator.hasKeyword("threadPath"));
      assertTrue(decorator.hasKeyword("decoratorThreadPath"));

      ParamSpec fieldsParam = decorator.getKeywordParam("fields");
      assertTrue(fieldsParam.multi());
    }

    @Test
    void decorateByKeyHasCorrectParameters() {
      FunctionSpec decorator = FunctionRegistry.getPipelineOperator("decorateByKey");
      assertNotNull(decorator);

      // First param: event type
      ParamSpec eventTypeParam = decorator.getPositionalParam(0);
      assertNotNull(eventTypeParam);
      assertEquals(ParamType.EVENT_TYPE, eventTypeParam.type());

      // Required keywords: key, decoratorKey
      assertTrue(decorator.hasKeyword("key"));
      assertTrue(decorator.hasKeyword("decoratorKey"));
      assertTrue(decorator.hasKeyword("fields"));

      ParamSpec keyParam = decorator.getKeywordParam("key");
      assertTrue(keyParam.required());

      ParamSpec decoratorKeyParam = decorator.getKeywordParam("decoratorKey");
      assertTrue(decoratorKeyParam.required());
    }

    @Test
    void timerangeHasCorrectParameters() {
      FunctionSpec timerange = FunctionRegistry.getPipelineOperator("timerange");
      assertNotNull(timerange);

      // Optional positional: time field
      ParamSpec timeParam = timerange.getPositionalParam(0);
      assertNotNull(timeParam);
      assertEquals(ParamType.FIELD_PATH, timeParam.type());
      assertFalse(timeParam.required());

      // Keywords: duration, format
      assertTrue(timerange.hasKeyword("duration"));
      assertTrue(timerange.hasKeyword("format"));

      ParamSpec formatParam = timerange.getKeywordParam("format");
      assertEquals(ParamType.STRING, formatParam.type());
    }

    @Test
    void quantilesHasVarargs() {
      FunctionSpec quantiles = FunctionRegistry.getPipelineOperator("quantiles");
      assertNotNull(quantiles);
      assertTrue(quantiles.hasVarargs());

      // First positional is number (quantile values)
      ParamSpec quantileParam = quantiles.getPositionalParam(0);
      assertNotNull(quantileParam);
      assertEquals(ParamType.NUMBER, quantileParam.type());

      // Optional keyword: path
      assertTrue(quantiles.hasKeyword("path"));
    }

    @Test
    void selectHasVarargs() {
      FunctionSpec select = FunctionRegistry.getPipelineOperator("select");
      assertNotNull(select);
      assertTrue(select.hasVarargs());
    }
  }

  @Nested
  class FilterFunctions {

    @Test
    void allFilterFunctionsRegistered() {
      List<String> expectedFunctions =
          List.of(
              "contains", "exists", "empty", "between", "len", "matches", "startsWith", "endsWith");

      for (String func : expectedFunctions) {
        FunctionSpec spec = FunctionRegistry.getFilterFunction(func);
        assertNotNull(spec, "Missing filter function: " + func);
        assertTrue(spec.isFilter(), func + " should be a filter function");
      }
    }

    @Test
    void containsHasCorrectParameters() {
      FunctionSpec contains = FunctionRegistry.getFilterFunction("contains");
      assertNotNull(contains);

      assertEquals(2, contains.positionalParams().size());

      ParamSpec fieldParam = contains.getPositionalParam(0);
      assertEquals(ParamType.FIELD_PATH, fieldParam.type());

      ParamSpec substringParam = contains.getPositionalParam(1);
      assertEquals(ParamType.STRING, substringParam.type());
    }

    @Test
    void betweenHasThreeParameters() {
      FunctionSpec between = FunctionRegistry.getFilterFunction("between");
      assertNotNull(between);

      assertEquals(3, between.positionalParams().size());

      ParamSpec fieldParam = between.getPositionalParam(0);
      assertEquals(ParamType.FIELD_PATH, fieldParam.type());

      ParamSpec minParam = between.getPositionalParam(1);
      assertEquals(ParamType.NUMBER, minParam.type());

      ParamSpec maxParam = between.getPositionalParam(2);
      assertEquals(ParamType.NUMBER, maxParam.type());
    }

    @Test
    void existsHasOneParameter() {
      FunctionSpec exists = FunctionRegistry.getFilterFunction("exists");
      assertNotNull(exists);

      assertEquals(1, exists.positionalParams().size());

      ParamSpec fieldParam = exists.getPositionalParam(0);
      assertEquals(ParamType.FIELD_PATH, fieldParam.type());
    }

    @Test
    void matchesHasOptionalFlags() {
      FunctionSpec matches = FunctionRegistry.getFilterFunction("matches");
      assertNotNull(matches);

      // Two required, one optional
      ParamSpec flagsParam = matches.getPositionalParam(2);
      assertNotNull(flagsParam);
      assertFalse(flagsParam.required());
      assertEquals(ParamType.STRING, flagsParam.type());
    }
  }

  @Nested
  class SelectFunctions {

    @Test
    void allSelectFunctionsRegistered() {
      List<String> expectedFunctions =
          List.of("if", "upper", "lower", "substring", "length", "coalesce");

      for (String func : expectedFunctions) {
        FunctionSpec spec = FunctionRegistry.getSelectFunction(func);
        assertNotNull(spec, "Missing select function: " + func);
        assertTrue(spec.isSelect(), func + " should be a select function");
      }
    }

    @Test
    void ifHasThreeParameters() {
      FunctionSpec ifFunc = FunctionRegistry.getSelectFunction("if");
      assertNotNull(ifFunc);

      assertEquals(3, ifFunc.positionalParams().size());

      for (int i = 0; i < 3; i++) {
        ParamSpec param = ifFunc.getPositionalParam(i);
        assertEquals(ParamType.EXPRESSION, param.type());
      }
    }

    @Test
    void substringHasOptionalLength() {
      FunctionSpec substring = FunctionRegistry.getSelectFunction("substring");
      assertNotNull(substring);

      ParamSpec lengthParam = substring.getPositionalParam(2);
      assertNotNull(lengthParam);
      assertFalse(lengthParam.required());
      assertEquals(ParamType.NUMBER, lengthParam.type());
    }

    @Test
    void coalesceHasVarargs() {
      FunctionSpec coalesce = FunctionRegistry.getSelectFunction("coalesce");
      assertNotNull(coalesce);
      assertTrue(coalesce.hasVarargs());
    }
  }

  @Nested
  class RegistryOperations {

    @Test
    void getPipelineOperatorsReturnsOnlyPipeline() {
      Collection<FunctionSpec> operators = FunctionRegistry.getPipelineOperators();
      assertFalse(operators.isEmpty());

      for (FunctionSpec spec : operators) {
        assertEquals(FunctionCategory.PIPELINE, spec.category());
      }
    }

    @Test
    void getFilterFunctionsReturnsOnlyFilter() {
      Collection<FunctionSpec> functions = FunctionRegistry.getFilterFunctions();
      assertFalse(functions.isEmpty());

      for (FunctionSpec spec : functions) {
        assertEquals(FunctionCategory.FILTER, spec.category());
      }
    }

    @Test
    void getSelectFunctionsReturnsOnlySelect() {
      Collection<FunctionSpec> functions = FunctionRegistry.getSelectFunctions();
      assertFalse(functions.isEmpty());

      for (FunctionSpec spec : functions) {
        assertEquals(FunctionCategory.SELECT, spec.category());
      }
    }

    @Test
    void existsReturnsTrueForKnownFunctions() {
      assertTrue(FunctionRegistry.exists("count"));
      assertTrue(FunctionRegistry.exists("groupBy"));
      assertTrue(FunctionRegistry.exists("contains"));
      assertTrue(FunctionRegistry.exists("if"));
    }

    @Test
    void existsReturnsFalseForUnknownFunctions() {
      assertFalse(FunctionRegistry.exists("unknown"));
      assertFalse(FunctionRegistry.exists("notAFunction"));
    }

    @Test
    void functionLookupIsCaseInsensitive() {
      assertNotNull(FunctionRegistry.getPipelineOperator("GROUPBY"));
      assertNotNull(FunctionRegistry.getPipelineOperator("GroupBy"));
      assertNotNull(FunctionRegistry.getPipelineOperator("groupby"));
    }

    @Test
    void allFunctionsHaveDescriptions() {
      for (FunctionSpec spec : FunctionRegistry.getAllFunctions()) {
        assertNotNull(spec.description(), spec.name() + " should have a description");
        assertFalse(spec.description().isEmpty(), spec.name() + " description should not be empty");
      }
    }

    @Test
    void allFunctionsHaveTemplates() {
      for (FunctionSpec spec : FunctionRegistry.getAllFunctions()) {
        assertNotNull(spec.template(), spec.name() + " should have a template");
        assertTrue(
            spec.template().contains(spec.name()),
            spec.name() + " template should contain function name");
      }
    }
  }

  @Nested
  class ParamSpecTests {

    @Test
    void positionalParamIsPositional() {
      ParamSpec param = ParamSpec.positional(0, ParamType.FIELD_PATH, "test");
      assertTrue(param.isPositional());
      assertFalse(param.isKeyword());
    }

    @Test
    void keywordParamIsKeyword() {
      ParamSpec param = ParamSpec.keyword("name", ParamType.STRING, "test");
      assertTrue(param.isKeyword());
      assertFalse(param.isPositional());
      assertEquals("name=", param.keywordPrefix());
    }

    @Test
    void enumParamHasValues() {
      ParamSpec param = ParamSpec.enumKeyword("type", List.of("a", "b", "c"), false, "test");
      assertEquals(ParamType.ENUM, param.type());
      assertEquals(3, param.enumValues().size());
      assertTrue(param.enumValues().contains("a"));
    }

    @Test
    void enumParamRequiresValues() {
      assertThrows(
          IllegalArgumentException.class,
          () -> new ParamSpec(0, null, ParamType.ENUM, List.of(), true, false, "test"));
    }
  }
}
