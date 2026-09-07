package io.jafar.shell.core.llm;

/**
 * Compact grammar references for the shell's query languages.
 *
 * <p>These are hand-written summaries rather than the full documents. The JfrPath reference in
 * {@code doc/cli/JFRPath.md} is over 1200 lines; sending it whole would work — the prefix is cached
 * — but most of it is prose aimed at humans, and a tighter reference measurably reduces the number
 * of invalid queries because the rules that actually trip a model up are stated where it will see
 * them.
 *
 * <p>The three rules at the top of the JfrPath section are there because each one produced a wrong
 * query during development: descending-by-default sorting, the bracketed argument to {@code
 * filter()}, and duration literals being nanoseconds unless suffixed.
 *
 * <p>These strings must stay byte-stable between calls: they are the cached prompt prefix.
 */
public final class LanguageReference {

  private LanguageReference() {}

  /** Returns the reference for a module id ({@code jfr}, {@code hdump}, {@code pprof}, ...). */
  public static String forModule(String moduleId) {
    if (moduleId == null) {
      return JFR_PATH;
    }
    return switch (moduleId.toLowerCase(java.util.Locale.ROOT)) {
      case "hdump" -> HDUMP_PATH;
      case "pprof", "otlp" -> SAMPLES_PATH;
      default -> JFR_PATH;
    };
  }

  /** Display name of the language for a module id. */
  public static String languageName(String moduleId) {
    if (moduleId == null) {
      return "JfrPath";
    }
    return switch (moduleId.toLowerCase(java.util.Locale.ROOT)) {
      case "hdump" -> "HdumpPath";
      case "pprof" -> "PprofPath";
      case "otlp" -> "OtlpPath";
      default -> "JfrPath";
    };
  }

  public static final String JFR_PATH =
      """
      Shape: <root>[/<segment>][<filter>] ( | <operator> )*

      Roots: events/<type>, metadata/<type>, chunks, constants (alias cp)

      Filters go in square brackets, directly after a segment:
        events/jdk.FileRead[bytes>1000]
        events/jdk.FileRead[path~"/tmp/.*"]
        events/jdk.FileRead[bytes>1000 and path~"/tmp/.*"]
      Operators: = != > >= < <= ~ (regex). Combine with and / or / not and parentheses.
      Filter functions: contains, startsWith, endsWith, matches(path,"re"[,"i"]), exists, empty,
      between(path,a,b), len(path), before, after, on.
      List fields take a match mode prefix: any: (default), all:, none: —
        events/jdk.ExecutionSample[none:stackTrace/frames[matches(method/name/string,".*Test.*")]]
      Filters can be interleaved at any segment:
        events/jdk.GCHeapSummary[when/when="After GC"]/heapSpace[committedSize>1000000]

      Numeric literals take unit suffixes:
        size (binary): K KB = 1024, M MB = 1024^2, G GB = 1024^3   ->  [bytes>1MB]
        duration (to nanoseconds): ns us ms s                      ->  [duration>10ms]
      A bare number in a duration field is nanoseconds: [duration>10000000] == [duration>10ms].
      There is no minute suffix; m already means mebibytes.

      Pipeline operators:
        terminal aggregations (cannot be chained with each other):
          count(), sum([path]), stats([path]), quantiles(q,...[, path=]), sketch([path]),
          timerange([path][, duration=][, format=]), flamegraph([direction=]),
          stackprofile([direction=][, buckets=][, minPct=])
        grouping and ordering:
          groupBy(key[, agg=count|sum|avg|min|max][, value=path][, sortBy=key|value][, asc=]),
          sortBy(field[, asc=]), top(n[, by=path][, asc=]), head(n), tail(n), distinct()
        shaping: select(...), filter([predicate])
        correlation:
          decorateByTime(<type>, fields=f1,f2 [, threadPath=] [, decoratorThreadPath=])
          decorateByKey(<type>, key=<path>, decoratorKey=<path>, fields=f1,f2)
          decorated fields are read with the $decorator. prefix
        value transforms: len, uppercase, lowercase, trim, abs, round, floor, ceil, contains,
          replace, formatDuration, asDateTime

      Three rules that cause most invalid queries:
        1. sortBy and top are DESCENDING by default. Pass asc=true for ascending — this matters
           for time series, where sortBy(startTime) gives the recording backwards.
        2. filter() takes a BRACKETED predicate, unlike a root filter:
             groupBy(path, agg=sum, value=bytes) | filter([sum>1048576])
        3. Terminal aggregations consume the stream; you cannot chain two of them.

      Examples:
        events/jdk.ExecutionSample | groupBy(sampledThread/javaName) | top(10, by=count)
        events/jdk.GCPhasePause | quantiles(0.5, 0.9, 0.99, path=duration)
        events/jdk.JavaMonitorEnter | groupBy(monitorClass, agg=sum, value=duration) | top(10, by=value)
        events/jdk.ObjectAllocationSample | groupBy(objectClass/name, agg=sum, value=weight) | top(20, by=value)
        events/jdk.FileRead[duration>10ms] | groupBy(path, agg=count) | top(10, by=count)
        events/jdk.ExecutionSample | timerange()
      """;

  public static final String HDUMP_PATH =
      """
      Shape: <root>[/<type>][<predicates>] ( | <operator> )*

      Roots: objects, classes, gcroots, clusters, duplicates, ages

      Type specs accept exact names, globs (java.util.*), instanceof/ for subclasses, and array
      forms (int[] or [I). Size units K KB M MB G GB work in predicates.
      Predicates: = != > >= < <= ~ (regex), and / or / not, plus contains(), startsWith(),
      between(), exists().

      Sorting takes a direction word and is descending by default:
        sortBy(retained desc), sortBy(name asc), sortBy(class asc, shallow desc)

      Operators: select, top, groupBy, count, sum, stats, sortBy, head, tail, filter, distinct,
      len, uppercase, lowercase, trim, replace, abs, round, floor, ceil,
      and the heap-specific ones:
        pathToRoot(), retentionPaths(), dominators(), retainedBreakdown(),
        checkLeaks(detector=threadlocal-leak|classloader-leak|duplicate-strings|
                   growing-collections|listener-leak|finalizer-queue),
        waste(), cacheStats(), threadOwner(), dominatedSize(), estimateAge(), whatif(),
        join(session=<id|alias>[, root="<jfr event type>"][, by=<field>])

      Rank by retained size, not shallow size: a large byte[] or String population is normal in
      every Java heap and only its dominator is a finding.

      On the classes root the join key is inferred as `name`; by=class applies to the objects root.

      Examples:
        classes | sortBy(retained desc) | top(20)
        objects/java.util.HashMap | waste() | sortBy(wastedBytes desc) | top(20)
        clusters | sortBy(score desc) | top(10)
        classes/com.example.Entry | retentionPaths()
        classes | join(session=rec, root="jdk.ObjectAllocationSample") | filter(allocCount > 0)
      """;

  public static final String SAMPLES_PATH =
      """
      Shape: samples[<predicates>] ( | <operator> )*

      Single root: samples.
      Fields: one per profile sample type (cpu, alloc_objects, ...), stackTrace as a leaf-first
      list addressable by index (stackTrace/0/name), plus label keys such as thread.
      Predicates: = != > >= < <=, combined with and / or.

      Operators: count, top, groupBy, stats, head, tail, filter (alias where), select,
      sortBy (aliases sort, orderby), stackprofile, distinct (alias unique).

      There is no join and no cross-session operator for these formats.

      Examples:
        samples | groupBy(stackTrace/0/name) | top(20)
        samples | groupBy(thread) | top(10)
        samples | stackprofile()
      """;
}
