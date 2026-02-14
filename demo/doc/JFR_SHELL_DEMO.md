# JFR Shell Interactive Demo Guide

This guide demonstrates how to use `jfr-shell` to interactively explore and analyze JFR recordings. We'll use the `demo.jfr` file which contains a Datadog profiler recording with rich profiling data.

## Prerequisites

Build the jfr-shell tool:
```bash
cd /Users/jaroslav.bachorik/opensource/jafar
./gradlew :jfr-shell:jlinkZip
```

The jlinked binary with bundled JRE will be at: `jfr-shell/build/jlink/bin/jfr-shell`

## Demo 1: Quick Overview

First, let's get a quick overview of the recording using non-interactive commands:

```bash
cd /Users/jaroslav.bachorik/opensource/jafar
JFR_SHELL="jfr-shell/build/jlink/bin/jfr-shell"
DEMO_JFR="/Users/jaroslav.bachorik/demo.jfr"

# Get metadata summary
$JFR_SHELL metadata $DEMO_JFR --summary

# List all event types
$JFR_SHELL metadata $DEMO_JFR --events-only

# Count total events
$JFR_SHELL show $DEMO_JFR "events/datadog.ExecutionSample | count()"
```

**Expected Output:**
```
Types Summary:
  Non-primitive: 322 (events=225, non-events=97)
  Primitives:    9
  All metadata:  331

# For ExecutionSample count:
| count |
+-------+
| 14229 |
```

## Demo 2: CPU Hot Spot Analysis

Identify the most CPU-intensive methods from execution samples:

```bash
# Top 10 methods by sample count (descending)
$JFR_SHELL show $DEMO_JFR \
  "events/datadog.ExecutionSample | \
   groupBy(stackTrace/frames[0]/method/type/name) | \
   top(10, by=count)"

# Same analysis but group by method name instead of class
$JFR_SHELL show $DEMO_JFR \
  "events/datadog.MethodSample | \
   groupBy(stackTrace/frames[0]/method/name) | \
   top(10, by=count)"
```

**What to look for:**
- Methods with high sample counts are CPU hotspots
- Native methods like `poll` often appear at the top
- Focus on application code (not JVM internals) for optimization opportunities

## Demo 3: Thread Activity Analysis

Analyze which threads are most active:

```bash
# Top threads by execution samples
$JFR_SHELL show $DEMO_JFR \
  "events/datadog.ExecutionSample | \
   groupBy(eventThread/osName) | \
   top(15, by=count)"

# Thread state distribution
$JFR_SHELL show $DEMO_JFR \
  "events/datadog.ExecutionSample | \
   groupBy(state) | \
   top(10, by=count)"
```

**Insights:**
- Thread pool patterns (e.g., `qtp` prefix indicates Jetty thread pool)
- RUNNABLE vs PARKED state ratios show thread utilization
- High PARKED counts may indicate lock contention

## Demo 4: Exception Pattern Analysis

Find the most common exceptions:

```bash
# Count exception samples
$JFR_SHELL show $DEMO_JFR \
  "events/datadog.ExceptionSample | count()"

# Top exception types
$JFR_SHELL show $DEMO_JFR \
  "events/datadog.ExceptionSample | \
   groupBy(exceptionType/name) | \
   top(10, by=count)"
```

**Analysis Tips:**
- High exception counts can indicate error-prone code paths
- Some exceptions are expected (e.g., validation errors)
- Focus on unexpected exception types

## Demo 5: System Resource Metrics

Analyze CPU and heap usage patterns:

```bash
# CPU load statistics
$JFR_SHELL show $DEMO_JFR \
  "events/jdk.CPULoad/machineTotal | stats()"

# CPU load over time (with percentiles)
$JFR_SHELL show $DEMO_JFR \
  "events/jdk.CPULoad/machineTotal | sketch()"

# Heap usage statistics from Datadog profiler
$JFR_SHELL show $DEMO_JFR \
  "events/datadog.HeapUsage/used | stats()" --format json
```

**Expected Output:**
```json
{
  "min" : 1.65,
  "max" : 3.14,
  "avg" : 2.28,
  "stddev" : 0.32
}
```

## Demo 6: Endpoint Performance

Analyze web endpoint activity:

```bash
# Top endpoints by request count
$JFR_SHELL show $DEMO_JFR \
  "events/datadog.Endpoint | \
   groupBy(operation) | \
   top(15, by=count)"

# Specific endpoint filter
$JFR_SHELL show $DEMO_JFR \
  "events/datadog.Endpoint[contains(operation, \"api\")] | count()"
```

