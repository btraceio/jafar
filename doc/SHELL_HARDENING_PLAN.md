# JFR Shell Hardening Plan

**Date:** 2026-01-19
**Scope:** jfr-shell module robustness and reliability improvements
**Context:** Analysis following discovery of critical control flow bugs

## Executive Summary

This document outlines a comprehensive plan to harden the jfr-shell implementation against bugs similar to those discovered in the conditional control flow system. The analysis identified systematic issues in:
- Error handling and state recovery
- Incomplete feature implementation
- Input validation gaps
- State machine consistency

## Bug Pattern Analysis

### Original Bugs Discovered

1. **`or` operator causes both if/else branches to execute**
   - Root cause: Unsupported operator `or` (only `||` implemented)
   - Impact: Exception thrown, conditional state never updated
   - Result: System thinks it's not in a conditional block, executes all branches

2. **`contains` operator causes both if/else branches to execute**
   - Root cause: Same as #1 - unsupported infix operator
   - Impact: Identical failure mode

3. **Variable assignment doesn't preserve object properties**
   - Root cause: No variable-to-variable copy syntax
   - Impact: Attempting `set a = b` treats `b` as a JfrPath query, not a variable reference

### Common Failure Patterns

All three bugs share these characteristics:

1. **Feature Incompleteness**: User-facing syntax not fully implemented
2. **Silent Failures**: Errors caught but state left inconsistent
3. **No Recovery**: Exception paths don't restore state to valid configuration
4. **Documentation Mismatch**: Help text suggests features that don't work

## Critical Issues by Priority

### Priority 1: State Machine Consistency (CRITICAL)

**Issue**: Conditional state updates can be skipped when exceptions occur, causing both branches to execute.

**Affected Code**:
- `CommandDispatcher.java:2246-2261` (handleIf)
- `CommandDispatcher.java:2263-2283` (handleElif)
- `CommandDispatcher.java:114-217` (dispatch exception handling)
- `ConditionalState.java:57-69` (handleElif state transitions)

**Impact**: Complete control flow failure - scripts execute incorrectly

**Recommended Fixes**:

1. **Always update conditional state, even on errors**:
   ```java
   private boolean handleIf(String line) {
     String condition = line.length() > 2 ? line.substring(2).trim() : "";

     boolean result = false;
     boolean shouldEnter = true;

     if (condition.isEmpty()) {
       io.error("if requires a condition");
       result = false; // Treat as false condition
     } else if (conditionalState.isActive() || !conditionalState.inConditional()) {
       try {
         ConditionEvaluator evaluator = new ConditionEvaluator(getSessionStore(), globalStore);
         result = evaluator.evaluate(condition);
       } catch (Exception e) {
         io.error("Condition evaluation failed: " + e.getMessage());
         result = false; // Treat evaluation failure as false
       }
     }

     // ALWAYS update state, regardless of evaluation success
     conditionalState.enterIf(result);
     return true;
   }
   ```

2. **Add state recovery in exception handler**:
   ```java
   public boolean dispatch(String line) {
     // ... existing code ...
     try {
       if ("if".equals(cmd)) {
         return handleIf(line);
       }
       // ... other handlers ...
     } catch (Exception e) {
       io.error("Error: " + e.getMessage());
       // If we're in the middle of a conditional statement and it failed,
       // the state should already be updated by handleIf/handleElif/handleElse
       return true;
     }
   }
   ```

3. **Make state transitions atomic**:
   ```java
   // In ConditionalState.java
   public void handleElif(boolean condition) {
     if (stack.isEmpty()) {
       throw new IllegalStateException("elif without if");
     }

     // Make pop and push atomic by doing both in one step
     IfBlock current = stack.peek(); // Don't pop yet
     BranchState newState;

     if (current.state == BranchState.BRANCH_TAKEN ||
         current.state == BranchState.CONDITION_MET) {
       newState = BranchState.BRANCH_TAKEN;
     } else {
       newState = condition ? BranchState.CONDITION_MET : BranchState.CONDITION_FAILED;
     }

     // Now atomically replace top of stack
     stack.pop();
     stack.push(new IfBlock(newState));
   }
   ```

