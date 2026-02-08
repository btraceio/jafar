package io.jafar.hdump.index;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Integration tests for IndexWriter and ObjectIndexReader. */
class IndexIntegrationTest {

  private Path tempDir;

  @BeforeEach
  void setUp() throws IOException {
    tempDir = Files.createTempDirectory("jafar-index-test");
  }

  @AfterEach
  void tearDown() throws IOException {
    // Clean up temp directory
    if (tempDir != null && Files.exists(tempDir)) {
      Files.walk(tempDir)
          .sorted((a, b) -> -a.compareTo(b)) // Delete files before directories
          .forEach(
              path -> {
                try {
                  Files.delete(path);
                } catch (IOException e) {
                  // Ignore
                }
              });
    }
  }

  @Test
  void testWriteAndReadObjectsIndex() throws IOException {
    // Write index with 10 sample objects
    int objectCount = 10;
    try (IndexWriter writer = new IndexWriter(tempDir)) {
      writer.beginObjectsIndex(objectCount);

      for (int i = 0; i < objectCount; i++) {
        writer.writeObjectEntry(
            i, // objectId32
            1000L + (i * 100), // fileOffset
            64 + (i * 8), // dataSize
            i % 3, // classId (3 different classes)
            i < 5 ? i * 10 : -1, // arrayLength (first 5 are arrays)
            i < 5 ? IndexFormat.FLAG_IS_OBJECT_ARRAY : (byte) 0, // flags
            (byte) 0 // elementType (object arrays)
            );
      }

      writer.finishObjectsIndex();
    }

    // Verify index file exists
    Path indexFile = tempDir.resolve(IndexFormat.OBJECTS_INDEX_NAME);
    assertTrue(Files.exists(indexFile), "Index file should exist");

    // Expected size: header (20 bytes) + entries (10 × 33 bytes) = 350 bytes
    long expectedSize = IndexFormat.HEADER_SIZE + (objectCount * IndexFormat.OBJECT_ENTRY_SIZE);
    assertEquals(expectedSize, Files.size(indexFile), "Index file size should match");

    // Read back and verify
    try (ObjectIndexReader reader = new ObjectIndexReader(tempDir)) {
      assertEquals(objectCount, reader.getEntryCount(), "Entry count should match");
      assertEquals(
          IndexFormat.FORMAT_VERSION, reader.getFormatVersion(), "Format version should match");

      // Verify each entry
      for (int i = 0; i < objectCount; i++) {
        ObjectIndexReader.ObjectMetadata meta = reader.readObject(i);

        assertEquals(i, meta.objectId32, "Object ID should match");
        assertEquals(1000L + (i * 100), meta.fileOffset, "File offset should match");
        assertEquals(64 + (i * 8), meta.dataSize, "Data size should match");
        assertEquals(i % 3, meta.classId, "Class ID should match");

        if (i < 5) {
          assertEquals(i * 10, meta.arrayLength, "Array length should match");
          assertTrue(meta.isArray(), "Should be identified as array");
          assertTrue(meta.isObjectArray(), "Should be identified as object array");
        } else {
          assertEquals(-1, meta.arrayLength, "Should not be an array");
          assertFalse(meta.isArray(), "Should not be identified as array");
        }
      }
    }
  }

  @Test
  void testReadOutOfRange() throws IOException {
    // Write index with 5 objects
    try (IndexWriter writer = new IndexWriter(tempDir)) {
      writer.beginObjectsIndex(5);
      for (int i = 0; i < 5; i++) {
        writer.writeObjectEntry(i, 1000L + i, 64, 0, -1, (byte) 0, (byte) 0);
      }
      writer.finishObjectsIndex();
    }

    // Try to read beyond range
    try (ObjectIndexReader reader = new ObjectIndexReader(tempDir)) {
      assertThrows(
          IllegalArgumentException.class,
          () -> reader.readObject(-1),
          "Should throw on negative ID");
      assertThrows(
          IllegalArgumentException.class, () -> reader.readObject(5), "Should throw on ID >= count");
      assertThrows(
          IllegalArgumentException.class,
          () -> reader.readObject(100),
          "Should throw on large ID");
    }
  }

  @Test
  void testInvalidMagicNumber() throws IOException {
    // Write invalid file
    Path indexFile = tempDir.resolve(IndexFormat.OBJECTS_INDEX_NAME);
    Files.write(indexFile, new byte[] {0, 0, 0, 0, 0, 0, 0, 0});

    // Should throw on open
    assertThrows(
        IOException.class,
        () -> new ObjectIndexReader(tempDir),
        "Should throw on invalid magic number");
  }

  @Test
  void testAtomicWrite() throws IOException {
    // Verify temp file is cleaned up
    try (IndexWriter writer = new IndexWriter(tempDir)) {
      writer.beginObjectsIndex(1);
      writer.writeObjectEntry(0, 1000L, 64, 0, -1, (byte) 0, (byte) 0);

      // Temp file should exist during write
      Path tempFile = tempDir.resolve(IndexFormat.OBJECTS_INDEX_NAME + ".tmp");
      assertTrue(Files.exists(tempFile), "Temp file should exist during write");

      writer.finishObjectsIndex();

      // Temp file should be gone after finish
      assertFalse(Files.exists(tempFile), "Temp file should be cleaned up");
    }

    // Final file should exist
    Path finalFile = tempDir.resolve(IndexFormat.OBJECTS_INDEX_NAME);
    assertTrue(Files.exists(finalFile), "Final index file should exist");
  }
}
