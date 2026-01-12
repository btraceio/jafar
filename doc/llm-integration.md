# LLM Integration for JFR Shell

## Overview

JFR Shell now supports natural language queries powered by Large Language Models (LLMs). This allows you to ask questions in plain English and have them automatically translated into JfrPath queries.

**Key Features:**
- **Natural Language Queries**: Ask questions like "which threads allocated the most memory?" or "show me file reads over 1MB"
- **Local-First Privacy**: Default configuration uses local Ollama (no data leaves your machine)
- **Cloud Provider Support**: Optional integration with OpenAI or Anthropic (Claude)
- **Conversation History**: Follow-up questions with context awareness
- **Audit Logging**: Transparent logging of all LLM interactions
- **Zero External Dependencies**: Built using only Java 21+ HttpClient

## Quick Start

### 1. Install Ollama (Local LLM)

The easiest way to get started is with Ollama, which runs models locally on your machine:

```bash
# macOS
brew install ollama

# Linux
curl https://ollama.ai/install.sh | sh

# Start the Ollama service
ollama serve
```

### 2. Pull a Model

```bash
# Recommended: Fast and accurate
ollama pull llama3.1:8b

# Alternative: Larger model for complex queries
ollama pull llama3.1:70b
```

### 3. Try It Out

```bash
# Start jfr-shell
./gradlew :jfr-shell:run --console=plain

# Open a JFR recording
jfr> open /path/to/recording.jfr

# Enter chat mode and ask questions
jfr> chat
jafar> which threads allocated the most memory?
Generated query: events/jdk.ObjectAllocationSample | groupBy(eventThread/javaName, agg=sum, value=bytes) | top(10, by=sum)
Explanation: Groups allocation events by thread and sums the bytes allocated, then shows the top 10 threads

[Results displayed in table format]

jafar> /exit
jfr>
```

## Configuration

Configuration is stored in `~/.jfr-shell/llm-config.properties`.

### Default Configuration (Local Ollama)

```properties
provider=LOCAL
endpoint=http://localhost:11434
model=llama3.1:8b
timeoutSeconds=30
maxTokens=2048
temperature=0.1

# Privacy settings
privacy.mode=LOCAL_ONLY
privacy.allowStackTraces=false
privacy.allowThreadNames=true
privacy.allowEventValues=false
privacy.sensitivePatterns=password,secret,.*key.*,token,credential
privacy.auditEnabled=true
```

### Using OpenAI

```properties
provider=OPENAI
endpoint=https://api.openai.com/v1
model=gpt-4o-mini
apiKey=sk-...your-key-here...
timeoutSeconds=30
maxTokens=2048
temperature=0.1

# Privacy settings
privacy.mode=CLOUD_WITH_CONFIRM
privacy.auditEnabled=true
```

**Getting an API Key:**
1. Visit https://platform.openai.com/api-keys
2. Create a new API key
3. Add it to your config file or set `OPENAI_API_KEY` environment variable

### Using Anthropic (Claude)

```properties
provider=ANTHROPIC
endpoint=https://api.anthropic.com
model=claude-3-5-sonnet-20241022
apiKey=sk-ant-...your-key-here...
timeoutSeconds=30
maxTokens=2048
temperature=0.1

# Privacy settings
privacy.mode=CLOUD_WITH_CONFIRM
privacy.auditEnabled=true
```

**Getting an API Key:**
1. Visit https://console.anthropic.com/settings/keys
2. Create a new API key
3. Add it to your config file or set `ANTHROPIC_API_KEY` environment variable

## Commands

### `chat [query]`

Enter conversational mode for natural language JFR analysis.

**Usage:**

```bash
# Enter chat mode
jfr> chat
jafar> which threads allocated the most memory?
[Results displayed]
jafar> show me only thread 'main'
[Results for main thread]
jafar> /exit
jfr>

# Or provide initial query
jfr> chat top 10 methods by CPU time
jafar> show me methods in package com.myapp
jafar> /exit
```

**Commands in chat mode:**
- Type questions naturally without command prefix
- `/exit` or `/quit` - Return to normal CLI
- `/clear` - Reset conversation history
- `/help` - Show tips and examples

**Examples:**

```bash
# Memory analysis
jafar> which threads allocated the most memory?
jafar> show me allocations over 1MB
jafar> what objects were allocated the most?

# File I/O
jafar> show file reads over 1MB
jafar> which files were read most frequently?
jafar> what was the average file read size?

# CPU profiling
jafar> top 10 methods by CPU time
jafar> show me execution samples for MyClass
jafar> which threads were running most?

# GC analysis
jafar> count garbage collection events
jafar> what was the average GC pause time?
jafar> show me GC pauses over 100ms

# Event decoration and correlation
jafar> show top 5 hottest methods decorated with duration from VirtualThreadPinned
jafar> execution samples embellished with GC phase name
jafar> allocations extended with traceId from datadog.Timeline
jafar> top endpoints by CPU usage with request context
jafar> show samples decorated with requestId from RequestStart
```

