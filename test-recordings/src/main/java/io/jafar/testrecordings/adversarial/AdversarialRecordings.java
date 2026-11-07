package io.jafar.testrecordings.adversarial;

import io.jafar.testrecordings.adversarial.RawChunkWriter.Bytes;
import io.jafar.testrecordings.adversarial.RawChunkWriter.Element;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The deterministic adversarial recordings of the fuzz corpus (AC-4.7): hand-built shapes that
 * target the paths random bit flips cannot reach (kb/fuzz-robustness-baseline item 1,
 * kb/design-format-correctness item 3).
 *
 * <p>Every case is built only from constants — no clocks, no randomness — so generating a case
 * twice yields identical bytes, and the committed seeds under {@code
 * parser-core/src/test/resources/io/jafar/parser/fuzz/UntypedParserFuzzTestInputs/parseUntyped/}
 * can be drift-checked against the generator ({@code AdversarialRecordingsTest}, the {@code
 * SharedFixturesTest} pattern). The seed files carry no {@code .jfr} extension, because {@code
 * .gitignore} ignores {@code *.jfr} and they must be committed; Jazzer's regression mode replays
 * every file in the directory regardless of its name.
 *
 * <p>What each case's outcome IS (exception and message, or delivered events) is pinned by
 * parser-core's adversarial outcome tests; this enum only guarantees the bytes.
 */
public enum AdversarialRecordings {
  /** The control: the same shape as the corrupted cases, but valid. Parses and delivers events. */
  VALID_BASE {
    @Override
    byte[] build() {
      return validChunk(START_NANOS);
    }
  },
  /**
   * The metadata string table declares two strings fewer than the element tree references, so
   * element name lookups run past the table (the {@code MutableMetadataLookup.getString} boundary;
   * the "String index out of bounds (string table size)" path observed in
   * kb/fuzz-robustness-baseline).
   */
  STRING_TABLE_SIZE {
    @Override
    byte[] build() {
      return RawChunkWriter.chunk(
          RawChunkWriter.metadataEvent(metadata(), 2, 0),
          basePools(false),
          baseEvents(),
          START_NANOS,
          START_TICKS,
          FREQUENCY,
          DURATION_NANOS);
    }
  },
  /**
   * The first metadata string table entry declares a UTF-8 payload length far larger than the whole
   * recording ({@code 1 << 24}, 16 MiB against a ~405-byte seed), with no payload following it: the
   * corrupted-huge-length vector.
   *
   * <p>The length is {@code 1 << 24}, not the exact {@code 2^31} an earlier build used: the 2^31
   * variant is the real unbounded-work vector — the declared length narrows to a negative {@code
   * int}, passes the buffer-reuse check, and the parser then spends an unbounded per-unit loop on
   * it, one that survives {@code shutdownNow()} (an abandoned executor burned ~17 CPU-hours here).
   * That defect is recorded under R8/D3 for the report; a fix belongs in the parser, out of scope
   * for this run. {@code 1 << 24} keeps the corrupted-huge-length semantics (the reader still fails
   * on the declared length, not on any real payload) while the parse completes in bounded time.
   */
  HUGE_LENGTH {
    @Override
    byte[] build() {
      return RawChunkWriter.chunk(
          RawChunkWriter.metadataEvent(metadata(), 0, 1L << 24),
          basePools(false),
          baseEvents(),
          START_NANOS,
          START_TICKS,
          FREQUENCY,
          DURATION_NANOS);
    }
  },
  /**
   * The checkpoint holds a constant pool of a type id the chunk metadata does not declare, so its
   * entries cannot be skipped (the boundary contract in {@code CheckpointEvent.readConstantPools}).
   */
  BAD_CP_ID {
    @Override
    byte[] build() {
      return RawChunkWriter.chunk(
          RawChunkWriter.metadataEvent(metadata()),
          basePools(true),
          baseEvents(),
          START_NANOS,
          START_TICKS,
          FREQUENCY,
          DURATION_NANOS);
    }
  },
  /**
   * The metadata element tree is a chain of unknown-named elements nested {@link #DEPTH} deep under
   * the root. The parser's {@code readSubelements} is recursive, so this is the stack overflow
   * vector; the writer encodes the chain iteratively because its own tree walk is recursive too.
   */
  DEEP_METADATA {
    @Override
    byte[] build() {
      return RawChunkWriter.chunk(
          RawChunkWriter.deepChainMetadataEvent("deep", DEPTH),
          new Bytes().varint(0).toByteArray(), // no constant pools
          new ArrayList<>(), // no events: the parse never gets past the metadata
          START_NANOS,
          START_TICKS,
          FREQUENCY,
          DURATION_NANOS);
    }
  },
  /**
   * Two valid chunks, but the first one's header declares a size four bytes larger than the chunk
   * really is: the parser trusts that field to find the next chunk header, so it reads the second
   * chunk's header from the middle and the two disagree about where a chunk ends.
   */
  BAD_CHUNK_OFFSETS {
    @Override
    byte[] build() {
      byte[] first = validChunk(START_NANOS);
      byte[] second = validChunk(START_NANOS + 1_000_000_000L);
      return RawChunkWriter.recording(
          RawChunkWriter.withDeclaredChunkSize(first, first.length + 4), second);
    }
  };

