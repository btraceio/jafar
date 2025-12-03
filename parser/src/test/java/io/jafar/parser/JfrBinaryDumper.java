package io.jafar.parser;

import io.jafar.parser.internal_api.RecordingStreamReader;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Utility for analyzing JFR binary format. Helps diagnose string encoding issues by dumping file
 * structure and string tables.
 */
public class JfrBinaryDumper {

  public static void dumpJfrFile(Path jfrFile) throws Exception {
    System.out.println("\n=== JFR Binary Dump: " + jfrFile.getFileName() + " ===\n");

    RecordingStreamReader reader = RecordingStreamReader.mapped(jfrFile);
    try {
      // Dump file header
      dumpFileHeader(reader);

      // Dump chunks
      int chunkIndex = 0;
      while (reader.remaining() > 0) {
        System.out.println("\n--- Chunk #" + chunkIndex + " ---");
        dumpChunk(reader, chunkIndex);
        chunkIndex++;
      }
    } finally {
      reader.close();
    }

    System.out.println("\n=== End of Dump ===\n");
  }

  private static void dumpFileHeader(RecordingStreamReader reader) throws IOException {
    System.out.println("File Header:");
    byte[] magic = new byte[4];
    reader.read(magic, 0, 4);
    System.out.println("  Magic: " + new String(magic) + " (should be 'FLR\\0')");

    int major = reader.readShort();
    int minor = reader.readShort();
    System.out.println("  Version: " + major + "." + minor);

    long chunkSize = reader.readLong();
    System.out.println("  First Chunk Size: " + chunkSize + " bytes");

    long cpOffset = reader.readLong();
    System.out.println("  Constant Pool Offset: " + cpOffset);

    long metadataOffset = reader.readLong();
    System.out.println("  Metadata Offset: " + metadataOffset);

    long startNanos = reader.readLong();
    System.out.println("  Start Time: " + startNanos);

    long durationNanos = reader.readLong();
    System.out.println("  Duration: " + durationNanos);

    long startTicks = reader.readLong();
    System.out.println("  Start Ticks: " + startTicks);

    long ticksPerSecond = reader.readLong();
    System.out.println("  Ticks Per Second: " + ticksPerSecond);

    int features = reader.readInt();
    System.out.println("  Features: 0x" + Integer.toHexString(features));
  }

  private static void dumpChunk(RecordingStreamReader reader, int chunkIndex) throws IOException {
    long chunkStart = reader.position();
    System.out.println("  Chunk Start Position: 0x" + Long.toHexString(chunkStart));

    // Read chunk size
    long chunkSize = reader.readLong();
    System.out.println("  Chunk Size: " + chunkSize + " bytes");

    long cpOffset = reader.readLong();
    System.out.println("  Constant Pool Offset: " + cpOffset);

    long metadataOffset = reader.readLong();
    System.out.println("  Metadata Offset: " + metadataOffset);

    // Skip to metadata
    if (metadataOffset > 0) {
      reader.position(chunkStart + metadataOffset);
      System.out.println(
          "\n  Metadata Event at offset 0x" + Long.toHexString(reader.position()) + ":");
      dumpMetadataEvent(reader);
    }

    // Skip to end of chunk
    reader.position(chunkStart + chunkSize);
  }

  private static void dumpMetadataEvent(RecordingStreamReader reader) throws IOException {
    long eventStart = reader.position();

    // Read event header
    long eventSize = reader.readVarint();
    long eventTypeId = reader.readVarint();

    System.out.println("    Event Size: " + eventSize + " bytes");
    System.out.println("    Event Type ID: " + eventTypeId);

    // Read start time
    long startTime = reader.readVarint();
    System.out.println("    Start Time: " + startTime);

    // Read duration
    long duration = reader.readVarint();
    System.out.println("    Duration: " + duration);

    // Read metadata ID
    long metadataId = reader.readVarint();
    System.out.println("    Metadata ID: " + metadataId);

    // Read string table
    System.out.println("\n    String Table:");
    int stringCount = (int) reader.readVarint();
    System.out.println("      Count: " + stringCount);

    String[] strings = new String[stringCount];
    for (int i = 0; i < stringCount; i++) {
      strings[i] = readString(reader);
      System.out.println("      [" + i + "] = \"" + strings[i] + "\"");
    }

    // Don't parse full metadata structure, just skip to end
    long eventEnd = eventStart + eventSize;
    reader.position(eventEnd);
  }

