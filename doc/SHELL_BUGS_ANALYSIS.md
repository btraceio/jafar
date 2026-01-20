# JFR Shell Bug Analysis and Fixes

**Date:** 2026-01-19
**Status:** Analysis Complete - Ready for Implementation
**Source:** Real bugs discovered during JFR validation script development

## Overview

Three critical bugs were discovered in jfr-shell that prevent basic control flow from working correctly. This document provides detailed analysis and specific code fixes for each bug.

## Bug #1: `or` Operator Causes Both Branches to Execute

### Severity
**CRITICAL** - Breaks basic conditional logic

### Reproduction
```bash
jbang jfr-shell@btraceio script - <<'EOF'
set scenario = "ddprof_only"

if "${scenario}" == "ddprof_with_tracer" or "${scenario}" == "ddprof_only"
  echo "Matched with OR"
else
  echo "Did not match"
endif

close
EOF
```

**Expected Output:**
```
Matched with OR
```

**Actual Output:**
```
Matched with OR
Did not match
```

### Root Cause Analysis

The `ConditionEvaluator` class only recognizes `||` as the logical OR operator, not the word `or`. When it encounters `or`:

1. Parses `"${scenario}" == "ddprof_with_tracer"` successfully → evaluates to `false`
2. Encounters `or` which is not recognized
3. Throws `IllegalArgumentException: Unexpected characters at position X: or...`
4. Exception caught in `CommandDispatcher.dispatch()` at line 213-216
5. Error printed, but **`conditionalState.enterIf()` never called**
6. Conditional stack remains empty
7. `conditionalState.isActive()` returns `true` when stack is empty (ConditionalState.java:31-32)
8. **Both branches execute because system doesn't know it's in a conditional block**

### Code Locations

**File:** `jfr-shell/src/main/java/io/jafar/shell/cli/ConditionEvaluator.java`
- Lines 72-81: `parseOrExpr()` only checks for `||`
- Lines 83-92: `parseAndExpr()` only checks for `&&`
- Line 64-67: Throws exception for unexpected characters

**File:** `jfr-shell/src/main/java/io/jafar/shell/cli/CommandDispatcher.java`
- Lines 2246-2261: `handleIf()` - calls `enterIf()` only if no exception
- Lines 213-216: Exception handler that swallows error

**File:** `jfr-shell/src/main/java/io/jafar/shell/cli/ConditionalState.java`
- Lines 30-35: `isActive()` returns true when stack is empty

### Proposed Fix

**1. Add keyword matching support to ConditionEvaluator.java**

Add new helper method after line 364:
```java
/**
 * Matches a keyword operator ensuring word boundaries.
 * This prevents matching 'or' inside 'fork' or 'and' inside 'band'.
 */
private boolean matchKeyword(String keyword) {
  skipWhitespace();
  int keywordLen = keyword.length();

  if (pos + keywordLen > input.length()) {
    return false;
  }

  // Case-insensitive match
  if (!input.regionMatches(true, pos, keyword, 0, keywordLen)) {
    return false;
  }

  // Ensure word boundary - not part of a larger identifier
  if (pos + keywordLen < input.length()) {
    char nextChar = input.charAt(pos + keywordLen);
    if (Character.isLetterOrDigit(nextChar) || nextChar == '_') {
      return false;
    }
  }

  pos += keywordLen;
  return true;
}
```

**2. Update parseOrExpr() at line 72:**
```java
private Object parseOrExpr() throws Exception {
  Object left = parseAndExpr();

  while (match("||") || matchKeyword("or")) {
    Object right = parseAndExpr();
    left = toBoolean(left) || toBoolean(right);
  }

  return left;
}
```

**3. Update parseAndExpr() at line 83:**
```java
private Object parseAndExpr() throws Exception {
  Object left = parseNotExpr();

  while (match("&&") || matchKeyword("and")) {
    Object right = parseNotExpr();
    left = toBoolean(left) && toBoolean(right);
  }

  return left;
}
```

**4. Fix error handling in CommandDispatcher.handleIf() at line 2246:**
```java
private boolean handleIf(String line) {
  String condition = line.length() > 2 ? line.substring(2).trim() : "";

  if (condition.isEmpty()) {
    io.error("if requires a condition");
    conditionalState.enterIf(false);
    return true;
  }

  boolean result = false;

  if (conditionalState.isActive() || !conditionalState.inConditional()) {
    try {
      ConditionEvaluator evaluator = new ConditionEvaluator(getSessionStore(), globalStore);
      result = evaluator.evaluate(condition);
    } catch (Exception e) {
      io.error("Condition evaluation failed: " + e.getMessage());
      result = false;
      // Continue to enterIf - don't return early
    }
  }

  // CRITICAL: Always update state, even after errors
  conditionalState.enterIf(result);
  return true;
}
```

