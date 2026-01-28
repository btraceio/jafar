# JFR Shell Comprehensive Test Plan

## Overview

This test plan covers all documented JFR Shell functionality from the tutorial and scripting guide, plus extensive fuzzing tests for input validation and error handling.

---

## 1. Session Management Tests

### 1.1 Basic Session Operations
- [ ] Open single JFR file
- [ ] Open JFR file with `--alias` option
- [ ] Open multiple JFR files with different aliases
- [ ] `sessions` command lists all open sessions correctly
- [ ] `sessions` command shows active session marker (*)
- [ ] `use <id>` switches to session by ID
- [ ] `use <alias>` switches to session by alias
- [ ] `close <id>` closes session by ID
- [ ] `close <alias>` closes session by alias
- [ ] `close --all` closes all sessions
- [ ] `info` displays current session information
- [ ] Session info shows event count, chunk count, file size

### 1.2 Session Error Cases
- [ ] Open non-existent file shows error
- [ ] Open invalid JFR file shows error
- [ ] `use` with invalid session ID shows error
- [ ] `close` with invalid session ID shows error
- [ ] Commands requiring session fail when no session open
- [ ] Duplicate alias handling
- [ ] Opening same file multiple times

### 1.3 Session Edge Cases
- [ ] Very large JFR files (> 1GB)
- [ ] Empty JFR files
- [ ] JFR files with no events
- [ ] JFR files with single event
- [ ] Corrupted JFR chunks

---

## 2. JfrPath Query Language Tests

### 2.1 Query Roots
- [ ] `events/<type>` queries work
- [ ] `metadata/<type>` queries work
- [ ] `chunks` queries work
- [ ] `cp/<type>` constant pool queries work
- [ ] Invalid root shows error

### 2.2 Path Navigation
- [ ] Simple field access: `events/jdk.FileRead/path`
- [ ] Nested field access: `events/jdk.ExecutionSample/thread/name`
- [ ] Deep nesting: `events/jdk.ExecutionSample/stackTrace/frames/0/method/name`
- [ ] Array element access: `stackTrace/frames/0`
- [ ] Array element access with large indices
- [ ] Invalid field names show error
- [ ] Null field handling

### 2.3 Filters - Operators
- [ ] Equality: `[field="value"]`
- [ ] Inequality: `[field!="value"]`
- [ ] Greater than: `[field>100]`
- [ ] Greater than or equal: `[field>=100]`
- [ ] Less than: `[field<100]`
- [ ] Less than or equal: `[field<=100]`
- [ ] Regex match: `[field~"pattern"]`

### 2.4 Filters - Complex Conditions
- [ ] AND logic: `[field1>100 and field2="value"]`
- [ ] OR logic: `[field1>100 or field2="value"]`
- [ ] NOT logic: `[not field>100]`
- [ ] Nested boolean expressions with parentheses
- [ ] Nested field filters: `[thread/name="main"]`

### 2.5 Filters - Functions
- [ ] `len(field)` for length checks
- [ ] `exists(field)` for null checks
- [ ] `startsWith(field, "prefix")`
- [ ] `contains(field, "substring")`
- [ ] `matches(field, "regex")`
- [ ] `between(field, min, max)`

### 2.6 Filters - List Matching
- [ ] `any:` quantifier on arrays
- [ ] `all:` quantifier on arrays
- [ ] `none:` quantifier on arrays
- [ ] Interleaved filters at different path levels

### 2.7 Filters - Edge Cases
- [ ] Empty filter: `[]`
- [ ] Malformed filter syntax
- [ ] Unclosed brackets
- [ ] Invalid operators
- [ ] Type mismatches (string vs number)
- [ ] Special characters in regex patterns
- [ ] Unicode in filter values
- [ ] Very long filter expressions

---

## 3. Aggregation Tests

### 3.1 Count Aggregation
- [ ] `| count()` on events
- [ ] `| count()` on filtered events
- [ ] Count with zero results

