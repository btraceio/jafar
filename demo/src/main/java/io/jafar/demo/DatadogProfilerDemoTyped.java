package io.jafar.demo;

import io.jafar.demo.types.*;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.TypedJafarParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Comprehensive Datadog profiler analysis using the typed API.
 *
 * <p>Demonstrates type-safe event handling with: - Complete profiling metrics (CPU, memory,
 * exceptions, I/O) - Datadog endpoint correlation via span IDs - Unorthodox insights (allocation
 * waste, efficiency analysis, virtual threads) - Single-pass analysis for optimal performance
 */
public class DatadogProfilerDemoTyped {

  public static void main(String[] args) throws Exception {
    Path jfrFile;
    if (args.length < 1) {
      System.err.println("Usage: java DatadogProfilerDemoTyped <recording.jfr>");
      jfrFile = Path.of("src/test/resources/test-dd.jfr");
      if (Files.exists(jfrFile)) {
        System.err.println("Using the default value of " + jfrFile.toAbsolutePath());
      } else {
        System.err.println("Use ../get_resources.sh to download the test resources");
        System.exit(1);
      }
    } else {
      jfrFile = Path.of(args[0]);
    }
    System.out.println("=".repeat(80));
    System.out.println("Comprehensive Datadog Profiler Analysis - Typed API");
    System.out.println("=".repeat(80));
    System.out.println("Analyzing: " + jfrFile);
    System.out.println();

    ParsingContext ctx = ParsingContext.create();

    System.out.println("Performing comprehensive single-pass analysis...");
    long startTime = System.currentTimeMillis();
    AnalysisResults results = analyzeSinglePass(ctx, jfrFile);
    long analysisTime = System.currentTimeMillis() - startTime;
    System.out.println();

    System.out.println("[1] CPU HOT SPOTS");
    System.out.println("-".repeat(80));
    displayHotSpots(results);
    System.out.println();

    System.out.println("[2] MEMORY & ALLOCATION ANALYSIS");
    System.out.println("-".repeat(80));
    displayMemoryAnalysis(results);
    System.out.println();

    System.out.println("[3] EXCEPTION ANALYSIS");
    System.out.println("-".repeat(80));
    displayExceptionAnalysis(results);
    System.out.println();

    System.out.println("[4] THREAD ANALYSIS");
    System.out.println("-".repeat(80));
    displayThreadAnalysis(results);
    System.out.println();

    System.out.println("[5] DATABASE & I/O INSIGHTS");
    System.out.println("-".repeat(80));
    displayDatabaseInsights(results);
    System.out.println();

    System.out.println("[6] DATADOG ENDPOINT PROFILING");
    System.out.println("-".repeat(80));
    displayEndpointProfiling(results);
    System.out.println();

    System.out.println("[7] ENDPOINT EFFICIENCY INSIGHTS");
    System.out.println("-".repeat(80));
    displayEndpointEfficiency(results);
    System.out.println();

    System.out.println("[8] ENDPOINT BLOCKING ANALYSIS");
    System.out.println("-".repeat(80));
    displayEndpointBlockingAnalysis(results);
    System.out.println();

    System.out.println("[9] ADVANCED ENDPOINT INSIGHTS");
    System.out.println("-".repeat(80));
    displayAdvancedEndpointInsights(results);
    System.out.println();

    System.out.println("[10] VIRTUAL THREAD INSIGHTS");
    System.out.println("-".repeat(80));
    displayVirtualThreadInsights(results);
    System.out.println();

    System.out.println("=".repeat(80));
    System.out.printf(
        "Analyzed %,d events in %dms (parser: %dms)%n",
        results.totalEvents.get(), analysisTime, ctx.uptime() / 1_000_000);
    System.out.println("=".repeat(80));
  }

  private static class EndpointMetrics {
    long cpuSamples = 0;
    long allocationSamples = 0;
    long allocationBytes = 0;
    long exceptions = 0;
    long parkedSamples = 0;
    long runnableSamples = 0;
    long queueTime = 0;
    long queueCount = 0;
    long endpointDuration = 0;
    long endpointCount = 0;
    final Set<String> topAllocatingClasses = new TreeSet<>();
    final Set<String> exceptionTypes = new TreeSet<>();

    // Per-request duration tracking (samples per span)
    final List<Long> requestDurationsInSamples = new ArrayList<>();

    // Wallclock sampling data
    long wallclockSamples = 0; // Total wallclock samples for this endpoint
    long blockedSamples = 0; // Samples where thread was PARKED/WAITING

    double getCpuIntensity() {
      long total = cpuSamples + parkedSamples;
      return total == 0 ? 0 : (runnableSamples * 100.0) / total;
    }

    double getBlockingPercentage() {
      return wallclockSamples == 0 ? 0 : (blockedSamples * 100.0) / wallclockSamples;
    }

    // Estimate blocking time in milliseconds
    long getEstimatedBlockingTimeMs(long samplingIntervalMs) {
      return blockedSamples * samplingIntervalMs;
    }

    double getAvgQueueTime() {
      return queueCount == 0 ? 0 : queueTime / (double) queueCount / 1_000_000.0;
    }

    double getAvgExecutionTime() {
      return endpointCount == 0 ? 0 : endpointDuration / (double) endpointCount / 1_000_000.0;
    }

    double getAllocationRate() {
      return endpointDuration == 0 ? 0 : (allocationBytes * 1_000_000_000.0) / endpointDuration;
    }

