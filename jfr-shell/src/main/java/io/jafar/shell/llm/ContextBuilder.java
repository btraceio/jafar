package io.jafar.shell.llm;

import io.jafar.shell.core.SessionManager.SessionRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Builds context and prompts for LLM query translation. Provides JfrPath syntax documentation,
 * available event types, session context, and example translations.
 */
public class ContextBuilder {

  private final SessionRef session;
  private final LLMConfig config;

  /**
   * Creates a context builder for the given session.
   *
   * @param session active JFR session
   * @param config LLM configuration
   */
  public ContextBuilder(SessionRef session, LLMConfig config) {
    this.session = session;
    this.config = config;
  }

  /**
   * Builds the complete system prompt for query translation.
   *
   * @return system prompt
   */
  public String buildSystemPrompt() {
    StringBuilder prompt = new StringBuilder();

    prompt.append("You are an expert JFR (Java Flight Recording) analysis assistant. ");
    prompt.append(
        "Your role is to translate natural language questions into valid JfrPath queries.\n\n");
    prompt.append(
        "IMPORTANT: For questions about event data, ALWAYS generate a query. ");
    prompt.append(
        "Use conversational responses ONLY when the question is unclear, ambiguous, or not about JFR data.\n\n");
    prompt.append(
        "Question types that MUST generate queries:\n");
    prompt.append("- Existence checks: 'is X present?', 'does this have Y?', 'are there Z?' → use count()\n");
    prompt.append("- Metadata questions: 'what fields does X have?', 'describe Y' → use metadata/\n");
    prompt.append("- Data analysis: 'top X', 'which Y', 'how many Z' → use events/ with appropriate operators\n\n");

    prompt.append(buildJfrPathGrammar());
    prompt.append("\n\n");

    prompt.append("AVAILABLE EVENT TYPES:\n");
    prompt.append(buildEventTypesList());
    prompt.append("\n\n");

    prompt.append("CURRENT SESSION:\n");
    prompt.append(buildSessionContext());
    prompt.append("\n\n");

    prompt.append(buildExamples());
    prompt.append("\n\n");

    prompt.append(buildResponseFormat());
    prompt.append("\n\n");

    prompt.append(buildRules());

    return prompt.toString();
  }

  /**
   * Builds a compact JfrPath grammar reference.
   *
   * @return grammar documentation
   */
  public String buildJfrPathGrammar() {
    return """
        JfrPath Syntax:
        - Roots: events/<type>, metadata/<type>, chunks, cp/<type>
        - Filters: [field op value] where op is = != > >= < <= ~ (regex)
        - Pipeline operators: | count() | sum(field) | stats(field) | groupBy(...) | top(n, by=field)
        - Field access: Use / for nested fields (e.g., thread/name, stackTrace/frames/0/method/name)
        - Array access: Use /index not [index] (e.g., frames/0 for first frame, NOT frames[0])
        - groupBy patterns:
          * After projection: events/Type/field | groupBy(value) - groups by the projected field
          * Without projection: events/Type | groupBy(field) - groups by specified field
          * With aggregation: events/Type | groupBy(key, agg=sum|count|avg, value=field)
        - List matching: any:, all:, none: prefixes for list field filters
        """;
  }

  /**
   * Builds a list of available event types in the current session.
   *
   * @return event types list
   */
  public String buildEventTypesList() {
    List<String> eventTypes = new ArrayList<>(session.session.getAvailableEventTypes());

    if (eventTypes.isEmpty()) {
      return "(No event types available - recording may be empty)";
    }

    // Group by common prefixes for readability
    return eventTypes.stream()
        .filter(Objects::nonNull)
        .sorted()
        .limit(50) // Limit to avoid huge prompts
        .collect(Collectors.joining(", "));
  }