### 3.2 Sum Aggregation
- [ ] `| sum()` on numeric fields
- [ ] Sum with projection: `events/jdk.FileRead/bytes | sum()`
- [ ] Sum on empty result set
- [ ] Sum on non-numeric fields (error case)

### 3.3 GroupBy Aggregation
- [ ] `| groupBy(field)` basic grouping
- [ ] `| groupBy(field, agg=sum, value=otherField)`
- [ ] `| groupBy(field, agg=count)`
- [ ] GroupBy on nested fields
- [ ] GroupBy with multiple aggregations
- [ ] GroupBy on null values

### 3.4 Top-N Aggregation
- [ ] `| top(N, by=field)` with numeric field
- [ ] `| top(N, by=field)` with string field
- [ ] Top with N=0
- [ ] Top with N > result count
- [ ] Top with negative N (error case)

### 3.5 Statistics Aggregation
- [ ] `| stats()` basic statistics
- [ ] Stats on empty dataset
- [ ] Stats on single value
- [ ] `| quantiles(0.5, 0.9, 0.99)`
- [ ] `| sketch()` combined stats + quantiles
- [ ] Quantiles with invalid percentiles (error case)

### 3.6 Chained Aggregations
- [ ] GroupBy followed by Top
- [ ] Filter → GroupBy → Top pipeline
- [ ] Multiple aggregation levels
- [ ] Invalid aggregation chains

---

## 4. Event Decoration Tests

### 4.1 DecorateByTime
- [ ] Basic time-based decoration with default thread matching
- [ ] DecorateByTime with custom `threadPath`
- [ ] DecorateByTime with custom `decoratorThreadPath`
- [ ] Multiple decorator fields: `fields=field1,field2,field3`
- [ ] Events with overlapping time ranges
- [ ] Events with no overlap (null decorator fields)
- [ ] Events with zero duration
- [ ] Decorator events filtered before decoration

### 4.2 DecorateByKey
- [ ] Basic key-based decoration
- [ ] Custom correlation keys: `key=path, decoratorKey=path`
- [ ] Nested key paths
- [ ] Multiple decorator fields
- [ ] No matching keys (null decorator fields)
- [ ] Null key values

### 4.3 Decorator Field Access
- [ ] `$decorator.field` in projections
- [ ] `$decorator.field` in filters
- [ ] `$decorator.field` in groupBy
- [ ] `$decorator.field` in top
- [ ] Nested decorator fields: `$decorator.field.subfield`
- [ ] Handling null decorator values

### 4.4 Decoration Edge Cases
- [ ] Very large decorator event sets (> 1M events)
- [ ] Decorator event type not in recording
- [ ] Invalid decorator field names
- [ ] Circular decoration attempts
- [ ] Multiple decorations chained
- [ ] Decoration with filtered primary events

---

## 5. Metadata Exploration Tests

### 5.1 Metadata Commands
- [ ] `metadata` lists all types
- [ ] `metadata --events-only` lists only event types
- [ ] `metadata --search "pattern"` searches types
- [ ] `metadata --search "jdk.*"` filters by prefix
- [ ] `metadata --summary` shows summary info

### 5.2 Metadata Queries
- [ ] `show metadata/TypeName` displays type structure
- [ ] `show metadata/TypeName --tree` shows tree view
- [ ] `show metadata/TypeName --tree --depth N` limits depth
- [ ] `show metadata/TypeName/fields` lists fields
- [ ] `show metadata/TypeName/fields/name` projects field names
- [ ] `show metadata/TypeName/superType` shows inheritance

### 5.3 Metadata Edge Cases
- [ ] Non-existent type name
- [ ] Invalid metadata paths
- [ ] Tree view with depth=0
- [ ] Tree view with very large depth

---

## 6. Output Format Tests

### 6.1 Table Format (Default)
- [ ] Default table output for events
- [ ] Table with wide columns
- [ ] Table with many rows
- [ ] Table with nested structures
- [ ] Table with null values
- [ ] Table with Unicode characters

### 6.2 JSON Format
- [ ] `--format json` produces valid JSON
- [ ] JSON output for single event
- [ ] JSON output for arrays
- [ ] JSON output for nested objects
- [ ] JSON escaping of special characters
- [ ] JSON with null values