    long getDurationP50(long samplingIntervalMs) {
      if (requestDurationsInSamples.isEmpty()) return 0;
      Collections.sort(requestDurationsInSamples);
      return requestDurationsInSamples.get(requestDurationsInSamples.size() / 2)
          * samplingIntervalMs;
    }

    long getDurationP95(long samplingIntervalMs) {
      if (requestDurationsInSamples.isEmpty()) return 0;
      Collections.sort(requestDurationsInSamples);
      return requestDurationsInSamples.get((int) (requestDurationsInSamples.size() * 0.95))
          * samplingIntervalMs;
    }

    long getDurationP99(long samplingIntervalMs) {
      if (requestDurationsInSamples.isEmpty()) return 0;
      Collections.sort(requestDurationsInSamples);
      return requestDurationsInSamples.get((int) (requestDurationsInSamples.size() * 0.99))
          * samplingIntervalMs;
    }
  }

  private static class AnalysisResults {
    final Map<String, AtomicLong> methodSamples = new HashMap<>();
    final Map<String, AtomicLong> threadSamples = new HashMap<>();
    final Map<String, AtomicLong> threadStates = new HashMap<>();
    final Map<String, AtomicLong> exceptionTypes = new HashMap<>();
    final List<Double> cpuLoads = new ArrayList<>();
    final List<Long> heapUsages = new ArrayList<>();
    final Map<String, AtomicLong> eventCounts = new TreeMap<>();
    final AtomicLong totalEvents = new AtomicLong();

    final Map<String, Long> socketReadDurations = new HashMap<>();
    final Map<String, AtomicLong> socketReadCounts = new HashMap<>();
    final Map<String, AtomicLong> allocationByThread = new HashMap<>();
    final Map<String, AtomicLong> allocationByClass = new HashMap<>();
    final AtomicLong totalAllocated = new AtomicLong();
    final List<Long> socketReadLatencies = new ArrayList<>();
    final AtomicLong parkedThreadSamples = new AtomicLong();
    final AtomicLong runnableThreadSamples = new AtomicLong();
    final Map<String, String> socketTechnology = new HashMap<>();

    final Map<Long, String> spanToEndpoint = new HashMap<>();
    final Map<String, EndpointMetrics> endpointMetrics = new HashMap<>();
    final Map<Long, AtomicLong> spanSampleCounts = new HashMap<>(); // Track samples per span

    final AtomicLong totalExceptions = new AtomicLong();
    final AtomicLong virtualThreadPinned = new AtomicLong();
    final Map<String, AtomicLong> pinnedReasons = new HashMap<>();

    // Wallclock profiling configuration
    long wallclockSamplingIntervalMs = 10; // Default 10ms, will be read from config

    EndpointMetrics getOrCreateMetrics(String endpoint) {
      return endpointMetrics.computeIfAbsent(endpoint, k -> new EndpointMetrics());
    }
  }

