package io.jafar.testrecordings.adversarial;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds single-chunk JFR recording bytes at the byte level.
 *
 * <p>This is a deliberate copy of {@code parser-core/src/test/java/io/jafar/parser/impl/
 * SyntheticRecording.java}: the {@code test-recordings} module cannot see parser-core's test
 * classes (they are not on any published or test classpath), and moving that class out of
 * parser-core's test sources would touch the parity-shared {@code NodePoolFixture} path (its bytes
 * are mirrored into {@code go-parser/jfr/testdata} and drift-checked by {@code
 * SharedFixturesTest}), which this task must not disturb. Keep the two in sync where they overlap;
 * the divergent parts are the adversarial knobs that {@link AdversarialRecordings} needs and
 * parser-core's tests do not.
 *
 * <p>The layout follows the chunk format the JVM writes: a 68 byte header, the events, one
 * checkpoint event and the metadata event, with the header pointing at the latter two. Integers are
 * written with the compressed (varint) encoding.
 */
final class RawChunkWriter {
  private static final int CHUNK_HEADER_SIZE = 68;
  private static final int CHUNK_SIZE_FIELD_OFFSET = 8; // magic(4) + major(2) + minor(2)
  private static final int FEATURE_COMPRESSED_INTS = 1;

  /** An element of the metadata element tree. */
  static final class Element {
    final String name;
    final Map<String, String> attributes = new LinkedHashMap<>();
    final List<Element> children = new ArrayList<>();

    Element(String name, String... keyValues) {
      this.name = name;
      for (int i = 0; i < keyValues.length; i += 2) {
        attributes.put(keyValues[i], keyValues[i + 1]);
      }
    }

    Element with(Element... elements) {
      children.addAll(Arrays.asList(elements));
      return this;
    }
  }

  static Element elem(String name, String... keyValues) {
    return new Element(name, keyValues);
  }

  static Element primitiveClass(long id, String name) {
    return elem("class", "id", Long.toString(id), "name", name, "simpleType", "true");
  }

  /** A constant pool of a checkpoint event, as in parser-core's SyntheticRecording. */
  static final class Pool {
    final long typeId;
    private final List<Long> ids = new ArrayList<>();
    private final List<byte[]> values = new ArrayList<>();

    Pool(long typeId) {
      this.typeId = typeId;
    }

    /** Adds an entry; {@code value} is its encoding, without the index. */
    Pool entry(long id, byte[] value) {
      ids.add(id);
      values.add(value);
      return this;
    }

    /** Adds a UTF-8 string entry. */
    Pool string(long id, String value) {
      return entry(id, new Bytes().utf8(value).toByteArray());
    }

    /** The constant pool section of a checkpoint holding {@code pools}: the count, then each. */
    static byte[] section(Pool... pools) {
      Bytes w = new Bytes().varint(pools.length);
      for (Pool pool : pools) {
        w.varint(pool.typeId).varint(pool.ids.size());
        for (int i = 0; i < pool.ids.size(); i++) {
          w.varint(pool.ids.get(i)).raw(pool.values.get(i));
        }
      }
      return w.toByteArray();
    }
  }

  /** Accumulates the bytes of an event body or a chunk. */
  static final class Bytes {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    Bytes varint(long v) {
      for (int i = 0; i < 8; i++) {
        int b = (int) (v & 0x7F);
        v >>>= 7;
        if (v == 0) {
          out.write(b);
          return this;
        }
        out.write(b | 0x80);
      }
      out.write((int) (v & 0xFF));
      return this;
    }

    /** Writes an {@code int} the way the JVM does: the 32 bit value zero extended to a varint. */
    Bytes intVarint(int v) {
      return varint(v & 0xFFFFFFFFL);
    }

    Bytes utf8(String s) {
      byte[] payload = s.getBytes(StandardCharsets.UTF_8);
      out.write(3); // UTF-8 string encoding tag
      varint(payload.length);
      out.write(payload, 0, payload.length);
      return this;
    }

    Bytes raw(byte[] b) {
      out.write(b, 0, b.length);
      return this;
    }

    /** Writes a {@code float} as its 4 byte big-endian IEEE 754 bits. */
    Bytes f32(float v) {
      return raw(ByteBuffer.allocate(4).putFloat(v).array());
    }

