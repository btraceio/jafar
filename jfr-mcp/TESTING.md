# JFR-MCP Testing Guide

## Running Tests

### Fast Unit Tests (Default)
```bash
./gradlew :jfr-mcp:test
```
**Results**: 15 tests (HandlerLogicTest only), ~0.4 seconds, 21% code coverage

### Integration Tests (Large JFR Files)
```bash
./gradlew :jfr-mcp:test -DenableIntegrationTests=true
```
**Note**: Requires real 171MB JFR file at `demo/src/test/resources/test-ap.jfr`
**Results**: 97+ tests, several seconds, requires 2GB+ heap
**Warning**: These tests are disabled by default to avoid CI failures

### Coverage Report
```bash
./gradlew :jfr-mcp:test :jfr-mcp:jacocoTestReport
open jfr-mcp/build/reports/jacoco/test/html/index.html
```

## Test Structure

### Unit Tests (Fast)
- **HandlerLogicTest**: 15 tests using synthetic JFR files
- **Coverage**: Infrastructure (sessions, queries, API validation)
- **JFR Files**: Created by `SimpleJfrFileBuilder` (~10KB each)
- **Execution Time**: < 1 second

### Integration Tests (Slow)
- **HandlerLogicIntegrationTest**: Tests using real 171MB JFR files
- **Coverage**: Deep processing (flamegraph, callgraph, hotmethods)
- **Execution Time**: Several seconds
- **Status**: Some tests fail due to missing data in test files

## Outstanding Issues

### 1. Integration Tests Disabled by Default
**Status**: RESOLVED - Integration tests now require explicit flag
- **Tests**: JafarMcpServer*Test and HandlerLogicIntegrationTest classes (97+ tests)
- **Reason**: These tests load 171MB JFR files and cause OOM in CI
- **Solution**: Annotated with `@EnabledIfSystemProperty(named = "enableIntegrationTests", matches = "true")`
- **To run**: Use `-DenableIntegrationTests=true` flag

### 2. Coverage Below Target
**Current**: 21% instruction, 15% branch coverage
**Goal**: 80% instruction and branch coverage
**Gap**: ~6,000 instructions uncovered (59% shortfall)

**Root Cause**: Processing logic requires real event data with stack traces
- Flamegraph tree building
- Callgraph construction
- Exception analysis
- USE/TSA methods
- Hotmethods analysis

**Synthetic files cannot provide**:
- Realistic stack traces
- Thread states
- Event timing relationships
- Complex event hierarchies

### 3. Integration Test Failures
**Issue**: `HandlerLogicIntegrationTest.exceptionsGroupsByType()` fails
- **Cause**: Test JFR file doesn't contain exception events
- **Fix**: Either skip test or use different JFR file with exceptions

## Recommended Improvements

### Short Term
1. **Fix failing integration test**: Update to skip if no exceptions found
2. **Investigate test discovery**: Debug why JafarMcpServer*Test classes aren't discovered
3. **Document test data**: Clarify what each test JFR file contains

### Long Term - Achieve 80% Coverage
**Approach**: Create small real JFR files for unit testing

**Steps**:
1. Write Java program with diverse operations:
   ```java
   public class JfrTestDataGenerator {
       public static void main(String[] args) {
           // Create diverse stack traces
           recursiveMethod(5);
           exceptionMethod();
           concurrentMethod();
           // ... more operations
       }
   }
   ```

2. Record with JFR profiling:
   ```bash
   java -XX:StartFlightRecording=filename=test-small.jfr,duration=1s \
        -XX:FlightRecorderOptions=stackdepth=64 \
        JfrTestDataGenerator
   ```

3. Extract small recording (~1MB) with real events
4. Add to `src/test/resources/`
5. Update `BaseJfrTest` to use small files
6. Re-run coverage: expect 70-80% in < 5 seconds

**Expected Benefits**:
- Fast execution (< 5 seconds for all tests)
- High coverage (70-80%+)
- Real event data for processing logic
- Maintainable test suite

## Test Configuration

### Gradle Settings
```gradle
test {
    useJUnitPlatform()
    jvmArgs '-Xmx2g', '-Xms512m'  // Sufficient for real JFR files
    finalizedBy jacocoTestReport
}
```

### JUnit 5 Notes
- Avoid `@TempDir` on static fields with `@BeforeAll`
- Use `Files.createTempFile()` with `deleteOnExit()` instead
- Test classes can be package-private (no need for `public`)

## Troubleshooting

### Tests Not Running
**Symptom**: `./gradlew test` shows 0 or 2 tests
**Solutions**:
1. Clean and rebuild: `./gradlew :jfr-mcp:clean :jfr-mcp:test`
2. Check test discovery: `./gradlew :jfr-mcp:test --info`
3. Run specific test: `./gradlew :jfr-mcp:test --tests HandlerLogicTest`

### OutOfMemoryError
**Symptom**: Test process crashes with heap space error
**Solution**: Already fixed - heap increased to 2GB in `build.gradle`

### Compilation Up-to-Date but Tests Missing
**Symptom**: Gradle says UP-TO-DATE but test classes don't exist
**Solution**: Run `./gradlew :jfr-mcp:clean` to clear stale cache

## Test Maintenance

### Adding New Tests
1. For infrastructure tests: Add to `HandlerLogicTest`
2. For processing logic: Add to `HandlerLogicIntegrationTest`
3. Use `SimpleJfrFileBuilder` for synthetic data
4. Use `BaseJfrTest` for real JFR files

### Modifying Synthetic Files
Edit `SimpleJfrFileBuilder.java`:
- Keep files small (< 50KB)
- Avoid JMC Writer implicit fields (stackTrace, eventThread, startTime)
- Use simple builtin types (STRING, LONG, etc.)
- Test with `SimpleJfrFileBuilder.createExecutionSampleFile(10)`
