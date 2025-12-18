# Request Tracing Example

This example demonstrates using `decorateByKey` to correlate application events with request context using thread IDs as correlation keys.

## Scenario

Your application emits custom JFR events for request start/end. You want to analyze execution samples, allocations, or I/O operations in the context of specific requests or endpoints.

## Assumptions

You have custom JFR events like:
- `RequestStart` - Contains `requestId`, `endpoint`, `userId`, `thread/javaThreadId`
- `RequestEnd` - Contains `requestId`, `statusCode`, `duration`

## Basic Query

```bash
show events/jdk.ExecutionSample | decorateByKey(RequestStart,
                                                  key=sampledThread/javaThreadId,
                                                  decoratorKey=thread/javaThreadId,
                                                  fields=requestId,endpoint,userId)
```

## Breakdown

1. **Primary Event**: `jdk.ExecutionSample` - Profiling samples
2. **Decorator Event**: `RequestStart` - Request context
3. **Correlation Key**: `javaThreadId` - Threads processing requests
4. **Decorator Fields**: `requestId`, `endpoint`, `userId`

## Analysis Queries

### Group execution samples by endpoint

```bash
show events/jdk.ExecutionSample | decorateByKey(RequestStart,
                                                  key=sampledThread/javaThreadId,
                                                  decoratorKey=thread/javaThreadId,
                                                  fields=endpoint)
  | groupBy($decorator.endpoint)
```

### Top endpoints by CPU usage (sample count)

```bash
show events/jdk.ExecutionSample | decorateByKey(RequestStart,
                                                  key=sampledThread/javaThreadId,
                                                  decoratorKey=thread/javaThreadId,
                                                  fields=endpoint)
  | groupBy($decorator.endpoint, agg=count) | top(10, by=count)
```

### File I/O by endpoint

```bash
show events/jdk.FileRead | decorateByKey(RequestStart,
                                          key=eventThread/javaThreadId,
                                          decoratorKey=thread/javaThreadId,
                                          fields=endpoint)
  | groupBy($decorator.endpoint, agg=sum, value=bytes)
```

### Allocations by request

```bash
show events/jdk.ObjectAllocationSample | decorateByKey(RequestStart,
                                                        key=eventThread/javaThreadId,
                                                        decoratorKey=thread/javaThreadId,
                                                        fields=requestId,endpoint)
  | groupBy($decorator.endpoint, agg=sum, value=allocationSize)
```

### Network activity by user

```bash
show events/jdk.SocketWrite | decorateByKey(RequestStart,
                                             key=eventThread/javaThreadId,
                                             decoratorKey=thread/javaThreadId,
                                             fields=userId,endpoint)
  | groupBy($decorator.userId, agg=sum, value=bytesWritten)
```

## Advanced: Multiple Decorators

You can sequentially decorate with different event types (note: requires multiple queries in v1):

### Step 1: Decorate with request context
```bash
show events/jdk.ExecutionSample | decorateByKey(RequestStart,
                                                  key=sampledThread/javaThreadId,
                                                  decoratorKey=thread/javaThreadId,
                                                  fields=requestId,endpoint)
```

### Step 2: Further analyze by endpoint
```bash
# Use output from step 1 to drill down into specific endpoint
show events/jdk.ExecutionSample[stackTrace/frames[matches(method/name/string, ".*Database.*")]]
  | decorateByKey(RequestStart,
                  key=sampledThread/javaThreadId,
                  decoratorKey=thread/javaThreadId,
                  fields=endpoint)
  | groupBy($decorator.endpoint)
```

## Best Practices

1. **Choose appropriate correlation keys**: Thread IDs work well for single-threaded request processing
2. **Emit request events early**: Ensure `RequestStart` fires before interesting events occur
3. **Limit decorator fields**: Only request fields you need for analysis
4. **Handle nulls**: Events without matching decorator will have null `$decorator.*` fields
5. **Cross-thread tracing**: For async processing, use custom correlation IDs instead of thread IDs

## Limitations

- Correlation is one-to-one: first matching decorator is used
- Thread ID correlation works only for thread-per-request models
- For async/reactive applications, emit custom correlation ID fields
