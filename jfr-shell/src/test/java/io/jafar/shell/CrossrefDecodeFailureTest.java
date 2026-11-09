package io.jafar.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.jafar.parser.api.EventDecodingException;
import io.jafar.parser.api.ParserContext;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.impl.UntypedParserContextFactory;
import io.jafar.parser.internal_api.ChunkHeader;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.MutableConstantPool;
import io.jafar.parser.internal_api.StreamingChunkParser;
import io.jafar.shell.cli.CommandDispatcher;
import io.jafar.shell.core.Session;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.providers.ConstantPoolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openjdk.jmc.flightrecorder.writer.api.Recording;
import org.openjdk.jmc.flightrecorder.writer.api.Recordings;
import org.openjdk.jmc.flightrecorder.writer.api.Type;
import org.openjdk.jmc.flightrecorder.writer.api.Types;

/**
 * {@code crossref()} on a recording with one value that cannot be decoded.
 *
 * <p>{@code JafarSources.JafarConstantPoolSource.crossref} decodes every event and every constant
 * pool entry with its own {@code GenericValueReader}. It used to wrap both reads in {@code catch
 * (Throwable ignore) {}}, so a corrupted value produced incomplete reference counts, printed as if
 * they were complete. Now the command fails and the error names what could not be decoded and
 * where.
 *
 * <p>The fixture is written with the JMC writer: {@value #EVENTS} {@value #TYPE} events whose last
 * field is a string; event {@value #BAD} carries {@value #MARKER}. One string encoding byte is then
 * set to {@code 0x7f}, which no JFR string encoding uses (0..5 are defined).
 */
class CrossrefDecodeFailureTest {
  private static final String TYPE = "test.Corruptible";
  private static final int EVENTS = 20;
  private static final int BAD = 7;
  private static final String MARKER = "CORRUPT_THIS_LABEL";
  private static final byte BAD_ENCODING = 0x7f;

  /** {@code java.lang.String} is a pool every JMC-written recording with strings has. */
  private static final String EVENT_PREFIX = "Cannot decode event of type " + TYPE + " (id ";

  private static final String CROSSREF = "constants/java.lang.String | crossref()";

  static final class BufferIO implements CommandDispatcher.IO {
    final StringBuilder out = new StringBuilder();
    final StringBuilder err = new StringBuilder();

    @Override
    public void println(String s) {
      out.append(s).append('\n');
    }

    @Override
    public void printf(String fmt, Object... args) {
      out.append(String.format(fmt, args));
    }

    @Override
    public void error(String s) {
      err.append(s).append('\n');
    }
  }

  @TempDir Path dir;
  private BufferIO io;
  private SessionManager<Session> sessions;
  private CommandDispatcher dispatcher;

  @BeforeEach
  void setUp() {
    ParsingContext ctx = ParsingContext.create();
    sessions = new SessionManager<>((path, c) -> new JFRSession(path, (ParsingContext) c), ctx);
    io = new BufferIO();
    dispatcher = new CommandDispatcher(sessions, io, r -> {});
  }

  @AfterEach
  void tearDown() throws Exception {
    sessions.closeAll();
  }

  /** Guard: the fixture itself is fine, so the failures below come from the corruption alone. */
  @Test
  void cleanRecordingPrintsTheCrosscheck() throws Exception {
    Path clean = dir.resolve("clean.jfr");
    write(clean);
    String err = run(clean);
    assertEquals("", err, "stdout:\n" + io.out);
    String out = io.out.toString();
    assertTrue(out.contains("referencingEventTypes"), "stdout:\n" + out);
    assertTrue(out.contains("| java.lang.String "), "stdout:\n" + out);
  }