### 6.3 Tree Format
- [ ] `--tree` for nested data
- [ ] `--tree --depth N` limits expansion
- [ ] Tree view for arrays
- [ ] Tree view for complex nested structures

### 6.4 Output Limits
- [ ] `--limit N` restricts output rows
- [ ] `--limit 0` (edge case)
- [ ] `--limit` larger than result set
- [ ] Output pagination for very large results

---

## 7. Non-Interactive Mode Tests

### 7.1 Command-Line Execution
- [ ] `jfr-shell show <file> "<query>"`
- [ ] Single query with table output
- [ ] Single query with JSON output
- [ ] Query with filters and aggregations
- [ ] Exit codes on success
- [ ] Exit codes on error

### 7.2 Metadata in Non-Interactive Mode
- [ ] `jfr-shell metadata <file> --events-only`
- [ ] `jfr-shell metadata <file> --search "pattern"`

### 7.3 Chunk Queries
- [ ] `jfr-shell chunks <file>`
- [ ] `jfr-shell chunks <file> --summary`

---

## 8. Tab Completion Tests

### 8.1 Command Completion
- [ ] Complete command names
- [ ] Complete `show` command
- [ ] Complete root types: `events/`, `metadata/`, etc.

### 8.2 Event Type Completion
- [ ] Complete event type names after `events/`
- [ ] Complete partial event type names
- [ ] Complete with prefix filtering (e.g., `jdk.Exe<TAB>`)

### 8.3 Field Path Completion
- [ ] Complete field names after `/`
- [ ] Complete nested field paths
- [ ] Complete array access syntax

### 8.4 Filter Completion
- [ ] Complete field names inside `[...]`
- [ ] Complete operators after field names
- [ ] Complete logical operators (`&&`, `||`)
- [ ] Complete filter functions

### 8.5 Pipeline Completion
- [ ] Complete aggregation functions after `|`
- [ ] Complete function parameters

### 8.6 Option Completion
- [ ] Complete `--limit`, `--format`, etc.
- [ ] Complete option values

---

## 9. Scripting Tests

### 9.1 Basic Script Execution
- [ ] Execute script with comments
- [ ] Execute script with blank lines
- [ ] Execute multi-line script
- [ ] Script from file with `.jfrs` extension
- [ ] Script from stdin with `-`

### 9.2 Positional Parameters
- [ ] `$1`, `$2`, `$3` substitution
- [ ] `$@` expands to all parameters
- [ ] Parameters in filters: `[bytes>=$2]`
- [ ] Parameters in limits: `--limit $3`
- [ ] Out-of-bounds parameter error
- [ ] Missing required parameter error

### 9.3 Variables - Scalar
- [ ] `set name = "value"` creates scalar variable
- [ ] `set num = 123` creates numeric variable
- [ ] `${varname}` substitution
- [ ] Variable in echo: `echo "${varname}"`
- [ ] Variable in query: `[bytes>=${threshold}]`
- [ ] Spaces around `=` in set command

### 9.4 Variables - Lazy Queries
- [ ] `set var = events/...` creates lazy query variable
- [ ] Lazy variable evaluated on first access
- [ ] Lazy variable result caching
- [ ] `${var.field}` accesses result fields
- [ ] `${var[0]}` accesses array elements
- [ ] `${var.size}` gets result size
- [ ] Nested field access: `${var.field.subfield}`

### 9.5 Variables - Maps
- [ ] `set map = {"key": "value"}` creates map
- [ ] Map with nested structure: `{"db": {"host": "localhost"}}`
- [ ] `${map.key}` accesses map fields
- [ ] `${map.nested.field}` accesses nested fields
- [ ] `${map.size}` gets map entry count
- [ ] Map with numbers, booleans, null

### 9.6 Variable Scopes
- [ ] Session-scoped variables (default)
- [ ] Global variables with `--global`
- [ ] Session variables cleared on session close
- [ ] Global variables persist across sessions

