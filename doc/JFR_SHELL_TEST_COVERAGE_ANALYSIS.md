# JFR Shell Test Coverage Analysis

## Summary

| Metric | Count |
|--------|-------|
| Test Classes | 60 |
| Unit Tests (@Test) | 419 |
| Property-Based Tests (@Property) | 31 |
| **Total Tests** | **450** |

---

## Test Categories and Coverage Assessment

### Legend
- ✅ **Well Covered** - Comprehensive tests exist
- ⚠️ **Partially Covered** - Some tests exist, gaps remain
- ❌ **Not Covered** - No tests found
- 🔄 **Property-Based** - Covered by jqwik fuzzing

---

## 1. Session Management

| Feature | Status | Test File(s) |
|---------|--------|--------------|
| Open/close sessions | ✅ | `SessionManagerTest.java` |
| Session aliases | ✅ | `SessionManagerTest.java` |
| Duplicate alias rejection | ✅ | `SessionManagerTest.java` |
| Switch sessions (`use`) | ✅ | `SessionManagerTest.java` |
| List sessions | ⚠️ | Basic test only |
| Session info | ❌ | Missing |
| Close all sessions | ❌ | Missing |
| Error cases (invalid file) | ❌ | Missing |

**Gap Analysis:** Missing tests for `info` command, `close --all`, and error scenarios.

---

## 2. JfrPath Query Language

### 2.1 Query Roots
| Feature | Status | Test File(s) |
|---------|--------|--------------|
| `events/` root | ✅ | `JfrPathEvaluatorTest.java`, `JfrPathParserTest.java` |
| `metadata/` root | ✅ | `JfrPathEvaluatorMetadataTest.java`, `ShowMetadataJfrPathTest.java` |
| `chunks/` root | ✅ | `JfrPathEvaluatorChunksTest.java` |
| `cp/` (constant pools) | ✅ | `ShowConstantPoolEntriesTest.java`, `ShellCompleterCpTest.java` |

### 2.2 Path Navigation
| Feature | Status | Test File(s) |
|---------|--------|--------------|
| Simple field access | ✅ | `JfrPathEvaluatorTest.java` |
| Nested field access | ✅ | `JfrPathEvaluatorTest.java` |
| Array element access | ✅ | `JfrPathEvaluatorProjectionTest.java` |
| Invalid field names | ⚠️ | Implicit in integration tests |

### 2.3 Filters
| Feature | Status | Test File(s) |
|---------|--------|--------------|
| Equality (`=`, `==`) | ✅ | `JfrPathParserTest.java` |
| Inequality (`!=`) | ✅ | `ConditionEvaluatorTest.java` |
| Comparison (`>`, `>=`, `<`, `<=`) | ✅ | `JfrPathEvaluatorTest.java`, `JfrPathParserTest.java` |
| Regex (`~`, `=~`) | ✅ | `JfrPathParserTest.java` |
| Boolean logic (`and`, `or`, `not`) | ✅ | `ConditionEvaluatorTest.java`, `ConditionalFlowTest.java` |
| Multiple chained filters | ✅ | `JfrPathParserTest.java` |
| Interleaved filters | ✅ | `JfrPathInterleavedFiltersTest.java` |

### 2.4 Filter Functions
| Feature | Status | Test File(s) |
|---------|--------|--------------|
| `len()` | ✅ | `JfrPathLenOpTest.java` |
| `exists()` | ✅ | `ConditionEvaluatorTest.java` |
| `starts_with()` | ⚠️ | Completion tests only |
| `contains()` | ✅ | `ConditionEvaluatorTest.java` |
| `matches()` | ⚠️ | Completion tests only |
| `between()` | ❌ | Missing |

### 2.5 List Matching
| Feature | Status | Test File(s) |
|---------|--------|--------------|
| `any:` quantifier | ✅ | `JfrPathEvaluatorListMatchTest.java` |
| `all:` quantifier | ✅ | `JfrPathEvaluatorListMatchTest.java` |
| `none:` quantifier | ✅ | `JfrPathEvaluatorListMatchTest.java` |

---

## 3. Aggregations

| Feature | Status | Test File(s) |
|---------|--------|--------------|
| `count()` | ✅ | `JfrPathEvaluatorTest.java`, `ShowAggregationsIntegrationTest.java` |
| `sum()` | ⚠️ | Completion tests, needs evaluator tests |
| `stats()` | ✅ | `JfrPathEvaluatorTest.java` |
| `quantiles()` | ✅ | `JfrPathEvaluatorTest.java` |
| `sketch()` | ❌ | Missing |
| `groupBy()` | ⚠️ | Parser tests, needs evaluator tests |
| `top()` | ⚠️ | Completion tests, needs evaluator tests |
| `toMap()` | ✅ | `JfrPathParserTest.java` |
| `merge()` | ✅ | `MapVariablesTest.java` |
| `select()` | ✅ | `JfrPathParserTest.java`, `JfrPathSelectOpTest.java` |
| Chained aggregations | ⚠️ | Parser level only |