  private static AnalysisResults analyzeSinglePass(ParsingContext ctx, Path jfrFile)
      throws Exception {
    AnalysisResults results = new AnalysisResults();

    try (TypedJafarParser parser = ctx.newTypedParser(jfrFile)) {
      // Read wallclock profiling configuration
      parser.handle(
          JFRDatadogDatadogProfilerConfig.class,
          (event, ctl) -> {
            results.totalEvents.incrementAndGet();
            long wallInterval = event.wallInterval();
            if (wallInterval > 0) {
              // Check if value is in nanoseconds or milliseconds
              // If > 1000, likely nanoseconds; if < 1000, likely milliseconds
              if (wallInterval > 1000) {
                results.wallclockSamplingIntervalMs = wallInterval / 1_000_000L;
              } else {
                results.wallclockSamplingIntervalMs = wallInterval;
              }
            }
          });

      // Endpoint tracking
      parser.handle(
          JFRDatadogEndpoint.class,
          (event, ctl) -> {
            results.totalEvents.incrementAndGet();
            long spanId = event.localRootSpanId();
            String endpoint = event.endpoint();
            String operation = event.operation();

            if (spanId != 0 && endpoint != null && !endpoint.isEmpty()) {
              String endpointKey =
                  operation != null && !operation.isEmpty() ? operation + " " + endpoint : endpoint;
              results.spanToEndpoint.put(spanId, endpointKey);

              EndpointMetrics metrics = results.getOrCreateMetrics(endpointKey);
              metrics.endpointCount++;
              long duration = event.duration();
              metrics.endpointDuration += duration;
            }
          });

      // JDK ExecutionSample (actual CPU profiling)
      parser.handle(
          JFRDatadogExecutionSample.class,
          (event, ctl) -> {
            results.totalEvents.incrementAndGet();
            processExecutionSample(event, results);
          });

      // Datadog MethodSample (wallclock profiling - use for blocking analysis)
      parser.handle(
          JFRDatadogMethodSample.class,
          (event, ctl) -> {
            results.totalEvents.incrementAndGet();
            processMethodSampleForBlocking(event, results);
          });

      // Exception samples
      parser.handle(
          JFRDatadogExceptionSample.class,
          (event, ctl) -> {
            results.totalEvents.incrementAndGet();
            results.totalExceptions.incrementAndGet();
            processExceptionSample(event, results);
          });

      // CPU load
      parser.handle(
          JFRJdkCPULoad.class,
          (event, ctl) -> {
            results.totalEvents.incrementAndGet();
            results.cpuLoads.add((double) event.machineTotal());
          });

      // Datadog heap usage
      parser.handle(
          JFRDatadogHeapUsage.class,
          (event, ctl) -> {
            results.totalEvents.incrementAndGet();
            results.heapUsages.add(event.size());
          });

      // JDK heap summary
      parser.handle(
          JFRJdkGCHeapSummary.class,
          (event, ctl) -> {
            results.totalEvents.incrementAndGet();
            results.heapUsages.add(event.heapUsed());
          });

      // Socket reads
      parser.handle(
          JFRJdkSocketRead.class,
          (event, ctl) -> {
            results.totalEvents.incrementAndGet();
            processSocketRead(event, results);
          });

      // Thread allocation statistics
      parser.handle(
          JFRJdkThreadAllocationStatistics.class,
          (event, ctl) -> {
            results.totalEvents.incrementAndGet();
            long allocated = event.allocated();
            results.totalAllocated.addAndGet(allocated);

            JFRJavaLangThread thread = event.thread();
            if (thread != null && thread.javaName() != null) {
              results
                  .allocationByThread
                  .computeIfAbsent(thread.javaName(), k -> new AtomicLong())
                  .addAndGet(allocated);
            }
          });

      // Datadog object allocation samples
      parser.handle(
          JFRDatadogObjectSample.class,
          (event, ctl) -> {
            results.totalEvents.incrementAndGet();
            long spanId = event.localRootSpanId();

            JFRJavaLangClass objectClass = event.objectClass();
            if (objectClass != null && objectClass.name() != null) {
              results
                  .allocationByClass
                  .computeIfAbsent(objectClass.name(), k -> new AtomicLong())
                  .incrementAndGet();
            }

            if (spanId != 0) {
              String endpoint = results.spanToEndpoint.get(spanId);
              if (endpoint != null) {
                EndpointMetrics metrics = results.getOrCreateMetrics(endpoint);
                metrics.allocationSamples++;
                // Insight #5: Weighted allocation tracking (weight × size)
                long weightedBytes = (long) (event.size() * event.weight());
                metrics.allocationBytes += weightedBytes;

                if (objectClass != null
                    && objectClass.name() != null
                    && metrics.topAllocatingClasses.size() < 5) {
                  metrics.topAllocatingClasses.add(objectClass.name().replace('/', '.'));
                }
              }
            }
          });

      // Queue time analysis
      parser.handle(
          JFRDatadogQueueTime.class,
          (event, ctl) -> {
            results.totalEvents.incrementAndGet();
            long spanId = event.localRootSpanId();
            if (spanId != 0) {
              String endpoint = results.spanToEndpoint.get(spanId);
              if (endpoint != null) {
                EndpointMetrics metrics = results.getOrCreateMetrics(endpoint);
                metrics.queueTime += event.duration();
                metrics.queueCount++;
              }
            }
          });

      // Virtual thread pinning
      parser.handle(
          JFRJdkVirtualThreadPinned.class,
          (event, ctl) -> {
            results.totalEvents.incrementAndGet();
            results.virtualThreadPinned.incrementAndGet();

            JFRJdkTypesStackTrace stackTrace = event.stackTrace();
            if (stackTrace != null
                && stackTrace.frames() != null
                && stackTrace.frames().length > 0) {
              JFRJdkTypesStackFrame topFrame = stackTrace.frames()[0];
              JFRJdkTypesMethod method = topFrame.method();
              if (method != null && method.type() != null && method.type().name() != null) {
                String className = method.type().name().replace('/', '.');
                results
                    .pinnedReasons
                    .computeIfAbsent(className, k -> new AtomicLong())
                    .incrementAndGet();
              }
            }
          });

      parser.run();

      // After parsing all events, populate per-request durations for each endpoint
      for (Map.Entry<Long, AtomicLong> entry : results.spanSampleCounts.entrySet()) {
        long spanId = entry.getKey();
        long sampleCount = entry.getValue().get();
        String endpoint = results.spanToEndpoint.get(spanId);
        if (endpoint != null) {
          EndpointMetrics metrics = results.endpointMetrics.get(endpoint);
          if (metrics != null) {
            metrics.requestDurationsInSamples.add(sampleCount);
          }
        }
      }
    }

    return results;
  }

  private static void processExecutionSample(
      JFRDatadogExecutionSample event, AnalysisResults results) {
    JFRJdkTypesStackTrace stackTrace = event.stackTrace();
    if (stackTrace != null && stackTrace.frames() != null && stackTrace.frames().length > 0) {
      JFRJdkTypesStackFrame topFrame = stackTrace.frames()[0];
      JFRJdkTypesMethod method = topFrame.method();

      if (method != null) {
        String className = "";
        if (method.type() != null && method.type().name() != null) {
          className = method.type().name().replace('/', '.');
        }
        String methodName = method.name() != null ? method.name() : "unknown";
        String key = className.isEmpty() ? methodName : className + "." + methodName;
        results.methodSamples.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
      }
    }

    JFRJavaLangThread thread = event.eventThread();
    if (thread != null && thread.osName() != null && !thread.osName().isEmpty()) {
      results
          .threadSamples
          .computeIfAbsent(thread.osName(), k -> new AtomicLong())
          .incrementAndGet();
    }

    String state = event.state();
    if (state != null && !state.isEmpty()) {
      if ("RUNNABLE".equals(state)) {
        results.runnableThreadSamples.incrementAndGet();
      } else if ("PARKED".equals(state)) {
        results.parkedThreadSamples.incrementAndGet();
      }
      results.threadStates.computeIfAbsent(state, k -> new AtomicLong()).incrementAndGet();
    }
  }