### 9.7 Variable Management
- [ ] `vars` lists all variables
- [ ] `vars --session` lists session variables only
- [ ] `vars --global` lists global variables only
- [ ] `vars --info <name>` shows detailed info
- [ ] `unset <name>` removes variable
- [ ] `unset --global <name>` removes global variable
- [ ] `invalidate <name>` clears lazy cache

### 9.8 Echo Command
- [ ] `echo "text"` outputs text
- [ ] Echo with variable substitution
- [ ] Echo with multiple variables
- [ ] Echo with special characters

---

## 10. Conditional Tests

### 10.1 Basic Conditionals
- [ ] `if`/`endif` block execution
- [ ] `if`/`else`/`endif` block execution
- [ ] `if`/`elif`/`else`/`endif` block execution
- [ ] Multiple `elif` branches
- [ ] Nested conditionals

### 10.2 Condition Expressions
- [ ] Equality: `if ${a} == ${b}`
- [ ] Inequality: `if ${a} != ${b}`
- [ ] Greater than: `if ${a} > 100`
- [ ] Greater than or equal: `if ${a} >= 100`
- [ ] Less than: `if ${a} < 100`
- [ ] Less than or equal: `if ${a} <= 100`

### 10.3 Logical Operators
- [ ] AND: `if ${a} > 0 && ${b} > 0`
- [ ] OR: `if ${a} == 0 || ${b} == 0`
- [ ] NOT: `if !${flag}`
- [ ] Parenthesized expressions: `if (${a} && ${b}) || ${c}`

### 10.4 Arithmetic in Conditions
- [ ] Addition: `if ${a} + ${b} > 100`
- [ ] Multiplication: `if ${a} * 2 > 100`
- [ ] Division: `if ${total} / ${count} > 50`

### 10.5 Built-in Functions
- [ ] `exists(varname)` checks variable existence
- [ ] `empty(varname)` checks for empty/null
- [ ] `!exists(varname)` negation
- [ ] `!empty(varname)` negation

### 10.6 Conditional Error Cases
- [ ] Unclosed conditional at EOF
- [ ] `elif` without `if`
- [ ] `else` without `if`
- [ ] `endif` without `if`
- [ ] Invalid condition syntax
- [ ] Type mismatch in comparisons

### 10.7 Interactive Conditional Prompts
- [ ] Prompt changes with nesting depth: `...(1)>`
- [ ] Nested prompts: `...(2)>`, `...(3)>`, etc.
- [ ] Exit with unclosed conditionals shows warning

---

## 11. Script Management Tests

### 11.1 Scripts Directory
- [ ] Scripts stored in `~/.jfr-shell/scripts`
- [ ] `script list` shows available scripts
- [ ] Script descriptions from first comment line
- [ ] `script run <name>` executes by name
- [ ] Script name without `.jfrs` extension

### 11.2 Script Execution Modes
- [ ] Execute by absolute path
- [ ] Execute by relative path
- [ ] Execute from stdin
- [ ] Execute with `--continue-on-error`
- [ ] Default fail-fast on error

### 11.3 Error Reporting
- [ ] Line number in error messages
- [ ] Command shown in error messages
- [ ] Summary of errors at end
- [ ] Executed/total command count

---

## 12. Recording Tests

### 12.1 Recording Commands
- [ ] `record start` with default path
- [ ] `record start /path/to/file.jfrs` with custom path
- [ ] `record status` shows current recording
- [ ] `record status` when not recording
- [ ] `record stop` saves recording
- [ ] Auto-save on shell exit

### 12.2 Recorded Script Format
- [ ] Timestamp comments included
- [ ] Commands recorded correctly
- [ ] Executable format (can re-run)
- [ ] Comments for each command

---

## 13. Shebang Tests

### 13.1 Shebang Execution
- [ ] Script with `#!/usr/bin/env -S jbang jfr-shell@btraceio script -`
- [ ] Execute directly: `./script.jfrs`
- [ ] Parameters passed to script
- [ ] Multiple parameters

### 13.2 Shebang Error Cases
- [ ] JBang not installed
- [ ] Script not executable
- [ ] Invalid shebang syntax

