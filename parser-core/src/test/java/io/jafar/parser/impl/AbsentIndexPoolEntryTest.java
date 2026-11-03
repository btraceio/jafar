package io.jafar.parser.impl;

import static io.jafar.parser.impl.SyntheticRecording.elem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.api.Values;
import io.jafar.parser.impl.SyntheticRecording.Bytes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A checkpoint entry whose index is {@code Long.MIN_VALUE} - the JMC writer's "no value" index,
 * never written for a real entry, so only a corrupted recording has one. It is also {@code
 * LongLongHashMap}'s empty-slot key: {@code MutableConstantPool.containsKey} answers false for it,
 * so {@code CheckpointEvent} passed it to {@code addOffset}, which stored it - an AssertionError
 * under -ea (as in this run), and the empty-slot key written as a real key without it. It is now
 * ignored; the entries around it index normally.
 */
class AbsentIndexPoolEntryTest {
  private static final int T_INT = 5;
  private static final int T_STRING = 9;
  private static final int T_NODE = 50;
  private static final int T_EVENT = 101;

  @TempDir Path dir;

  @Test
  void entryIndexedAsLongMinValueIsIgnored() throws Exception {
    boolean assertions = false;
    assert assertions = true;
    assertTrue(assertions, "this test needs -ea to show the failure it guards against");

    Bytes pools = new Bytes().varint(1); // one pool
    pools.varint(T_NODE).varint(3);
    pools.varint(1).utf8("one");
    pools.varint(Long.MIN_VALUE).utf8("bogus");
    pools.varint(2).utf8("two");
    List<byte[]> events = new ArrayList<>();
    long[] refs = {1, 2, Long.MIN_VALUE};
    for (int i = 0; i < refs.length; i++) {
      events.add(new Bytes().varint(T_EVENT).varint(i).varint(refs[i]).toByteArray());
    }
    Path jfr = dir.resolve("min-entry.jfr");
    Files.write(
        jfr,
        SyntheticRecording.chunk(
            elem("root")
                .with(
                    elem("metadata")
                        .with(
                            elem("class", "id", "" + T_INT, "name", "int"),
                            elem("class", "id", "" + T_STRING, "name", "java.lang.String"),
                            elem("class", "id", "" + T_NODE, "name", "test.Node")
                                .with(elem("field", "name", "name", "class", "" + T_STRING)),
                            elem(
                                    "class",
                                    "id",
                                    "" + T_EVENT,
                                    "name",
                                    "test.Holder",
                                    "superType",
                                    "jdk.jfr.Event")
                                .with(
                                    elem("field", "name", "seq", "class", "" + T_INT),
                                    elem(
                                        "field",
                                        "name",
                                        "node",
                                        "class",
                                        "" + T_NODE,
                                        "constantPool",
                                        "true"))),
                    elem("region", "dst", "0", "gmtOffset", "0", "locale", "en_US")),
            pools.toByteArray(),
            events,
            1_700_000_000_000_000_000L,
            5_000_000,
            1_000_000,
            1000));

    List<String> seen = Collections.synchronizedList(new ArrayList<>());
    try (UntypedJafarParser p = UntypedJafarParser.open(jfr)) {
      p.handle(
          (type, value, ctl) -> {
            if ("test.Holder".equals(type.getName())) {
              Map<?, ?> node = (Map<?, ?>) Values.resolvedDeep(value).get("node");
              seen.add(value.get("seq") + " " + (node == null ? null : node.get("name")));
            }
          });
      p.run();
    }
    assertEquals(List.of("0 one", "1 two", "2 null"), seen);
  }
}