---

## 4. Event Decoration

| Feature | Status | Test File(s) |
|---------|--------|--------------|
| `decorateByTime()` basic | ✅ | `JfrPathDecoratorTest.java` |
| Thread filtering | ✅ | `JfrPathDecoratorTest.java` |
| Edge cases (boundaries) | ✅ | `JfrPathDecoratorTest.java` |
| `decorateByKey()` | ✅ | `JfrPathDecoratorTest.java` |
| Missing key handling | ✅ | `JfrPathDecoratorTest.java` |
| `$decorator.` field access | ✅ | `JfrPathDecoratorTest.java` |
| Parse syntax | ✅ | `JfrPathDecoratorTest.java` |
| Decorator in filters | ❌ | Missing |
| Decorator in groupBy | ❌ | Missing |

---

## 5. Variables

| Feature | Status | Test File(s) |
|---------|--------|--------------|
| Scalar variables | ✅ | `MapVariablesTest.java` |
| Lazy query variables | ⚠️ | Implicit in scripting tests |
| Map variables | ✅ | `MapVariablesTest.java` (39 tests) |
| Nested field access | ✅ | `MapVariablesTest.java` |
| Array element access | ⚠️ | Minimal coverage |
| `.size` property | ✅ | `MapVariablesTest.java` |
| Variable scopes | ✅ | `MapVariablesTest.java` |
| `vars` command | ✅ | `MapVariablesTest.java` |
| `unset` command | ✅ | `MapVariablesTest.java` |
| `invalidate` command | ❌ | Missing |
| Variable copy | ✅ | `VariableCopyTest.java` |

---

## 6. Conditionals

| Feature | Status | Test File(s) |
|---------|--------|--------------|
| `if`/`endif` | ✅ | `ConditionalFlowTest.java` |
| `if`/`else`/`endif` | ✅ | `ConditionalFlowTest.java` |
| `elif` branches | ✅ | `ConditionalFlowTest.java` |
| Nested conditionals | ✅ | `ConditionalFlowTest.java` |
| Comparisons (`==`, `!=`, `>`, etc.) | ✅ | `ConditionEvaluatorTest.java` |
| Logical ops (`&&`, `||`, `!`) | ✅ | `ConditionEvaluatorTest.java` |
| Keyword ops (`and`, `or`, `not`) | ✅ | `ConditionEvaluatorTest.java` |
| `contains` operator | ✅ | `ConditionEvaluatorTest.java` |
| `exists()` function | ✅ | `ConditionEvaluatorTest.java` |
| `empty()` function | ❌ | Missing |
| Arithmetic in conditions | ❌ | Missing |
| Error handling | ✅ | `ConditionalFlowTest.java` |

---

## 7. Scripting

| Feature | Status | Test File(s) |
|---------|--------|--------------|
| Comments | ✅ | `ScriptRunnerTest.java` |
| Blank lines | ✅ | `ScriptRunnerTest.java` |
| Positional params (`$1`, `$2`) | ✅ | `ScriptRunnerTest.java` |
| `$@` expansion | ✅ | `ScriptRunnerTest.java` |
| Out-of-bounds params | ✅ | `ScriptRunnerTest.java` |
| Optional params (`${2:-default}`) | ✅ | `ScriptRunnerTest.java` |
| Required params (`${1:?error}`) | ✅ | `ScriptRunnerTest.java` |
| Continue-on-error | ✅ | `ScriptRunnerTest.java` |
| `echo` command | ✅ | `MapVariablesTest.java` |
| `script list` | ❌ | Missing |
| `script run` | ❌ | Missing |
| Stdin execution | ❌ | Missing |

---

## 8. Recording Commands

| Feature | Status | Test File(s) |
|---------|--------|--------------|
| `record start` | ✅ | `CommandRecorderTest.java` |
| `record stop` | ✅ | `CommandRecorderTest.java` |
| `record status` | ⚠️ | Basic coverage |
| Auto-save on exit | ❌ | Missing |

---

## 9. Tab Completion