**5. Apply same fix to handleElif() at line 2263:**
```java
private boolean handleElif(String line) {
  String condition = line.length() > 4 ? line.substring(4).trim() : "";

  if (condition.isEmpty()) {
    io.error("elif requires a condition");
    conditionalState.handleElif(false);
    return true;
  }

  boolean result = false;
  int depth = conditionalState.depth();

  if (depth > 0) {
    try {
      ConditionEvaluator evaluator = new ConditionEvaluator(getSessionStore(), globalStore);
      result = evaluator.evaluate(condition);
    } catch (Exception e) {
      io.error("Condition evaluation failed: " + e.getMessage());
      result = false;
      // Continue to handleElif - don't return early
    }
  }

  // CRITICAL: Always update state
  conditionalState.handleElif(result);
  return true;
}
```

## Bug #2: `contains` Operator Causes Both Branches to Execute

### Severity
**CRITICAL** - Same root cause as Bug #1

### Reproduction
```bash
jbang jfr-shell@btraceio script - <<'EOF'
set test_string = "foo,bar,baz"

if "${test_string}" contains "bar"
  echo "Contains bar: YES"
else
  echo "Contains bar: NO"
endif

close
EOF
```

**Expected Output:**
```
Contains bar: YES
```

**Actual Output:**
```
Contains bar: YES
Contains bar: NO
```

### Root Cause Analysis

Same as Bug #1, but for the `contains` operator. The `ConditionEvaluator` only supports comparison operators (`==`, `!=`, `>`, `<`, etc.) but not `contains` as an infix operator.

### Proposed Fix

**Add contains operator to parseComparison() at line 102:**

```java
private Object parseComparison() throws Exception {
  Object left = parseAddExpr();

  skipWhitespace();
  if (match("==")) {
    Object right = parseAddExpr();
    return compare(left, right) == 0;
  } else if (match("!=")) {
    Object right = parseAddExpr();
    return compare(left, right) != 0;
  } else if (match(">=")) {
    Object right = parseAddExpr();
    return compare(left, right) >= 0;
  } else if (match("<=")) {
    Object right = parseAddExpr();
    return compare(left, right) <= 0;
  } else if (match(">")) {
    Object right = parseAddExpr();
    return compare(left, right) > 0;
  } else if (match("<")) {
    Object right = parseAddExpr();
    return compare(left, right) < 0;
  } else if (matchKeyword("contains")) {
    Object right = parseAddExpr();
    return containsCheck(left, right);
  }

  return left;
}

/**
 * Checks if left contains right (as strings).
 */
private boolean containsCheck(Object left, Object right) {
  if (left == null || right == null) {
    return false;
  }
  String leftStr = String.valueOf(left);
  String rightStr = String.valueOf(right);
  return leftStr.contains(rightStr);
}
```

## Bug #3: Variable Assignment Doesn't Preserve Object Properties

### Severity
**HIGH** - Prevents variable aliasing and references

### Reproduction
```bash
# Requires actual JFR file
jbang jfr-shell@btraceio script - test.jfr <<'EOF'
open $1

set jdk_exec_count = events/jdk.ExecutionSample | count()
echo "Original variable: ${jdk_exec_count.count}"

# Try to assign to another variable
set exec_count = jdk_exec_count

echo "Aliased variable: ${exec_count.count}"
echo "Expected both to show the same number, but aliased shows nothing"

close
EOF
```

**Expected Output:**
```
Original variable: 264
Aliased variable: 264
```

**Actual Output:**
```
Original variable: 264
Aliased variable:
```

### Root Cause Analysis

When the user writes `set exec_count = jdk_exec_count`, the system:

1. Extracts `jdk_exec_count` as the expression string
2. **Does NOT perform variable substitution** on the expression
3. Tries to parse `jdk_exec_count` as a JfrPath query
4. Fails to find an event type named `jdk_exec_count`
5. Stores null or empty result

The system has no way to reference an existing variable in an assignment because variable substitution using `${}` syntax doesn't work in the right-hand side of assignments.