    /** Writes a {@code double} as its 8 byte big-endian IEEE 754 bits. */
    Bytes f64(double v) {
      return raw(ByteBuffer.allocate(8).putDouble(v).array());
    }

    Bytes u8(int b) {
      out.write(b);
      return this;
    }

    int size() {
      return out.size();
    }

    byte[] toByteArray() {
      return out.toByteArray();
    }
  }

  private static int varintLen(long v) {
    return new Bytes().varint(v).size();
  }

  /** Prefixes a body with its size; the size covers the prefix itself. */
  private static byte[] sized(byte[] body) {
    for (int prefix = 1; ; prefix++) {
      long size = body.length + prefix;
      if (varintLen(size) == prefix) {
        return new Bytes().varint(size).raw(body).toByteArray();
      }
    }
  }

  private static void collect(Element e, Map<String, Integer> strings) {
    strings.putIfAbsent(e.name, strings.size());
    for (Map.Entry<String, String> a : e.attributes.entrySet()) {
      strings.putIfAbsent(a.getKey(), strings.size());
      strings.putIfAbsent(a.getValue(), strings.size());
    }
    for (Element c : e.children) {
      collect(c, strings);
    }
  }

  private static void encodeElement(Bytes w, Element e, Map<String, Integer> strings) {
    w.varint(strings.get(e.name));
    w.varint(e.attributes.size());
    for (Map.Entry<String, String> a : e.attributes.entrySet()) {
      w.varint(strings.get(a.getKey()));
      w.varint(strings.get(a.getValue()));
    }
    w.varint(e.children.size());
    for (Element c : e.children) {
      encodeElement(w, c, strings);
    }
  }

  /**
   * Assembles the metadata event of {@code root}, as parser-core's SyntheticRecording does: event
   * type 0, fixed timing fields, the string table collected from the tree, then the tree itself.
   */
  static byte[] metadataEvent(Element root) {
    return metadataEvent(root, 0, 0);
  }

  /**
   * Assembles a metadata event with the adversarial knobs {@link AdversarialRecordings} needs:
   *
   * <ul>
   *   <li>{@code missingStrings} &gt; 0 declares a string table that many strings smaller than the
   *       tree actually references, so element name lookups run past the table;
   *   <li>{@code hugeStringLength} &gt; 0 makes the first string table entry a UTF-8 string whose
   *       declared payload length is that value, with no payload following it (the reader fails at
   *       the declared length long before the absent payload could matter).
   * </ul>
   *
   * <p>Both knobs are off when passed 0.
   */
  static byte[] metadataEvent(Element root, int missingStrings, long hugeStringLength) {
    Map<String, Integer> strings = new LinkedHashMap<>();
    collect(root, strings);
    Bytes body = new Bytes();
    body.varint(0); // event type: metadata
    body.varint(100); // start time
    body.varint(0); // duration
    body.varint(1); // metadata id
    body.varint(strings.size() - missingStrings);
    boolean huge = hugeStringLength > 0;
    for (String s : strings.keySet()) {
      if (huge) {
        body.u8(3); // UTF-8 string encoding tag
        body.varint(hugeStringLength); // the declared payload length, with no payload after it
        huge = false;
        continue;
      }
      body.utf8(s);
    }
    encodeElement(body, root, strings);
    return sized(body.toByteArray());
  }

  /**
   * Assembles a metadata event whose element tree is a chain of {@code depth} unknown-named
   * elements nested under the root, written iteratively: {@code collect} and {@code encodeElement}
   * are recursive and would overflow the generator's own stack at adversarial depths. The parser's
   * {@code readSubelements} path is recursive, which is the point of this shape.
   */
  static byte[] deepChainMetadataEvent(String childName, int depth) {
    Bytes body = new Bytes();
    body.varint(0); // event type: metadata
    body.varint(100); // start time
    body.varint(0); // duration
    body.varint(1); // metadata id
    body.varint(2); // string table: "root" and the chain element's name
    body.utf8("root");
    body.utf8(childName);
    // The root element, with one child...
    body.varint(0); // element name -> "root"
    body.varint(0); // attribute count
    body.varint(1); // one child
    // ...then the chain: each level has one child, except the deepest.
    for (int i = 0; i < depth; i++) {
      body.varint(1); // element name -> childName, unknown to the parser
      body.varint(0); // attribute count
      body.varint(i < depth - 1 ? 1 : 0); // child count
    }
    return sized(body.toByteArray());
  }