  /**
   * The event's own string field: the JMC writer stores event strings in the {@code
   * java.lang.String} pool, so the event ends with encoding id 2 (pool reference) and a one-byte
   * index. Decoding the event throws {@code IOException("Unexpected string constant id: 127")}.
   */
  @Test
  void undecodableEventFailsTheCommand() throws Exception {
    Path clean = dir.resolve("clean.jfr");
    write(clean);
    long[] bad = eventExtents(clean).get(BAD);
    Path corrupt = dir.resolve("corrupt-event.jfr");
    Files.write(corrupt, corruptEvent(clean, bad));

    String err = run(corrupt);
    assertFailedNaming(
        err, EVENT_PREFIX, eventFailure(1, bad), "Unexpected string constant id: 127");

    // Programmatic callers get #135's typed exception in the cause chain.
    java.io.IOException e =
        org.junit.jupiter.api.Assertions.assertThrows(
            java.io.IOException.class,
            () -> ConstantPoolProvider.crossref(corrupt, "java.lang.String"));
    EventDecodingException decode = null;
    for (Throwable c = e; c != null && decode == null; c = c.getCause()) {
      if (c instanceof EventDecodingException d) {
        decode = d;
      }
    }
    assertTrue(decode != null, chain(e));
    assertEquals(TYPE, decode.getTypeName());
    assertEquals(1, decode.getChunkIndex());
    assertEquals(bad[0], decode.getPosition());
    assertEquals(bad[1], decode.getSize());
  }

  /**
   * Chunks are decoded in parallel. With the bad event in chunks 2 and 4 of four (JFR chunks
   * concatenate into a valid recording), the error must name chunk 2 on every run: the parser
   * collects chunk results in chunk order, so the first failing chunk wins regardless of which
   * worker thread failed first. Positions are chunk-relative, so the bad event's position is the
   * same as in the one-chunk file.
   */
  @Test
  void firstFailingChunkIsReportedWhenChunksDecodeInParallel() throws Exception {
    Path clean = dir.resolve("clean.jfr");
    write(clean);
    long[] bad = eventExtents(clean).get(BAD);
    byte[] good = Files.readAllBytes(clean);
    byte[] corrupt = corruptEvent(clean, bad);
    Path jfr = dir.resolve("chunks.jfr");
    try (var out = Files.newOutputStream(jfr)) {
      out.write(good);
      out.write(corrupt);
      out.write(good);
      out.write(corrupt);
    }

    for (int run = 0; run < 10; run++) {
      io.err.setLength(0);
      String err = run(jfr);
      assertFailedNaming(
          err, EVENT_PREFIX, eventFailure(2, bad), "Unexpected string constant id: 127");
    }
  }

  /**
   * The pooled string itself, with its encoding byte set to {@code 0x7f}. Since btraceio/jafar#135
   * the parser rejects this while indexing the pool, before {@code crossref} decodes anything:
   * entries are not length-prefixed, so past one that cannot be skipped no later offset is known.
   * The shell shows only the parser's top-level message; the cause chain names the entry.
   */
  @Test
  void undecodablePoolStringIsRejectedWhileIndexing() throws Exception {
    Path clean = dir.resolve("clean.jfr");
    write(clean);
    byte[] bytes = Files.readAllBytes(clean);
    byte[] marker = MARKER.getBytes(StandardCharsets.UTF_8);
    int m = indexOf(bytes, marker, 0);
    if (m < 2 || indexOf(bytes, marker, m + 1) >= 0) {
      fail("expected exactly one occurrence of the marker, first at " + m);
    }
    // <encoding byte> <one-byte varint length> <bytes>
    assertEquals(marker.length, bytes[m - 1], "length byte before the marker");
    int at = m - 2;
    assertTrue(bytes[at] == 3 || bytes[at] == 5, "encoding byte before marker: " + bytes[at]);
    bytes[at] = BAD_ENCODING;
    Path corrupt = dir.resolve("corrupt-pool.jfr");
    Files.write(corrupt, bytes);

    java.io.IOException e =
        org.junit.jupiter.api.Assertions.assertThrows(
            java.io.IOException.class,
            () -> ConstantPoolProvider.crossref(corrupt, "java.lang.String"));
    String chain = chain(e);
    assertTrue(chain.contains("Constant pool java.lang.String, index "), chain);
    assertTrue(chain.contains("Unexpected string constant id: 127"), chain);

    String err = run(corrupt);
    String ctx = "stderr:\n" + err + "stdout:\n" + io.out;
    assertTrue(err.startsWith("Error: "), ctx);
    assertTrue(!io.out.toString().contains("referencingEventTypes"), "counts were printed; " + ctx);
  }

