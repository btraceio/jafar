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
  private static final long T_EVENT = 100;
  private static final long T_BOX = 300;
  private static final long T_ENTRY = 301;

  /** Index of the one ByteBox entry: 300 is a two-byte varint, so a one-byte skip misframes. */
  private static final long BOX_INDEX = 300;

  @TempDir Path dir;

  private static SyntheticRecording.Element metadata() {
    return elem("root")
        .with(
            elem("metadata")
                .with(
                    primitiveClass(T_BOOLEAN, "boolean"),
                    primitiveClass(T_BYTE, "byte"),
                    primitiveClass(T_INT, "int"),
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
                                Long.toString(T_BOX),
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

  @Test
  void aPooledSimpleTypeFieldIsSkippedAsItsIndex() throws Exception {
    byte[] chunk =
        SyntheticRecording.chunk(
            metadata(),
            SyntheticRecording.Pool.section(
                // a simple type's entry is stored unwrapped: the byte itself
                new SyntheticRecording.Pool(T_BOX).entry(BOX_INDEX, new byte[] {42}),
                new SyntheticRecording.Pool(T_ENTRY).entry(1, entry(7)).entry(2, entry(8))),
            List.of(ref(1), ref(2)),
            1_700_000_000_000_000_000L,
            0,
            1_000_000_000L,
            1000);
    Path file = dir.resolve("pooled-simple-type.jfr");
    Files.write(file, chunk);

    List<Object> ns = Collections.synchronizedList(new ArrayList<>());
    try (UntypedJafarParser p = UntypedJafarParser.open(file)) {
      p.handle(
          (type, value, ctl) -> {
            Object entry = Values.resolvedShallow(value).get("entry");
            ns.add(entry instanceof Map ? ((Map<?, ?>) entry).get("n") : "entry=" + entry);
          });
      p.run();
    }
    Collections.sort(ns, (a, b) -> String.valueOf(a).compareTo(String.valueOf(b)));
    assertEquals(List.of(7L, 8L), ns);
  }
}
