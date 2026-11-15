package io.jafar.parser.impl;

import static io.jafar.parser.impl.SyntheticRecording.elem;
import static io.jafar.parser.impl.SyntheticRecording.primitiveClass;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.ParserContext;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.api.UntypedStrategy;
import io.jafar.parser.internal_api.ChunkHeader;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.UntypedDeserializerCache;
import io.jafar.parser.internal_api.UntypedEventDeserializer;
import io.jafar.testsupport.jfr.JfrCanonicalDump;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Untyped parses made from one {@link ParsingContext} share one {@link UntypedDeserializerCache}:
 * the second parse of a recording generates no deserializer, and a deserializer generated for one
 * recording is never used for a differently shaped type of another.
 *
 * <p>The cache is observed, not assumed: a {@link ChunkParserListener} reads the instance each
 * chunk context carries, and "generated nothing" is asserted on the identity of the cached
 * deserializers, which only {@code EventStreamGenerated} puts there.
 */
class SharedUntypedDeserializerCacheTest {
  private static final Path TCK = Paths.get("../jfr-shell-tck/src/main/resources/tck-test.jfr");

  /**
   * Every event of {@code tck-test.jfr}; the figure {@code SharedParsingContextStressTest} pins.
   */
  private static final long TCK_EVENTS = 67_657;

  /** What one parse saw: the cache its chunks carried, its contents afterwards, its events. */
  private static final class Seen {
    final UntypedDeserializerCache cache;
    final Map<String, UntypedEventDeserializer> deserializers;
    final List<String> events;
    final long eventCount;

    Seen(UntypedDeserializerCache cache, List<String> events, long eventCount) {
      this.cache = cache;
      this.deserializers = new HashMap<>(cache);
      this.events = events;
      this.eventCount = eventCount;
    }
  }

  /** Records the cache instance of every chunk context; identity, as {@code Impl} has equals. */
  private static final class CacheProbe implements ChunkParserListener {
    private final Set<UntypedDeserializerCache> caches =
        Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

    @Override
    public boolean onChunkStart(ParserContext context, int chunkIndex, ChunkHeader header) {
      caches.add(context.get(UntypedDeserializerCache.class));
      return true;
    }

    UntypedDeserializerCache only() {
      assertEquals(1, caches.size(), "every chunk of one parse carries the same cache");
      return caches.iterator().next();
    }
  }

  private static Seen run(UntypedJafarParser parser) throws Exception {
    return run(parser, false);
  }

  /**
   * @param render whether to keep every event as {@code name{field=value, ...}}, sorted; only for
   *     the synthetic recordings, whose values are plain longs and strings
   */
  private static Seen run(UntypedJafarParser parser, boolean render) throws Exception {
    CacheProbe probe = new CacheProbe();
    List<String> events = Collections.synchronizedList(new ArrayList<>());
    LongAdder count = new LongAdder();
    try (UntypedJafarParser probed = parser.<UntypedJafarParser>withParserListener(probe)) {
      assertTrue(
          ((UntypedJafarParserImpl) probed).usesGeneratedReader(),
          "this test is about the generated reader; parser-codegen must be on the classpath");
      probed.handle(
          (type, value, ctl) -> {
            count.increment();
            if (render) {
              events.add(type.getName() + value);
            }
          });
      probed.run();
    }
    List<String> sorted = new ArrayList<>(events);
    Collections.sort(sorted);
    return new Seen(probe.only(), sorted, count.sum());
  }

  private static Seen run(ParsingContext context, Path file, UntypedStrategy strategy)
      throws Exception {
    try (UntypedJafarParser parser = context.newUntypedParser(file, strategy)) {
      return run(parser, !file.equals(TCK));
    }
  }

  /** Same keys, and for every key the very same instance: nothing was generated in between. */
  private static void assertNothingGenerated(Seen first, Seen second) {
    assertSame(first.cache, second.cache, "both parses must carry the context's one cache");
    assertFalse(first.deserializers.isEmpty(), "the first parse generated no deserializer");
    assertEquals(first.deserializers.keySet(), second.deserializers.keySet());
    assertEquals(TCK_EVENTS, first.eventCount);
    assertEquals(TCK_EVENTS, second.eventCount);
    first.deserializers.forEach(
        (key, deserializer) ->
            assertSame(deserializer, second.deserializers.get(key), "regenerated: " + key));
  }