  private static String readString(RecordingStreamReader reader) throws IOException {
    byte id = reader.read();

    if (id == 0) {
      return null;
    } else if (id == 1) {
      return "";
    } else if (id == 2) {
      // String constant reference
      int ptr = (int) reader.readVarint();
      return "<STRING_REF:" + ptr + ">";
    } else if (id == 3) {
      // UTF-8 encoded
      int size = (int) reader.readVarint();
      byte[] bytes = new byte[size];
      reader.read(bytes, 0, size);
      return new String(bytes, "UTF-8");
    } else if (id == 4) {
      // UTF-16
      int numChars = (int) reader.readVarint();
      char[] chars = new char[numChars];
      for (int i = 0; i < numChars; i++) {
        chars[i] = (char) reader.readShort();
      }
      return new String(chars);
    } else if (id == 5) {
      // LATIN1
      int size = (int) reader.readVarint();
      byte[] bytes = new byte[size];
      reader.read(bytes, 0, size);
      return new String(bytes, "ISO-8859-1");
    } else {
      throw new IOException("Unknown string encoding ID: " + id);
    }
  }

  public static void dumpMetadataStrings(Path jfrFile) throws Exception {
    System.out.println("\n=== Metadata String Table: " + jfrFile.getFileName() + " ===\n");

    RecordingStreamReader reader = RecordingStreamReader.mapped(jfrFile);
    try {
      // Skip file header (68 bytes)
      reader.position(68);

      // Read chunk header
      long chunkStart = reader.position();
      long chunkSize = reader.readLong();
      long cpOffset = reader.readLong();
      long metadataOffset = reader.readLong();

      // Jump to metadata
      if (metadataOffset > 0) {
        reader.position(chunkStart + metadataOffset);

        // Skip event header fields
        reader.readVarint(); // eventSize
        reader.readVarint(); // eventTypeId
        reader.readVarint(); // startTime
        reader.readVarint(); // duration
        reader.readVarint(); // metadataId

        // Read string table
        int stringCount = (int) reader.readVarint();
        System.out.println("String Count: " + stringCount + "\n");

        for (int i = 0; i < stringCount; i++) {
          String str = readString(reader);
          System.out.println("[" + i + "] = \"" + str + "\"");
        }
      }
    } finally {
      reader.close();
    }

    System.out.println("\n=== End of String Table ===\n");
  }

  public static void analyzeStringEncoding(Path jfrFile, String expectedString) throws Exception {
    System.out.println("\n=== Analyzing String: \"" + expectedString + "\" ===\n");

    RecordingStreamReader reader = RecordingStreamReader.mapped(jfrFile);
    try {
      // Search for the string in the file
      byte[] searchBytes = expectedString.getBytes("UTF-8");
      long position = findBytes(reader, searchBytes);

      if (position >= 0) {
        System.out.println(
            "Found \"" + expectedString + "\" at position: 0x" + Long.toHexString(position));

        // Show context around the string
        reader.position(Math.max(0, position - 20));
        System.out.println("\nContext (hex dump):");
        dumpHex(reader, 20 + searchBytes.length + 20);
      } else {
        System.out.println("String \"" + expectedString + "\" not found in file");
      }
    } finally {
      reader.close();
    }

    System.out.println("\n=== End of Analysis ===\n");
  }

  private static long findBytes(RecordingStreamReader reader, byte[] searchBytes)
      throws IOException {
    reader.position(0);
    int matchIndex = 0;
    long startPos = -1;

    while (reader.remaining() > 0) {
      byte b = reader.read();

      if (b == searchBytes[matchIndex]) {
        if (matchIndex == 0) {
          startPos = reader.position() - 1;
        }
        matchIndex++;
        if (matchIndex == searchBytes.length) {
          return startPos;
        }
      } else {
        matchIndex = 0;
        startPos = -1;
      }
    }

    return -1;
  }

  private static void dumpHex(RecordingStreamReader reader, int length) throws IOException {
    long startPos = reader.position();
    System.out.print("0x" + Long.toHexString(startPos) + ": ");

    for (int i = 0; i < length && reader.remaining() > 0; i++) {
      if (i > 0 && i % 16 == 0) {
        System.out.print("\n0x" + Long.toHexString(reader.position()) + ": ");
      }
      byte b = reader.read();
      System.out.print(String.format("%02x ", b & 0xFF));
    }
    System.out.println();
  }
}