  private static void processMethodSampleForBlocking(
      JFRDatadogMethodSample event, AnalysisResults results) {
    // MethodSample is wallclock profiling - use for blocking time analysis
    String state = event.state();
    if (state != null && !state.isEmpty()) {
      if ("RUNNABLE".equals(state)) {
        results.runnableThreadSamples.incrementAndGet();
      } else if ("PARKED".equals(state)) {
        results.parkedThreadSamples.incrementAndGet();
      }
      results.threadStates.computeIfAbsent(state, k -> new AtomicLong()).incrementAndGet();
    }

    JFRJavaLangThread thread = event.eventThread();
    if (thread != null && thread.osName() != null && !thread.osName().isEmpty()) {
      results
          .threadSamples
          .computeIfAbsent(thread.osName(), k -> new AtomicLong())
          .incrementAndGet();
    }

    // Track blocking behavior per endpoint using wallclock samples
    long spanId = event.localRootSpanId();
    if (spanId != 0) {
      String endpoint = results.spanToEndpoint.get(spanId);
      if (endpoint != null) {
        EndpointMetrics metrics = results.getOrCreateMetrics(endpoint);
        metrics.wallclockSamples++; // Count total wallclock samples

        // Track state for legacy metrics
        metrics.cpuSamples++; // Legacy: Actually wallclock samples
        if ("RUNNABLE".equals(state)) {
          metrics.runnableSamples++;
        } else if ("PARKED".equals(state) || "WAITING".equals(state)) {
          metrics.parkedSamples++;
          metrics.blockedSamples++; // Count blocked time samples
        }
      }

      // Track samples per span for duration percentiles
      results.spanSampleCounts.computeIfAbsent(spanId, k -> new AtomicLong()).incrementAndGet();
    }
  }

  private static void processExceptionSample(
      JFRDatadogExceptionSample event, AnalysisResults results) {
    JFRJdkTypesStackTrace stackTrace = event.stackTrace();
    if (stackTrace != null && stackTrace.frames() != null && stackTrace.frames().length > 0) {
      String exceptionType = null;

      for (JFRJdkTypesStackFrame frame : stackTrace.frames()) {
        JFRJdkTypesMethod method = frame.method();
        if (method == null || !"<init>".equals(method.name())) {
          break;
        }

        if (method.type() != null && method.type().name() != null) {
          String exName = method.type().name().replace('/', '.');
          if (!exName.equals("java.lang.Exception")
              && !exName.equals("java.lang.Error")
              && !exName.equals("java.lang.Throwable")) {
            exceptionType = exName;
          }
        }
      }

      if (exceptionType != null && !exceptionType.isEmpty()) {
        results
            .exceptionTypes
            .computeIfAbsent(exceptionType, k -> new AtomicLong())
            .incrementAndGet();
      }

      long spanId = event.localRootSpanId();
      if (spanId != 0) {
        String endpoint = results.spanToEndpoint.get(spanId);
        if (endpoint != null) {
          EndpointMetrics metrics = results.getOrCreateMetrics(endpoint);
          metrics.exceptions++;
          if (exceptionType != null && metrics.exceptionTypes.size() < 3) {
            metrics.exceptionTypes.add(exceptionType);
          }
        }
      }
    }
  }

  private static void processSocketRead(JFRJdkSocketRead event, AnalysisResults results) {
    try {
      String address = event.address();
      int port = event.port();
      long duration = event.duration();

      if (address != null && !address.isEmpty() && duration > 0) {
        String endpoint = address + ":" + port;

        results.socketReadDurations.merge(endpoint, duration, Long::sum);
        results.socketReadCounts.computeIfAbsent(endpoint, k -> new AtomicLong()).incrementAndGet();
        results.socketReadLatencies.add(duration);

        JFRJdkTypesStackTrace stackTrace = event.stackTrace();
        if (stackTrace != null && stackTrace.frames() != null) {
          for (JFRJdkTypesStackFrame frame : stackTrace.frames()) {
            JFRJdkTypesMethod method = frame.method();
            if (method != null && method.type() != null && method.type().name() != null) {
              String className = method.type().name();
              if (className.contains("postgresql")) {
                results.socketTechnology.put(endpoint, "PostgreSQL");
                break;
              } else if (className.contains("rabbitmq") || className.contains("amqp")) {
                results.socketTechnology.put(endpoint, "RabbitMQ");
                break;
              } else if (className.contains("redis") || className.contains("lettuce")) {
                results.socketTechnology.put(endpoint, "Redis");
                break;
              } else if (className.contains("mysql")) {
                results.socketTechnology.put(endpoint, "MySQL");
                break;
              }
            }
          }
        }
      }
    } catch (Exception e) {
      // Skip malformed events
    }
  }

  private static void displayHotSpots(AnalysisResults results) {
    System.out.println("Top 10 methods by CPU time (from datadog.ExecutionSample):");
    results.methodSamples.entrySet().stream()
        .sorted(
            Map.Entry.<String, AtomicLong>comparingByValue(
                (a, b) -> Long.compare(b.get(), a.get())))
        .limit(10)
        .forEach(e -> System.out.printf("  %6d samples: %s%n", e.getValue().get(), e.getKey()));

    if (results.methodSamples.isEmpty()) {
      System.out.println("  No execution samples found");
    }
  }