---

## 14. Help System Tests

### 14.1 General Help
- [ ] `help` shows command list
- [ ] `help <command>` shows command-specific help
- [ ] `help show`, `help metadata`, etc.
- [ ] `help jfrpath` shows JfrPath syntax

### 14.2 Help Edge Cases
- [ ] `help` for non-existent command
- [ ] Help text formatting
- [ ] Help with very long descriptions

---

## 15. Integration Tests

### 15.1 Real-World Use Cases
- [ ] CPU profiling workflow
- [ ] Memory leak detection workflow
- [ ] GC analysis workflow
- [ ] I/O performance analysis workflow
- [ ] Thread contention analysis workflow
- [ ] Exception analysis workflow

### 15.2 Example Scripts
- [ ] `basic-analysis.jfrs` executes successfully
- [ ] `thread-profiling.jfrs` executes successfully
- [ ] `gc-analysis.jfrs` executes successfully

### 15.3 Multi-File Analysis
- [ ] Compare two recordings
- [ ] Aggregate data from multiple recordings
- [ ] Session switching during analysis

---

## 16. Fuzzing Tests - Query Syntax

### 16.1 Malformed Queries
- [ ] Missing closing bracket: `events/jdk.FileRead[bytes>1000`
- [ ] Extra closing bracket: `events/jdk.FileRead[bytes>1000]]`
- [ ] Mismatched brackets: `events/jdk.FileRead]bytes>1000[`
- [ ] Empty query string
- [ ] Query with only whitespace
- [ ] Query with null bytes
- [ ] Query with control characters

### 16.2 Invalid Paths
- [ ] Path with consecutive slashes: `events//jdk.FileRead`
- [ ] Path ending with slash: `events/jdk.FileRead/`
- [ ] Path with backslashes: `events\jdk.FileRead`
- [ ] Path with special characters: `events/jdk.File$Read`
- [ ] Path with Unicode: `events/jdk.文件读取`
- [ ] Very long path (> 1000 characters)
- [ ] Circular path references

### 16.3 Filter Fuzzing
- [ ] Filter with unmatched quotes: `[path="value]`
- [ ] Filter with mixed quotes: `[path="value']`
- [ ] Filter with escape sequences: `[path="\\n\\t\\r"]`
- [ ] Filter with SQL injection patterns: `[path="'; DROP TABLE--"]`
- [ ] Filter with regex DoS patterns: `[path~"(a+)+b"]`
- [ ] Filter with very long regex (> 10000 chars)
- [ ] Filter with null byte in value
- [ ] Filter with Unicode normalization issues

### 16.4 Operator Fuzzing
- [ ] Invalid operators: `[field <> value]`
- [ ] Double operators: `[field >> 100]`
- [ ] Operator without operands: `[>]`
- [ ] Operator with wrong types: `[stringField > 100]`
- [ ] Division by zero in filters
- [ ] Integer overflow in numeric comparisons

### 16.5 Function Fuzzing
- [ ] Function with missing parentheses: `len`
- [ ] Function with missing arguments: `startsWith()`
- [ ] Function with too many arguments: `exists(a, b, c)`
- [ ] Function with wrong argument types
- [ ] Nested function calls: `len(exists(field))`
- [ ] Unknown function names

---

## 17. Fuzzing Tests - Variable Substitution

### 17.1 Variable Name Fuzzing
- [ ] Variable with special characters: `${va$r}`
- [ ] Variable with spaces: `${my var}`
- [ ] Variable with Unicode: `${变量}`
- [ ] Variable with numbers only: `${123}`
- [ ] Variable starting with number: `${1var}`
- [ ] Variable with very long name (> 1000 chars)
- [ ] Empty variable name: `${}`

### 17.2 Variable Value Fuzzing
- [ ] Variable with null value
- [ ] Variable with very large string (> 1MB)
- [ ] Variable with binary data
- [ ] Variable with nested substitutions: `${var${other}}`
- [ ] Variable with circular references
- [ ] Variable with special JSON characters in map

