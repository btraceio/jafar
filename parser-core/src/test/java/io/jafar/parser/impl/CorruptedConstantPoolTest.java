package io.jafar.parser.impl;

import static io.jafar.parser.impl.SyntheticRecording.elem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.api.Values;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A constant pool that cannot be indexed must fail the parse, not be quietly dropped.
 *
 * <p>{@code CheckpointEvent} indexes pool entries by skipping over them. Two ways of losing the
 * stream alignment used to be tolerated:
 *
 * <ul>
 *   <li>{@code ParsingUtils.skipUTF8} treated an unknown string encoding id as an empty string,
 *       while {@code readUTF8} threw {@code Unexpected string constant id} for the same byte. The
 *       entries after it were indexed from inside the string's bytes.
 *   <li>A pool whose type id is not declared in the chunk metadata - which is also what such a
 *       misaligned read usually produces next - was logged at WARN, and the remaining pools of the
 *       checkpoint were abandoned while the parse went on.
 * </ul>
 *
 * <p>Because constant pool values are decoded lazily, a query that never touched an abandoned or
 * misindexed entry printed the clean file's numbers. The JDK's own reader rejects both cases.
 */
public class CorruptedConstantPoolTest {
  private static final long T_STRING = 9;
  private static final long T_NODE = 50;
  private static final long T_UNDECLARED = 77;
  private static final long T_EVENT = 101;

  @TempDir Path dir;

  private static SyntheticRecording.Element metadata() {
    SyntheticRecording.Element node =
        elem("class", "id", Long.toString(T_NODE), "name", "test.Node")
            .with(elem("field", "name", "name", "class", Long.toString(T_STRING)));
    SyntheticRecording.Element event =
        elem(
                "class",
                "id",
                Long.toString(T_EVENT),
                "name",
                "test.Labelled",
                "superType",
                "jdk.jfr.Event")
            .with(
                elem(
                    "field",
                    "name",
                    "node",
                    "class",
                    Long.toString(T_NODE),
                    "constantPool",
                    "true"));
    return elem("root")
        .with(
            elem("metadata")
                .with(
                    // Declared as the JVM declares it: not a simple type. A field-less simple
                    // type cannot be skipped (TypeSkipper unwraps it to its first field).
                    elem("class", "id", Long.toString(T_STRING), "name", "java.lang.String"),
                    node,
                    event),
            elem("region", "dst", "0", "gmtOffset", "0", "locale", "en_US"));
  }

  private static byte[] utf8(String s) {
    return new SyntheticRecording.Bytes().utf8(s).toByteArray();
  }

  /** Encodes {@code s} as a string with encoding id {@code id} and a UTF-8 shaped payload. */
  private static byte[] stringWithEncoding(int id, String s) {
    byte[] b = utf8(s);
    b[0] = (byte) id;
    return b;
  }

  private static byte[] event(long nodeIndex) {
    return new SyntheticRecording.Bytes().varint(T_EVENT).varint(nodeIndex).toByteArray();
  }

  private Path write(String name, List<SyntheticRecording.Pool> pools) throws Exception {
    Path file = dir.resolve(name);
    Files.write(
        file,
        SyntheticRecording.chunk(
            metadata(), pools, Collections.singletonList(event(2)), 0, 0, 1_000_000_000L, 1000));
    return file;
  }

  private static SyntheticRecording.Pool nodes(byte[] first) {
    return new SyntheticRecording.Pool(T_NODE).entry(1, first).entry(2, utf8("beta"));
  }

  private static List<String> labels(Path file) throws Exception {
    List<String> labels = new ArrayList<>();
    try (UntypedJafarParser p = UntypedJafarParser.open(file)) {
      p.handle((type, value, ctl) -> labels.add(String.valueOf(Values.get(value, "node", "name"))));
      p.run();
    }
    return labels;
  }

  private static void assertCause(Throwable thrown, String expected) {
    for (Throwable t = thrown; t != null; t = t.getCause()) {
      if (t.getMessage() != null && t.getMessage().contains(expected)) {
        return;
      }
    }
    fail("expected a cause containing '" + expected + "', got " + thrown, thrown);
  }

  /** Guard: the uncorrupted shape parses and resolves the pooled string. Passes both ways. */
  @Test
  void cleanPoolResolves() throws Exception {
    Path file = write("clean.jfr", Collections.singletonList(nodes(utf8("alpha"))));
    assertEquals(Collections.singletonList("beta"), labels(file));
  }

  /**
   * The event references entry 2, which follows the corrupted entry 1. Before the fix entry 1 was
   * skipped as if empty, entry 2 was indexed at the wrong offset, and the parse did not fail.
   */
  @Test
  void unknownStringEncodingInPoolFailsTheParse() throws Exception {
    for (int id : Arrays.asList(6, 0x7f)) {
      Path file =
          write(
              "bad-" + id + ".jfr",
              Collections.singletonList(nodes(stringWithEncoding(id, "alpha"))));
      Exception e = assertThrows(Exception.class, () -> labels(file), "encoding id " + id);
      assertCause(e, "Unexpected string constant id: " + id);
      // The shells print only the top-level message, so the cause and where it was found must
      // be in it, not just somewhere down the chain.
      String top = String.valueOf(e.getMessage());
      assertTrue(
          top.contains("constant pool test.Node, index 1: Unexpected string constant id: " + id),
          top);
    }
  }

  /** A pool of an undeclared type used to be logged at WARN, abandoning the checkpoint's rest. */
  @Test
  void undeclaredPoolTypeFailsTheParse() throws Exception {
    Path file =
        write(
            "undeclared.jfr",
            Arrays.asList(
                new SyntheticRecording.Pool(T_UNDECLARED).entry(1, utf8("x")),
                nodes(utf8("alpha"))));
    Exception e = assertThrows(Exception.class, () -> labels(file));
    assertCause(e, "type id " + T_UNDECLARED);
  }
}
