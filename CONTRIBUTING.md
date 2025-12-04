# Contributing to JAFAR

Thank you for your interest in contributing to JAFAR! This document provides guidelines for contributing to the project.

## Table of Contents
- [Code of Conduct](#code-of-conduct)
- [Getting Started](#getting-started)
- [Development Setup](#development-setup)
- [How to Contribute](#how-to-contribute)
- [Coding Standards](#coding-standards)
- [Testing Guidelines](#testing-guidelines)
- [Pull Request Process](#pull-request-process)
- [Issue Reporting](#issue-reporting)

## Code of Conduct

We are committed to providing a welcoming and inclusive environment for all contributors. Please be respectful and professional in all interactions.

## Getting Started

### Prerequisites

- **Java 21+**: Required for building and testing
- **Git LFS**: Required for test resources (install from [GitHub docs](https://docs.github.com/en/repositories/working-with-files/managing-large-files/installing-git-large-file-storage))
- **Gradle**: Included via wrapper (`./gradlew`)

### First-Time Setup

1. **Fork and clone the repository**:
   ```bash
   git clone https://github.com/YOUR_USERNAME/jafar.git
   cd jafar
   ```

2. **Configure Git hooks** (for automatic code formatting):
   ```bash
   git config core.hooksPath .githooks
   ```

3. **Fetch test resources**:
   ```bash
   ./get_resources.sh
   ```

4. **Build the project**:
   ```bash
   ./gradlew build
   ```

5. **Run tests to verify setup**:
   ```bash
   ./gradlew test
   ```

## Development Setup

### Project Structure

- **`parser/`**: Core JFR parser library (Java)
- **`tools/`**: Utilities built on the parser (e.g., Scrubber)
- **`demo/`**: Sample CLI application
- **`jafar-gradle-plugin/`**: Gradle plugin for generating typed interfaces
- **`benchmarks/`**: JMH performance benchmarks
- **`examples/`**: Example projects using JAFAR

### Common Development Commands

```bash
# Compile all modules
./gradlew build

# Run all tests
./gradlew test

# Run specific test class
./gradlew :parser:test --tests TypedJafarParserTest

# Create fat JARs
./gradlew shadowJar

# Run the demo application
./gradlew :demo:run --args="jafar /path/to/file.jfr"

# Run benchmarks
./gradlew :benchmarks:jmh

# Format code (automatic via git hook, or manual)
./gradlew spotlessApply

# Check code formatting
./gradlew spotlessCheck

# Publish to local Maven repository
./gradlew publishToMavenLocal
```

## How to Contribute

### Ways to Contribute

1. **Report bugs**: File issues with reproduction steps
2. **Suggest features**: Open issues with use cases and rationale
3. **Improve documentation**: Fix typos, add examples, clarify explanations
4. **Write tests**: Add test coverage for edge cases
5. **Fix bugs**: Submit PRs for reported issues
6. **Implement features**: Work on planned features (check issues labeled `help wanted`)
7. **Optimize performance**: Profile and improve parser performance

### Finding Work

- Check issues labeled [`good first issue`](https://github.com/jbachorik/jafar/labels/good%20first%20issue) for beginner-friendly tasks
- Look for [`help wanted`](https://github.com/jbachorik/jafar/labels/help%20wanted) issues
- Review the [LIMITATIONS.md](LIMITATIONS.md) for areas needing improvement

## Coding Standards

### Code Style

- **Language**: Java 21 (parser/tools/demo), Groovy (Gradle plugin)
- **Indentation**: 4 spaces (no tabs)
- **Line length**: Aim for 120 characters max
- **Naming conventions**:
  - Classes: `PascalCase`
  - Methods/fields: `camelCase`
  - Constants: `UPPER_SNAKE_CASE`
  - Packages: `io.jafar.*`

### Code Formatting

JAFAR uses [Spotless](https://github.com/diffplug/spotless) for automatic code formatting.

- **Automatic**: Git pre-commit hook runs `spotlessApply` and restages changes
- **Manual**: Run `./gradlew spotlessApply` before committing
- **CI check**: PRs must pass `spotlessCheck`

### API Design Principles

1. **Keep public API minimal**: Only expose what users need
2. **Prefer package-private**: Use package-private for internal classes
3. **Immutability**: Use `final` where appropriate
4. **Meaningful names**: Clear, descriptive variable and method names
5. **No breaking changes in v0.x**: API can evolve, but avoid gratuitous changes

### Documentation

- **Javadoc**: All public classes and methods must have Javadoc
- **Inline comments**: Explain non-obvious logic, not what the code does
- **Examples**: Include code examples in Javadoc where helpful
- **No hallucinations**: Documentation must match actual implementation

Example:
```java
/**
 * Parses a JFR recording from the specified path.
 *
 * <p>Example usage:
 * <pre>{@code
 * try (TypedJafarParser parser = TypedJafarParser.open("recording.jfr")) {
 *   parser.handle(MyEvent.class, (event, ctl) -> {
 *     System.out.println(event.value());
 *   });
 *   parser.run();
 * }
 * }</pre>
 *
 * @param path the path to the JFR file
 * @return a new parser instance
 * @throws IOException if the file cannot be read
 */
public static TypedJafarParser open(String path) throws IOException {
  // implementation
}
```

## Testing Guidelines

### Test Framework

- **JUnit Jupiter 5**: All new tests should use JUnit 5
- **Mockito**: For mocking when necessary
- **Location**: Tests go in `src/test/java` mirroring package structure

### Test Naming

- Test classes: `*Test.java`
- Test methods: Descriptive names (e.g., `testDualConstantPoolAccess`)

### Test Coverage

- **Unit tests**: Test individual methods and classes
- **Integration tests**: Test end-to-end parsing scenarios
- **Edge cases**: Parameterized tests for boundary conditions
- **Error cases**: Test error handling and exceptions

### Writing Good Tests

1. **Arrange-Act-Assert**: Structure tests clearly
2. **One assertion per test**: Or use subtests
3. **Clear failure messages**: Include context in assertions
4. **Use test data**: Small `.jfr` files in `src/test/resources`
5. **Clean up**: Use try-with-resources for parsers

Example:
```java
@Test
void testParseValidRecording() throws Exception {
  URI uri = getClass().getClassLoader().getResource("test.jfr").toURI();

  try (TypedJafarParser parser = TypedJafarParser.open(new File(uri).getAbsolutePath())) {
    AtomicInteger count = new AtomicInteger(0);

    parser.handle(MyEvent.class, (event, ctl) -> {
      assertNotNull(event, "Event should not be null");
      assertEquals("expected", event.value(), "Event value should match");
      count.incrementAndGet();
    });

    parser.run();

    assertTrue(count.get() > 0, "Should have processed at least one event");
  }
}
```

### Running Tests

```bash
# All tests
./gradlew test

# Specific module
./gradlew :parser:test

# Specific test class
./gradlew :parser:test --tests TypedJafarParserTest

# Specific test method
./gradlew :parser:test --tests TypedJafarParserTest.testDualConstantPoolAccess

# With debug output
./gradlew test --info
```

## Pull Request Process

### Before Submitting

1. **Run tests locally**:
   ```bash
   ./gradlew test shadowJar
   ```

2. **Format code**:
   ```bash
   ./gradlew spotlessApply
   ```

3. **Update documentation**: If you changed public APIs or behavior

4. **Add tests**: New features need test coverage

### PR Guidelines

1. **Create a feature branch**:
   ```bash
   git checkout -b feature/my-feature
   ```

2. **Commit messages**:
   - Use imperative mood: "Add feature" not "Added feature"
   - Be concise but descriptive
   - Reference issues: "Fix parsing bug (#123)"

3. **PR Description**:
   - Describe what changed and why
   - Link to related issues
   - Include before/after examples if relevant
   - Note any breaking changes

4. **PR Template**:
   ```markdown
   ## Summary
   Brief description of changes

   ## Motivation
   Why are these changes needed?

   ## Changes
   - List of specific changes

   ## Testing
   How was this tested?

   ## Checklist
   - [ ] Tests added/updated
   - [ ] Documentation updated
   - [ ] No breaking changes (or documented)
   - [ ] CI passes
   ```

5. **Review process**:
   - Maintainers will review your PR
   - Address feedback promptly
   - Keep PRs focused and reasonably sized
   - Squash commits if requested

### CI Requirements

All PRs must pass:
- ✅ Tests on JDK 8 and 21
- ✅ Code formatting check (`spotlessCheck`)
- ✅ Build succeeds

## Issue Reporting

### Bug Reports

Use the bug report template and include:

1. **JAFAR version**: Which version are you using?
2. **JDK version**: Output of `java -version`
3. **Description**: Clear description of the bug
4. **Reproduction steps**: How to reproduce the issue
5. **Expected behavior**: What should happen
6. **Actual behavior**: What actually happens
7. **Sample JFR file**: If possible, attach a minimal reproducing file
8. **Stack trace**: Full exception trace if applicable

Example:
```markdown
**JAFAR Version**: 0.1.0
**JDK Version**: 21.0.5

**Description**:
Parser throws NullPointerException when parsing recordings with empty constant pools.

**Reproduction Steps**:
1. Create JFR file with empty CP (attached: empty-cp.jfr)
2. Parse with TypedJafarParser
3. Exception thrown

**Expected**: Parser should handle empty CPs gracefully
**Actual**: NPE thrown at ConstantPoolAccessor.java:42

**Stack Trace**:
```
java.lang.NullPointerException: ...
```
```

### Feature Requests

1. **Use case**: Describe the problem you're trying to solve
2. **Proposed solution**: How would you like it to work?
3. **Alternatives**: What alternatives have you considered?
4. **Additional context**: Any relevant examples or references

## Security Issues

**Do not report security vulnerabilities as public issues.**

Please email security concerns to: **jbachorik+jafar-security@gmail.com**

See [SECURITY.md](SECURITY.md) for details.

## Questions?

- **Documentation**: Check [README.md](README.md) and [LIMITATIONS.md](LIMITATIONS.md)
- **Issues**: Search existing issues first
- **Discussions**: For general questions, open a GitHub Discussion

## License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0.

---

Thank you for contributing to JAFAR! 🎉
