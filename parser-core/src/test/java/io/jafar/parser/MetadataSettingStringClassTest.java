package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.ParserContext;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.internal_api.metadata.MetadataEvent;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression test for Bug #100, Bug 1: {@code MetadataSetting.onAttribute()} threw {@code
 * NumberFormatException} when the {@code class} attribute contained a class name string (e.g.
 * {@code "datadog.WallClockSamplingEpoch"}) instead of a numeric type ID.
 *
 * <p>Some JFR producers (e.g. dd-trace-java) write setting class attributes as class name strings
 * rather than numeric type IDs. This test builds a minimal synthetic JFR binary with exactly that
 * non-standard attribute and verifies that parsing completes without exception.
 */
public class MetadataSettingStringClassTest {

  @TempDir Path tempDir;

  // -------------------------------------------------------------------------
  // Binary helpers
  // -------------------------------------------------------------------------

  /** Encodes {@code v} as an unsigned LEB128 varint into {@code out}. */
  private static void writeVarint(ByteArrayOutputStream out, long v) {
    do {
      byte b = (byte) (v & 0x7F);
      v >>>= 7;
      if (v != 0) b |= (byte) 0x80;
      out.write(b & 0xFF);
    } while (v != 0);
  }

  /** Returns the number of bytes required to encode {@code v} as a varint. */
  private static int varintLen(long v) {
    int len = 0;
    do {
      v >>>= 7;
      len++;
    } while (v != 0);
    return len;
  }

  /**
   * Computes the total event size (including the size varint itself) given the payload length
   * (everything after the size varint).
   */
  private static int eventSize(int payloadLen) {
    int size = payloadLen + 1;
    while (true) {
      int next = payloadLen + varintLen(size);
      if (next == size) break;
      size = next;
    }
    return size;
  }

  /** Encodes {@code s} using LATIN-1 (encoding type 5) as used in the JFR metadata string table. */
  private static void writeLatin1String(ByteArrayOutputStream out, String s) throws IOException {
    out.write(5); // LATIN-1 encoding
    writeVarint(out, s.length());
    out.write(s.getBytes(StandardCharsets.ISO_8859_1));
  }

  // -------------------------------------------------------------------------
  // Synthetic JFR binary
  // -------------------------------------------------------------------------