  @Test
  void twoParsersOfOneContextShareTheCacheAndGenerateOnce() throws Exception {
    ParsingContext context = ParsingContext.create();

    Seen first = run(context, TCK, UntypedStrategy.SPARSE_ACCESS);
    Seen second = run(context, TCK, UntypedStrategy.SPARSE_ACCESS);

    assertNothingGenerated(first, second);
  }

  @Test
  void twoRunsOfOneParserShareTheCacheAndGenerateOnce() throws Exception {
    try (UntypedJafarParser parser = ParsingContext.create().newUntypedParser(TCK)) {
      Seen first = run(parser);
      Seen second = run(parser);

      assertNothingGenerated(first, second);
    }
  }

  @Test
  void parallelismViewsShareTheContextsCache() throws Exception {
    ParsingContext context = ParsingContext.create();

    Seen first = run(context, TCK, UntypedStrategy.SPARSE_ACCESS);
    Seen sequential = run(context.withParallelism(1), TCK, UntypedStrategy.SPARSE_ACCESS);
    Seen viewOfView =
        run(context.withParallelism(1).withParallelism(4), TCK, UntypedStrategy.SPARSE_ACCESS);

    assertNothingGenerated(first, sequential);
    assertNothingGenerated(first, viewOfView);
  }

  /** The strategy is part of the key, so one cache serves all of them without mixing them up. */
  @Test
  void strategiesShareTheCacheUnderTheirOwnKeys() throws Exception {
    ParsingContext context = ParsingContext.create();

    Seen sparse = run(context, TCK, UntypedStrategy.SPARSE_ACCESS);
    Seen full = run(context, TCK, UntypedStrategy.FULL_ITERATION);

    assertSame(sparse.cache, full.cache);
    for (String key : sparse.deserializers.keySet()) {
      assertTrue(key.startsWith("SPARSE_ACCESS|"), key);
    }
    Map<String, UntypedEventDeserializer> added = new HashMap<>(full.deserializers);
    added.keySet().removeAll(sparse.deserializers.keySet());
    assertEquals(sparse.deserializers.size(), added.size(), "one deserializer per type, again");
    for (String key : added.keySet()) {
      assertTrue(key.startsWith("FULL_ITERATION|"), key);
    }
  }

  @Test
  void separateContextsHaveSeparateCaches() throws Exception {
    Seen first = run(ParsingContext.create(), TCK, UntypedStrategy.SPARSE_ACCESS);
    Seen second = run(ParsingContext.create(), TCK, UntypedStrategy.SPARSE_ACCESS);

    assertNotSame(first.cache, second.cache);
  }

  /**
   * {@code UntypedJafarParser.open(path)} names no context; it uses the process-wide {@code
   * ParsingContextImpl.EMPTY}. That one must not accumulate generated classes for the life of the
   * JVM, so each of its parses keeps a cache of its own.
   */
  @Test
  void parsersOpenedWithoutAContextDoNotShare() throws Exception {
    Seen first;
    Seen second;
    try (UntypedJafarParser parser = UntypedJafarParser.open(TCK)) {
      first = run(parser);
    }
    try (UntypedJafarParser parser = UntypedJafarParser.open(TCK)) {
      second = run(parser);
    }

    assertNotSame(first.cache, second.cache);
  }

  /** A context that keeps meeting new layouts does not keep every generated class forever. */
  @Test
  void aCacheOverItsLimitIsDroppedBeforeTheNextParse() throws Exception {
    ParsingContext context = ParsingContext.create();
    Seen first = run(context, TCK, UntypedStrategy.SPARSE_ACCESS);
    UntypedEventDeserializer filler = (stream, ctx) -> Collections.emptyMap();
    for (int i = 0; i <= ParsingContextImpl.MAX_UNTYPED_DESERIALIZERS; i++) {
      first.cache.put("filler-" + i, filler);
    }

    Seen second = run(context, TCK, UntypedStrategy.SPARSE_ACCESS);

    assertSame(first.cache, second.cache);
    assertEquals(first.deserializers.keySet(), second.deserializers.keySet());
    assertEquals(TCK_EVENTS, second.eventCount);
  }

  // --- A deserializer of one recording is never used for another recording's different type ---

  private static final long T_LONG = 6;
  private static final long T_STRING = 9;
  private static final long START_TICKS = 1_000_000L;
  private static final long START_NANOS = 1_700_000_000_000_000_000L;

  private static SyntheticRecording.Element metadata(SyntheticRecording.Element... classes) {
    SyntheticRecording.Element md =
        elem("metadata")
            .with(primitiveClass(T_LONG, "long"), primitiveClass(T_STRING, "java.lang.String"));
    md.with(classes);
    return elem("root").with(md);
  }

