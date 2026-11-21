package io.jafar.parser.impl;

import static io.jafar.parser.impl.SyntheticRecording.elem;
import static io.jafar.parser.impl.SyntheticRecording.primitiveClass;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.api.Values;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A pooled field holds one varint, the referenced entry's index, whatever the entry's type: here a
 * pooled reference to {@code test.ByteBox}, a simple type wrapping one inline {@code byte}.
 *
 * <p>Constant pool entries are not length-prefixed; the parser finds each entry by skipping the one
 * before it with a {@link io.jafar.parser.internal_api.TypeSkipper}. The skipper unwrapped simple
 * types before it looked at the pool flag, so once it unwrapped them at all (its {@code
 * unwrappable} compared a scalar's dimension with 0, but a scalar's dimension is -1, so it never
 * did) the pooled {@code box} field would be skipped as one byte instead of its two-byte index,
 * misframing every entry after it. The Go parser decides on the pool flag first ({@code
 * skipFieldValue}).
 */
public class PooledSimpleTypeSkipTest {
  private static final long T_BOOLEAN = 1;
  private static final long T_BYTE = 2;
  private static final long T_INT = 3;
  private static final long T_FLOAT = 4;

  private static final long T_EVENT = 100;
  private static final long T_BOX = 300;
  private static final long T_ENTRY = 301;

  /** Index of the one ByteBox entry: 300 is a two-byte varint, so a one-byte skip misframes. */
  private static final long BOX_INDEX = 300;

  @TempDir Path dir;

  private static SyntheticRecording.Element metadata() {
    return metadata(T_BOX);
  }

  /** As {@link #metadata()}, with {@code test.Entry.box} pooled of class {@code boxType}. */
  private static SyntheticRecording.Element metadata(long boxType) {
    return elem("root")
        .with(
            elem("metadata")
                .with(
                    primitiveClass(T_BOOLEAN, "boolean"),
                    primitiveClass(T_BYTE, "byte"),
                    primitiveClass(T_INT, "int"),
                    primitiveClass(T_FLOAT, "float"),
                    elem(
                            "class",
                            "id",
                            Long.toString(T_BOX),
                            "name",
                            "test.ByteBox",
                            "simpleType",
                            "true")
                        .with(elem("field", "name", "b", "class", Long.toString(T_BYTE))),
                    elem("class", "id", Long.toString(T_ENTRY), "name", "test.Entry")
                        .with(
                            elem(
                                "field",
                                "name",
                                "box",
                                "class",
                                Long.toString(boxType),
                                "constantPool",
                                "true"),
                            elem("field", "name", "n", "class", Long.toString(T_INT))),
                    elem(
                            "class",
                            "id",
                            Long.toString(T_EVENT),
                            "name",
                            "test.Ref",
                            "superType",
                            "jdk.jfr.Event")
                        .with(
                            elem(
                                "setting",
                                "name",
                                "enabled",
                                "class",
                                Long.toString(T_BOOLEAN),
                                "defaultValue",
                                "true"),
                            elem(
                                "field",
                                "name",
                                "entry",
                                "class",
                                Long.toString(T_ENTRY),
                                "constantPool",
                                "true"))),
            elem("region", "dst", "0", "gmtOffset", "0", "locale", "en_US"));
  }

  private static byte[] entry(int n) {
    return new SyntheticRecording.Bytes().varint(BOX_INDEX).varint(n).toByteArray();
  }

  private static byte[] ref(long entryIndex) {
    return new SyntheticRecording.Bytes().varint(T_EVENT).varint(entryIndex).toByteArray();
  }

  /** {@code entry.n} and {@code entry.box} of each event, sorted. */
  private List<String> parse(byte[] chunk) throws Exception {
    Path file = dir.resolve("pooled.jfr");
    Files.write(file, chunk);
    List<String> seen = Collections.synchronizedList(new ArrayList<>());
    try (UntypedJafarParser p = UntypedJafarParser.open(file)) {
      p.handle(
          (type, value, ctl) -> {
            Object entry = Values.resolvedShallow(value).get("entry");
            seen.add(
                entry instanceof Map
                    ? "n=" + ((Map<?, ?>) entry).get("n") + " box=" + ((Map<?, ?>) entry).get("box")
                    : "entry=" + entry);
          });
      p.run();
    }
    Collections.sort(seen);
    return seen;
  }

  private static byte[] chunk(SyntheticRecording.Element metadata, byte[] pools) throws Exception {
    return SyntheticRecording.chunk(
        metadata,
        pools,
        List.of(ref(1), ref(2)),
        1_700_000_000_000_000_000L,
        0,
        1_000_000_000L,
        1000);
  }

  @Test
  void aPooledSimpleTypeFieldIsSkippedAsItsIndex() throws Exception {
    byte[] pools =
        SyntheticRecording.Pool.section(
            // a simple type's entry is stored unwrapped: the byte itself
            new SyntheticRecording.Pool(T_BOX).entry(BOX_INDEX, new byte[] {42}),
            new SyntheticRecording.Pool(T_ENTRY).entry(1, entry(7)).entry(2, entry(8)));
    // box resolves through its own pool to the entry wrapping the byte
    assertEquals(List.of("n=7 box={b=42}", "n=8 box={b=42}"), parse(chunk(metadata(), pools)));
  }

  /**
   * A pooled field of a primitive type is one varint too. The skipper read it as the primitive,
   * here 4 bytes of a {@code float}, where the value reader reads the varint, so every entry after
   * it was misframed.
   */
  @Test
  void aPooledPrimitiveFieldIsSkippedAsItsIndex() throws Exception {
    byte[] pools =
        SyntheticRecording.Pool.section(
            new SyntheticRecording.Pool(T_ENTRY).entry(1, entry(7)).entry(2, entry(8)));
    List<String> seen = parse(chunk(metadata(T_FLOAT), pools));
    assertEquals(2, seen.size(), String.valueOf(seen));
    assertEquals("n=7", seen.get(0).substring(0, 3), String.valueOf(seen));
    assertEquals("n=8", seen.get(1).substring(0, 3), String.valueOf(seen));
  }
}
