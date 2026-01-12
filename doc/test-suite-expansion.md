# LLM Prompt Tuning Test Suite Expansion

## Date: 2026-01-12

## Overview

Expanded the LLM prompt tuning test suite from 10 to 20 test cases to provide more comprehensive coverage of JFR event types and query patterns.

## Summary of Changes

- **Version**: 1.0 → 2.0
- **Test Cases**: 10 → 20 (100% increase)
- **New Categories**: Network I/O, Class Loading, Exceptions, Compilation
- **Difficulty Distribution**:
  - SIMPLE: 6 → 11 (+5)
  - MEDIUM: 4 → 8 (+4)
  - COMPLEX: 0 → 1 (+1)

## New Test Cases Added

### Network I/O (3 tests)

#### 1. network-reads
- **Query**: "show network read events"
- **Expected**: `events/jdk.SocketRead`
- **Category**: network
- **Difficulty**: SIMPLE
- **Purpose**: Basic event selection for network events

#### 2. network-large-reads
- **Query**: "network reads larger than 1KB"
- **Expected**: `events/jdk.SocketRead[bytesRead>1024]`
- **Category**: network
- **Difficulty**: SIMPLE
- **Purpose**: Filtering network events by bytes read

#### 3. socket-address-groupby
- **Query**: "network traffic by remote address"
- **Expected**: `events/jdk.SocketRead | groupBy(address, agg=sum, value=bytesRead)`
- **Category**: network
- **Difficulty**: MEDIUM
- **Purpose**: Aggregating network traffic by remote address

### Class Loading (1 test)

#### 4. class-loading-top
- **Query**: "which classes took longest to load"
- **Expected**: `events/jdk.ClassLoad | groupBy(loadedClass/name, agg=max, value=duration) | top(10, by=max)`
- **Category**: classloading
- **Difficulty**: MEDIUM
- **Purpose**: Finding slowest class loading operations with max aggregation

### Exceptions (1 test)

#### 5. exception-count
- **Query**: "count exceptions by type"
- **Expected**: `events/jdk.JavaExceptionThrow | groupBy(thrownClass/name)`
- **Category**: exceptions
- **Difficulty**: SIMPLE
- **Purpose**: Grouping exceptions by exception class

### Threading (1 test)

#### 6. thread-park-duration
- **Query**: "threads with longest park times"
- **Expected**: `events/jdk.ThreadPark | groupBy(eventThread/javaName, agg=sum, value=duration) | top(10, by=sum)`
- **Category**: threading
- **Difficulty**: MEDIUM
- **Purpose**: Analyzing thread parking patterns with duration aggregation

### I/O (1 test)

#### 7. file-write-large
- **Query**: "file writes over 5MB"
- **Expected**: `events/jdk.FileWrite[bytesWritten>5242880]`
- **Category**: io
- **Difficulty**: SIMPLE
- **Purpose**: Filtering file write events by size

### Compilation (1 test)

#### 8. compilation-slow
- **Query**: "slow compilations over 1 second"
- **Expected**: `events/jdk.Compilation[duration>1000000000]`
- **Category**: compilation
- **Difficulty**: MEDIUM
- **Purpose**: Finding long-running JIT compilation operations

### Complex Queries (1 test)

#### 9. multiple-filters-complex
- **Query**: "GC events in young generation longer than 50ms"
- **Expected**: `events/jdk.GarbageCollection[name=~".*Young.*"][duration>50000000]`
- **Category**: gc
- **Difficulty**: COMPLEX
- **Purpose**: Combining regex filter with numeric filter

### Memory (1 test)

#### 10. allocation-size-stats
- **Query**: "statistics on object allocation sizes"
- **Expected**: `events/jdk.ObjectAllocationSample | stats(weight)`
- **Category**: memory
- **Difficulty**: SIMPLE
- **Purpose**: Computing statistics on allocation weights

---

## Coverage Analysis

### By Category

| Category | Test Count | Percentage |
|----------|-----------|------------|
| CPU | 2 | 10% |
| Memory | 3 | 15% |
| GC | 4 | 20% |
| I/O | 3 | 15% |
| Network | 3 | 15% |
| Threading | 2 | 10% |
| Class Loading | 1 | 5% |
| Exceptions | 1 | 5% |
| Compilation | 1 | 5% |

### By Difficulty

| Difficulty | Test Count | Percentage |
|-----------|-----------|------------|
| SIMPLE | 11 | 55% |
| MEDIUM | 8 | 40% |
| COMPLEX | 1 | 5% |

### By Query Pattern