### Code Locations

**File:** `jfr-shell/src/main/java/io/jafar/shell/cli/CommandDispatcher.java`
- Lines 1436-1612: `cmdSet()` method
- Line 1469: Expression string extracted
- No variable substitution performed before parsing

### Proposed Fix

**Option 1: Support bare variable names (recommended)**

Add detection for bare variable references in `cmdSet()` after line 1478:

```java
private void cmdSet(List<String> args, String fullLine) throws Exception {
  // ... existing code to parse command ...

  String varName = rest.substring(0, eqPos).trim();
  String exprPart = rest.substring(eqPos + 1).trim();

  if (varName.isEmpty()) {
    io.error("Variable name cannot be empty");
    return;
  }
  if (!varName.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
    io.error("Invalid variable name: " + varName);
    return;
  }

  VariableStore store = getTargetStore(isGlobal);

  // NEW: Check if expression is a bare variable name (variable reference)
  if (exprPart.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
    Value existingValue = resolveVariable(exprPart);
    if (existingValue != null) {
      // Copy the variable value
      store.set(varName, existingValue);
      if (verbose) {
        io.println("Set " + varName + " = " + existingValue.describe());
      }
      return;
    }
    // If variable not found, continue with normal processing
    // (might be a literal or query)
  }

  // Continue with existing map literal, string literal, query handling...
  if (exprPart.startsWith("{")) {
    // ... existing code ...
  }
  // ... rest of method ...
}
```

**Option 2: Add explicit $ prefix syntax**

Support `set target = $source` syntax:

```java
// After the bare name check above, add:
if (exprPart.matches("\\$[a-zA-Z_][a-zA-Z0-9_]*")) {
  String sourceVarName = exprPart.substring(1);
  Value existingValue = resolveVariable(sourceVarName);
  if (existingValue == null) {
    io.error("Variable not found: " + sourceVarName);
    return;
  }
  store.set(varName, existingValue);
  if (verbose) {
    io.println("Set " + varName + " = " + existingValue.describe());
  }
  return;
}
```

**Option 3: Perform variable substitution before parsing (simplest)**

Add variable substitution at line 1469:

```java
String exprPart = rest.substring(eqPos + 1).trim();

// NEW: Perform variable substitution on the expression
VariableSubstitutor sub = new VariableSubstitutor(getSessionStore(), globalStore);
if (VariableSubstitutor.hasVariables(exprPart)) {
  try {
    exprPart = sub.substitute(exprPart);
  } catch (Exception e) {
    io.error("Variable substitution failed: " + e.getMessage());
    return;
  }
}

// Continue with existing logic...
```

**Recommendation:** Implement **Option 1** (bare variable names) for natural syntax, and optionally add **Option 3** (substitution) to enable expressions like `set x = ${y}.field`.

## Bug #4: No Support for Optional Positional Arguments

### Severity
**MEDIUM** - Limits script reusability and flexibility

### Problem Description

Currently, all positional parameters in scripts are **required**. If a script references `$1` but no argument is provided, the script fails with:
```
Error: Positional parameter $1 out of bounds. Script has 0 argument(s).
```

This prevents writing flexible scripts that can work with or without arguments, or scripts with default values.

### Expected Behavior

Scripts should support optional positional parameters with default values, similar to standard shell scripting:

**Desired syntax:**
- `${1:-default}` - Use $1 if provided, otherwise use "default"
- `${1}` - Use $1 if provided, otherwise use empty string (when optional mode enabled)
- `${1:?error message}` - Use $1 if provided, otherwise fail with custom error message

### Use Cases

**Example 1: Optional output format**
```bash
# Script with optional format parameter (defaults to "table")
# Usage: analyze.jfrs recording.jfr [format]

open $1
set format = ${2:-table}
set output json  # Set default format

show events/jdk.ExecutionSample --format ${format}
close
```

**Example 2: Optional filter threshold**
```bash
# Script with optional threshold (defaults to 1000)
# Usage: filter-reads.jfrs recording.jfr [threshold]

open $1
set threshold = ${2:-1000}

show events/jdk.FileRead[bytes>=${threshold}]
close
```

**Example 3: Multiple optional parameters**
```bash
# Usage: analyze.jfrs recording.jfr [limit] [format] [event-type]

open $1
set limit = ${2:-100}
set format = ${3:-table}
set event_type = ${4:-jdk.ExecutionSample}

show events/${event_type} --limit ${limit} --format ${format}
close
```

