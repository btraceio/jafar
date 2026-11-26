package io.jafar.testsupport.work;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The work counters and the parser methods behind them: the one list the work-budget agent
 * instruments and the work tests report (R7). Its rationale is in {@code
 * doc/plans/work-budgets.md}.
 *
 * <p>A probe is either the entry of a named method ({@link Probe#isCall()} false) or a call from a
 * named method to a named callee ({@link Probe#isCall()} true). A call probe exists where the work
 * to count has no method of its own: {@code GenericValueReader.readValue} decodes events and
 * constant-pool entries alike, so only its call from {@code EventStream.onEvent} means "an event
 * was decoded".
 *
 * <p>An owner ending in {@code $*} matches every nested class of the named class: the typed parser
 * reads events in an anonymous listener class inside {@code TypedJafarParserImpl}, whose numbered
 * name is not stable, while its enclosing class and method are.
 *
 * <p>Names, descriptors and call sites are checked against the compiled parser classes by {@code
 * WorkProbesTest}, and the agent reports a probe it could not place in a class it loaded: a renamed
 * method fails loudly instead of leaving its counter at 0 (R3).
 *
 * <p>Every counter name is exactly two dot-separated words ({@code events.decoded}), so a budget
 * key {@code <module>.<entry>.<fixture>.<counter>} splits unambiguously into its entry point and
 * counter, which {@code scripts/work-budget-check.sh} relies on.
 */
public final class WorkProbes {

  /** Internal name of the class whose static {@code hit(int)} every probe calls. */
  public static final String COUNTERS_CLASS = "io/jafar/testsupport/work/WorkCounters";

  /** Name and descriptor of the counting method in {@link #COUNTERS_CLASS}. */
  public static final String HIT_METHOD = "hit";

  public static final String HIT_DESCRIPTOR = "(I)V";

  /**
   * System property the agent sets in {@code premain}; its absence means the JVM runs without the
   * agent and every counter would read 0.
   */
  public static final String AGENT_PROPERTY = "jafar.work.agent";

  /**
   * System property listing what the agent placed, as {@code <probe id>@<class>=<sites>} entries
   * separated by {@code ;}. A system property, not a static field, because the agent and the test
   * may see this class through different class loaders.
   */
  public static final String APPLIED_PROPERTY = "jafar.work.applied";

  /** System property listing the agent's failures, separated by {@code ;}; absent when none. */
  public static final String ERRORS_PROPERTY = "jafar.work.errors";

  private static final String RS = "Lio/jafar/parser/internal_api/RecordingStream;";
  private static final String CTX = "Lio/jafar/parser/api/ParserContext;";
  private static final String MC = "Lio/jafar/parser/internal_api/metadata/MetadataClass;";
  private static final String HEADER = "Lio/jafar/parser/internal_api/ChunkHeader;";
  private static final String LISTENER = "Lio/jafar/parser/internal_api/ChunkParserListener;";
  private static final String ON_EVENT = "(" + CTX + "JJJJ)Z";

  private static final String CHUNK_PARSER = "io/jafar/parser/internal_api/StreamingChunkParser";
  private static final String METADATA_CLASS =
      "io/jafar/parser/internal_api/metadata/MetadataClass";
  private static final String POOL = "io/jafar/parser/internal_api/MutableConstantPool";

  /** Chunks the chunk parser started: every chunk of the recording. */
  public static final String CHUNKS_STARTED = "chunks.started";

  /** Chunks whose constant pools and events were read: not skipped after their metadata. */
  public static final String CHUNKS_READ = "chunks.read";

  /** Event records (type id above 1) handed to the parser listener. */
  public static final String EVENTS_READ = "events.read";

  /** Events whose payload was decoded into a value, by any of the three decoders. */
  public static final String EVENTS_DECODED = "events.decoded";

  /** Varints read through {@code RecordingStream.readVarint}, from any part of a chunk. */
  public static final String VARINTS_READ = "varints.read";

  /** Values skipped by the {@code TypeSkipper} interpreter: fields, and constant-pool entries. */
  public static final String VALUES_SKIPPED = "values.skipped";

  /** Constant-pool entries indexed: located and skipped while the checkpoints are read. */
  public static final String CONSTANTS_INDEXED = "constants.indexed";

  /** Constant-pool entries decoded into a value (lazily, on first use). */
  public static final String CONSTANTS_DECODED = "constants.decoded";

  /** The counters, in report order; a counter's index is the argument its probes pass. */
  public static final List<String> COUNTERS =
      Collections.unmodifiableList(
          Arrays.asList(
              CHUNKS_STARTED,
              CHUNKS_READ,
              EVENTS_READ,
              EVENTS_DECODED,
              VARINTS_READ,
              VALUES_SKIPPED,
              CONSTANTS_INDEXED,
              CONSTANTS_DECODED));

  /** Every probe; several may feed one counter. */
  public static final List<Probe> PROBES;

  static {
    List<Probe> probes = new ArrayList<>();
    probes.add(
        Probe.entry(
            CHUNKS_STARTED, CHUNK_PARSER, "processChunk", "(" + RS + HEADER + LISTENER + "J)V"));
    probes.add(
        Probe.entry(
            CHUNKS_READ, CHUNK_PARSER, "readConstantPool", "(" + RS + HEADER + LISTENER + ")Z"));
    probes.add(
        Probe.call(
            EVENTS_READ,
            CHUNK_PARSER,
            "processChunk",
            "(" + RS + HEADER + LISTENER + "J)V",
            "io/jafar/parser/internal_api/ChunkParserListener",
            "onEvent",
            ON_EVENT));
    // the generic untyped reader
    probes.add(
        Probe.call(
            EVENTS_DECODED,
            "io/jafar/parser/impl/EventStream",
            "onEvent",
            ON_EVENT,
            "io/jafar/parser/internal_api/GenericValueReader",
            "readValue",
            "(" + RS + MC + ")V"));
    // the generated untyped reader (parser-codegen); the generated classes themselves are not
    // instrumented: they are defined at runtime under per-type names
    probes.add(
        Probe.call(
            EVENTS_DECODED,
            "io/jafar/parser/impl/EventStreamGenerated",
            "onEvent",
            ON_EVENT,
            "io/jafar/parser/internal_api/UntypedEventDeserializer",
            "deserialize",
            "(" + RS + CTX + ")Ljava/util/Map;"));
    // the typed parser: MetadataClass.read decodes constant-pool entries too (readPooled calls
    // it), so only its call from the typed listener's onEvent counts an event
    probes.add(
        Probe.call(
            EVENTS_DECODED,
            "io/jafar/parser/impl/TypedJafarParserImpl$*",
            "onEvent",
            ON_EVENT,
            METADATA_CLASS,
            "read",
            "(" + RS + ")Ljava/lang/Object;"));
    probes.add(
        Probe.entry(
            VARINTS_READ, "io/jafar/parser/internal_api/RecordingStream", "readVarint", "()J"));
    // skip(RecordingStream) delegates here, so every skip is counted once
    probes.add(
        Probe.entry(
            VALUES_SKIPPED,
            "io/jafar/parser/internal_api/TypeSkipper",
            "skip",
            "(" + RS + "Lio/jafar/parser/internal_api/TypeSkipper$Listener;)V"));
    probes.add(
        Probe.call(
            CONSTANTS_INDEXED,
            "io/jafar/parser/internal_api/CheckpointEvent",
            "readConstantPools",
            "()V",
            METADATA_CLASS,
            "skipDirect",
            "(" + RS + ")V"));
    // the two lazy decodes of a pool entry: as a map (untyped) and as an object (typed, strings)
    probes.add(Probe.entry(CONSTANTS_DECODED, POOL, "decodeAsMap", "(JJ)Ljava/util/Map;"));
    probes.add(
        Probe.call(
            CONSTANTS_DECODED,
            POOL,
            "get",
            "(J)Ljava/lang/Object;",
            METADATA_CLASS,
            "readPooled",
            "(" + RS + ")Ljava/lang/Object;"));
    PROBES = Collections.unmodifiableList(probes);
  }

  private WorkProbes() {}

  /** The index of {@code counter} in {@link #COUNTERS}; fails on an unknown name. */
  public static int indexOf(String counter) {
    int index = COUNTERS.indexOf(counter);
    if (index < 0) {
      throw new IllegalArgumentException("unknown work counter: " + counter);
    }
    return index;
  }

  /** One instrumentation point. */
  public static final class Probe {
    /** The counter this probe feeds, an index into {@link #COUNTERS}. */
    public final int counter;

    /**
     * Internal name of the class holding the method; a trailing {@code $*} means nested classes.
     */
    public final String owner;

    public final String method;
    public final String descriptor;

    /** For a call probe, the callee; {@code null} for an entry probe. */
    public final String calleeOwner;

    public final String calleeName;
    public final String calleeDescriptor;

    private Probe(
        String counter,
        String owner,
        String method,
        String descriptor,
        String calleeOwner,
        String calleeName,
        String calleeDescriptor) {
      this.counter = indexOf(counter);
      this.owner = owner;
      this.method = method;
      this.descriptor = descriptor;
      this.calleeOwner = calleeOwner;
      this.calleeName = calleeName;
      this.calleeDescriptor = calleeDescriptor;
    }

    static Probe entry(String counter, String owner, String method, String descriptor) {
      return new Probe(counter, owner, method, descriptor, null, null, null);
    }

    static Probe call(
        String counter,
        String owner,
        String method,
        String descriptor,
        String calleeOwner,
        String calleeName,
        String calleeDescriptor) {
      return new Probe(
          counter, owner, method, descriptor, calleeOwner, calleeName, calleeDescriptor);
    }

    public boolean isCall() {
      return calleeOwner != null;
    }

    /** Whether the probe's owner pattern matches the class with internal name {@code className}. */
    public boolean matchesClass(String className) {
      if (owner.endsWith("$*")) {
        String outer = owner.substring(0, owner.length() - 1); // keeps the '$'
        return className.startsWith(outer) && className.length() > outer.length();
      }
      return owner.equals(className);
    }

    /** A stable, readable id: {@code <counter>:<owner>.<method>[-><callee>]}. */
    public String id() {
      String id = COUNTERS.get(counter) + ":" + owner + "." + method;
      return isCall() ? id + "->" + calleeOwner + "." + calleeName : id;
    }

    @Override
    public String toString() {
      return id();
    }
  }
}