  private static void displayMemoryAnalysis(AnalysisResults results) {
    if (results.totalAllocated.get() > 0) {
      System.out.printf("Total allocated: %s%n", formatBytes(results.totalAllocated.get()));
      System.out.println("\nTop allocating threads:");

      results.allocationByThread.entrySet().stream()
          .sorted(
              Map.Entry.<String, AtomicLong>comparingByValue(
                  (a, b) -> Long.compare(b.get(), a.get())))
          .limit(10)
          .forEach(
              e -> System.out.printf("  %s: %s%n", formatBytes(e.getValue().get()), e.getKey()));
    }

    if (!results.allocationByClass.isEmpty()) {
      System.out.println("\nTop allocating classes (potential waste candidates):");
      results.allocationByClass.entrySet().stream()
          .sorted(
              Map.Entry.<String, AtomicLong>comparingByValue(
                  (a, b) -> Long.compare(b.get(), a.get())))
          .limit(10)
          .forEach(
              e -> {
                String className = e.getKey().replace('/', '.');
                long count = e.getValue().get();
                System.out.printf("  %6d allocations: %s", count, className);

                if (className.contains("StringBuilder")
                    || className.contains("StringBuffer")
                    || className.contains("ArrayList")) {
                  System.out.print(" ⚠️  [HIGH CHURN]");
                } else if (className.startsWith("java.util.stream")
                    || className.contains("$$Lambda$")) {
                  System.out.print(" ℹ️  [STREAM/LAMBDA]");
                } else if (className.contains("Exception")) {
                  System.out.print(" 🔥 [EXCEPTION]");
                }
                System.out.println();
              });
    }

    if (!results.heapUsages.isEmpty()) {
      long avgHeap =
          (long) results.heapUsages.stream().mapToLong(Long::longValue).average().orElse(0);
      long maxHeap = results.heapUsages.stream().mapToLong(Long::longValue).max().orElse(0);
      System.out.printf(
          "\nHeap Used: avg=%s, max=%s (from %d samples)%n",
          formatBytes(avgHeap), formatBytes(maxHeap), results.heapUsages.size());
    }
  }

  private static void displayExceptionAnalysis(AnalysisResults results) {
    if (results.totalExceptions.get() == 0) {
      System.out.println("No exceptions detected");
      return;
    }

    long total = results.exceptionTypes.values().stream().mapToLong(AtomicLong::get).sum();
    System.out.printf("Total exception samples: %,d%n", total);

    System.out.println("\nTop exception types:");
    results.exceptionTypes.entrySet().stream()
        .sorted(
            Map.Entry.<String, AtomicLong>comparingByValue(
                (a, b) -> Long.compare(b.get(), a.get())))
        .limit(10)
        .forEach(
            e -> {
              long count = e.getValue().get();
              double pct = (count * 100.0) / total;
              System.out.printf("  %6d (%.1f%%): %s%n", count, pct, e.getKey());
            });

    long reflectionExceptions =
        results.exceptionTypes.keySet().stream()
            .filter(
                k -> k.contains("InvocationTargetException") || k.contains("NoSuchMethodException"))
            .mapToLong(k -> results.exceptionTypes.get(k).get())
            .sum();

    if (reflectionExceptions > total * 0.3) {
      System.out.printf(
          "\n⚠️  HIGH REFLECTION USAGE: %.0f%% of exceptions are reflection-related%n",
          (reflectionExceptions * 100.0) / total);
    }
  }

  private static void displayThreadAnalysis(AnalysisResults results) {
    long totalSamples = results.runnableThreadSamples.get() + results.parkedThreadSamples.get();
    if (totalSamples == 0) {
      System.out.println("No thread state data available");
      return;
    }

    double runnable = (results.runnableThreadSamples.get() * 100.0) / totalSamples;
    double parked = (results.parkedThreadSamples.get() * 100.0) / totalSamples;

    System.out.printf("Thread utilization: %.1f%% RUNNABLE, %.1f%% PARKED%n", runnable, parked);

    if (parked > 20) {
      System.out.printf(
          "\n⚠️  HIGH THREAD PARKING: %.0f%% suggests lock contention or blocking I/O%n", parked);
    }

    // Overall application behavior assessment
    if (parked > runnable * 2) {
      System.out.println(
          "\n⚠️  HIGH I/O BLOCKING: Application spends more time waiting than executing!");
      System.out.println("   Consider: async I/O, connection pooling, or caching");
    } else if (runnable > parked * 3) {
      System.out.println("\n💪 CPU-BOUND APPLICATION: Most time spent executing");
      System.out.println("   Consider: algorithm optimization, parallelization");
    }

    System.out.println("\nTop 10 most active threads:");
    results.threadSamples.entrySet().stream()
        .sorted(
            Map.Entry.<String, AtomicLong>comparingByValue(
                (a, b) -> Long.compare(b.get(), a.get())))
        .limit(10)
        .forEach(e -> System.out.printf("  %6d samples: %s%n", e.getValue().get(), e.getKey()));
  }