| Pattern | Test Count | Examples |
|---------|-----------|----------|
| Simple event selection | 2 | `events/jdk.SocketRead` |
| Filtering with single condition | 6 | `events/jdk.FileRead[bytes>1048576]` |
| Filtering with multiple conditions | 1 | `events/jdk.GarbageCollection[name=~".*Young.*"][duration>50000000]` |
| GroupBy without aggregation | 2 | `events/jdk.JavaExceptionThrow \| groupBy(thrownClass/name)` |
| GroupBy with aggregation | 5 | `events/jdk.ObjectAllocationSample \| groupBy(objectClass/name, agg=sum, value=weight)` |
| GroupBy + top | 5 | `... \| groupBy(...) \| top(10, by=sum)` |
| Stats | 2 | `events/jdk.GarbageCollection \| stats(duration)` |
| Count | 1 | `events/jdk.GarbageCollection \| count()` |
| Projection + GroupBy + top | 1 | `events/jdk.ExecutionSample/stackTrace/frames/0/method/type/name \| groupBy(value) \| top(5, by=count)` |

---

## Rationale for New Test Cases

### 1. Network I/O Coverage
Added 3 network tests to cover:
- Basic socket event selection
- Byte-based filtering (similar to file I/O patterns)
- Aggregation by network address (common use case)

**Why important**: Network I/O is a common area for performance analysis, and the LLM needs to distinguish between `jdk.SocketRead`/`jdk.SocketWrite` and file I/O events.

### 2. Class Loading
Added 1 test for slow class loading detection.

**Why important**:
- Tests `jdk.ClassLoad` event type
- Uses `max` aggregation (not just `sum` or `count`)
- Class loading performance is critical for application startup analysis

### 3. Exception Tracking
Added 1 test for exception counting by type.

**Why important**:
- Exception analysis is a common JFR use case
- Tests `jdk.JavaExceptionThrow` event type
- Validates grouping by exception class field paths

### 4. Thread Parking
Added 1 test for thread parking analysis.

**Why important**:
- `jdk.ThreadPark` is different from `jdk.JavaMonitorEnter`
- Tests LLM's ability to distinguish between different threading events
- Duration aggregation on parking events is a common pattern

### 5. File Writes
Added 1 test for large file writes.

**Why important**:
- Complements existing file read tests
- Tests `jdk.FileWrite` vs `jdk.FileRead` distinction
- Uses `bytesWritten` field (different from `bytes` in FileRead)

### 6. Compilation Events
Added 1 test for slow JIT compilations.

**Why important**:
- `jdk.Compilation` is important for JIT performance analysis
- Tests duration filtering (1 second = 1,000,000,000 nanoseconds)
- Validates correct unit conversion understanding

### 7. Complex Filtering
Added 1 COMPLEX test with multiple filters including regex.

**Why important**:
- Tests advanced query patterns (regex + numeric filter)
- First COMPLEX difficulty test case
- Validates handling of multiple filter conditions
- Tests regex pattern syntax (`name=~".*Young.*"`)

### 8. Statistics Operations
Added 1 test for allocation size statistics.

**Why important**:
- Tests `stats()` operator on different field (`weight` vs `duration`)
- Complements existing GC stats test
- Validates stats on memory-related metrics

---

## Key Learning Objectives

The expanded test suite now covers:

1. **Event Type Variety**: 15+ different JFR event types
2. **Filter Patterns**: Simple, byte-based, duration-based, regex
3. **Aggregation Functions**: sum, max, count, stats
4. **Complex Queries**: Multiple filters, regex patterns
5. **Field Path Diversity**: Class names, thread names, addresses, durations, byte counts

---

## Expected Impact on Prompt Tuning

### Baseline Expectations
- **Current Success Rate**: 100% on 10 tests (with targeted examples)
- **Expected on 20 tests**: 80-90% initially

### Areas Likely to Need Improvement
1. **Network events**: May confuse socket fields with file I/O fields
2. **Regex syntax**: Complex filter syntax may cause issues
3. **Max aggregation**: LLM primarily trained on sum/count patterns
4. **Field name variations**: `bytesWritten` vs `bytes`, `thrownClass` vs `objectClass`

### Iteration Strategy
1. Run baseline tests on expanded suite
2. Identify new failure patterns
3. Add targeted examples for failing patterns
4. Re-run and validate improvements
5. Document success rate progression

---

## Running Tests

### Execute Prompt Tuning on Expanded Suite
```bash
./gradlew :jfr-shell:tunePrompts
```

### View Results
```bash
cat build/reports/prompt-tuning/tuning-report.md
```

### Expected Output
```
Loaded 20 test cases
Testing variant: baseline
Success Rate: 80-90% (16-18/20)
...
```

---

## Next Steps

1. ✅ Test suite expanded (10 → 20 tests)
2. Run baseline tuning on expanded suite
3. Analyze new failure patterns
4. Create new prompt variants to address failures
5. Iterate until 90%+ success rate achieved
6. Update integration test suite with key new test cases
7. Document findings and update production prompts

---

## Files Modified

- `jfr-shell/src/test/resources/llm-tuning/test-suite.json` - Added 10 new test cases, version 1.0 → 2.0

## Related Documentation

- [LLM Prompt Tuning Summary](llm-prompt-tuning-summary.md) - Original tuning results
- [LLM Integration Test Results](llm-integration-test-results.md) - Integration test validation