  /**
   * A pool entry that indexes but does not decode, so {@code crossref}'s own pool-entry path is the
   * one that fails: a {@code test.Node} entry's string field is a reference (encoding 2) into the
   * {@code java.lang.String} pool, and its index is changed to one the pool does not hold. Skipping
   * the field reads only the varint; reading it resolves the reference and throws.
   */
  @Test
  void poolEntryThatIndexesButDoesNotDecodeFailsTheCommand() throws Exception {
    Path clean = dir.resolve("nodes.jfr");
    writeNodes(clean);
    long[] entry = nodeEntry(clean); // {offset of the name field, string pool size}
    byte[] bytes = Files.readAllBytes(clean);
    int at = (int) entry[0];
    assertEquals(2, bytes[at], "name must be a string pool reference (encoding id 2)");
    assertTrue(bytes[at + 1] > 0, "one-byte index expected, got " + bytes[at + 1]);
    assertTrue(entry[1] < DANGLING, "string pool must not hold id " + DANGLING + ": " + entry[1]);
    bytes[at + 1] = DANGLING;
    Path corrupt = dir.resolve("dangling.jfr");
    Files.write(corrupt, bytes);

    String err = run(corrupt);
    assertFailedNaming(
        err,
        "Cannot decode constant pool entry test.Node#",
        " in chunk 1 at position ",
        "String constant " + DANGLING + " not found in the java.lang.String pool");
  }

  private static final byte DANGLING = 0x7e;

  /** {@value #EVENTS} {@code test.NodeEvent}s pointing at one pooled {@code test.Node}. */
  private static void writeNodes(Path jfr) throws Exception {
    try (Recording recording = Recordings.newRecording(jfr)) {
      Types types = recording.getTypes();
      Type stackTraceType = types.getType(Types.JDK.STACK_TRACE);
      Type threadType = types.getType(Types.JDK.THREAD);
      Type node = types.getOrAdd("test.Node", true, t -> t.addField("name", Types.Builtin.STRING));
      Type event = recording.registerEventType("test.NodeEvent", t -> t.addField("node", node));
      for (int i = 0; i < EVENTS; i++) {
        recording.writeEvent(
            event.asValue(
                v -> {
                  v.putField("stackTrace", stackTraceType.nullValue());
                  v.putField("eventThread", threadType.nullValue());
                  v.putField("startTime", System.nanoTime());
                  v.putField("node", n -> n.putField("name", "the-node"));
                }));
      }
    }
  }

  /** The only {@code test.Node} entry's offset (its first field) and the string pool's size. */
  private static long[] nodeEntry(Path jfr) throws Exception {
    long[] out = {-1, -1};
    try (StreamingChunkParser p = new StreamingChunkParser(new UntypedParserContextFactory())) {
      p.parse(
          jfr,
          new ChunkParserListener() {
            @Override
            public boolean onChunkEnd(ParserContext c, int chunkIndex, boolean skipped) {
              c.getConstantPools()
                  .pools()
                  .forEach(
                      cp -> {
                        String name = cp.getType().getName();
                        if (name.equals("test.Node")) {
                          assertEquals(1, cp.entryCount(), "one distinct node expected");
                          MutableConstantPool mcp = (MutableConstantPool) cp;
                          out[0] = mcp.getOffset(mcp.ids().next());
                        } else if (name.equals("java.lang.String")) {
                          out[1] = cp.entryCount();
                        }
                      });
              return true;
            }
          });
    }
    assertTrue(out[0] > 0, "no test.Node entry found");
    return out;
  }

  private static String chain(Throwable t) {
    StringBuilder sb = new StringBuilder();
    for (Throwable c = t; c != null; c = c.getCause()) {
      sb.append("\n  ").append(c);
    }
    return sb.toString();
  }