  private static void displayDatabaseInsights(AnalysisResults results) {
    if (results.socketReadCounts.isEmpty()) {
      System.out.println("No database I/O detected");
      return;
    }

    System.out.println("Socket I/O Analysis:");

    results.socketReadDurations.entrySet().stream()
        .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
        .limit(5)
        .forEach(
            e -> {
              String endpoint = e.getKey();
              long totalNs = e.getValue();
              long count = results.socketReadCounts.get(endpoint).get();
              long avgNs = totalNs / count;

              String tech = results.socketTechnology.get(endpoint);
              String endpointDisplay = tech != null ? endpoint + " (" + tech + ")" : endpoint;

              System.out.printf("  %s%n", endpointDisplay);
              System.out.printf(
                  "    %,d reads, total time: %,d ms, avg: %.2f ms%n",
                  count, totalNs / 1_000_000, avgNs / 1_000_000.0);
            });

    if (!results.socketReadLatencies.isEmpty()) {
      Collections.sort(results.socketReadLatencies);
      long p50 = results.socketReadLatencies.get(results.socketReadLatencies.size() / 2);
      long p95 = results.socketReadLatencies.get((int) (results.socketReadLatencies.size() * 0.95));
      long p99 = results.socketReadLatencies.get((int) (results.socketReadLatencies.size() * 0.99));

      System.out.println("\nLatency percentiles:");
      System.out.printf(
          "  p50: %.2f ms, p95: %.2f ms, p99: %.2f ms%n",
          p50 / 1_000_000.0, p95 / 1_000_000.0, p99 / 1_000_000.0);

      if (p95 > 10_000_000) {
        System.out.println(
            "\n⚠️  HIGH LATENCY DETECTED: p95 > 10ms suggests network or database issues");
      }
    }
  }

  private static void displayEndpointProfiling(AnalysisResults results) {
    if (results.spanToEndpoint.isEmpty()) {
      System.out.println("No Datadog endpoint data available");
      return;
    }

    System.out.printf("Tracked %d unique endpoints%n", results.spanToEndpoint.size());

    Map<String, AtomicLong> endpointCpuSamples = new HashMap<>();
    Map<String, AtomicLong> endpointAllocations = new HashMap<>();
    Map<String, Long> endpointAllocationBytes = new HashMap<>();

    for (Map.Entry<String, EndpointMetrics> entry : results.endpointMetrics.entrySet()) {
      EndpointMetrics m = entry.getValue();
      if (m.cpuSamples > 0) {
        endpointCpuSamples.put(entry.getKey(), new AtomicLong(m.cpuSamples));
      }
      if (m.allocationSamples > 0) {
        endpointAllocations.put(entry.getKey(), new AtomicLong(m.allocationSamples));
        endpointAllocationBytes.put(entry.getKey(), m.allocationBytes);
      }
    }

    if (!endpointCpuSamples.isEmpty()) {
      System.out.println("\nTop endpoints by CPU samples:");
      endpointCpuSamples.entrySet().stream()
          .sorted(
              Map.Entry.<String, AtomicLong>comparingByValue(
                  (a, b) -> Long.compare(b.get(), a.get())))
          .limit(10)
          .forEach(e -> System.out.printf("  %6d samples: %s%n", e.getValue().get(), e.getKey()));
    }

    if (!endpointAllocations.isEmpty()) {
      System.out.println("\nTop endpoints by allocation samples:");
      endpointAllocations.entrySet().stream()
          .sorted(
              Map.Entry.<String, AtomicLong>comparingByValue(
                  (a, b) -> Long.compare(b.get(), a.get())))
          .limit(10)
          .forEach(
              e -> {
                long samples = e.getValue().get();
                long bytes = endpointAllocationBytes.getOrDefault(e.getKey(), 0L);
                System.out.printf(
                    "  %6d samples (%s): %s%n", samples, formatBytes(bytes), e.getKey());
              });
    }
  }

