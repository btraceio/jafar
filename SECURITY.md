# Security Policy

## Supported Versions

JAFAR is currently in early release (v0.x). Security updates will be provided for the latest release only.

| Version | Supported          |
| ------- | ------------------ |
| 0.1.x   | :white_check_mark: |
| < 0.1   | :x:                |

Once JAFAR reaches v1.0, we will maintain security updates for the current major version and the previous major version for 6 months after the new major version is released.

## Reporting a Vulnerability

**Please do not report security vulnerabilities through public GitHub issues.**

Instead, please report security vulnerabilities by email to:

**jbachorik+jafar-security@gmail.com**

You should receive a response within 48 hours. If for some reason you do not, please follow up via email to ensure we received your original message.

Please include the following information in your report:
- Type of issue (e.g., buffer overflow, injection, denial of service)
- Full paths of source file(s) related to the issue
- Location of the affected source code (tag/branch/commit or direct URL)
- Step-by-step instructions to reproduce the issue
- Proof-of-concept or exploit code (if possible)
- Impact of the issue, including how an attacker might exploit it

This information will help us triage your report more quickly.

## Security Considerations When Using JAFAR

### Parsing Untrusted JFR Files

JAFAR parses binary JFR files, which may be crafted to exploit parsing vulnerabilities. When processing JFR files from untrusted sources:

1. **Validate file origin** - Only parse JFR recordings from trusted sources
2. **Resource limits** - Set appropriate JVM heap limits to prevent memory exhaustion from malformed files
3. **Timeout parsing** - Implement timeouts when parsing large or potentially malicious files
4. **Sandboxing** - Consider running JAFAR in a sandboxed environment when parsing untrusted files

Example of defensive parsing:
```java
ExecutorService executor = Executors.newSingleThreadExecutor();
Future<?> future = executor.submit(() -> {
  try (TypedJafarParser parser = TypedJafarParser.open(untrustedFile)) {
    parser.handle(MyEvent.class, (event, ctl) -> {
      // Process events
    });
    parser.run();
  }
});

try {
  future.get(30, TimeUnit.SECONDS); // 30 second timeout
} catch (TimeoutException e) {
  future.cancel(true);
  // Handle timeout
} finally {
  executor.shutdownNow();
}
```

### Known Security-Relevant Limitations

1. **No input validation** - JAFAR assumes well-formed JFR files. Malformed files may cause crashes or unexpected behavior
2. **Memory exhaustion** - Very large constant pools or deeply nested structures may consume excessive memory
3. **Bytecode generation** - JAFAR generates bytecode at runtime using ASM. Malicious recordings could potentially trigger code generation bugs
4. **Class loading** - Generated classes are loaded into the JVM. Ensure appropriate SecurityManager policies if running in a security-sensitive context

### Sensitive Data in JFR Files

JFR recordings may contain sensitive information:
- Stack traces with method names and line numbers
- Thread names and IDs
- System properties
- Environment variables
- Network addresses
- File paths

**Recommendations**:
1. Use the JAFAR scrubbing tool to redact sensitive fields before sharing recordings
2. Review event data before logging or storing
3. Apply appropriate access controls to JFR files

Example of scrubbing:
```bash
# Redact sensitive fields from a JFR file
java -cp jafar-parser.jar io.jafar.tools.Scrubber \
  --input recording.jfr \
  --output recording-scrubbed.jfr \
  --scrub-field jdk.SystemProperty.value
```

### Dependency Security

JAFAR has minimal dependencies:
- SLF4J (logging)
- FastUtil (collections)
- ASM (bytecode generation)

We monitor these dependencies for known vulnerabilities and will update them in response to security advisories.

To check for known vulnerabilities in JAFAR's dependencies:
```bash
./gradlew dependencyCheckAnalyze
```

## Disclosure Policy

When we receive a security bug report, we will:

1. Confirm the problem and determine affected versions
2. Audit code to find similar problems
3. Prepare fixes for all supported versions
4. Release patched versions as soon as possible

We aim to:
- Acknowledge reports within 48 hours
- Provide an initial assessment within 7 days
- Release fixes within 30 days for critical issues, 90 days for others

We will credit reporters in release notes (unless they prefer to remain anonymous).

## Security Updates

Security updates will be announced via:
- GitHub Security Advisories: https://github.com/jbachorik/jafar/security/advisories
- Release notes in CHANGELOG.md
- Git tags with security fix annotations

Subscribe to GitHub repository releases to receive notifications of security updates.

## Comments on This Policy

If you have suggestions for improving this policy, please submit a pull request or open an issue.