### Priority 2: Missing Operator Support (HIGH)

**Issue**: Natural language operators (`or`, `and`, `contains`) not implemented despite user expectations.

**Affected Code**:
- `ConditionEvaluator.java:72-127` (logical and comparison operators)

**Impact**: Script failures, user confusion, workarounds required

**Recommended Fixes**:

1. **Add `or` and `and` keyword support**:
   ```java
   private Object parseOrExpr() throws Exception {
     Object left = parseAndExpr();

     while (match("||") || matchKeyword("or")) {
       Object right = parseAndExpr();
       left = toBoolean(left) || toBoolean(right);
     }

     return left;
   }

   private Object parseAndExpr() throws Exception {
     Object left = parseNotExpr();

     while (match("&&") || matchKeyword("and")) {
       Object right = parseNotExpr();
       left = toBoolean(left) && toBoolean(right);
     }

     return left;
   }

   // Helper method ensures word boundaries
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

     // Ensure word boundary (not part of identifier)
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

2. **Add `contains` as comparison operator**:
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

   private boolean containsCheck(Object left, Object right) {
     if (left == null || right == null) {
       return false;
     }
     String leftStr = String.valueOf(left);
     String rightStr = String.valueOf(right);
     return leftStr.contains(rightStr);
   }
   ```

3. **Add `not` as unary operator alias**:
   ```java
   private Object parseNotExpr() throws Exception {
     if (match("!") || matchKeyword("not")) {
       Object value = parseNotExpr();
       return !toBoolean(value);
     }
     return parseComparison();
   }
   ```

### Priority 3: Variable Reference Support (HIGH)

**Issue**: Cannot copy variables or reference existing variables in assignments.

**Affected Code**:
- `CommandDispatcher.java:1436-1612` (cmdSet method)

**Impact**: Users must manually recreate query results, can't build on existing variables

**Recommended Fixes**:

1. **Add bare variable name detection**:
   ```java
   private void cmdSet(List<String> args, String fullLine) throws Exception {
     // ... existing parsing code ...

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

     // NEW: Check if expression is a bare variable reference
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
       // If not found as variable, continue with normal parsing
     }

     // Continue with existing map literal, string literal, etc. handling...
   }
   ```