### Code Locations

**File:** `jfr-shell/src/main/java/io/jafar/shell/cli/ScriptRunner.java`
- Line 29: `VAR_PATTERN` - only matches simple `$N` syntax
- Lines 132-163: `substituteVariables()` - throws exception when parameter missing
- Lines 146-154: Hard error on missing parameter

### Root Cause

The current pattern `\\$(\\d+|@)` only matches basic `$1`, `$2` syntax without any optional/default value syntax.

The substitution logic at line 146 immediately throws when an argument index is out of bounds, with no provision for:
- Default values
- Optional parameters
- Custom error messages

### Proposed Fix

**1. Update VAR_PATTERN to support extended syntax (line 29):**

```java
// Match patterns:
// $1, $2, etc. - simple positional param
// ${1}, ${2} - bracketed positional param
// ${1:-default} - with default value
// ${1:?error} - with custom error message
private static final Pattern VAR_PATTERN = Pattern.compile(
    "\\$(?:(\\d+|@)|\\{(\\d+|@)(?:(:[-?])(.+?))?\\})"
);
```

**Pattern explanation:**
- Group 1: Simple form `$N` - captures N
- Group 2: Bracketed form `${N...}` - captures N
- Group 3: Operator `:-` or `:?`
- Group 4: Default value or error message

**2. Update substituteVariables() method (lines 132-163):**

```java
private String substituteVariables(String line) {
  StringBuffer result = new StringBuffer();
  Matcher matcher = VAR_PATTERN.matcher(line);

  while (matcher.find()) {
    String simpleRef = matcher.group(1);      // $N form
    String bracketedRef = matcher.group(2);   // ${N} form
    String operator = matcher.group(3);       // :- or :?
    String operand = matcher.group(4);        // default value or error message

    String varRef = simpleRef != null ? simpleRef : bracketedRef;
    String value;

    if ("@".equals(varRef)) {
      // $@ expands to all arguments space-separated
      value = String.join(" ", arguments);
    } else {
      // $1, $2, etc. - positional parameter (1-indexed)
      int index = Integer.parseInt(varRef) - 1;

      if (index < 0 || index >= arguments.size()) {
        // Parameter not provided
        if (operator != null) {
          switch (operator) {
            case ":-":
              // Use default value
              value = operand != null ? operand : "";
              break;
            case ":?":
              // Throw custom error
              String errorMsg = operand != null ? operand : "required parameter not provided";
              throw new IllegalArgumentException(
                  "Positional parameter $" + varRef + ": " + errorMsg);
            default:
              throw new IllegalArgumentException(
                  "Unknown operator: " + operator + " in positional parameter");
          }
        } else if (bracketedRef != null) {
          // ${N} without operator - use empty string for missing parameters
          value = "";
        } else {
          // $N form without braces - still required (backward compatibility)
          throw new IllegalArgumentException(
              "Positional parameter $"
                  + varRef
                  + " out of bounds. "
                  + "Script has "
                  + arguments.size()
                  + " argument(s).");
        }
      } else {
        // Parameter provided
        value = arguments.get(index);
      }
    }

    matcher.appendReplacement(result, Matcher.quoteReplacement(value));
  }

  matcher.appendTail(result);
  return result.toString();
}
```

**3. Update class documentation (lines 11-26):**

```java
/**
 * Executes JFR shell script files with positional parameter substitution.
 *
 * <p>Scripts are line-based text files containing shell commands and JfrPath queries. Lines
 * starting with '#' are comments. Positional parameters are substituted with values
 * provided via the constructor.
 *
 * <p>Supported parameter syntax:
 * <ul>
 *   <li>$1, $2, ... - Required positional parameters (1-indexed)
 *   <li>${1}, ${2}, ... - Optional positional parameters (empty string if missing)
 *   <li>${1:-default} - Parameter with default value
 *   <li>${1:?error message} - Required parameter with custom error message
 *   <li>$@ - All arguments space-separated
 * </ul>
 *
 * <p>Example script:
 *
 * <pre>
 * # Analysis script with optional parameters
 * # Usage: script analysis.jfrs /path/to/recording.jfr [limit] [format]
 * open $1
 * set limit = ${2:-100}
 * set format = ${3:-table}
 * show events/jdk.FileRead[bytes>=1000] --limit ${limit} --format ${format}
 * close
 * </pre>
 */
```

