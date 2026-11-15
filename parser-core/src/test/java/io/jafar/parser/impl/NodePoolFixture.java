package io.jafar.parser.impl;

import static io.jafar.parser.impl.SyntheticRecording.elem;

import io.jafar.parser.impl.SyntheticRecording.Bytes;
import io.jafar.parser.impl.SyntheticRecording.Element;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A one-chunk recording with one damaged-or-not {@code test.Node} constant pool, shared by both
 * untyped parsers.
 *
 * <p>The pool stores entries 5, 1, 2, 3 in that order, where a node is {@code (String name, pooled
 * Node next)}; entry 5's {@code next} is 0, entry 1's is 3. {@value #EVENT} events {@code (int seq,
 * pooled Node node)} refer to nodes {@code 5, 0, Long.MIN_VALUE, 1, 2, 3, 9}: 0 is how HotSpot
 * writes an absent value, {@code Long.MIN_VALUE} how the JMC writer writes a null one, and 9 is
 * never declared. {@link Shape} says how the pool is damaged.
 *
 * <p>The Go parser's tests read the same bytes from {@code go-parser/jfr/testdata}; {@link
 * SharedFixturesTest} fails when those files drift from what this class builds.
 */
final class NodePoolFixture {
  static final String EVENT = "test.Holder";
  static final long[] NODE_REFS = {5, 0, Long.MIN_VALUE, 1, 2, 3, 9};

  static final int T_INT = 5;
  static final int T_STRING = 9;
  static final int T_NODE = 50;
  static final int T_EVENT = 101;
  static final int T_UNDECLARED = 77;

  enum Shape {
    /** Nothing is damaged. */
    INTACT,
    /** Entry 2's name has an encoding id no encoding uses, so the entry cannot be skipped. */
    UNREADABLE_ENTRY,
    /**
     * A pool of type {@value NodePoolFixture#T_UNDECLARED}, which the metadata does not declare,
     * precedes the node pool in the checkpoint. Its entries cannot be skipped without the type.
     */
    UNDECLARED_POOL,
    /**
     * Entry 1's name is a reference into a {@code java.lang.String} pool the chunk does not have.
     * The pool indexes fine (a string reference is skipped by its varint); the entry fails only
     * when it is resolved.
     */
    DANGLING_NAME;

    /** The fixture's file name under {@code go-parser/jfr/testdata}. */
    String fileName() {
      return "node-pool-" + name().toLowerCase(Locale.ROOT).replace('_', '-') + ".jfr";
    }
  }

  static byte[] bytes(Shape shape) {
    byte[] bad = nodeEntry("b", 0);
    if (shape == Shape.UNREADABLE_ENTRY) {
      bad[0] = 0x7f; // no JFR string encoding uses this id (0..5 are defined)
    }
    byte[] one =
        shape == Shape.DANGLING_NAME
            ? new Bytes().u8(2).varint(42).varint(3).toByteArray() // name -> string pool #42
            : nodeEntry("a", 3);

    Bytes pools = new Bytes();
    if (shape == Shape.UNDECLARED_POOL) {
      pools.varint(2); // pool count
      pools.varint(T_UNDECLARED).varint(1).varint(1).varint(0); // one entry: id 1, payload 0
    } else {
      pools.varint(1);
    }
    pools.varint(T_NODE).varint(4);
    pools.varint(5).raw(nodeEntry("e", 0));
    pools.varint(1).raw(one);
    pools.varint(2).raw(bad);
    pools.varint(3).raw(nodeEntry("c", 0));

    List<byte[]> events = new ArrayList<>();
    for (int i = 0; i < NODE_REFS.length; i++) {
      events.add(new Bytes().varint(T_EVENT).varint(i).varint(NODE_REFS[i]).toByteArray());
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

  private static byte[] nodeEntry(String name, long next) {
    return new Bytes().utf8(name).varint(next).toByteArray();
  }

  private static Element metadata() {
    return elem("root")
        .with(
            elem("metadata")
                .with(
                    // Not SyntheticRecording.primitiveClass: Java's TypeSkipper unwraps a
                    // simpleType through its first field, and a primitive has none.
                    elem("class", "id", "" + T_INT, "name", "int"),
                    elem("class", "id", "" + T_STRING, "name", "java.lang.String"),
                    elem("class", "id", "" + T_NODE, "name", "test.Node")
                        .with(
                            elem("field", "name", "name", "class", "" + T_STRING),
                            elem(
                                "field",
                                "name",
                                "next",
                                "class",
                                "" + T_NODE,
                                "constantPool",
                                "true")),
                    elem("class", "id", "" + T_EVENT, "name", EVENT, "superType", "jdk.jfr.Event")
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
            elem("region", "dst", "0", "gmtOffset", "0", "locale", "en_US"));
  }

  private NodePoolFixture() {}
}