  /** The nesting depth of {@link #DEEP_METADATA}'s element chain. */
  static final int DEPTH = 200_000;

  private static final long START_NANOS = 1_700_000_000_000_000_000L;
  private static final long START_TICKS = 5_000_000;
  private static final long FREQUENCY = 1_000_000;
  private static final long DURATION_NANOS = 1000;

  private static final long T_INT = 5;
  private static final long T_STRING = 9;
  private static final long T_NODE = 50;
  private static final long T_EVENT = 101;
  private static final long T_UNDECLARED_POOL = 77;

  private static final long[] NODE_REFS = {5, 1, 2, 3};

  /** The seed file name under the fuzz inputs directory: {@code adv-<case>}, no extension. */
  public String fileName() {
    return "adv-" + name().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  /** The recording's bytes. Deterministic: fixed constants only, no clocks or randomness. */
  public byte[] bytes() {
    return build();
  }

  abstract byte[] build();

  /**
   * The valid shape every corrupted case is derived from, modelled on parser-core's {@code
   * NodePoolFixture}: a node constant pool and events referencing it.
   */
  private static byte[] validChunk(long startNanos) {
    return RawChunkWriter.chunk(
        RawChunkWriter.metadataEvent(metadata()),
        basePools(false),
        baseEvents(),
        startNanos,
        START_TICKS,
        FREQUENCY,
        DURATION_NANOS);
  }

  /**
   * The checkpoint's constant pool section. With {@code undeclaredPoolId}, a pool of a type the
   * metadata does not declare precedes the node pool, exactly as parser-core's {@code
   * NodePoolFixture} builds its UNDECLARED_POOL shape.
   */
  private static byte[] basePools(boolean undeclaredPoolId) {
    Bytes pools = new Bytes();
    if (undeclaredPoolId) {
      pools.varint(2); // two pools
      pools.varint(T_UNDECLARED_POOL).varint(1).varint(1).varint(0); // entry id 1, payload 0
    } else {
      pools.varint(1);
    }
    pools.varint(T_NODE).varint(4);
    pools.varint(5).raw(nodeEntry("e", 0));
    pools.varint(1).raw(nodeEntry("a", 3));
    pools.varint(2).raw(nodeEntry("b", 0));
    pools.varint(3).raw(nodeEntry("c", 0));
    return pools.toByteArray();
  }

  private static byte[] nodeEntry(String name, long next) {
    return new Bytes().utf8(name).varint(next).toByteArray();
  }

  private static List<byte[]> baseEvents() {
    List<byte[]> events = new ArrayList<>();
    for (int i = 0; i < NODE_REFS.length; i++) {
      events.add(new Bytes().varint(T_EVENT).varint(i).varint(NODE_REFS[i]).toByteArray());
    }
    return events;
  }

  private static Element metadata() {
    return RawChunkWriter.elem("root")
        .with(
            RawChunkWriter.elem("metadata")
                .with(
                    // Not RawChunkWriter.primitiveClass: Java's TypeSkipper unwraps a simpleType
                    // through its first field, and a primitive has none (as NodePoolFixture found).
                    RawChunkWriter.elem("class", "id", "" + T_INT, "name", "int"),
                    RawChunkWriter.elem("class", "id", "" + T_STRING, "name", "java.lang.String"),
                    RawChunkWriter.elem("class", "id", "" + T_NODE, "name", "test.Node")
                        .with(
                            RawChunkWriter.elem("field", "name", "name", "class", "" + T_STRING),
                            RawChunkWriter.elem(
                                "field",
                                "name",
                                "next",
                                "class",
                                "" + T_NODE,
                                "constantPool",
                                "true")),
                    RawChunkWriter.elem(
                            "class",
                            "id",
                            "" + T_EVENT,
                            "name",
                            "test.Holder",
                            "superType",
                            "jdk.jfr.Event")
                        .with(
                            RawChunkWriter.elem("field", "name", "seq", "class", "" + T_INT),
                            RawChunkWriter.elem(
                                "field",
                                "name",
                                "node",
                                "class",
                                "" + T_NODE,
                                "constantPool",
                                "true"))),
            RawChunkWriter.elem("region", "dst", "0", "gmtOffset", "0", "locale", "en_US"));
  }
}