### Backward Compatibility

The fix maintains backward compatibility:
- `$1`, `$2` - Still required (throws error if missing) - **NO CHANGE**
- `${1}`, `${2}` - NEW: Optional (empty if missing)
- `${1:-default}` - NEW: With default value
- `${1:?error}` - NEW: With custom error

Existing scripts using `$1` will continue to work exactly as before.

### Testing

**Unit tests in `ScriptRunnerTest.java`:**

```java
@Test
void testOptionalParameterWithDefault() {
  ScriptRunner runner = new ScriptRunner(dispatcher, io, List.of("arg1"));
  String result = runner.substituteVariables("value=${2:-default}");
  assertEquals("value=default", result);
}

@Test
void testOptionalParameterProvided() {
  ScriptRunner runner = new ScriptRunner(dispatcher, io, List.of("arg1", "arg2"));
  String result = runner.substituteVariables("value=${2:-default}");
  assertEquals("value=arg2", result);
}

@Test
void testOptionalParameterEmpty() {
  ScriptRunner runner = new ScriptRunner(dispatcher, io, List.of("arg1"));
  String result = runner.substituteVariables("value=${2}");
  assertEquals("value=", result);
}

@Test
void testRequiredParameterWithCustomError() {
  ScriptRunner runner = new ScriptRunner(dispatcher, io, List.of());
  assertThrows(IllegalArgumentException.class,
      () -> runner.substituteVariables("open ${1:?recording file required}"),
      "recording file required");
}

@Test
void testBackwardCompatibility() {
  ScriptRunner runner = new ScriptRunner(dispatcher, io, List.of());
  // Old syntax still required
  assertThrows(IllegalArgumentException.class,
      () -> runner.substituteVariables("open $1"));
}

@Test
void testMultipleOptionalParameters() {
  ScriptRunner runner = new ScriptRunner(dispatcher, io, List.of("file.jfr"));
  String result = runner.substituteVariables(
      "open $1 --limit ${2:-100} --format ${3:-table}");
  assertEquals("open file.jfr --limit 100 --format table", result);
}
```

### Implementation Checklist

- [ ] Update `VAR_PATTERN` regex to support `${N:-default}` and `${N:?error}` syntax
- [ ] Update `substituteVariables()` to handle optional parameters
- [ ] Add support for `:-` (default value) operator
- [ ] Add support for `:?` (custom error) operator
- [ ] Support `${N}` as optional (empty string if missing)
- [ ] Maintain backward compatibility for `$N` (still required)
- [ ] Update class documentation with new syntax
- [ ] Add comprehensive unit tests
- [ ] Add integration test scripts
- [ ] Update user documentation/help text

### Estimated Effort

- Implementation: 2-3 hours
- Testing: 1-2 hours
- Documentation: 1 hour

**Total: 4-6 hours**

---

## Testing Plan

### Unit Tests

Create `ConditionEvaluatorRobustnessTest.java`:

```java
@Test
void testOrKeywordSupport() {
  ConditionEvaluator eval = new ConditionEvaluator(null, null);
  assertTrue(eval.evaluate("1 == 1 or 2 == 3"));
  assertFalse(eval.evaluate("1 == 2 or 2 == 3"));
}

@Test
void testAndKeywordSupport() {
  ConditionEvaluator eval = new ConditionEvaluator(null, null);
  assertTrue(eval.evaluate("1 == 1 and 2 == 2"));
  assertFalse(eval.evaluate("1 == 1 and 2 == 3"));
}

@Test
void testContainsOperator() {
  ConditionEvaluator eval = new ConditionEvaluator(null, null);
  assertTrue(eval.evaluate("\"foo,bar,baz\" contains \"bar\""));
  assertFalse(eval.evaluate("\"foo,bar,baz\" contains \"qux\""));
}

@Test
void testMixedCaseKeywords() {
  ConditionEvaluator eval = new ConditionEvaluator(null, null);
  assertTrue(eval.evaluate("1 == 1 OR 2 == 3"));
  assertTrue(eval.evaluate("1 == 1 And 2 == 2"));
  assertTrue(eval.evaluate("\"test\" CONTAINS \"es\""));
}

@Test
void testWordBoundaries() {
  ConditionEvaluator eval = new ConditionEvaluator(null, null);
  // 'or' inside 'fork' shouldn't match
  assertThrows(Exception.class, () -> eval.evaluate("fork == 1"));
}
```

