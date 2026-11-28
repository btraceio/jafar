package io.jafar.shell.backend.impl;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes multi-chunk JFR recordings whose chunks give one type id to different event types, the
 * shape the JDK's merged type view loses types on (#193, #198). Each chunk defines one event type,
 * {@code long} fields only, and holds a few events of it.
 *
 * <p>The chunk layout is the one {@code test-recordings}' {@code RawChunkWriter} writes (header,
 * events, an empty checkpoint, the metadata event), with one difference that matters here: every
 * chunk's metadata event carries its own metadata id. That writer is not reused because it fixes
 * the id at 1, and this module's tests do not depend on {@code test-recordings}. The JDK's reader
 * keeps the previous chunk's types while the id stays the same, so with a shared id it would decode
 * every chunk's events with the first chunk's types.
 */
final class ChunkLocalRecording {

  /** One chunk: an event type {@code name} at {@code typeId}, with these fields after startTime. */
  record Chunk(long typeId, String name, List<String> fields, int events) {}

  private static final int HEADER_SIZE = 68;
  private static final long LONG_CLASS_ID = 1;

  static byte[] write(Chunk... chunks) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    long metadataId = 1;
    long ticks = 1_000;
    for (Chunk chunk : chunks) {
      byte[] bytes = chunk(chunk, metadataId++, ticks);
      out.write(bytes, 0, bytes.length);
      ticks += 1_000;
    }
    return out.toByteArray();
  }

  private static byte[] chunk(Chunk chunk, long metadataId, long startTicks) {
    Bytes events = new Bytes();
    for (int i = 0; i < chunk.events(); i++) {
      Bytes event = new Bytes().varint(chunk.typeId()).varint(startTicks + i);
      for (int f = 0; f < chunk.fields().size(); f++) {
        event.varint(100 + f);
      }
      events.raw(sized(event.bytes()));
    }
    Bytes checkpoint =
        new Bytes()
            .varint(1) // event type: checkpoint
            .varint(startTicks) // start time
            .varint(0) // duration
            .varint(0) // delta to the next checkpoint: none
            .u8(0) // checkpoint type
            .varint(0); // no constant pools
    byte[] cp = sized(checkpoint.bytes());
    byte[] meta = metadata(chunk, metadataId, startTicks);

    long cpOffset = HEADER_SIZE + events.size();
    long metaOffset = cpOffset + cp.length;
    ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE);
    header.put(new byte[] {'F', 'L', 'R', 0});
    header.putShort((short) 2).putShort((short) 1);
    header.putLong(metaOffset + meta.length); // chunk size
    header.putLong(cpOffset);
    header.putLong(metaOffset);
    header.putLong(startTicks * 1_000_000L); // start, nanoseconds
    header.putLong(1_000_000L); // duration, nanoseconds
    header.putLong(startTicks);
    header.putLong(1_000L); // ticks per second
    // Byte 64 is the file state, 0 for a finished chunk; byte 67 the features, 1 for compressed
    // integers
    header.putInt(1);
    return new Bytes().raw(header.array()).raw(events.bytes()).raw(cp).raw(meta).bytes();
  }

  private static byte[] metadata(Chunk chunk, long metadataId, long startTicks) {
    Element event =
        new Element("class")
            .attr("id", Long.toString(chunk.typeId()))
            .attr("name", chunk.name())
            .attr("superType", "jdk.jfr.Event");
    event.child(new Element("field").attr("name", "startTime").attr("class", "1"));
    for (String field : chunk.fields()) {
      event.child(new Element("field").attr("name", field).attr("class", "1"));
    }
    Element metadata =
        new Element("metadata")
            .child(
                new Element("class").attr("id", Long.toString(LONG_CLASS_ID)).attr("name", "long"))
            .child(event);
    Element root =
        new Element("root")
            .child(metadata)
            .child(new Element("region").attr("locale", "en_US").attr("gmtOffset", "0"));

    Map<String, Integer> strings = new LinkedHashMap<>();
    root.collect(strings);
    Bytes body =
        new Bytes()
            .varint(0) // event type: metadata
            .varint(startTicks) // start time
            .varint(0) // duration
            .varint(metadataId)
            .varint(strings.size());
    for (String s : strings.keySet()) {
      body.utf8(s);
    }
    root.encode(body, strings);
    return sized(body.bytes());
  }

  /** A metadata element: a name, attributes and child elements. */
  private static final class Element {
    final String name;
    final Map<String, String> attributes = new LinkedHashMap<>();
    final List<Element> children = new ArrayList<>();

    Element(String name) {
      this.name = name;
    }

    Element attr(String key, String value) {
      attributes.put(key, value);
      return this;
    }

    Element child(Element child) {
      children.add(child);
      return this;
    }

    void collect(Map<String, Integer> strings) {
      strings.putIfAbsent(name, strings.size());
      attributes.forEach(
          (k, v) -> {
            strings.putIfAbsent(k, strings.size());
            strings.putIfAbsent(v, strings.size());
          });
      children.forEach(c -> c.collect(strings));
    }

    void encode(Bytes w, Map<String, Integer> strings) {
      w.varint(strings.get(name)).varint(attributes.size());
      attributes.forEach((k, v) -> w.varint(strings.get(k)).varint(strings.get(v)));
      w.varint(children.size());
      children.forEach(c -> c.encode(w, strings));
    }
  }

  /** An event or chunk under construction; integers in the compressed (varint) encoding. */
  private static final class Bytes {
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

    Bytes utf8(String s) {
      byte[] payload = s.getBytes(StandardCharsets.UTF_8);
      out.write(3); // UTF-8 string encoding
      varint(payload.length);
      out.write(payload, 0, payload.length);
      return this;
    }

    Bytes u8(int b) {
      out.write(b);
      return this;
    }

    Bytes raw(byte[] b) {
      out.write(b, 0, b.length);
      return this;
    }

    int size() {
      return out.size();
    }

    byte[] bytes() {
      return out.toByteArray();
    }
  }

  /** Prefixes {@code body} with its size, which counts the prefix too. */
  private static byte[] sized(byte[] body) {
    for (int prefix = 1; ; prefix++) {
      long size = body.length + prefix;
      if (new Bytes().varint(size).size() == prefix) {
        return new Bytes().varint(size).raw(body).bytes();
      }
    }
  }

  private ChunkLocalRecording() {}
}