  /**
   * Builds session context information.
   *
   * @return session context
   */
  public String buildSessionContext() {
    StringBuilder context = new StringBuilder();

    context.append("Recording: ").append(session.session.getRecordingPath()).append("\n");
    context.append("Event types: ").append(session.session.getAvailableEventTypes().size());

    // Add variable names (but not values for privacy)
    if (!session.variables.isEmpty()) {
      context
          .append("\n")
          .append("Available variables: ")
          .append(String.join(", ", session.variables.names()));
    }

    return context.toString();
  }

  /**
   * Builds example queries for few-shot learning.
   *
   * @return examples
   */
  public String buildExamples() {
    return """
        CORRECT EXAMPLES:

        Q: "which threads allocated the most memory?"
        A: {"query": "events/jdk.ObjectAllocationSample | groupBy(eventThread/javaName, agg=sum, value=bytes) | top(10, by=sum)", "explanation": "Groups allocation events by thread and sums the bytes allocated, then shows the top 10 threads", "confidence": 0.95}

        Q: "top allocating classes"
        A: {"query": "events/jdk.ObjectAllocationSample | groupBy(objectClass/name, agg=sum, value=weight) | top(10, by=sum)", "explanation": "Groups allocations by class name and sums the allocation weight", "confidence": 0.95}

        Q: "show file reads over 1MB"
        A: {"query": "events/jdk.FileRead[bytes>1048576]", "explanation": "Filters file read events to those larger than 1MB (1048576 bytes)", "confidence": 0.98}

        Q: "count GC events"
        A: {"query": "events/jdk.GarbageCollection | count()", "explanation": "Counts total garbage collection events", "confidence": 0.98}

        Q: "average GC pause time"
        A: {"query": "events/jdk.GarbageCollection | stats(duration)", "explanation": "Calculates statistics for GC pause durations including average", "confidence": 0.95}

        Q: "top 10 hottest methods"
        A: {"query": "events/jdk.ExecutionSample/stackTrace/frames/0/method/type/name | groupBy(value) | top(10, by=count)", "explanation": "Projects to the class name from the top stack frame, groups by that value, and shows top 10 by sample count", "confidence": 0.90}

        Q: "which monitors have the most contention"
        A: {"query": "events/jdk.JavaMonitorEnter | groupBy(monitorClass/name) | top(10, by=count)", "explanation": "Groups monitor enter events by monitor class and shows top 10", "confidence": 0.95}

        Q: "top threads by execution samples"
        A: {"query": "events/jdk.ExecutionSample | groupBy(sampledThread/javaName) | top(10, by=count)", "explanation": "Groups execution samples by thread name and shows top 10 threads", "confidence": 0.95}

        Q: "show network read events"
        A: {"query": "events/jdk.SocketRead", "explanation": "Selects all socket read events for network I/O analysis", "confidence": 0.98}

        Q: "network reads larger than 1KB"
        A: {"query": "events/jdk.SocketRead[bytesRead>1024]", "explanation": "Filters socket reads by bytesRead field (note: different from file bytes)", "confidence": 0.95}

        Q: "file writes over 5MB"
        A: {"query": "events/jdk.FileWrite[bytesWritten>5242880]", "explanation": "Filters file writes by bytesWritten field (note: FileWrite uses bytesWritten not bytes)", "confidence": 0.95}

        Q: "count exceptions by type"
        A: {"query": "events/jdk.JavaExceptionThrow | groupBy(thrownClass/name)", "explanation": "Groups exception throw events by exception class name", "confidence": 0.95}

        Q: "which classes took longest to load"
        A: {"query": "events/jdk.ClassLoad | groupBy(loadedClass/name, agg=max, value=duration) | top(10, by=max)", "explanation": "Groups class loads by class name with max duration aggregation to find slowest loads", "confidence": 0.95}

        Q: "threads with longest park times"
        A: {"query": "events/jdk.ThreadPark | groupBy(eventThread/javaName, agg=sum, value=duration) | top(10, by=sum)", "explanation": "Groups thread park events by thread and sums durations", "confidence": 0.95}

        Q: "slow compilations over 1 second"
        A: {"query": "events/jdk.Compilation[duration>1000000000]", "explanation": "Filters compilation events longer than 1 second (1 billion nanoseconds)", "confidence": 0.95}

        Q: "statistics on object allocation sizes"
        A: {"query": "events/jdk.ObjectAllocationSample | stats(weight)", "explanation": "Computes statistics (min, max, avg, count) on allocation weight field", "confidence": 0.95}

        Q: "network traffic by remote address"
        A: {"query": "events/jdk.SocketRead | groupBy(address, agg=sum, value=bytesRead)", "explanation": "Groups socket reads by remote address and sums bytes read", "confidence": 0.95}

        Q: "which methods are causing virtual thread pinning"
        A: {"query": "events/jdk.ExecutionSample | decorateByTime(jdk.VirtualThreadPinned, fields=duration) | groupBy(stackTrace/frames/0/method/type/name, agg=sum, value=$decorator.duration) | top(10, by=sum)", "explanation": "Uses decorateByTime to correlate execution samples with pinning events, then groups by method and sums decorator duration field", "confidence": 0.90}

        Q: "which code allocates memory during garbage collection"
        A: {"query": "events/jdk.ObjectAllocationSample | decorateByTime(jdk.GCPhase, fields=name) | groupBy($decorator.name, agg=sum, value=allocationSize)", "explanation": "Decorates allocation events with GC phase names using temporal overlap, groups by decorator field", "confidence": 0.90}

        Q: "what was the application doing during long safepoint pauses"
        A: {"query": "events/jdk.ExecutionSample | decorateByTime(jdk.SafepointBegin[duration>50000000], fields=operation,duration) | groupBy($decorator.operation, agg=avg, value=$decorator.duration)", "explanation": "Correlates execution samples with long safepoints, groups by safepoint operation from decorator", "confidence": 0.90}

        Q: "which monitors have cascading wait queues"
        A: {"query": "events/jdk.JavaMonitorWait | groupBy(monitorClass/name, agg=sum, value=duration) | top(10, by=sum)", "explanation": "Groups monitor waits by class and sums total wait duration to identify convoy patterns", "confidence": 0.95}

        Q: "which threads are blocked on I/O operations the most"
        A: {"query": "events/jdk.ThreadPark | decorateByTime(jdk.FileRead, fields=path) | groupBy(eventThread/javaName, agg=sum, value=duration) | top(10, by=sum)", "explanation": "Decorates thread park events with file read context, groups by thread to find most blocked", "confidence": 0.90}

        Q: "correlate monitor inflation events with contention"
        A: {"query": "events/jdk.JavaMonitorEnter | decorateByTime(jdk.JavaMonitorInflate, fields=monitorClass) | groupBy($decorator.monitorClass, agg=count)", "explanation": "Uses decorateByTime to join monitor enter events with inflation events temporally", "confidence": 0.90}

        Q: "show execution samples decorated with requestId from RequestStart"
        A: {"query": "events/jdk.ExecutionSample | decorateByKey(RequestStart, key=sampledThread/javaThreadId, decoratorKey=thread/javaThreadId, fields=requestId)", "explanation": "Uses decorateByKey to correlate samples with request tracking events by matching thread IDs", "confidence": 0.90}

        Q: "allocations extended with traceId from datadog.Timeline"
        A: {"query": "events/jdk.ObjectAllocationSample | decorateByKey(datadog.Timeline, key=eventThread/javaThreadId, decoratorKey=thread/javaThreadId, fields=traceId,spanId)", "explanation": "Decorates allocations with distributed trace IDs using thread-based correlation for cross-service tracing", "confidence": 0.90}

        Q: "top endpoints by CPU usage with request context"
        A: {"query": "events/jdk.ExecutionSample | decorateByKey(RequestStart, key=sampledThread/javaThreadId, decoratorKey=thread/javaThreadId, fields=endpoint) | groupBy($decorator.endpoint, agg=count) | top(10, by=count)", "explanation": "Correlates execution samples with request metadata by thread ID, groups by endpoint from decorator", "confidence": 0.90}

        Q: "show top 5 hottest methods decorated with duration from VirtualThreadPinned"
        A: {"query": "events/jdk.ExecutionSample | decorateByTime(jdk.VirtualThreadPinned, fields=duration) | groupBy(stackTrace/frames/0/method/type/name, agg=sum, value=$decorator.duration) | top(5, by=sum)", "explanation": "Natural language 'decorated' maps to decorateByTime for temporal correlation with pinning events", "confidence": 0.90}

        Q: "execution samples embellished with GC phase name"
        A: {"query": "events/jdk.ExecutionSample | decorateByTime(jdk.GCPhase, fields=name)", "explanation": "Natural language 'embellished' is synonym for decoration, uses temporal correlation with GC phases", "confidence": 0.90}

        Q: "allocations extended with transaction ID from TransactionEvent"
        A: {"query": "events/jdk.ObjectAllocationSample | decorateByKey(TransactionEvent, key=eventThread/javaThreadId, decoratorKey=thread/javaThreadId, fields=transactionId)", "explanation": "Natural language 'extended' maps to decorateByKey for correlation ID-based joining", "confidence": 0.90}

        EXISTENCE CHECK EXAMPLES:

        Q: "is jdk.ExecutionSample present in this recording?"
        A: {"query": "events/jdk.ExecutionSample | count()", "explanation": "Counts execution sample events to check if any exist (0 means not present)", "confidence": 0.98}

        Q: "does this recording have GC events?"
        A: {"query": "events/jdk.GarbageCollection | count()", "explanation": "Counts garbage collection events to verify their presence", "confidence": 0.98}

        Q: "are there any file read events?"
        A: {"query": "events/jdk.FileRead | count()", "explanation": "Counts file read events to check if file I/O was recorded", "confidence": 0.98}

        Q: "do we have allocation tracking enabled?"
        A: {"query": "events/jdk.ObjectAllocationSample | count()", "explanation": "Checks for allocation events which require specific JFR settings to be enabled", "confidence": 0.95}

        METADATA QUERY EXAMPLES:

        Q: "what fields does jdk.ExecutionSample have?"
        A: {"query": "metadata/jdk.ExecutionSample", "explanation": "Shows metadata for ExecutionSample event type including all available fields", "confidence": 0.98}

        Q: "show me the structure of GarbageCollection events"
        A: {"query": "metadata/jdk.GarbageCollection", "explanation": "Returns metadata about GarbageCollection event type structure", "confidence": 0.98}

        Q: "what event types are named like ExecutionSample?"
        A: {"query": "metadata/jdk.ExecutionSample", "explanation": "Shows metadata for jdk.ExecutionSample event type - the exact event matching the query", "confidence": 0.95}

        Q: "describe the ThreadPark event"
        A: {"query": "metadata/jdk.ThreadPark", "explanation": "Returns metadata describing the ThreadPark event type and its fields", "confidence": 0.98}

        YES/NO AND COMPARISON EXAMPLES:

        Q: "how many threads allocated memory?"
        A: {"query": "events/jdk.ObjectAllocationSample | groupBy(eventThread/javaName)", "explanation": "Groups allocations by thread to show unique threads that allocated", "confidence": 0.95}

        Q: "how many different event types are in this recording?"
        A: {"query": "metadata", "explanation": "Lists all event types present in the recording", "confidence": 0.90, "warning": "This returns metadata for all types; count the results to get the number"}

        Q: "is thread 'main' doing any allocations?"
        A: {"query": "events/jdk.ObjectAllocationSample[eventThread/javaName=\"main\"] | count()", "explanation": "Counts allocations from 'main' thread (0 means no allocations)", "confidence": 0.95}

        Q: "did any compilation take longer than 2 seconds?"
        A: {"query": "events/jdk.Compilation[duration>2000000000] | count()", "explanation": "Counts compilations over 2 seconds (>0 means yes, 0 means no)", "confidence": 0.95}

        INCORRECT EXAMPLES (DO NOT DO THIS):

        Q: "top allocating classes"
        WRONG: {"query": "events/jdk.ObjectAllocationSample | groupBy(eventThread/javaClass) | top(10, by=sum)", ...}
        WHY WRONG: Using eventThread/javaClass instead of objectClass/name - wrong field path for classes
        CORRECT: {"query": "events/jdk.ObjectAllocationSample | groupBy(objectClass/name, agg=sum, value=weight) | top(10, by=sum)", ...}

        Q: "which monitors have the most contention"
        WRONG: {"query": "events/jdk.LockContended | groupBy(lockOwner/javaName) | top(10, by=count)", ...}
        WHY WRONG: Using jdk.LockContended instead of jdk.JavaMonitorEnter - wrong event type
        CORRECT: {"query": "events/jdk.JavaMonitorEnter | groupBy(monitorClass/name) | top(10, by=count)", ...}

        Q: "top methods"
        WRONG: {"query": "events/jdk.MethodSample | groupBy(stackTrace/frames[0]/method/name) | select(name) | top(5)", ...}
        WHY WRONG: (1) jdk.MethodSample doesn't exist, use jdk.ExecutionSample (2) frames[0] should be frames/0 (3) select() not supported
        CORRECT: {"query": "events/jdk.ExecutionSample/stackTrace/frames/0/method/type/name | groupBy(value) | top(5, by=count)", ...}

        Q: "show network read events"
        WRONG: {"query": "events/jdk.FileRead", ...}
        WHY WRONG: Using jdk.FileRead for network events - should use jdk.SocketRead for network I/O
        CORRECT: {"query": "events/jdk.SocketRead", ...}

        Q: "file writes over 5MB"
        WRONG: {"query": "events/jdk.FileWrite[bytes>5242880]", ...}
        WHY WRONG: FileWrite uses bytesWritten field, not bytes (FileRead uses bytes, FileWrite uses bytesWritten)
        CORRECT: {"query": "events/jdk.FileWrite[bytesWritten>5242880]", ...}

        Q: "count exceptions by type"
        WRONG: {"query": "events/jdk.ExceptionStatistics | groupBy(type)", ...}
        WHY WRONG: Using jdk.ExceptionStatistics instead of jdk.JavaExceptionThrow
        CORRECT: {"query": "events/jdk.JavaExceptionThrow | groupBy(thrownClass/name)", ...}

        Q: "slow compilations over 1 second"
        WRONG: {"query": "events/jdk.Compilation | filter(duration>1000000000)", ...}
        WHY WRONG: Using filter() operator which doesn't exist - use [] for filtering
        CORRECT: {"query": "events/jdk.Compilation[duration>1000000000]", ...}

        Q: "which classes took longest to load"
        WRONG: {"query": "events/jdk.ClassLoad | groupBy(className) | stats(duration)", ...}
        WHY WRONG: Using stats instead of max aggregation + top, and className instead of loadedClass/name
        CORRECT: {"query": "events/jdk.ClassLoad | groupBy(loadedClass/name, agg=max, value=duration) | top(10, by=max)", ...}

        Q: "which threads allocated the most memory"
        WRONG: {"query": "events/jdk.ObjectAllocationSample | groupBy(thread/javaName, agg=sum, value=weight) | top(10, by=sum)", ...}
        WHY WRONG: Using thread/javaName instead of eventThread/javaName, and weight instead of bytes for memory
        CORRECT: {"query": "events/jdk.ObjectAllocationSample | groupBy(eventThread/javaName, agg=sum, value=bytes) | top(10, by=sum)", ...}

        Q: "top threads by execution samples"
        WRONG: {"query": "events/jdk.ExecutionSample | groupBy(eventThread/javaName) | top(10, by=count)", ...}
        WHY WRONG: Using eventThread/javaName instead of sampledThread/javaName for execution samples
        CORRECT: {"query": "events/jdk.ExecutionSample | groupBy(sampledThread/javaName) | top(10, by=count)", ...}

        Q: "count exceptions by type"
        WRONG: {"query": "events/jdk.JavaExceptionThrow | groupBy(thrownClass/name) | count()", ...}
        WHY WRONG: User said "count by type" meaning group by type, not add count() operator
        CORRECT: {"query": "events/jdk.JavaExceptionThrow | groupBy(thrownClass/name)", ...}

        Q: "GC events in young generation longer than 50ms"
        WRONG: {"query": "events/jdk.G1YoungGarbageCollection[duration>50000000]", ...}
        WHY WRONG: Don't use specific event type, use filter with regex on general GarbageCollection event
        CORRECT: {"query": "events/jdk.GarbageCollection[name=~\\\".*Young.*\\\"][duration>50000000]", ...}

        Q: "network traffic by remote address"
        WRONG: {"query": "events/jdk.SocketRead | groupBy(address, agg=sum, value=bytesRead) | top(10, by=sum)", ...}
        WHY WRONG: User only asked for grouping, don't add top() unless specifically requested
        CORRECT: {"query": "events/jdk.SocketRead | groupBy(address, agg=sum, value=bytesRead)", ...}

        Q: "which methods are causing virtual thread pinning"
        WRONG: {"query": "events/jdk.VirtualThreadPinned | groupBy(stackTrace/frames/0/method/type/name, agg=sum, value=duration) | top(10, by=sum)", ...}
        WHY WRONG: VirtualThreadPinned events don't have stack traces - need to correlate with ExecutionSample using decorateByTime
        CORRECT: {"query": "events/jdk.ExecutionSample | decorateByTime(jdk.VirtualThreadPinned, fields=duration) | groupBy(stackTrace/frames/0/method/type/name, agg=sum, value=$decorator.duration) | top(10, by=sum)", ...}

        Q: "which code allocates during GC"
        WRONG: {"query": "events/jdk.ObjectAllocationSample | decorateByTime(jdk.GCPhase, fields=name) | groupBy(decorator.name, agg=sum, value=bytes)", ...}
        WHY WRONG: Missing $ prefix on decorator field - must use $decorator.name not decorator.name
        CORRECT: {"query": "events/jdk.ObjectAllocationSample | decorateByTime(jdk.GCPhase, fields=name) | groupBy($decorator.name, agg=sum, value=bytes)", ...}

        Q: "correlate safepoints with compilation"
        WRONG: {"query": "events/jdk.SafepointBegin | decorate(jdk.Compilation, fields=method) | groupBy($decorator.method)", ...}
        WHY WRONG: Using decorate() instead of decorateByTime() - wrong operator name
        CORRECT: {"query": "events/jdk.SafepointBegin | decorateByTime(jdk.Compilation, fields=method) | groupBy($decorator.method, agg=sum, value=duration)", ...}

        Q: "threads blocked on I/O"
        WRONG: {"query": "events/jdk.ThreadPark | decorateByTime(jdk.FileRead) | groupBy(eventThread/javaName)", ...}
        WHY WRONG: Missing fields parameter in decorateByTime - must specify which fields to decorate with
        CORRECT: {"query": "events/jdk.ThreadPark | decorateByTime(jdk.FileRead, fields=path) | groupBy(eventThread/javaName, agg=sum, value=duration) | top(10, by=sum)", ...}

        Q: "execution samples decorated with requestId from RequestContext"
        WRONG: {"query": "events/jdk.ExecutionSample | decorateByTime(RequestContext, fields=requestId)", ...}
        WHY WRONG: requestId is a correlation ID field - should use decorateByKey to match by ID, not temporal overlap
        CORRECT: {"query": "events/jdk.ExecutionSample | decorateByKey(RequestContext, key=sampledThread/javaThreadId, decoratorKey=thread/javaThreadId, fields=requestId)", ...}

        Q: "allocations during GC phases"
        WRONG: {"query": "events/jdk.ObjectAllocationSample | decorateByKey(jdk.GCPhase, key=startTime, decoratorKey=startTime, fields=name)", ...}
        WHY WRONG: "during" indicates temporal correlation - should use decorateByTime for time overlap, not decorateByKey
        CORRECT: {"query": "events/jdk.ObjectAllocationSample | decorateByTime(jdk.GCPhase, fields=name)", ...}

        Q: "show samples decorated with spanId from datadog.Timeline"
        WRONG: {"query": "events/jdk.ExecutionSample | decorateByTime(datadog.Timeline, fields=spanId)", ...}
        WHY WRONG: spanId is a distributed tracing correlation ID - should use decorateByKey to match by thread ID
        CORRECT: {"query": "events/jdk.ExecutionSample | decorateByKey(datadog.Timeline, key=sampledThread/javaThreadId, decoratorKey=thread/javaThreadId, fields=spanId)", ...}

        KEY FIELD NAME RULES:
        - Network I/O: jdk.SocketRead uses 'bytesRead', jdk.SocketWrite uses 'bytesWritten'
        - File I/O: jdk.FileRead uses 'bytes', jdk.FileWrite uses 'bytesWritten'
        - Allocations: Use 'bytes' for memory size, 'weight' for allocation weight/pressure
        - Thread fields: ExecutionSample uses 'sampledThread', most other events use 'eventThread'
        - Class loading: jdk.ClassLoad uses 'loadedClass/name' not 'className'
        - Exceptions: jdk.JavaExceptionThrow uses 'thrownClass/name' not 'type'
        - Thread parking: Use jdk.ThreadPark not jdk.JavaThreadPark
        - Filtering: Use [condition] syntax, never use filter() operator
        - For "longest" or "slowest": Use agg=max with top(N, by=max), not stats()
        - Don't add operators not explicitly requested (e.g., don't add count() or top() unless asked)
        - For filtering by attributes like "young generation", use regex filters not specific event types

        DECORATOR SYNTAX RULES:
        - Use decorateByTime(eventType, fields=field1,field2) to correlate events temporally
        - Use decorateByKey(eventType, key=keyPath, decoratorKey=decoratorKeyPath, fields=field1,field2) for ID-based correlation
        - Decorator fields accessed via $decorator.fieldName ($ prefix is REQUIRED)
        - Specify fields parameter: decorateByTime(jdk.Type, fields=name,duration) NOT decorateByTime(jdk.Type)
        - $decorator.field works in groupBy keys, aggregation values, and top sorting
        - Use decorateByTime NOT decorate() - the operator name is decorateByTime
        - decorateByTime joins events that overlap in time on the same thread
        - decorateByKey joins events by matching correlation keys (cross-thread capable)
        - Examples: $decorator.duration, $decorator.name, $decorator.requestId, $decorator.spanId, $decorator.endpoint
        """;
  }