  private static SyntheticRecording.Element eventClass(
      long id, String name, SyntheticRecording.Element... fields) {
    return elem("class", "id", Long.toString(id), "name", name, "superType", "jdk.jfr.Event")
        .with(fields);
  }

  private static SyntheticRecording.Element field(String name, long type) {
    return elem("field", "name", name, "class", Long.toString(type));
  }

  private static Path write(
      Path dir, String name, SyntheticRecording.Element metadata, byte[]... events)
      throws Exception {
    byte[] chunk =
        SyntheticRecording.chunk(
            metadata, Arrays.asList(events), START_NANOS, START_TICKS, 1_000_000_000L, 1_000_000L);
    return Files.write(dir.resolve(name), chunk);
  }

  private static byte[] longEvent(long typeId, long value) {
    return new SyntheticRecording.Bytes().varint(typeId).varint(value).toByteArray();
  }

  /**
   * The cross-recording twin of {@code GeneratedDeserializerCacheTest}: recording 1 declares id 100
   * as {@code test.A {x: long}}; recording 2 declares id 100 as {@code test.B {s: String}} and id
   * 101 as the same {@code test.A}; recording 3 keeps name and id of {@code test.A} but renames its
   * field. Parsed through one context, in both orders.
   */
  @ParameterizedTest
  @EnumSource(
      value = UntypedStrategy.class,
      names = {"FULL_ITERATION", "SPARSE_ACCESS"})
  void recordingsReusingATypeIdDecodeWithTheirOwnTypes(UntypedStrategy strategy, @TempDir Path dir)
      throws Exception {
    Path one =
        write(
            dir,
            "one.jfr",
            metadata(eventClass(100, "test.A", field("x", T_LONG))),
            longEvent(100, 41));
    Path two =
        write(
            dir,
            "two.jfr",
            metadata(
                eventClass(100, "test.B", field("s", T_STRING)),
                eventClass(101, "test.A", field("x", T_LONG))),
            new SyntheticRecording.Bytes().varint(100).utf8("bee").toByteArray(),
            longEvent(101, 42));
    Path three =
        write(
            dir,
            "three.jfr",
            metadata(eventClass(100, "test.A", field("y", T_LONG))),
            longEvent(100, 43));
    Map<Path, List<String>> expected = new HashMap<>();
    expected.put(one, Collections.singletonList("test.A{x=41}"));
    expected.put(two, Arrays.asList("test.A{x=42}", "test.B{s=bee}"));
    expected.put(three, Collections.singletonList("test.A{y=43}"));

    for (List<Path> order :
        Arrays.asList(Arrays.asList(one, two, three, one), Arrays.asList(three, two, one, three))) {
      ParsingContext context = ParsingContext.create();
      UntypedDeserializerCache cache = null;
      for (Path file : order) {
        Seen seen = run(context, file, strategy);
        assertEquals(expected.get(file), seen.events, strategy + " " + file.getFileName());
        if (cache != null) {
          assertSame(cache, seen.cache, "the recordings must share a cache for this to bite");
        }
        cache = seen.cache;
      }
      // test.A{x} under id 100 and under id 101, test.B, test.A{y}: four layouts, four keys.
      assertEquals(4, cache.size(), strategy + ": " + cache.keySet());
    }
  }

  /**
   * The cross-recording twin of {@code TemporalAnnotationPerChunkTest}: both recordings declare id
   * 100 as {@code test.T {a: long}}; only the second annotates {@code a} with
   * {@code @Timestamp("TICKS")}. Each must be normalized by its own annotations, in both orders.
   */
  @ParameterizedTest
  @EnumSource(
      value = UntypedStrategy.class,
      names = {"FULL_ITERATION", "SPARSE_ACCESS"})
  void recordingsDifferingOnlyInAnAnnotationAreNormalizedByTheirOwn(
      UntypedStrategy strategy, @TempDir Path dir) throws Exception {
    long offset = 5;
    SyntheticRecording.Element timestampAnnotation =
        elem(
                "class",
                "id",
                "200",
                "name",
                "jdk.jfr.Timestamp",
                "superType",
                "java.lang.annotation.Annotation")
            .with(field("value", T_STRING));
    Path plain =
        write(
            dir,
            "plain.jfr",
            metadata(eventClass(100, "test.T", field("a", T_LONG))),
            longEvent(100, START_TICKS + offset));
    Path annotated =
        write(
            dir,
            "annotated.jfr",
            metadata(
                eventClass(
                    100,
                    "test.T",
                    field("a", T_LONG).with(elem("annotation", "class", "200", "value", "TICKS"))),
                timestampAnnotation),
            longEvent(100, START_TICKS + offset));
    Map<Path, List<String>> expected = new HashMap<>();
    expected.put(plain, Collections.singletonList("test.T{a=" + (START_TICKS + offset) + "}"));
    expected.put(annotated, Collections.singletonList("test.T{a=" + (START_NANOS + offset) + "}"));

    for (List<Path> order :
        Arrays.asList(Arrays.asList(plain, annotated, plain), Arrays.asList(annotated, plain))) {
      ParsingContext context = ParsingContext.create();
      for (Path file : order) {
        Seen seen = run(context, file, strategy);
        assertEquals(expected.get(file), seen.events, strategy + " " + file.getFileName());
      }
    }
  }