2. **Add explicit copy syntax** (alternative/additional approach):
   ```java
   // Support: set target = $source (with $ prefix for explicit variable reference)
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

### Priority 4: Input Validation (MEDIUM)

**Issue**: Multiple command handlers don't validate input before processing, leading to crashes or incorrect behavior.

**Affected Code**:
- `CommandDispatcher.java:335-634` (cmdShow option parsing)
- `CommandDispatcher.java:1273-1332` (cmdChunks range parsing)
- `CommandDispatcher.java:1367-1417` (cmdCp range parsing)

**Impact**: Poor error messages, potential crashes, incorrect output

**Recommended Fixes**:

1. **Validate all numeric inputs**:
   ```java
   // In cmdChunks
   if (range != null) {
     String[] parts = range.split("-");
     if (parts.length == 0 || parts.length > 2) {
       io.error("Invalid range format. Use: --range N or --range N-M");
       return;
     }

     int start, end;
     try {
       start = Integer.parseInt(parts[0].trim());
       end = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : start;
     } catch (NumberFormatException e) {
       io.error("Invalid range values: " + range);
       return;
     }

     if (start < 0 || end < 0) {
       io.error("Range values must be non-negative");
       return;
     }

     if (start > end) {
       io.error("Range start must be <= end");
       return;
     }

     // Now safe to use start and end
     // ...
   }
   ```

2. **Validate options before list modification**:
   ```java
   // In cmdShow, parse all options first, then modify tokens
   Map<String, String> parsedOptions = new HashMap<>();
   List<String> optionErrors = new ArrayList<>();

   for (int i = 0; i < args.size(); i++) {
     String arg = args.get(i);
     if (arg.startsWith("--")) {
       if (i + 1 < args.size() && !args.get(i + 1).startsWith("--")) {
         String optValue = args.get(i + 1);
         // Validate value
         if ("--depth".equals(arg)) {
           try {
             Integer.parseInt(optValue);
             parsedOptions.put("depth", optValue);
           } catch (NumberFormatException e) {
             optionErrors.add("Invalid --depth value: " + optValue);
           }
         }
         // ... other options ...
         i++; // Skip value
       } else if ("--tree".equals(arg)) {
         parsedOptions.put("tree", "true");
       }
     }
   }

   // Report all errors at once
   if (!optionErrors.isEmpty()) {
     for (String error : optionErrors) {
       io.error(error);
     }
     return;
   }

   // Now apply validated options
   // ...
   ```

### Priority 5: State Rollback Mechanism (MEDIUM)

**Issue**: No way to restore previous state when operations fail partway through.

**Affected Code**:
- `CommandDispatcher.java:1436-1612` (cmdSet - variable replacement)
- `VariableStore.java:317-322` (set method)

**Impact**: Failed operations leave system in partially modified state

**Recommended Fixes**:

1. **Add transactional variable updates**:
   ```java
   // In VariableStore.java
   public void setTransactional(String name, Value newValue) throws Exception {
     Value oldValue = variables.get(name);

     try {
       // Validate new value by attempting to access it
       if (newValue instanceof LazyQueryValue lqv) {
         // Eagerly evaluate to catch errors before committing
         lqv.get();
       }

       // Commit the change
       variables.put(name, newValue);

       // Release old value only after successful commit
       if (oldValue != null) {
         oldValue.release();
       }
     } catch (Exception e) {
       // Rollback - restore old value
       if (oldValue != null) {
         variables.put(name, oldValue);
       } else {
         variables.remove(name);
       }
       throw new IllegalStateException("Failed to set variable: " + e.getMessage(), e);
     }
   }
   ```

2. **Implement checkpoint/restore for conditional blocks**:
   ```java
   // In ConditionalState.java
   public static class StateCheckpoint {
     private final List<IfBlock> stackSnapshot;

     private StateCheckpoint(Deque<IfBlock> stack) {
       this.stackSnapshot = new ArrayList<>(stack);
     }
   }

   public StateCheckpoint createCheckpoint() {
     return new StateCheckpoint(stack);
   }

   public void restoreCheckpoint(StateCheckpoint checkpoint) {
     stack.clear();
     stack.addAll(checkpoint.stackSnapshot);
   }
   ```

### Priority 6: Script Execution Robustness (LOW)

**Issue**: ScriptRunner doesn't preserve conditional state across errors when `continueOnError=true`.

**Affected Code**:
- `ScriptRunner.java:77-122` (execute method)

**Impact**: Scripts with errors in conditionals may execute incorrectly

**Recommended Fixes**:

1. **Track conditional depth before each command**:
   ```java
   public ExecutionResult execute(List<String> lines) {
     int successCount = 0;
     List<ScriptError> errors = new ArrayList<>();

     for (int i = 0; i < lines.size(); i++) {
       String line = lines.get(i).trim();
       int lineNumber = i + 1;

       if (line.isEmpty() || line.startsWith("#")) {
         continue;
       }

       // Track conditional depth before command
       int depthBefore = dispatcher.getConditionalState().depth();

       try {
         String processed = substituteVariables(line);
         boolean handled = dispatcher.dispatch(processed);

         if (!handled) {
           errors.add(new ScriptError(lineNumber, line, "Unknown command"));
           if (!continueOnError) {
             break;
           }
         } else {
           successCount++;
         }
       } catch (Exception e) {
         // Check if conditional depth changed unexpectedly
         int depthAfter = dispatcher.getConditionalState().depth();
         if (depthBefore != depthAfter) {
           errors.add(new ScriptError(
             lineNumber,
             line,
             "Conditional state corrupted: " + e.getMessage()
           ));
           // Reset conditional state
           dispatcher.getConditionalState().reset();
         } else {
           errors.add(new ScriptError(lineNumber, line, e.getMessage()));
         }

         if (!continueOnError) {
           break;
         }
       }
     }

     // Ensure conditional state is clean after script
     if (dispatcher.getConditionalState().inConditional()) {
       errors.add(new ScriptError(
         lines.size(),
         "",
         "Unclosed conditional block at end of script"
       ));
     }

     return new ExecutionResult(successCount, errors);
   }
   ```

## Testing Strategy

### Unit Tests Required

1. **Conditional Error Handling Tests**:
   ```java
   @Test
   void testIfWithInvalidCondition_ShouldNotExecuteBothBranches() {
     // Test that if with invalid condition doesn't execute both branches
   }

   @Test
   void testElifAfterIfError_ShouldMaintainCorrectState() {
     // Test that elif works correctly even if previous if had error
   }
   ```

2. **Operator Support Tests**:
   ```java
   @Test
   void testOrKeyword_ShouldWorkLikeDoubleBar() {
     // Test: if x == 1 or x == 2
   }

   @Test
   void testAndKeyword_ShouldWorkLikeDoubleAmpersand() {
     // Test: if x == 1 and y == 2
   }

   @Test
   void testContainsOperator_ShouldCheckStringContainment() {
     // Test: if "${str}" contains "substring"
   }
   ```

3. **Variable Reference Tests**:
   ```java
   @Test
   void testVariableCopy_ShouldPreserveProperties() {
     // Test: set a = ...; set b = a; check b.count == a.count
   }
   ```

### Integration Tests Required

1. **Script Execution Tests**:
   - Scripts with errors in conditionals
   - Scripts with nested conditionals and errors
   - Scripts using all operator types

2. **Error Recovery Tests**:
   - Verify state consistency after errors
   - Verify cleanup of resources after failures

## Implementation Roadmap

### Phase 1: Critical Fixes (1-2 days)
1. Fix conditional state update in error paths (Priority 1)
2. Add missing `or`, `and`, `contains` operators (Priority 2)
3. Add comprehensive unit tests for conditionals

### Phase 2: Variable System (1-2 days)
1. Add variable reference support (Priority 3)
2. Add transactional updates (Priority 5)
3. Add variable system tests

### Phase 3: Input Validation (2-3 days)
1. Add validation to all command handlers (Priority 4)
2. Standardize error reporting
3. Add validation tests

### Phase 4: Enhanced Robustness (2-3 days)
1. Add checkpoint/restore for state (Priority 5)
2. Improve script execution robustness (Priority 6)
3. Add integration tests

### Phase 5: Documentation & Polish (1 day)
1. Update help text to match implementation
2. Add examples for new operators
3. Document error recovery behavior

## Monitoring & Verification

### Success Criteria

1. All three original bugs are fixed and verified
2. No regressions in existing functionality
3. 90%+ code coverage for modified components
4. All integration tests pass
5. Documentation matches implementation

### Post-Implementation Review

After implementation:
1. Run original bug reproduction scripts - all must pass
2. Review error logs for unexpected patterns
3. User testing with realistic scripts
4. Performance impact assessment

## Appendix: Code References

### Files Requiring Modification

1. **ConditionEvaluator.java** (Priority 1, 2)
   - Lines 72-127: Operator support
   - Add matchKeyword() method

2. **CommandDispatcher.java** (Priority 1, 3, 4)
   - Lines 114-217: Exception handling
   - Lines 2246-2283: Conditional handlers
   - Lines 1436-1612: Variable assignment
   - Lines 335-634: Option parsing validation

3. **ConditionalState.java** (Priority 1, 5)
   - Lines 57-69: State transition atomicity
   - Add checkpoint/restore methods

4. **VariableStore.java** (Priority 5)
   - Lines 317-322: Transactional updates

5. **ScriptRunner.java** (Priority 6)
   - Lines 77-122: Error recovery

### Test Files to Create

1. `ConditionEvaluatorRobustnessTest.java`
2. `ConditionalStateRecoveryTest.java`
3. `VariableReferenceTest.java`
4. `CommandValidationTest.java`
5. `ScriptExecutionRobustnessTest.java`

## Conclusion

This hardening plan addresses the root causes of the discovered bugs and prevents similar issues across the entire shell implementation. The prioritized approach ensures critical issues are fixed first while providing a roadmap for comprehensive robustness improvements.

The estimated total effort is 7-11 days for full implementation and testing.
