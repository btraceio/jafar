package io.jafar.parser.impl;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a single-chunk JFR recording byte by byte, so that the parser can be exercised end to end
 * on shapes that no available writer can produce (the JMC {@code flightrecorder.writer} used
 * elsewhere in the tests cannot write array fields on events).
 *
 * <p>The layout follows the chunk format the JVM writes and mirrors the Go parser's test helper
 * ({@code go-parser/jfr/testrecording_test.go}): a 68 byte header, the events, one (empty)
 * checkpoint event and the metadata event, with the header pointing at the latter two. Integers are
 * written with the compressed (varint) encoding.
 */
final class SyntheticRecording {
  private static final int CHUNK_HEADER_SIZE = 68;
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

  private static byte[] metadataEvent(Element root) {
    Map<String, Integer> strings = new LinkedHashMap<>();
    collect(root, strings);
    Bytes body = new Bytes();
    body.varint(0); // event type: metadata
    body.varint(100); // start time
    body.varint(0); // duration
    body.varint(1); // metadata id
    body.varint(strings.size());
    for (String s : strings.keySet()) {
      body.utf8(s);
    }
    encodeElement(body, root, strings);
    return sized(body.toByteArray());
  }

  private static byte[] emptyCheckpointEvent() {
    Bytes body = new Bytes();
    body.varint(1); // event type: checkpoint
    body.varint(200); // start time
    body.varint(0); // duration
    body.varint(0); // next offset delta: last checkpoint of the chunk
    body.u8(0); // flush flag
    body.varint(0); // no constant pools
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
    Bytes eventBytes = new Bytes();
    for (byte[] ev : events) {
      eventBytes.raw(sized(ev));
    }
    byte[] checkpoint = emptyCheckpointEvent();
    byte[] meta = metadataEvent(metadata);

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

  private SyntheticRecording() {}
}