  /**
   * Builds a minimal single-chunk JFR recording whose metadata section contains a {@code <setting>}
   * element with {@code class="datadog.WallClockSamplingEpoch"} — a non-numeric class attribute as
   * emitted by dd-trace-java.
   *
   * <p>Layout (all offsets absolute from start of file):
   *
   * <pre>
   *   [0..67]   Chunk header (68 bytes)
   *   [68..74]  Checkpoint event (7 bytes, 0 constant pools)
   *   [75..]    Metadata event (string table + element tree)
   * </pre>
   *
   * <p>String table indices used in the element tree:
   *
   * <pre>
   *   0  "root"
   *   1  "metadata"
   *   2  "class"          (element type name AND attribute key)
   *   3  "setting"
   *   4  "id"
   *   5  "name"
   *   6  "superType"
   *   7  "100"            (type ID of the fake event class)
   *   8  "test.FakeEvent" (event class name)
   *   9  "jdk.jfr.Event"  (supertype)
   *   10 "period"         (setting name)
   *   11 "datadog.WallClockSamplingEpoch"  (non-numeric setting class)
   * </pre>
   *
   * <p>Element tree:
   *
   * <pre>
   *   root
   *     metadata
   *       class (id=100, name=test.FakeEvent, superType=jdk.jfr.Event)
   *         setting (class=datadog.WallClockSamplingEpoch, name=period)
   * </pre>
   */
  private static byte[] buildSyntheticJfr() throws IOException {
    // --- Build metadata event payload (everything after the size varint) ---
    ByteArrayOutputStream metaPayload = new ByteArrayOutputStream();
    writeVarint(metaPayload, 0); // typeId = 0 (metadata event)
    writeVarint(metaPayload, 0); // startTime
    writeVarint(metaPayload, 0); // duration
    writeVarint(metaPayload, 1); // metadataId

    // String table (14 entries)
    // Indices: 0=root, 1=metadata, 2=class, 3=setting, 4=id, 5=name, 6=superType,
    //          7="100" (FakeEvent type ID), 8=test.FakeEvent, 9=jdk.jfr.Event,
    //          10=period, 11=datadog.WallClockSamplingEpoch,
    //          12="200" (java.lang.String type ID), 13=java.lang.String
    // Note: java.lang.String must be present because onMetadataReady() resolves its type ID.
    String[] strings = {
      "root",
      "metadata",
      "class",
      "setting",
      "id",
      "name",
      "superType",
      "100",
      "test.FakeEvent",
      "jdk.jfr.Event",
      "period",
      "datadog.WallClockSamplingEpoch",
      "200",
      "java.lang.String"
    };
    writeVarint(metaPayload, strings.length);
    for (String s : strings) {
      writeLatin1String(metaPayload, s);
    }

    // Element tree (varint indices into string table):
    // root (type=0, 0 attrs, 1 sub)
    writeVarint(metaPayload, 0);
    writeVarint(metaPayload, 0);
    writeVarint(metaPayload, 1);
    // metadata (type=1, 0 attrs, 2 subs)
    writeVarint(metaPayload, 1);
    writeVarint(metaPayload, 0);
    writeVarint(metaPayload, 2); // 2 sub-classes
    // class java.lang.String (type=2, 2 attrs: id=200, name=java.lang.String, 0 subs)
    // Required: onMetadataReady() calls getClass("java.lang.String").getId()
    writeVarint(metaPayload, 2);
    writeVarint(metaPayload, 2); // attr count
    writeVarint(metaPayload, 4);
    writeVarint(metaPayload, 12); // id = "200"
    writeVarint(metaPayload, 5);
    writeVarint(metaPayload, 13); // name = "java.lang.String"
    writeVarint(metaPayload, 0); // 0 subs
    // class test.FakeEvent (type=2, 3 attrs, 1 sub)
    writeVarint(metaPayload, 2);
    writeVarint(metaPayload, 3); // attr count
    writeVarint(metaPayload, 4);
    writeVarint(metaPayload, 7); // id = "100"
    writeVarint(metaPayload, 5);
    writeVarint(metaPayload, 8); // name = "test.FakeEvent"
    writeVarint(metaPayload, 6);
    writeVarint(metaPayload, 9); // superType = "jdk.jfr.Event"
    writeVarint(metaPayload, 1); // 1 sub
    // setting (type=3, 2 attrs, 0 subs)
    writeVarint(metaPayload, 3);
    writeVarint(metaPayload, 2); // attr count
    writeVarint(metaPayload, 2);
    writeVarint(metaPayload, 11); // class = "datadog.WallClockSamplingEpoch" (non-numeric!)
    writeVarint(metaPayload, 5);
    writeVarint(metaPayload, 10); // name = "period"
    writeVarint(metaPayload, 0); // 0 subs

    byte[] metaPayloadBytes = metaPayload.toByteArray();
    int metaTotal = eventSize(metaPayloadBytes.length);

    ByteArrayOutputStream metaEvent = new ByteArrayOutputStream();
    writeVarint(metaEvent, metaTotal);
    metaEvent.write(metaPayloadBytes);
    byte[] metaEventBytes = metaEvent.toByteArray();

    // --- Build minimal checkpoint event (0 constant pools) ---
    ByteArrayOutputStream cpPayload = new ByteArrayOutputStream();
    writeVarint(cpPayload, 1); // typeId = 1 (checkpoint)
    writeVarint(cpPayload, 0); // startTime
    writeVarint(cpPayload, 0); // duration
    writeVarint(cpPayload, 0); // nextOffsetDelta = 0 (no more CP events)
    cpPayload.write(0); // isFlush = false
    writeVarint(cpPayload, 0); // cpCount = 0

    byte[] cpPayloadBytes = cpPayload.toByteArray();
    int cpTotal = eventSize(cpPayloadBytes.length);

    ByteArrayOutputStream cpEvent = new ByteArrayOutputStream();
    writeVarint(cpEvent, cpTotal);
    cpEvent.write(cpPayloadBytes);
    byte[] cpEventBytes = cpEvent.toByteArray();

    // --- Chunk layout ---
    int headerSize = 68;
    int cpOffset = headerSize;
    int metaOffset = cpOffset + cpEventBytes.length;
    int chunkSize = metaOffset + metaEventBytes.length;

    // --- Chunk header (big-endian) ---
    ByteBuffer header = ByteBuffer.allocate(headerSize).order(ByteOrder.BIG_ENDIAN);
    header.put((byte) 'F').put((byte) 'L').put((byte) 'R').put((byte) '\0');
    header.putShort((short) 2); // major version
    header.putShort((short) 0); // minor version
    header.putLong(chunkSize);
    header.putLong(cpOffset);
    header.putLong(metaOffset);
    header.putLong(0L); // startNanos
    header.putLong(0L); // duration
    header.putLong(0L); // startTicks
    header.putLong(1_000_000_000L); // frequency
    header.putInt(0); // compressed = false

    ByteArrayOutputStream chunk = new ByteArrayOutputStream();
    chunk.write(header.array());
    chunk.write(cpEventBytes);
    chunk.write(metaEventBytes);
    return chunk.toByteArray();
  }

  // -------------------------------------------------------------------------
  // Test
  // -------------------------------------------------------------------------

  @Test
  void parsesRecordingWithNonNumericSettingClassAttribute() throws Exception {
    Path jfrFile = tempDir.resolve("string-class-setting.jfr");
    Files.write(jfrFile, buildSyntheticJfr());

    // Before fix: MetadataSetting.onAttribute threw NumberFormatException for the
    // non-numeric "class" attribute ("datadog.WallClockSamplingEpoch").
    // After fix: parsing completes normally; the setting is created and getType() returns null
    // (since "datadog.WallClockSamplingEpoch" is not a registered metadata class).
    AtomicBoolean metadataSeen = new AtomicBoolean(false);

    assertDoesNotThrow(
        () -> {
          try (UntypedJafarParser parser =
              UntypedJafarParser.open(jfrFile)
                  .withParserListener(
                      new ChunkParserListener() {
                        @Override
                        public boolean onMetadata(ParserContext context, MetadataEvent metadata) {
                          MetadataClass fakeEvent =
                              context.getMetadataLookup().getClass("test.FakeEvent");
                          assertNotNull(fakeEvent, "test.FakeEvent should be registered");

                          Map<String, Map<String, Object>> settings = fakeEvent.getSettingsByName();
                          assertTrue(settings.containsKey("period"), "period setting should exist");

                          // "type" is null when getType() returned null, which happens because
                          // "datadog.WallClockSamplingEpoch" is not a registered metadata class.
                          // Without the fix we would never reach this point (NFE thrown earlier).
                          Map<String, Object> periodSetting = settings.get("period");
                          assertNull(
                              periodSetting.get("type"),
                              "setting type should be null for unresolvable class name");

                          metadataSeen.set(true);
                          return true;
                        }
                      })) {
            parser.run();
          }
        });

    assertTrue(metadataSeen.get(), "onMetadata callback must have been invoked");
  }
}
