# Parser Regex Filter Enhancement - Supporting =~ Operator

## Date: 2026-01-12

## Summary

Enhanced JfrPathParser to support `=~` regex operator syntax in addition to the existing `~` operator, providing better alignment with common programming language conventions.

## Background

The JfrPath query language supports regex matching using the `~` operator:
```
events/jdk.GarbageCollection[name~".*Young.*"]
```

However, many popular languages and tools use `=~` for regex matching:
- Ruby, Perl: `=~` for regex match
- PostgreSQL supports both `~` and `=~`
- Many scripting languages use `=~`

Users familiar with these conventions may naturally try to use `=~` in JfrPath queries.

## Problem

The parser only recognized `~` for regex matching. When users wrote queries with `=~`:

```
events/jdk.GarbageCollection[name=~".*Young.*"]
```

The parser would:
1. Match `=` as an equality operator
2. Consume the `=`, leaving `~".*Young.*"`
3. Fail trying to parse `~` as a literal value

**Result**: Parser error on syntactically intuitive queries

## Solution

Updated `JfrPathParser.java` to support both `=~` and `~` for regex matching.

### Changes Made

**1. Legacy Predicate Parser (Line 178)**
```java
// Before
if (match("~")) op = Op.REGEX;

// After
if (match("=~") || match("~")) op = Op.REGEX;
```

**2. Boolean Expression Parser (Line 254)**
```java
// Before
if (match("~")) op = Op.REGEX;

// After
if (match("=~") || match("~")) op = Op.REGEX;
```

**Key insight**: Check for `=~` BEFORE checking for `=` to avoid partial match.

### Tests Added

Added two parser tests to validate both syntaxes:

**1. `parsesRegexFilterWithEqualsTilde()`**
- Tests: `events/jdk.GarbageCollection[name=~".*Young.*"]`
- Verifies: `=~` operator parsed as `REGEX`
- Validates: Field path, literal value, operator type

**2. `parsesMultipleChainedFilters()`**
- Tests: `events/jdk.GarbageCollection[name=~".*Young.*"][duration>50000000]`
- Verifies: Multiple filters chain correctly
- Validates: Both regex and numeric predicates

## Usage Examples

Both syntaxes are now supported:

**Concise syntax (existing):**
```bash
events/jdk.GarbageCollection[name~".*Young.*"]
metadata/[name~".*ExecutionSample"]
events/jdk.ThreadPark[parkedClass~"java\\.util\\..*"]
```

**Conventional syntax (new):**
```bash
events/jdk.GarbageCollection[name=~".*Young.*"]
metadata/[name=~".*ExecutionSample"]
events/jdk.ThreadPark[parkedClass=~"java\\.util\\..*"]
```

**Chained filters:**
```bash
events/jdk.GarbageCollection[name=~".*Young.*"][duration>50000000]
```

## Files Modified

### 1. JfrPathParser.java (2 lines changed)
**Location**: `jfr-shell/src/main/java/io/jafar/shell/jfrpath/JfrPathParser.java`

**Changes**:
- Line 178: Added `=~` support in legacy predicate parser
- Line 254: Added `=~` support in boolean expression parser

### 2. JfrPathParserTest.java (64 lines added)
**Location**: `jfr-shell/src/test/java/io/jafar/shell/jfrpath/JfrPathParserTest.java`

**New Tests**:
- `parsesRegexFilterWithEqualsTilde()` - Single regex filter validation
- `parsesMultipleChainedFilters()` - Chained filters validation

## Validation

### Parser Unit Tests ✅
```bash
./gradlew :jfr-shell:test --tests JfrPathParserTest
```
**Result**: All tests pass, including new tests for both `~` and `=~` operators

### Manual Query Test ✅
```bash
events/jdk.GarbageCollection[name=~".*Young.*"][duration>50000000]
```
**Result**: Parses correctly, executes successfully

## Benefits

1. ✅ **Better user experience** - Supports familiar regex syntax from other languages
2. ✅ **Parser robustness** - Handles both conventional and concise syntaxes
3. ✅ **Backward compatible** - Existing queries with `~` continue to work
4. ✅ **Reduces confusion** - Users don't need to learn JfrPath-specific syntax

## Impact

- **Backward compatible**: All existing queries continue to work
- **No breaking changes**: Only adds support for alternative syntax
- **Minimal code change**: Two-line parser enhancement
- **Well tested**: New unit tests validate both syntaxes

---

**Summary**: Enhanced parser to accept `=~` regex operator alongside existing `~` operator, improving usability and aligning with common programming conventions.