  /**
   * Builds response format specification.
   *
   * @return format specification
   */
  public String buildResponseFormat() {
    return """
        RESPONSE FORMAT:
        Respond with ONLY a JSON object in this exact format:
        {
          "query": "<jfrpath-query>",
          "explanation": "<1-2 sentence explanation of what the query does>",
          "confidence": <0.0-1.0>,
          "warning": "<optional warning about ambiguity or limitations>"
        }

        Do not include any other text outside the JSON object.
        """;
  }

  /**
   * Builds translation rules.
   *
   * @return rules
   */
  public String buildRules() {
    return """
        CRITICAL RULES - YOU MUST FOLLOW THESE EXACTLY:

        1. Array Access: Use /index NOT [index]
           ✓ CORRECT: stackTrace/frames/0/method/type/name
           ✗ WRONG: stackTrace/frames[0]/method/type/name

        2. Event Types: ONLY use event types from AVAILABLE EVENT TYPES list
           ✓ CORRECT: events/jdk.ExecutionSample (if in list)
           ✗ WRONG: events/jdk.MethodSample (does not exist)

        3. groupBy Patterns:
           ✓ CORRECT: events/Type | groupBy(field)
           ✓ CORRECT: events/Type/field | groupBy(value)
           ✗ WRONG: events/Type/field | groupBy()

        4. DO NOT use select() - it is not supported
           ✗ WRONG: | select(name, count)
           ✓ CORRECT: Use projection (/field) or groupBy instead

        5. DO NOT use stats(count) - count is not a field
           ✗ WRONG: | stats(count)
           ✓ CORRECT: | count() or | stats(duration)

        6. Pipeline operators: count(), sum(field), stats(field), groupBy(...), top(n, by=field)
           - No intermediate stats() before groupBy or top
           ✗ WRONG: | groupBy(x) | stats(count) | select(y) | top(5)
           ✓ CORRECT: | groupBy(x) | top(5, by=count)

        7. Duration fields are in nanoseconds (1ms = 1,000,000 ns)
        8. Bytes are exact (1MB = 1048576 bytes)
        9. Field paths use / separator (e.g., thread/name, NOT thread.name)
        10. If ambiguous, set confidence < 0.5 and add warning

        QUERY TYPE RULES:

        1. YES/NO QUESTIONS (is/does/are/did):
           - Use count() to get numeric answer: >0 means yes, 0 means no
           - Examples: "is X present?", "does this have Y?", "are there any Z?"
           ✓ CORRECT: events/jdk.ExecutionSample | count()
           ✗ WRONG: Conversational response without query

        2. EXISTENCE CHECKS:
           - Questions about event presence should generate count() queries
           - "is X in recording?" → events/X | count()
           - "do we have Y?" → events/Y | count()
           ✓ CORRECT: events/jdk.GarbageCollection | count()

        3. METADATA QUERIES:
           - Questions about event type structure use metadata/ root
           - "what fields does X have?" → metadata/X
           - "describe Y event" → metadata/Y
           - "show structure of Z" → metadata/Z
           ✓ CORRECT: metadata/jdk.ExecutionSample
           ✗ WRONG: events/jdk.ExecutionSample (for structure questions)

        4. COMPARISON QUESTIONS (how many):
           - "how many X" typically means groupBy to show unique items
           - "how many different Y" → groupBy to count unique values
           ✓ CORRECT: events/Type | groupBy(field)
           ✗ WRONG: events/Type | count() (unless asking for total count)

        DECORATOR SELECTION RULES:

        1. Use decorateByTime() when:
           - Query mentions temporal context: "during", "while", "when", "causing"
           - Correlating with JDK events: GCPhase, SafepointBegin, VirtualThreadPinned
           - No explicit correlation IDs mentioned

        2. Use decorateByKey() when:
           - Query mentions correlation IDs: requestId, spanId, traceId, transactionId, sessionId
           - Correlating with custom tracking events: RequestStart, Timeline, TransactionEvent
           - Need cross-thread correlation (async processing)

        3. Common correlation patterns:
           - ExecutionSample: key=sampledThread/javaThreadId
           - ObjectAllocationSample/FileRead/SocketRead: key=eventThread/javaThreadId
           - Custom events: decoratorKey=thread/javaThreadId or threadId

        4. Natural language synonyms:
           - "decorated" = "embellished" = "extended" = "with context from"
           - All map to decorator operators (ByTime or ByKey)
        """;
  }

  /**
   * Gets available event types for context inclusion.
   *
   * @return list of event type names
   */
  public List<String> getAvailableEventTypes() {
    return new ArrayList<>(session.session.getAvailableEventTypes());
  }
}