  /** The event's last two bytes are its label: encoding id 2 (pool reference) + one-byte index. */
  private static byte[] corruptEvent(Path clean, long[] bad) throws Exception {
    byte[] bytes = Files.readAllBytes(clean);
    int at = (int) (bad[0] + bad[1] - 2);
    assertEquals(2, bytes[at], "label must be a constant pool reference (encoding id 2)");
    bytes[at] = BAD_ENCODING;
    return bytes;
  }

  /** The message shape shared with {@code EventDecodingException} (btraceio/jafar#135). */
  private static String eventFailure(int chunk, long[] bad) {
    return " in chunk " + chunk + " at position " + bad[0] + " (size " + bad[1] + "): ";
  }

  private String run(Path jfr) throws Exception {
    dispatcher.dispatch("open " + jfr);
    assertEquals("", io.err.toString(), "open failed");
    io.out.setLength(0);
    dispatcher.dispatch(CROSSREF);
    return io.err.toString();
  }

  private void assertFailedNaming(String err, String... fragments) {
    String ctx = "stderr:\n" + err + "stdout:\n" + io.out;
    assertTrue(err.contains("crossref(java.lang.String) aborted: "), ctx);
    assertEquals(1, err.split("\n", -1).length - 1, "exactly one error line; " + ctx);
    for (String f : fragments) {
      assertTrue(err.contains(f), "missing '" + f + "'; " + ctx);
    }
    assertTrue(
        !io.out.toString().contains("referencingEventTypes"),
        "partial counts were printed; " + ctx);
  }

  private static void write(Path jfr) throws Exception {
    try (Recording recording = Recordings.newRecording(jfr)) {
      Types types = recording.getTypes();
      Type stackTraceType = types.getType(Types.JDK.STACK_TRACE);
      Type threadType = types.getType(Types.JDK.THREAD);
      Type event =
          recording.registerEventType(
              TYPE,
              t -> {
                t.addField("seq", Types.Builtin.INT);
                t.addField("label", Types.Builtin.STRING);
              });
      for (int i = 0; i < EVENTS; i++) {
        int seq = i;
        recording.writeEvent(
            event.asValue(
                v -> {
                  // Implicit fields first: the JMC writer declares but does not write them.
                  v.putField("stackTrace", stackTraceType.nullValue());
                  v.putField("eventThread", threadType.nullValue());
                  v.putField("startTime", System.nanoTime());
                  v.putField("seq", seq);
                  v.putField("label", seq == BAD ? MARKER : "label-" + seq);
                }));
      }
    }
  }

  /** File offset and size of each {@value #TYPE} event, in order (one chunk, starting at 0). */
  private static List<long[]> eventExtents(Path jfr) throws Exception {
    List<long[]> extents = Collections.synchronizedList(new ArrayList<>());
    int[] chunks = {0};
    try (StreamingChunkParser p = new StreamingChunkParser(new UntypedParserContextFactory())) {
      p.parse(
          jfr,
          new ChunkParserListener() {
            @Override
            public boolean onChunkStart(ParserContext c, int chunkIndex, ChunkHeader header) {
              chunks[0]++;
              return true;
            }

            @Override
            public boolean onEvent(
                ParserContext c, long typeId, long start, long rawSize, long payloadSize) {
              if (TYPE.equals(c.getMetadataLookup().getClass(typeId).getName())) {
                extents.add(new long[] {start, rawSize});
              }
              return true;
            }
          });
    }
    assertEquals(1, chunks[0], "event offsets are chunk-relative; the fixture must be one chunk");
    assertEquals(EVENTS, extents.size());
    return extents;
  }

  private static int indexOf(byte[] haystack, byte[] needle, int from) {
    outer:
    for (int i = from; i <= haystack.length - needle.length; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (haystack[i + j] != needle[j]) {
          continue outer;
        }
      }
      return i;
    }
    return -1;
  }
}