Create `ConditionalFlowTest.java`:

```java
@Test
void testIfWithInvalidCondition_DoesNotExecuteBothBranches() {
  // Set up dispatcher with mock IO
  TestIO io = new TestIO();
  CommandDispatcher dispatcher = new CommandDispatcher(sessions, io, listener);

  // Execute invalid condition
  dispatcher.dispatch("if x or y");  // Invalid: undefined variables
  dispatcher.dispatch("echo branch1");
  dispatcher.dispatch("else");
  dispatcher.dispatch("echo branch2");
  dispatcher.dispatch("endif");

  // Should only execute else branch (or neither if if fails)
  // Should NOT execute both branches
  List<String> output = io.getOutput();
  assertFalse(output.contains("branch1") && output.contains("branch2"),
    "Both branches should not execute");
}

@Test
void testVariableReferenceCopy() {
  VariableStore store = new VariableStore();
  store.set("original", new ScalarValue(42));

  // Simulate: set copy = original
  CommandDispatcher dispatcher = new CommandDispatcher(sessions, io, listener);
  dispatcher.dispatch("set original = 42");
  dispatcher.dispatch("set copy = original");

  // Verify copy has same value
  Value copyValue = store.get("copy");
  assertNotNull(copyValue);
  assertEquals(42, ((ScalarValue) copyValue).value());
}
```

### Integration Tests

Create script test cases in `jfr-shell/src/test/resources/scripts/`:

**test-or-operator.jfrs:**
```
set x = "test"

if "${x}" == "other" or "${x}" == "test"
  echo "PASS"
else
  echo "FAIL"
endif
```

**test-contains-operator.jfrs:**
```
set str = "foo,bar,baz"

if "${str}" contains "bar"
  echo "PASS"
else
  echo "FAIL"
endif
```

**test-variable-copy.jfrs:**
```
# Requires JFR file as $1
open $1

set original = events/jdk.ExecutionSample | count()
set copy = original

if ${copy.count} == ${original.count}
  echo "PASS"
else
  echo "FAIL"
endif
```

## Implementation Checklist

- [ ] Add `matchKeyword()` method to `ConditionEvaluator.java`
- [ ] Update `parseOrExpr()` to support `or`
- [ ] Update `parseAndExpr()` to support `and`
- [ ] Update `parseNotExpr()` to support `not` (optional)
- [ ] Add `containsCheck()` method to `ConditionEvaluator.java`
- [ ] Update `parseComparison()` to support `contains`
- [ ] Fix error handling in `handleIf()` in `CommandDispatcher.java`
- [ ] Fix error handling in `handleElif()` in `CommandDispatcher.java`
- [ ] Add bare variable name detection to `cmdSet()`
- [ ] Add unit tests for all new operators
- [ ] Add unit tests for error recovery
- [ ] Add integration tests with sample scripts
- [ ] Run original bug reproduction scripts to verify fixes
- [ ] Update help text if needed
- [ ] Update documentation with new operator support

## Risk Assessment

### Low Risk Changes
- Adding `matchKeyword()` method (new code, no existing dependencies)
- Adding `containsCheck()` method (new code)
- Adding bare variable name detection (only affects `cmdSet()`)

### Medium Risk Changes
- Updating `parseOrExpr()` and `parseAndExpr()` (core parsing logic, but additive)
- Updating `parseComparison()` (core parsing logic, but additive)

### High Risk Changes
- Modifying error handling in `handleIf()` and `handleElif()` (affects control flow state machine)

**Mitigation:** Comprehensive testing of all control flow paths, including error cases.

## Estimated Effort

- Implementation: 4-6 hours
- Unit testing: 2-3 hours
- Integration testing: 2-3 hours
- Code review and refinement: 1-2 hours

**Total: 9-14 hours (1-2 days)**

## References

- Original bug report: `/Users/jaroslav.bachorik/dd/java-profiler-build/test-validation/JFR-SHELL-BUGS.md`
- Comprehensive hardening plan: `doc/SHELL_HARDENING_PLAN.md`
- Issue tracker: (to be created)

## Conclusion

All three bugs stem from similar patterns:
1. Incomplete operator support in `ConditionEvaluator`
2. Poor error handling that leaves state inconsistent
3. Missing variable reference syntax

The proposed fixes are targeted, well-defined, and have clear test cases. Implementation can proceed immediately.
