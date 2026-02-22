package io.jafar.parser;

import io.jafar.parser.internal_api.RecordingStreamReader;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Analyzes event data structure in JFR files. Specifically designed to understand how JMC Writer
 * encodes string fields.
 */
public class EventDataAnalyzer {

  public static void analyzeEventData(Path jfrFile) throws Exception {
    System.out.println("\n=== Event Data Analysis: " + jfrFile.getFileName() + " ===\n");

    RecordingStreamReader reader = RecordingStreamReader.mapped(jfrFile);
    try {
      // Skip file header (68 bytes)
      reader.position(68);

      // Read chunk header
      long chunkStart = reader.position();
      long chunkSize = reader.readLong();
      long cpOffset = reader.readLong();
      long metadataOffset = reader.readLong();

      System.out.println("Chunk Start: 0x" + Long.toHexString(chunkStart));
      System.out.println("Chunk Size: " + chunkSize);
      System.out.println("Constant Pool Offset: " + cpOffset);
      System.out.println("Metadata Offset: " + metadataOffset);
      System.out.println();

      // Jump past chunk header to find events
      long eventsStart = chunkStart + 24; // After chunk header
      reader.position(eventsStart);

      System.out.println("Scanning for events starting at 0x" + Long.toHexString(eventsStart));
      System.out.println();

      // Read events until we hit constant pool or metadata
      long scanLimit = chunkStart + Math.min(cpOffset, metadataOffset);
      int eventCount = 0;

      while (reader.position() < scanLimit && eventCount < 10) {
        long eventPos = reader.position();

        try {
          long eventSize = reader.readVarint();
          long eventTypeId = reader.readVarint();

          if (eventSize == 0 || eventSize > 10000) {
            System.out.println(
                "Invalid event size " + eventSize + " at 0x" + Long.toHexString(eventPos));
            break;
          }

          System.out.println(
              "--- Event #" + eventCount + " at 0x" + Long.toHexString(eventPos) + " ---");
          System.out.println("  Event Size: " + eventSize);
          System.out.println("  Event Type ID: " + eventTypeId);

          // Dump the event payload
          long payloadStart = reader.position();
          long payloadSize = eventSize - (payloadStart - eventPos);

          System.out.println("  Payload (" + payloadSize + " bytes):");
          dumpBytes(reader, (int) Math.min(payloadSize, 100));

          // Skip to next event
          reader.position(eventPos + eventSize);
          eventCount++;
          System.out.println();

        } catch (Exception e) {
          System.out.println(
              "Error reading event at 0x" + Long.toHexString(eventPos) + ": " + e.getMessage());
          break;
        }
      }

      System.out.println("Total events found: " + eventCount);

    } finally {
      reader.close();
    }

    System.out.println("\n=== End of Event Analysis ===\n");
  }

  private static void dumpBytes(RecordingStreamReader reader, int length) throws IOException {
    StringBuilder hex = new StringBuilder();
    StringBuilder ascii = new StringBuilder();

    for (int i = 0; i < length && reader.remaining() > 0; i++) {
      byte b = reader.read();

      hex.append(String.format("%02x ", b & 0xFF));

      if (b >= 32 && b < 127) {
        ascii.append((char) b);
      } else {
        ascii.append('.');
      }

      if ((i + 1) % 16 == 0) {
        System.out.println("    " + hex + " | " + ascii);
        hex.setLength(0);
        ascii.setLength(0);
      }
    }

    if (hex.length() > 0) {
      // Pad to align with previous lines
      while (hex.length() < 48) {
        hex.append("   ");
      }
      System.out.println("    " + hex + " | " + ascii);
    }
  }
}