### `llm status`

Check LLM configuration and provider availability.

```bash
jfr> llm status
LLM Configuration:
  Provider: LOCAL
  Endpoint: http://localhost:11434
  Model: llama3.1:8b
  Privacy Mode: LOCAL_ONLY
  Audit Enabled: true
  Status: Available
```

### `llm config`

Display configuration options and examples.

```bash
jfr> llm config
LLM Configuration
=================

Configuration file: ~/.jfr-shell/llm-config.properties

[...configuration examples...]
```

### `llm test`

Test the LLM connection with a simple request.

```bash
jfr> llm test
Testing LLM connection...
Success!
Response: Hello from jfr-shell!
Model: llama3.1:8b
Tokens: 8
Duration: 245ms
```

### `llm clear`

Clear the conversation history for the current session.

```bash
jfr> llm clear
Conversation history cleared.
```

### `llm audit`

View recent LLM interactions from the audit log.

```bash
jfr> llm audit
Recent LLM Interactions (last 50):
===================================
2024-01-15 10:50:22 | local | llama3.1:8b | ask | ctx=1234 bytes | resp=567 bytes | sent=false | 1250ms
2024-01-15 10:51:05 | local | llama3.1:8b | ask | ctx=1456 bytes | resp=612 bytes | sent=false | 980ms

Full log: /Users/username/.jfr-shell/llm-audit.log
```

## Privacy & Security

JFR Shell takes privacy seriously with a **local-first** approach by default.

### Privacy Modes

**LOCAL_ONLY** (Default)
- Only local Ollama provider is allowed
- Cloud providers are blocked
- Data never leaves your machine
- Best for sensitive production data

**CLOUD_WITH_CONFIRM**
- Cloud providers allowed
- User confirmation required before each request (future feature)
- Audit logging enabled
- Use for less sensitive data

**SMART** (Future)
- Simple queries use local provider
- Complex queries use cloud provider
- Automatic routing based on complexity

### What Data is Sent to LLMs?

When using the `ask` command, JFR Shell sends:

**✅ Always Sent (Metadata Only):**
- JfrPath grammar documentation
- List of available event type names (e.g., "jdk.ObjectAllocationSample")
- Session info: recording path, event type count
- Variable names (but not values)
- Your natural language question

**❌ Never Sent:**
- Actual event data or values
- Stack traces (unless enabled in config)
- Thread names (unless enabled in config)
- Secrets or credentials (filtered by sensitive patterns)
- Recording file contents

### Audit Trail

All LLM interactions are logged to `~/.jfr-shell/llm-audit.log`:

```
2024-01-15 10:50:22 | local | llama3.1:8b | ask | ctx=1234 bytes | resp=567 bytes | sent=false | 1250ms
```

Fields:
- Timestamp
- Provider (local, openai, anthropic)
- Model name
- Query type
- Context size (bytes)
- Response size (bytes)
- Data sent flag (always false for metadata-only)
- Duration (milliseconds)

You can review this log anytime to see what was sent to LLMs.

## Advanced Usage

### Conversation Context

The LLM remembers previous queries in chat mode, enabling follow-up questions:

```bash
jfr> chat
jafar> which threads allocated the most memory?
[Results shown]

jafar> what about CPU time?
Generated query: events/jdk.ExecutionSample | groupBy(eventThread/javaName) | top(10, by=count)
Explanation: Shows threads with most CPU samples, following up on the previous memory query
[Results shown]

jafar> show me the top method in thread "main"
[Results shown]

jafar> /exit
jfr>
```

To clear history:
```bash
jafar> /clear
```

### Confidence Scores

The LLM provides a confidence score (0.0-1.0) for each translation:

```bash
jfr> chat
jafar> show me weird stuff
Generated query: events/jdk.JavaErrorThrow
Explanation: Shows exception throw events, which may represent unusual behavior
Confidence: 45% (low)
Warning: Query is ambiguous - "weird stuff" could mean many things

[Results shown]
```

Low confidence (<50%) indicates:
- Ambiguous question
- Missing event types
- Unusual query patterns

Consider rephrasing your question or being more specific.

### Output Formats

Results respect the current output format setting:

```bash
# Table format (default)
jfr> chat
jafar> count GC events
events/jdk.GarbageCollection | count()
[Table with count]

# JSON format
jfr> set output json
jfr> chat
jafar> count GC events
[{"count": 42}]

# CSV format
jfr> set output csv
jfr> chat
jafar> count GC events
count
42
```

## Troubleshooting

### "LLM provider not available"

**For Ollama:**
```bash
# Check if Ollama is running
curl http://localhost:11434/api/tags

# If not, start it
ollama serve

# Check if model is pulled
ollama list

# If missing, pull it
ollama pull llama3.1:8b
```

**For OpenAI/Anthropic:**
- Verify API key is set in config or environment variable
- Check internet connection
- Verify endpoint URL is correct

### "Query execution failed"

The generated query may have syntax errors. This can happen with:
- Very complex or ambiguous questions
- Event types not available in your recording
- Low confidence translations

**Solutions:**
- Try rephrasing your question more clearly
- Check available event types: `metadata --events-only`
- Look at the generated query and fix it manually
- Use `llm clear` and rephrase from scratch

### Slow Response Times

**For Ollama:**
- Use a smaller model: `llama3.1:8b` instead of `llama3.1:70b`
- Ensure sufficient RAM (8GB minimum for 8b model)
- Close other memory-intensive applications

**For Cloud Providers:**
- Check network connection
- Try a faster model (e.g., `gpt-4o-mini` instead of `gpt-4o`)
- Increase `timeoutSeconds` in config for complex queries

### "No audit log found"

The audit log is created on first use. Try:
```bash
# Run a query first
jfr> chat
jafar> count all events

# Then check audit
jfr> llm audit
```

If audit is disabled:
```properties
# In ~/.jfr-shell/llm-config.properties
privacy.auditEnabled=true
```

## Best Practices

### Effective Questions

**✅ Good:**
- "which threads allocated the most memory?"
- "show file reads over 1MB"
- "count GC events with duration over 100ms"
- "top 10 methods by CPU samples"

**❌ Avoid:**
- "optimize my app" (too vague)
- "fix the bug" (requires analysis, not queries)
- "why is it slow?" (multi-step diagnosis)

### When to Use LLMs

**Use for:**
- Quick exploratory queries
- Learning JfrPath syntax
- Translating known patterns

**Don't use for:**
- Production automation (use explicit JfrPath)
- Security-critical queries (verify generated queries)
- When you know exact JfrPath syntax (just use it directly)

### Cost Optimization

**Free (Ollama):**
- Run models locally
- No API costs
- Privacy guaranteed

**Paid (OpenAI/Anthropic):**
- Use smallest model that works (gpt-4o-mini, claude-3-5-sonnet)
- Clear conversation history frequently (`llm clear`)
- Use local provider for simple queries
- Set lower `maxTokens` in config

## Example Workflows

### Memory Leak Investigation

```bash
jfr> chat
jafar> which threads allocated the most memory?
jafar> show me allocations over 10MB
jafar> what classes were allocated most?
jafar> show me allocation stack traces for MyLeakyClass
jafar> /exit
```

### Performance Profiling

```bash
jfr> chat
jafar> top 20 methods by CPU time
jafar> show execution samples in package com.mycompany
jafar> which threads had most CPU samples?
jafar> count samples per method for MyHotClass
jafar> /exit
```

### I/O Analysis

```bash
jfr> chat
jafar> show file reads
jafar> what was the total bytes read?
jafar> show reads over 1MB
jafar> which files were read most frequently?
jafar> /exit
```

### GC Analysis

```bash
jfr> chat
jafar> count GC events
jafar> what was average GC pause time?
jafar> show GC pauses over 100ms
jafar> which GC type was used most?
jafar> /exit
```

### Event Correlation and Context

The LLM understands natural language keywords for event decoration, allowing you to correlate events and add contextual information:

**Natural Language Keywords:**
- `decorated` / `embellished` / `extended` - Add context from related events
- `with context from` - Explicitly request correlation

**Temporal Correlation (decorateByTime):**
Used when events overlap in time on the same thread - great for "during" or "while" scenarios.

```bash
# Virtual thread pinning analysis
jfr> chat
jafar> show top 5 hottest methods decorated with duration from VirtualThreadPinned
Generated query: events/jdk.ExecutionSample | decorateByTime(jdk.VirtualThreadPinned, fields=duration) | groupBy(stackTrace/frames/0/method/type/name, agg=sum, value=$decorator.duration) | top(5, by=sum)

# GC impact on code
jafar> execution samples embellished with GC phase name
Generated query: events/jdk.ExecutionSample | decorateByTime(jdk.GCPhase, fields=name)

# What was running during safepoints
jafar> what was the application doing during long safepoint pauses
Generated query: events/jdk.ExecutionSample | decorateByTime(jdk.SafepointBegin, fields=operation,duration) | groupBy($decorator.operation, agg=avg, value=$decorator.duration)

# Allocations during GC
jafar> which code allocates memory during garbage collection
Generated query: events/jdk.ObjectAllocationSample | decorateByTime(jdk.GCPhase, fields=name) | groupBy($decorator.name, agg=sum, value=allocationSize)
```

