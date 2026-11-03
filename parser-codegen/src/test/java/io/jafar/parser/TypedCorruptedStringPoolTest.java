package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.jafar.parser.api.JfrField;
import io.jafar.parser.api.JfrType;
import io.jafar.parser.api.ParserContext;
import io.jafar.parser.api.TypedJafarParser;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.internal_api.ChunkParserListener;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The typed parser reads strings through the same {@code ParsingUtils.readUTF8} and indexes
 * constant pools through the same {@code ParsingUtils.skipUTF8} as the untyped one, so it gets the
 * same constant pool policy (see {@code EventDecodeFailureTest} in parser-core): a pooled string
 * that cannot be indexed fails the parse, and one that cannot be resolved is an error, never a
 * {@code null} label.
 *
 * <p>The fixture is the one {@code EventDecodeFailureTest} uses: {@value #EVENTS} events in one
 * chunk, whose labels the JMC writer stores in the {@code java.lang.String} constant pool.
 */
public class TypedCorruptedStringPoolTest {
  private static final String TYPE = "test.Corruptible";
  private static final int EVENTS = 20;
  private static final int BAD = 7;
  private static final String MARKER = "CORRUPT_THIS_LABEL";

  @JfrType(TYPE)
  public interface Corruptible {
    @JfrField("seq")
    int seq();

    @JfrField("label")
    String label();
  }

  @TempDir Path dir;

  @Test
  void cleanFixtureDeliversEveryLabel() throws Exception {
    Path jfr = dir.resolve("clean.jfr");
    write(jfr);
    List<String> labels = parse(jfr);
    assertEquals(EVENTS, labels.size(), String.valueOf(labels));
    for (int i = 0; i < EVENTS; i++) {
      assertTrue(labels.contains(i + "=" + (i == BAD ? MARKER : "label-" + i)), labels.toString());
    }
  }

  /**
   * A corrupted pooled string makes its checkpoint unindexable: the parse fails while the
   * checkpoint is read, before any event, naming the pool entry.
   */
  @Test
  void corruptedPooledStringFailsTheParseInsteadOfReadingNull() throws Exception {
    Path jfr = dir.resolve("clean.jfr");
    write(jfr);
    byte[] bytes = Files.readAllBytes(jfr);
    byte[] marker = MARKER.getBytes(StandardCharsets.UTF_8);
    int m = indexOf(bytes, marker);
    assertEquals(marker.length, bytes[m - 1], "length byte before the marker");
    assertTrue(bytes[m - 2] == 3 || bytes[m - 2] == 5, "encoding byte: " + bytes[m - 2]);
    bytes[m - 2] = 0x7f;
    Path bad = dir.resolve("corrupt-pool.jfr");
    Files.write(bad, bytes);

    List<String> labels = Collections.synchronizedList(new ArrayList<>());
    Exception thrown = assertThrows(Exception.class, () -> parseInto(bad, labels));
    assertTrue(labels.isEmpty(), "delivered: " + labels);
    String chain = chain(thrown);
    assertTrue(chain.contains("Constant pool java.lang.String, index "), chain);
    assertTrue(chain.contains("Unexpected string constant id: 127"), chain);
  }

  /**
   * A reference to a pool entry the chunk never declared fails the event; {@code readUTF8} used to
   * return {@code null}, and the typed parse completed with {@code label() == null}.
   */
  @Test
  void danglingPooledStringReferenceFailsTheParseInsteadOfReadingNull() throws Exception {
    Path jfr = dir.resolve("clean.jfr");
    write(jfr);
    byte[] bytes = Files.readAllBytes(jfr);
    long[] extent = eventExtents(jfr).get(BAD);
    int ref = (int) (extent[0] + extent[1] - 2);
    assertEquals(2, bytes[ref], "label must be a constant pool reference (encoding id 2)");
    bytes[ref + 1] = 0x7f; // one-byte varint 127; the pool has only EVENTS entries
    Path bad = dir.resolve("dangling.jfr");
    Files.write(bad, bytes);

    List<String> labels = Collections.synchronizedList(new ArrayList<>());
    Exception thrown = assertThrows(Exception.class, () -> parseInto(bad, labels));
    assertNoWrongLabel(labels);
    String chain = chain(thrown);
    assertTrue(chain.contains("String constant 127 not found in the java.lang.String pool"), chain);
  }

  private static void assertNoWrongLabel(List<String> labels) {
    synchronized (labels) {
      for (String l : labels) {
        int eq = l.indexOf('=');
        int seq = Integer.parseInt(l.substring(0, eq));
        assertEquals(
            "label-" + seq, l.substring(eq + 1), "wrong or missing label delivered: " + labels);
      }
    }
  }

  private static List<String> parse(Path jfr) throws Exception {
    List<String> labels = Collections.synchronizedList(new ArrayList<>());
    parseInto(jfr, labels);
    return labels;
  }

  private static void parseInto(Path jfr, List<String> labels) throws Exception {
    try (TypedJafarParser p = TypedJafarParser.open(jfr.toString())) {
      p.handle(Corruptible.class, (e, ctl) -> labels.add(e.seq() + "=" + e.label()));
      p.run();
    }
  }

  private static void write(Path jfr) throws Exception {
    JfrTestHelper.EventTypeBuilder type =
        JfrTestHelper.create(jfr).eventType(TYPE).intField("seq").stringField("label");
    for (int i = 0; i < EVENTS; i++) {
      type.event(i, i == BAD ? MARKER : "label-" + i);
    }
    type.build();
  }

  /** File offset and size of each {@value #TYPE} event, in order (one chunk, starting at 0). */
  private static List<long[]> eventExtents(Path jfr) throws Exception {
    List<long[]> extents = Collections.synchronizedList(new ArrayList<>());
    try (UntypedJafarParser p = UntypedJafarParser.open(jfr)) {
      p.withParserListener(
              new ChunkParserListener() {
                @Override
                public boolean onEvent(
                    ParserContext c, long typeId, long start, long rawSize, long payloadSize) {
                  if (TYPE.equals(c.getMetadataLookup().getClass(typeId).getName())) {
                    extents.add(new long[] {start, rawSize});
                  }
                  return true;
                }
              })
          .run();
    }
    assertEquals(EVENTS, extents.size());
    return extents;
  }

  private static int indexOf(byte[] haystack, byte[] needle) {
    int found = -1;
    outer:
    for (int i = 0; i <= haystack.length - needle.length; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (haystack[i + j] != needle[j]) {
          continue outer;
        }
      }
      if (found >= 0) {
        fail("marker occurs more than once");
      }
      found = i;
    }
    assertTrue(found >= 2, "marker not found");
    return found;
  }

  private static String chain(Throwable t) {
    StringBuilder sb = new StringBuilder();
    for (Throwable c = t; c != null; c = c.getCause()) {
      sb.append("\n  ").append(c);
    }
    return sb.toString();
  }
}