  /** A checkpoint event holding {@code constantPools}: the pool count, then each pool. */
  private static byte[] checkpointEvent(byte[] constantPools) {
    Bytes body = new Bytes();
    body.varint(1); // event type: checkpoint
    body.varint(200); // start time
    body.varint(0); // duration
    body.varint(0); // next offset delta: last checkpoint of the chunk
    body.u8(0); // flush flag
    body.raw(constantPools);
    return sized(body.toByteArray());
  }

  /**
   * Assembles a chunk.
   *
   * @param metadata the metadata root element
   * @param events event bodies, each starting with the event type id varint
   */
  static byte[] chunk(
      Element metadata,
      List<byte[]> events,
      long startNanos,
      long startTicks,
      long frequency,
      long durationNanos) {
    return chunk(
        metadata,
        new Bytes().varint(0).toByteArray(), // no constant pools
        events,
        startNanos,
        startTicks,
        frequency,
        durationNanos);
  }

  /**
   * Assembles a chunk with one checkpoint.
   *
   * @param metadata the metadata root element
   * @param constantPools the checkpoint's constant pool section: the pool count, then for each pool
   *     its type id, entry count and entries ({@code id} followed by the entry's bytes)
   * @param events event bodies, each starting with the event type id varint
   */
  static byte[] chunk(
      Element metadata,
      byte[] constantPools,
      List<byte[]> events,
      long startNanos,
      long startTicks,
      long frequency,
      long durationNanos) {
    return chunk(
        metadataEvent(metadata),
        constantPools,
        events,
        startNanos,
        startTicks,
        frequency,
        durationNanos);
  }

  /**
   * Assembles a chunk around a pre-built metadata event, so the adversarial variants of {@link
   * #metadataEvent(Element, int, long)} and {@link #deepChainMetadataEvent(String, int)} can be
   * used.
   */
  static byte[] chunk(
      byte[] metadataEvent,
      byte[] constantPools,
      List<byte[]> events,
      long startNanos,
      long startTicks,
      long frequency,
      long durationNanos) {
    Bytes eventBytes = new Bytes();
    for (byte[] ev : events) {
      eventBytes.raw(sized(ev));
    }
    byte[] checkpoint = checkpointEvent(constantPools);
    byte[] meta = metadataEvent;

    long cpOffset = CHUNK_HEADER_SIZE + eventBytes.size();
    long metaOffset = cpOffset + checkpoint.length;
    long size = metaOffset + meta.length;

    ByteBuffer header = ByteBuffer.allocate(CHUNK_HEADER_SIZE); // big endian
    header.put(new byte[] {'F', 'L', 'R', 0});
    header.putShort((short) 2); // major
    header.putShort((short) 1); // minor
    header.putLong(size);
    header.putLong(cpOffset);
    header.putLong(metaOffset);
    header.putLong(startNanos);
    header.putLong(durationNanos);
    header.putLong(startTicks);
    header.putLong(frequency);
    header.putInt(FEATURE_COMPRESSED_INTS);

    return new Bytes()
        .raw(header.array())
        .raw(eventBytes.toByteArray())
        .raw(checkpoint)
        .raw(meta)
        .toByteArray();
  }

  /** Concatenates chunks into one recording: the parser walks them by each header's size field. */
  static byte[] recording(byte[]... chunks) {
    Bytes out = new Bytes();
    for (byte[] c : chunks) {
      out.raw(c);
    }
    return out.toByteArray();
  }

  /**
   * Returns a copy of {@code chunk} whose header declares {@code declaredSize} as the chunk size,
   * leaving the bytes as they are: the adversarial way to make a chunk's size field disagree with
   * where the next chunk actually starts (the parser trusts the field to find it).
   */
  static byte[] withDeclaredChunkSize(byte[] chunk, long declaredSize) {
    byte[] out = chunk.clone();
    ByteBuffer.wrap(out, CHUNK_SIZE_FIELD_OFFSET, 8).putLong(declaredSize);
    return out;
  }

  private RawChunkWriter() {}
}