### 17.3 Substitution Syntax Fuzzing
- [ ] Unclosed substitution: `${var`
- [ ] Extra closing brace: `${var}}`
- [ ] Nested braces: `${{var}}`
- [ ] Multiple substitutions in one string
- [ ] Substitution in different contexts (filters, paths, etc.)

---

## 18. Fuzzing Tests - Script Parameters

### 18.1 Parameter Fuzzing
- [ ] Very large number of parameters (> 100)
- [ ] Parameters with whitespace: `script.jfrs "param with spaces"`
- [ ] Parameters with quotes: `script.jfrs "param\"with\"quotes"`
- [ ] Parameters with escape sequences
- [ ] Parameters with null bytes
- [ ] Parameters with Unicode
- [ ] Empty parameters: `script.jfrs ""`
- [ ] Parameters with special shell characters: `$`, `;`, `|`, `&`

### 18.2 $@ Expansion Fuzzing
- [ ] `$@` with no parameters
- [ ] `$@` with one parameter
- [ ] `$@` with many parameters
- [ ] `$@` in different contexts

---

## 19. Fuzzing Tests - Conditionals

### 19.1 Condition Expression Fuzzing
- [ ] Very long condition expressions (> 1000 chars)
- [ ] Deeply nested parentheses (> 50 levels)
- [ ] Mismatched parentheses
- [ ] Empty conditions: `if`
- [ ] Condition with only operators: `if && ||`
- [ ] Arithmetic overflow in conditions
- [ ] Division by zero in conditions

### 19.2 Conditional Block Fuzzing
- [ ] Very deep nesting (> 50 levels)
- [ ] Many elif branches (> 100)
- [ ] Mixed if/elif/else ordering errors
- [ ] Conditional blocks in different contexts

---

## 20. Fuzzing Tests - File Paths

### 20.1 Path Fuzzing
- [ ] Non-existent file paths
- [ ] Paths with special characters: `!@#$%^&*()`
- [ ] Paths with Unicode characters
- [ ] Paths with null bytes
- [ ] Relative paths: `../../../etc/passwd`
- [ ] Very long paths (> 4096 chars)
- [ ] Paths with trailing/leading whitespace
- [ ] Paths with repeated slashes: `///tmp///file.jfr`
- [ ] Paths with dots: `./file.jfr`, `../file.jfr`

### 20.2 File Content Fuzzing
- [ ] Empty files
- [ ] Binary files (not JFR)
- [ ] Truncated JFR files
- [ ] Corrupted JFR headers
- [ ] Very large files (> 10GB)
- [ ] Files with permission errors
- [ ] Symlinks to non-existent files
- [ ] Directories instead of files

---

## 21. Fuzzing Tests - Numeric Inputs

### 21.1 Integer Fuzzing
- [ ] Negative numbers where positive expected
- [ ] Zero where non-zero expected
- [ ] Very large numbers (> Integer.MAX_VALUE)
- [ ] Very small numbers (< Integer.MIN_VALUE)
- [ ] Floating-point numbers where integers expected
- [ ] Non-numeric strings where numbers expected
- [ ] Scientific notation: `1e10`

### 21.2 Limit Fuzzing
- [ ] `--limit -1`
- [ ] `--limit 0`
- [ ] `--limit` with very large value (> 1 billion)
- [ ] `--limit` with non-numeric value
- [ ] `--limit` with floating-point value

---

## 22. Fuzzing Tests - String Inputs

### 22.1 String Length Fuzzing
- [ ] Empty strings
- [ ] Very long strings (> 1MB)
- [ ] Strings with only whitespace
- [ ] Strings with null bytes
- [ ] Strings with control characters

### 22.2 String Content Fuzzing
- [ ] Strings with SQL injection patterns
- [ ] Strings with command injection patterns
- [ ] Strings with path traversal: `../../`
- [ ] Strings with format string attacks: `%s%s%s`
- [ ] Strings with Unicode normalization issues
- [ ] Strings with emoji and special Unicode
- [ ] Strings with right-to-left override characters
- [ ] Strings with homoglyphs

---