  private static void displayEndpointEfficiency(AnalysisResults results) {
    if (results.endpointMetrics.isEmpty()) {
      System.out.println("No endpoint data available");
      return;
    }

    System.out.println("🔥 Highest Allocation Rate (bytes/sec):");
    results.endpointMetrics.entrySet().stream()
        .sorted(
            Map.Entry.<String, EndpointMetrics>comparingByValue(
                (a, b) -> Double.compare(b.getAllocationRate(), a.getAllocationRate())))
        .limit(5)
        .forEach(
            e -> {
              EndpointMetrics m = e.getValue();
              if (m.getAllocationRate() > 0) {
                System.out.printf(
                    "  %s/sec: %s (avg exec: %.2fms)%n",
                    formatBytes((long) m.getAllocationRate()), e.getKey(), m.getAvgExecutionTime());
              }
            });

    System.out.println("\n💪 Most CPU-Intensive (RUNNABLE %):");
    results.endpointMetrics.entrySet().stream()
        .filter(e -> (e.getValue().cpuSamples + e.getValue().parkedSamples) > 10)
        .sorted(
            Map.Entry.<String, EndpointMetrics>comparingByValue(
                (a, b) -> Double.compare(b.getCpuIntensity(), a.getCpuIntensity())))
        .limit(5)
        .forEach(
            e -> {
              EndpointMetrics m = e.getValue();
              System.out.printf("  %.1f%% CPU: %s%n", m.getCpuIntensity(), e.getKey());
            });

    System.out.println("\n⏸️  Most I/O-Blocked (low RUNNABLE %):");
    List<Map.Entry<String, EndpointMetrics>> blocked =
        results.endpointMetrics.entrySet().stream()
            .filter(e -> (e.getValue().cpuSamples + e.getValue().parkedSamples) > 10)
            .filter(e -> e.getValue().getCpuIntensity() < 50) // Only show truly blocked endpoints
            .sorted(
                Map.Entry.<String, EndpointMetrics>comparingByValue(
                    (a, b) -> Double.compare(a.getCpuIntensity(), b.getCpuIntensity())))
            .limit(5)
            .collect(Collectors.toList());
    if (blocked.isEmpty()) {
      System.out.println("  No significantly blocked endpoints detected");
    } else {
      blocked.forEach(
          e -> {
            EndpointMetrics m = e.getValue();
            System.out.printf(
                "  %.1f%% RUNNABLE (%d/%d samples): %s%n",
                m.getCpuIntensity(), m.runnableSamples, m.cpuSamples + m.parkedSamples, e.getKey());
          });
    }

    List<Map.Entry<String, EndpointMetrics>> exceptionHeavy =
        results.endpointMetrics.entrySet().stream()
            .filter(e -> e.getValue().exceptions > 0)
            .sorted(
                Map.Entry.<String, EndpointMetrics>comparingByValue(
                    (a, b) -> Long.compare(b.exceptions, a.exceptions)))
            .limit(5)
            .collect(Collectors.toList());

    if (!exceptionHeavy.isEmpty()) {
      System.out.println("\n🔥 Exception-Heavy Endpoints:");
      for (Map.Entry<String, EndpointMetrics> e : exceptionHeavy) {
        EndpointMetrics m = e.getValue();
        System.out.printf("  %6d exceptions: %s%n", m.exceptions, e.getKey());
        if (m.cpuSamples > 0) {
          double exceptionRate = m.exceptions / (double) m.cpuSamples;
          if (exceptionRate > 0.1) {
            System.out.println(
                "    ⚠️  SUSPICIOUS: High exception rate suggests control-flow usage!");
          }
        }
      }
    }

    List<Map.Entry<String, EndpointMetrics>> queueBottlenecks =
        results.endpointMetrics.entrySet().stream()
            .filter(e -> e.getValue().queueCount > 0 && e.getValue().endpointCount > 0)
            .sorted(
                Map.Entry.<String, EndpointMetrics>comparingByValue(
                    (a, b) ->
                        Double.compare(
                            b.getAvgQueueTime() / Math.max(0.001, b.getAvgExecutionTime()),
                            a.getAvgQueueTime() / Math.max(0.001, a.getAvgExecutionTime()))))
            .limit(5)
            .collect(Collectors.toList());

    if (!queueBottlenecks.isEmpty()) {
      System.out.println("\n🚦 Worst Queue/Execution Ratio:");
      for (Map.Entry<String, EndpointMetrics> e : queueBottlenecks) {
        EndpointMetrics m = e.getValue();
        double ratio = m.getAvgQueueTime() / Math.max(0.001, m.getAvgExecutionTime());
        System.out.printf(
            "  %.2fx (queue: %.2fms, exec: %.2fms): %s%n",
            ratio, m.getAvgQueueTime(), m.getAvgExecutionTime(), e.getKey());
      }
    }
  }

  private static void displayEndpointBlockingAnalysis(AnalysisResults results) {
    if (results.endpointMetrics.isEmpty()) {
      System.out.println("No endpoint data available");
      return;
    }

    // Use actual sampling interval from DatadogProfilerConfig
    long intervalFromConfig = results.wallclockSamplingIntervalMs;

    // Make final for lambda capture
    final long samplingIntervalMs =
        (intervalFromConfig == 0 || intervalFromConfig == 10) ? 10 : intervalFromConfig;

    if (intervalFromConfig == 0 || intervalFromConfig == 10) {
      System.out.println(
          "Blocking Time Analysis (from wallclock profiling, assuming ~10ms intervals):");
    } else {
      System.out.println(
          "Blocking Time Analysis (from wallclock profiling, "
              + samplingIntervalMs
              + "ms intervals):");
    }
    System.out.println();

    // Show endpoints with highest blocking percentage
    System.out.println("🔴 Most Blocked Endpoints (by % time blocked):");
    results.endpointMetrics.entrySet().stream()
        .filter(
            e -> e.getValue().wallclockSamples > 10) // Minimum samples for statistical relevance
        .sorted(
            Map.Entry.<String, EndpointMetrics>comparingByValue(
                (a, b) -> Double.compare(b.getBlockingPercentage(), a.getBlockingPercentage())))
        .limit(5)
        .forEach(
            e -> {
              EndpointMetrics m = e.getValue();
              long estimatedBlockingMs = m.getEstimatedBlockingTimeMs(samplingIntervalMs);
              System.out.printf(
                  "  %.1f%% blocked (%d/%d samples, ~%dms blocked): %s%n",
                  m.getBlockingPercentage(),
                  m.blockedSamples,
                  m.wallclockSamples,
                  estimatedBlockingMs,
                  e.getKey());
              if (m.getBlockingPercentage() > 70) {
                System.out.println("    ⚠️  SEVERE BLOCKING: Endpoint spends most time waiting!");
              } else if (m.getBlockingPercentage() > 40) {
                System.out.println(
                    "    ⚠️  HIGH BLOCKING: Consider async I/O or reducing wait times");
              }
            });

    // Show endpoints with highest absolute blocking time
    System.out.println("\n⏱️  Longest Total Blocking Time:");
    results.endpointMetrics.entrySet().stream()
        .filter(e -> e.getValue().blockedSamples > 0)
        .sorted(
            Map.Entry.<String, EndpointMetrics>comparingByValue(
                (a, b) ->
                    Long.compare(
                        b.getEstimatedBlockingTimeMs(samplingIntervalMs),
                        a.getEstimatedBlockingTimeMs(samplingIntervalMs))))
        .limit(5)
        .forEach(
            e -> {
              EndpointMetrics m = e.getValue();
              long estimatedBlockingMs = m.getEstimatedBlockingTimeMs(samplingIntervalMs);
              System.out.printf(
                  "  ~%dms blocked (%.1f%%, %d calls): %s%n",
                  estimatedBlockingMs, m.getBlockingPercentage(), m.endpointCount, e.getKey());
            });

    // Show CPU-intensive endpoints (low blocking)
    System.out.println("\n💪 Most CPU-Intensive (least blocked):");
    results.endpointMetrics.entrySet().stream()
        .filter(e -> e.getValue().wallclockSamples > 10)
        .filter(e -> e.getValue().getBlockingPercentage() < 30)
        .sorted(
            Map.Entry.<String, EndpointMetrics>comparingByValue(
                (a, b) -> Double.compare(a.getBlockingPercentage(), b.getBlockingPercentage())))
        .limit(5)
        .forEach(
            e -> {
              EndpointMetrics m = e.getValue();
              System.out.printf(
                  "  %.1f%% blocked (%d/%d samples): %s%n",
                  m.getBlockingPercentage(), m.blockedSamples, m.wallclockSamples, e.getKey());
            });

    if (results.endpointMetrics.values().stream()
        .filter(m -> m.wallclockSamples > 10)
        .noneMatch(m -> m.getBlockingPercentage() < 30)) {
      System.out.println("  No CPU-intensive endpoints found (all show significant blocking)");
    }
  }