## Demo 7: Interactive Exploration

Now let's use the interactive shell for more exploratory analysis:

```bash
# Start interactive shell
$JFR_SHELL -f $DEMO_JFR
```

Once in the shell:

```
jfr> info
# Shows session information: file path, size, time range

jfr> metadata --search "datadog" --events-only
# Lists all Datadog-specific event types

jfr> show metadata/datadog.ExecutionSample --tree --depth 2
# Explore the structure of ExecutionSample events

jfr> show events/datadog.ExecutionSample --limit 5
# Display first 5 execution samples

jfr> show events/datadog.ExecutionSample/stackTrace/frames --limit 3
# Show stack traces from samples

jfr> show events/jdk.CPULoad[machineTotal>0.5] --limit 10
# Filter high CPU load events (>50%)

jfr> show events/datadog.ExecutionSample | \
      groupBy(eventThread/osName, agg=count) | \
      top(5, by=count)
# Top 5 threads interactively

jfr> exit
```

## Demo 8: Advanced Filtering

Complex queries using filters and aggregations:

```bash
# Find execution samples in RUNNABLE state
$JFR_SHELL show $DEMO_JFR \
  "events/datadog.ExecutionSample[state/name=\"RUNNABLE\"] | count()"

# Samples with specific method in stack
$JFR_SHELL show $DEMO_JFR \
  "events/datadog.ExecutionSample[\
     any:stackTrace/frames[\
       matches(method/name/string, \".*HashMap.*\")\
     ]\
   ] | count()"

# Thread CPU usage statistics
$JFR_SHELL show $DEMO_JFR \
  "events/jdk.ThreadCPULoad | \
   groupBy(eventThread/javaName, agg=avg, value=user)"
```

## Demo 9: Data Export

Export analysis results for further processing:

```bash
# Export to JSON for visualization tools
$JFR_SHELL show $DEMO_JFR \
  "events/datadog.ExecutionSample | \
   groupBy(stackTrace/frames[0]/method/type/name) | \
   top(20, by=count)" \
  --format json > hotspots.json

# Export CPU load timeline
$JFR_SHELL show $DEMO_JFR \
  "events/jdk.CPULoad" \
  --format json > cpu_timeline.json
```

## Demo 10: Comparing Metrics

Use jfr-shell to compare different aspects:

```bash
# Compare thread states
$JFR_SHELL show $DEMO_JFR \
  "events/datadog.ExecutionSample | groupBy(state)"

# Compare allocation patterns
$JFR_SHELL show $DEMO_JFR \
  "events/jdk.ThreadAllocationStatistics | \
   groupBy(thread/javaName, agg=sum, value=allocated) | \
   top(10, by=sum)"
```

## Key Takeaways

1. **Non-interactive mode** is perfect for automation and scripting
2. **Interactive mode** is great for exploratory analysis
3. **JfrPath expressions** provide powerful query capabilities
4. **Aggregation functions** enable statistical analysis
5. **JSON output** allows integration with visualization tools
6. **Metadata exploration** helps understand event structure

## Common Patterns

### Find Performance Bottlenecks
```bash
events/datadog.ExecutionSample | groupBy(stackTrace/frames[0]/method/type/name) | top(20, by=count)
```

### Analyze Thread Pool Utilization
```bash
events/datadog.ExecutionSample | groupBy(eventThread/osName, state)
```

### Track Resource Usage Over Time
```bash
events/jdk.CPULoad/machineTotal | sketch()
events/datadog.HeapUsage/used | stats()
```

### Identify Exception-Prone Code
```bash
events/datadog.ExceptionSample | groupBy(exceptionType/name) | top(10, by=count)
```

## Tips for Effective Analysis

1. **Start broad, then narrow**: Begin with counts and summaries, then filter
2. **Use --limit**: When exploring, limit results to avoid overwhelming output
3. **Leverage --format json**: For programmatic processing
4. **Combine filters**: Use AND/OR logic for complex queries
5. **Explore metadata first**: Understand event structure before querying
6. **Save useful queries**: Build a library of common analysis patterns

## Next Steps

- Explore the full JfrPath documentation in `doc/cli/JFRPath.md`
- Check `doc/jfr_shell_Usage.md` for complete command reference
- Experiment with your own JFR recordings
- Automate common analyses with shell scripts