  /**
   * Real recordings of three producers (JMC writer, JDK, Datadog profiler), parsed through one
   * context one after another, decode exactly as each does with a context of its own. Per event
   * type: the count and an order-dependent hash of the canonical lines, read with parallelism 1 so
   * that the order is the file's.
   */
  @Test
  void realRecordingsDecodeAsWithAContextOfTheirOwn() throws Exception {
    List<Path> recordings =
        Arrays.asList(resource("test-ap.jfr"), resource("test-jfr.jfr"), TCK.toAbsolutePath());
    Map<Path, Map<String, String>> reference = new HashMap<>();
    for (Path recording : recordings) {
      Map<String, String> digest = digest(ParsingContext.create(), recording, new int[2]);
      assertFalse(digest.isEmpty(), recording + " delivered no event");
      reference.put(recording, digest);
    }

    List<Path> reversed = new ArrayList<>(recordings);
    Collections.reverse(reversed);
    for (List<Path> order : Arrays.asList(recordings, reversed)) {
      ParsingContext shared = ParsingContext.create();
      for (int pass = 1; pass <= 2; pass++) {
        for (Path recording : order) {
          int[] keys = new int[2];
          assertEquals(
              reference.get(recording),
              digest(shared, recording, keys),
              "pass " + pass + " of " + recording.getFileName() + " through the shared context");
          System.out.println(
              "shared context, pass "
                  + pass
                  + ", "
                  + recording.getFileName()
                  + ": cached deserializers "
                  + keys[0]
                  + " -> "
                  + keys[1]);
          if (pass == 2) {
            assertEquals(keys[0], keys[1], "a second pass must generate nothing");
          }
        }
      }
    }
  }

  private static Path resource(String name) throws Exception {
    return new File(
            SharedUntypedDeserializerCacheTest.class.getClassLoader().getResource(name).toURI())
        .toPath();
  }

  /**
   * Event type to "count:hash" of its canonical lines in file order.
   *
   * @param keys receives the size of the parse's deserializer cache when its first chunk starts and
   *     after the parse
   */
  private static Map<String, String> digest(ParsingContext context, Path recording, int[] keys)
      throws Exception {
    Map<String, long[]> byType = new HashMap<>();
    UntypedDeserializerCache[] cache = new UntypedDeserializerCache[1];
    ChunkParserListener probe =
        new ChunkParserListener() {
          @Override
          public boolean onChunkStart(ParserContext ctx, int chunkIndex, ChunkHeader header) {
            if (cache[0] == null) { // parallelism 1: the first chunk starts before any is read
              cache[0] = ctx.get(UntypedDeserializerCache.class);
              keys[0] = cache[0].size();
            }
            return true;
          }
        };
    try (UntypedJafarParser parser =
        context
            .withParallelism(1)
            .newUntypedParser(recording)
            .<UntypedJafarParser>withParserListener(probe)) {
      parser.handle(
          (type, value, ctl) -> {
            String line = JfrCanonicalDump.eventLine(type.getName(), value);
            // One event at a time with parallelism 1, but not always on the same thread.
            synchronized (byType) {
              long[] acc = byType.computeIfAbsent(type.getName(), k -> new long[2]);
              acc[0]++;
              acc[1] = acc[1] * 31 + line.hashCode();
            }
          });
      parser.run();
    }
    keys[1] = cache[0].size();
    Map<String, String> digest = new TreeMap<>();
    synchronized (byType) {
      byType.forEach((type, acc) -> digest.put(type, acc[0] + ":" + Long.toHexString(acc[1])));
    }
    return digest;
  }
}