## 23. Fuzzing Tests - Memory and Performance

### 23.1 Memory Stress
- [ ] Open very large JFR file (> 10GB)
- [ ] Query returning millions of results without limit
- [ ] Very large groupBy result sets
- [ ] Decoration with millions of decorator events
- [ ] Very large variable values (> 1GB)
- [ ] Many concurrent sessions (> 100)

### 23.2 CPU Stress
- [ ] Complex regex in filters on large datasets
- [ ] Deeply nested aggregations
- [ ] Very large top-N queries
- [ ] Recursive decoration attempts
- [ ] Infinite loop conditions in scripts

### 23.3 Resource Exhaustion
- [ ] File descriptor exhaustion (open many sessions)
- [ ] Thread exhaustion
- [ ] Stack overflow with deep nesting
- [ ] Heap exhaustion with large result sets

---

## 24. Fuzzing Tests - Concurrency

### 24.1 Multi-Session Concurrency
- [ ] Rapidly switch between sessions
- [ ] Close session while query running
- [ ] Open same file multiple times concurrently
- [ ] Modify recording file while open

### 24.2 Command Interleaving
- [ ] Start recording, then execute queries
- [ ] Set variables during query execution
- [ ] Close session during variable evaluation

---

## 25. Fuzzing Tests - Encoding and Charset

### 25.1 Character Encoding
- [ ] UTF-8 input
- [ ] UTF-16 input
- [ ] Latin-1 input
- [ ] Mixed encoding in same file
- [ ] Invalid UTF-8 sequences
- [ ] Byte Order Mark (BOM) handling

### 25.2 Locale-Specific Issues
- [ ] Turkish locale (i/İ issues)
- [ ] Date/time formatting in different locales
- [ ] Number formatting in different locales
- [ ] Collation differences

---

## 26. Error Recovery Tests

### 26.1 Graceful Degradation
- [ ] Continue operation after non-fatal errors
- [ ] Recover from query syntax errors
- [ ] Handle missing event types gracefully
- [ ] Recover from decorator errors

### 26.2 Error Messages
- [ ] Error messages are clear and actionable
- [ ] Error messages include context (line numbers, etc.)
- [ ] Error messages don't expose sensitive data
- [ ] Stack traces only in debug mode

---

## 27. Regression Tests

### 27.1 Known Issues
- [ ] Previously fixed bugs don't reoccur
- [ ] Performance regressions detected
- [ ] Memory leak detection

### 27.2 Backward Compatibility
- [ ] Old scripts still work
- [ ] Old JFR files parseable
- [ ] API compatibility maintained

---

## Test Execution Strategy

### Priority Levels
- **P0 (Critical)**: Basic functionality, session management, core query syntax
- **P1 (High)**: Aggregations, decorations, scripting, variables
- **P2 (Medium)**: Advanced features, edge cases, conditionals
- **P3 (Low)**: Fuzzing, extreme edge cases, performance stress

### Automation
- Unit tests for individual components
- Integration tests for workflows
- Fuzzing framework for randomized testing
- Performance benchmarks for regression detection

### Coverage Goals
- Line coverage: > 80%
- Branch coverage: > 70%
- All documented examples tested
- All error paths tested

### Test Data
- Small JFR files (< 1MB) for quick tests
- Medium JFR files (10-100MB) for realistic tests
- Large JFR files (> 1GB) for stress tests
- Synthetic JFR files with edge cases
- Real production JFR files (anonymized)

---

## Implementation Notes

1. **Fuzzing Framework**: Use property-based testing (e.g., jqwik) for systematic fuzzing
2. **Mock Recordings**: Generate JFR files with specific patterns for targeted tests
3. **Performance Baselines**: Establish performance baselines for regression detection
4. **Test Isolation**: Each test should clean up sessions and variables
5. **Parallel Execution**: Tests should be parallelizable where possible
6. **CI Integration**: All tests run in CI/CD pipeline
7. **Nightly Fuzzing**: Extended fuzzing runs overnight with random seeds
8. **Coverage Tracking**: Track coverage trends over time
