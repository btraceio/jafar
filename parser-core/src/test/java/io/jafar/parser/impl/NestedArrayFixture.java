package io.jafar.parser.impl;

import static io.jafar.parser.impl.SyntheticRecording.elem;

import io.jafar.parser.impl.SyntheticRecording.Bytes;
import io.jafar.parser.impl.SyntheticRecording.Element;
import java.util.ArrayList;
import java.util.List;

/**
 * A one-chunk recording with an array of an inline type that itself has an array field, both on
 * events and inside constant pool entries - the shape that skipping got wrong (an array instruction
 * nested in array elements was not interpreted).
 *
 * <p>{@code test.Inner {int[] vals}}; pooled {@code test.Holder {Inner[] items, int tail}} with
 * entries 1 and 2 ({@code tail} = 101, 102); events {@code test.Nested {Inner[] items, pooled
 * Holder holder, int last}} referring to holders 1, 2, 1 with {@code last} = 1000 + seq. No
 * available writer produces inline complex values (the JMC writer pools them), so it is built byte
 * by byte. Public so that jafar-processor's tests can run generated handlers on it.
 */
public final class NestedArrayFixture {
  public static final String EVENT = "test.Nested";
  public static final String HOLDER = "test.Holder";
  public static final long[] HOLDER_REFS = {1, 2, 1};

  static final int T_INT = 5;
  static final int T_INNER = 60;
  static final int T_HOLDER = 61;
  static final int T_EVENT = 102;

  public static byte[] bytes() {
    Bytes pools = new Bytes().varint(1); // one pool
    pools.varint(T_HOLDER).varint(2);
    for (int id = 1; id <= 2; id++) {
      pools.varint(id);
      items(pools, id);
      pools.intVarint(100 + id); // tail
    }
    List<byte[]> events = new ArrayList<>();
    for (int seq = 0; seq < HOLDER_REFS.length; seq++) {
      Bytes e = new Bytes().varint(T_EVENT);
      items(e, seq + 7);
      e.varint(HOLDER_REFS[seq]);
      e.intVarint(1000 + seq); // last
      events.add(e.toByteArray());
    }
    return SyntheticRecording.chunk(
        metadata(),
        pools.toByteArray(),
        events,
        1_700_000_000_000_000_000L,
        5_000_000,
        1_000_000,
        1000);
  }

  /** {@code Inner[] items}: two inners, with 2 and 1 values derived from {@code n}. */
  private static void items(Bytes b, int n) {
    b.varint(2); // items.length
    b.varint(2).intVarint(n).intVarint(n + 1); // items[0].vals
    b.varint(1).intVarint(n * 10); // items[1].vals
  }

  private static Element metadata() {
    return elem("root")
        .with(
            elem("metadata")
                .with(
                    elem("class", "id", "" + T_INT, "name", "int"),
                    elem("class", "id", "" + T_INNER, "name", "test.Inner")
                        .with(elem("field", "name", "vals", "class", "" + T_INT, "dimension", "1")),
                    elem("class", "id", "" + T_HOLDER, "name", HOLDER)
                        .with(
                            elem("field", "name", "items", "class", "" + T_INNER, "dimension", "1"),
                            elem("field", "name", "tail", "class", "" + T_INT)),
                    elem("class", "id", "" + T_EVENT, "name", EVENT, "superType", "jdk.jfr.Event")
                        .with(
                            elem("field", "name", "items", "class", "" + T_INNER, "dimension", "1"),
                            elem(
                                "field",
                                "name",
                                "holder",
                                "class",
                                "" + T_HOLDER,
                                "constantPool",
                                "true"),
                            elem("field", "name", "last", "class", "" + T_INT))),
            elem("region", "dst", "0", "gmtOffset", "0", "locale", "en_US"));
  }

  private NestedArrayFixture() {}
}