| Feature | Status | Test File(s) |
|---------|--------|--------------|
| Command completion | ✅ | `ShellCompleterTest.java` |
| Root completion | ✅ | `ShellCompleterSelectRootsTest.java` |
| Event type completion | ✅ | `ShellCompleterTest.java` |
| Field path completion | ✅ | `ShellCompleterNestedFieldTest.java` |
| Filter field completion | ✅ | `ShellCompleterFilterCompletionTest.java` |
| Filter function completion | ✅ | `ShellCompleterFilterFunctionsTest.java` |
| Filter operator completion | ✅ | `ShellCompleterFilterOperatorsTest.java` |
| Pipeline operator completion | ✅ | `ShellCompleterAggregationsCompletionTest.java` |
| Function parameter completion | ✅ | `ShellCompleterFunctionParametersTest.java` |
| Metadata completion | ✅ | `ShellCompleterMetadataTest.java` |
| Chunks completion | ✅ | `ShellCompleterChunksTest.java` |
| CP completion | ✅ | `ShellCompleterCpTest.java` |
| Option completion | ⚠️ | Limited coverage |

---

## 10. Output Formats

| Feature | Status | Test File(s) |
|---------|--------|--------------|
| Table format | ⚠️ | Integration tests |
| JSON format | ❌ | Missing |
| Tree format | ✅ | `TreeRendererRecursiveTest.java`, `ShowMetadataTreeDepthTest.java` |
| CSV format | ✅ | `CsvRendererTest.java` |
| `--limit` option | ⚠️ | Implicit in integration tests |

---

## 11. Help System

| Feature | Status | Test File(s) |
|---------|--------|--------------|
| General help | ⚠️ | `HelpTypesTest.java` |
| Command-specific help | ⚠️ | `HelpSelectTest.java` |
| JfrPath help | ❌ | Missing |

---

## 12. Property-Based / Fuzzing Tests

| Feature | Status | Test File(s) |
|---------|--------|--------------|
| Completion never throws | ✅🔄 | `PropertyBasedCompletionTests.java` |
| Completion returns non-null | ✅🔄 | `PropertyBasedCompletionTests.java` |
| No duplicate candidates | ✅🔄 | `PropertyBasedCompletionTests.java` |
| Context type determinism | ✅🔄 | `PropertyBasedCompletionTests.java` |
| Filter completion | ✅🔄 | `PropertyBasedCompletionTests.java` |
| Pipeline completion | ✅🔄 | `PropertyBasedCompletionTests.java` |
| Edge cases (long paths, etc.) | ✅🔄 | `PropertyBasedCompletionTests.java` |
| Invalid expressions | ✅🔄 | `PropertyBasedCompletionTests.java` |

---

## Major Coverage Gaps

### High Priority (P0/P1)

1. **Non-Interactive Mode** - No tests for command-line execution
2. **`--format json`** - JSON output not tested
3. **`sketch()` aggregation** - Documented but not tested
4. **`between()` filter function** - Documented but not tested
5. **`empty()` conditional function** - Documented but not tested
6. **Arithmetic in conditions** - `${a} + ${b}` not tested
7. **Script management commands** - `script list`, `script run`

### Medium Priority (P2)

8. **`invalidate` command** - Cache invalidation not tested
9. **Multi-session queries** - Cross-session operations
10. **Real integration tests** - With actual JFR files for full workflows
11. **Error message quality** - Error formatting tests
12. **`$decorator` in aggregations** - Decorator fields in groupBy/top

### Low Priority (P3) - Fuzzing

13. **Query syntax fuzzing** - Malformed queries beyond completion
14. **Variable substitution fuzzing** - Edge cases in `${}`
15. **File path fuzzing** - Special characters, long paths
16. **Numeric overflow** - Large numbers in filters
17. **Memory stress** - Large result sets

---

## Strengths of Current Coverage

1. **Tab Completion** - Excellent coverage with 20+ test classes and property-based tests
2. **Conditionals** - Comprehensive coverage including keyword operators (`and`, `or`, `contains`)
3. **Map Variables** - 39 tests covering all documented features
4. **Script Parameters** - Optional/required parameter syntax well tested
5. **Event Decoration** - Good coverage of decorateByTime/decorateByKey
6. **List Matching** - `any:`, `all:`, `none:` quantifiers tested

---

## Recommendations

### Immediate Actions

1. Add integration tests for non-interactive mode
2. Add JSON output format tests
3. Test `sketch()` and `between()` functions
4. Add `empty()` function tests for conditionals
5. Test arithmetic expressions in conditions

### Short-term

6. Create end-to-end workflow tests with real JFR files
7. Add `script list`/`script run` command tests
8. Test `invalidate` cache command
9. Add decorator field access in aggregation contexts

### Long-term

10. Extend property-based testing beyond completion
11. Add fuzzing for query parsing
12. Add performance regression tests
13. Add memory stress tests

---

## Test Infrastructure Notes

- **Property-based testing** uses jqwik (1000 tries per property)
- **Mocking** uses Mockito for JFRSession isolation
- **Test JFR files** located in `parser/src/test/resources/`
- **BufferIO** pattern used for capturing output in tests