**ID-Based Correlation (decorateByKey):**
Used for matching events by correlation IDs (requestId, spanId, traceId, etc.) - great for distributed tracing and request tracking.

```bash
# Request tracing
jfr> chat
jafar> show execution samples decorated with requestId from RequestStart
Generated query: events/jdk.ExecutionSample | decorateByKey(RequestStart, key=sampledThread/javaThreadId, decoratorKey=thread/javaThreadId, fields=requestId)

# Distributed tracing
jafar> allocations extended with traceId and spanId from datadog.Timeline
Generated query: events/jdk.ObjectAllocationSample | decorateByKey(datadog.Timeline, key=eventThread/javaThreadId, decoratorKey=thread/javaThreadId, fields=traceId,spanId)

# Request-aware CPU profiling
jafar> top endpoints by CPU usage with request context
Generated query: events/jdk.ExecutionSample | decorateByKey(RequestStart, key=sampledThread/javaThreadId, decoratorKey=thread/javaThreadId, fields=endpoint) | groupBy($decorator.endpoint, agg=count) | top(10, by=count)

# Transaction tracking
jafar> allocations extended with transaction ID from TransactionEvent
Generated query: events/jdk.ObjectAllocationSample | decorateByKey(TransactionEvent, key=eventThread/javaThreadId, decoratorKey=thread/javaThreadId, fields=transactionId)

# File I/O by endpoint
jafar> file reads grouped by request endpoint
Generated query: events/jdk.FileRead | decorateByKey(RequestStart, key=eventThread/javaThreadId, decoratorKey=thread/javaThreadId, fields=endpoint) | groupBy($decorator.endpoint, agg=sum, value=bytes)
```

**Context-Bearing Events:**
The LLM supports any custom event type with correlation metadata:
- JDK 25+ `@Contextual` annotated events
- Custom application events (RequestStart, UserSession, TransactionEvent)
- APM vendor events (datadog.Timeline, newrelic.Transaction, etc.)

## Limitations

Current limitations (Phase 1 MVP):
- Single query translation only (no multi-query analysis)
- No automatic root cause analysis
- No report generation
- Query validation only (no result interpretation)
- Limited context window (first 50 event types)

See [future-enhancements.md](future-enhancements.md) for planned features:
- Automated root cause analysis
- Intelligent report generation
- Conversational debugging
- Pattern learning from baselines
- Health check assessments

## Support

For issues or questions:
- GitHub Issues: https://github.com/jbachorik/jafar/issues
- Documentation: https://github.com/jbachorik/jafar/tree/main/doc
- Examples: jfr-shell/src/main/resources/examples/

## Technical Details

### Architecture

- **Zero External Dependencies**: Uses only Java 21+ `HttpClient`
- **Simple JSON Parsing**: Regex-based parsing (no jackson/gson)
- **Properties Configuration**: Standard Java properties format
- **Session Integration**: Providers and history cached in session variables
- **Query Validation**: Uses existing `JfrPathParser` to validate generated queries
- **Privacy-First**: Data minimization and audit logging built-in

### Supported Models

**Local (Ollama):**
- llama3.1:8b (recommended)
- llama3.1:70b (high accuracy)
- codellama:34b (code-focused)
- mistral:7b (fast, lightweight)

**OpenAI:**
- gpt-4o-mini (recommended, fast and cheap)
- gpt-4o (highest accuracy)
- gpt-3.5-turbo (legacy, cheaper)

**Anthropic:**
- claude-3-5-sonnet-20241022 (recommended)
- claude-3-opus (highest accuracy, slower)
- claude-3-haiku (fastest, cheaper)

### Performance

Typical response times:
- **Local (Ollama with 8b model)**: 1-3 seconds
- **OpenAI (gpt-4o-mini)**: 0.5-2 seconds
- **Anthropic (claude-3-5-sonnet)**: 1-3 seconds

Factors affecting speed:
- Model size (8b vs 70b)
- Hardware (CPU, RAM, GPU)
- Query complexity
- Network latency (cloud providers)
- Conversation history length

---

**Last Updated**: 2025-01-10
**Version**: 0.8.0-SNAPSHOT (Phase 1 MVP)