  private static void displayAdvancedEndpointInsights(AnalysisResults results) {
    if (results.endpointMetrics.isEmpty()) {
      System.out.println("No endpoint data available");
      return;
    }

    // Insight #4: Request Duration Distribution (from wallclock profiling samples)
    final long samplingIntervalMs =
        results.wallclockSamplingIntervalMs > 0 ? results.wallclockSamplingIntervalMs : 10;
    System.out.println(
        "📊 Request Duration Distribution (samples × "
            + samplingIntervalMs
            + "ms interval, latency consistency):");
    results.endpointMetrics.entrySet().stream()
        .filter(e -> e.getValue().requestDurationsInSamples.size() >= 5)
        .sorted(
            Map.Entry.<String, EndpointMetrics>comparingByValue(
                (a, b) ->
                    Long.compare(
                        b.getDurationP99(samplingIntervalMs),
                        a.getDurationP99(samplingIntervalMs))))
        .limit(10)
        .forEach(
            e -> {
              EndpointMetrics m = e.getValue();
              long p50 = m.getDurationP50(samplingIntervalMs);
              long p95 = m.getDurationP95(samplingIntervalMs);
              long p99 = m.getDurationP99(samplingIntervalMs);

              System.out.printf("  %s%n", e.getKey());
              System.out.printf(
                  "    p50: %dms, p95: %dms, p99: %dms (n=%d requests)%n",
                  p50, p95, p99, m.requestDurationsInSamples.size());

              // Check for latency consistency
              if (p99 > p50 * 10) {
                System.out.println(
                    "    ⚠️  INCONSISTENT LATENCY: p99 is 10x+ p50, investigate outliers!");
              } else if (p99 < p50 * 3) {
                System.out.println("    ✓ CONSISTENT LATENCY: Well-behaved endpoint");
              }
            });

    // Insight #5: Weighted Allocation Bytes
    System.out.println("\n💾 Weighted Allocation Bytes (estimated actual allocations):");
    results.endpointMetrics.entrySet().stream()
        .filter(e -> e.getValue().allocationBytes > 0)
        .sorted(
            Map.Entry.<String, EndpointMetrics>comparingByValue(
                (a, b) -> Long.compare(b.allocationBytes, a.allocationBytes)))
        .limit(5)
        .forEach(
            e -> {
              EndpointMetrics m = e.getValue();
              System.out.printf(
                  "  %s (%d samples): %s%n",
                  formatBytes(m.allocationBytes), m.allocationSamples, e.getKey());
            });
  }

  private static void displayVirtualThreadInsights(AnalysisResults results) {
    if (results.virtualThreadPinned.get() == 0) {
      System.out.println("No virtual thread pinning detected");
      return;
    }

    System.out.printf("Virtual thread pinning events: %,d%n", results.virtualThreadPinned.get());
    System.out.println("\n⚠️  PERFORMANCE IMPACT: Virtual threads pinned to carrier threads!");

    if (!results.pinnedReasons.isEmpty()) {
      System.out.println("\nTop pinning locations:");
      results.pinnedReasons.entrySet().stream()
          .sorted(
              Map.Entry.<String, AtomicLong>comparingByValue(
                  (a, b) -> Long.compare(b.get(), a.get())))
          .limit(5)
          .forEach(e -> System.out.printf("  %6d times: %s%n", e.getValue().get(), e.getKey()));

      System.out.println("\n💡 Common causes:");
      System.out.println("   - synchronized blocks/methods");
      System.out.println("   - Native methods or foreign functions");
      System.out.println("   - Object.wait()");
      System.out.println("   Consider: ReentrantLock, async I/O, or restructure code");
    }
  }

  private static String formatBytes(long bytes) {
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
    if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
    return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
  }
}
