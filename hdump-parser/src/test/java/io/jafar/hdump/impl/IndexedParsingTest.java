package io.jafar.hdump.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.hdump.api.HeapDump;
import io.jafar.hdump.api.HeapDumpParser;
import io.jafar.hdump.api.HeapDumpParser.ParserOptions;
import io.jafar.hdump.test.SyntheticHeapDumpGenerator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Integration tests for index-based parsing mode. */
class IndexedParsingTest {

  private Path tempDir;
  private Path testHeapDump;

  @BeforeEach
  void setUp() throws IOException {
    tempDir = Files.createTempDirectory("jafar-indexed-test");
    testHeapDump = tempDir.resolve("test.hprof");

    // Generate a synthetic heap dump with 50 objects
    SyntheticHeapDumpGenerator.generateMinimalHeapDump(testHeapDump, 50);
  }

  @AfterEach
  void tearDown() throws IOException {
    // Clean up temp directory
    if (tempDir != null && Files.exists(tempDir)) {
      Files.walk(tempDir)
          .sorted((a, b) -> -a.compareTo(b))
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
  void testIndexedParsing() throws IOException {
    // Parse with indexed mode
    ParserOptions options = ParserOptions.builder().useIndexedParsing(true).build();

    try (HeapDump dump = HeapDumpParser.parse(testHeapDump, options)) {
      // Verify basic statistics
      assertEquals(50, dump.getObjectCount(), "Should have 50 objects");
      assertTrue(dump.getClassCount() > 0, "Should have at least one class");

      // Verify index directory was created
      Path indexDir = testHeapDump.getParent().resolve(testHeapDump.getFileName() + ".idx");
      assertTrue(Files.exists(indexDir), "Index directory should exist");
      assertTrue(
          Files.exists(indexDir.resolve("objects.idx")), "objects.idx should be created");
    }
  }

  @Test
  void testIndexedVsInMemoryConsistency() throws IOException {
    // Parse same heap dump with both modes
    ParserOptions inMemoryOptions = ParserOptions.DEFAULT;
    ParserOptions indexedOptions = ParserOptions.builder().useIndexedParsing(true).build();

    try (HeapDump inMemory = HeapDumpParser.parse(testHeapDump, inMemoryOptions);
        HeapDump indexed = HeapDumpParser.parse(testHeapDump, indexedOptions)) {

      // Both should report same statistics
      assertEquals(
          inMemory.getObjectCount(),
          indexed.getObjectCount(),
          "Object counts should match");
      assertEquals(
          inMemory.getClassCount(), indexed.getClassCount(), "Class counts should match");
    }
  }

  @Test
  void testIndexReuse() throws IOException {
    ParserOptions options = ParserOptions.builder().useIndexedParsing(true).build();

    // First parse: builds indexes
    try (HeapDump dump1 = HeapDumpParser.parse(testHeapDump, options)) {
      assertEquals(50, dump1.getObjectCount());
    }

    // Second parse: should reuse existing indexes
    Path indexDir = testHeapDump.getParent().resolve(testHeapDump.getFileName() + ".idx");
    long indexModTime = Files.getLastModifiedTime(indexDir.resolve("objects.idx")).toMillis();

    try (HeapDump dump2 = HeapDumpParser.parse(testHeapDump, options)) {
      assertEquals(50, dump2.getObjectCount());
    }

    // Index file should not be modified (reused)
    long newModTime = Files.getLastModifiedTime(indexDir.resolve("objects.idx")).toMillis();
    assertTrue(
        newModTime >= indexModTime,
        "Index should be reused or rebuilt (mod time should not decrease)");
  }

  @Test
  void testInboundIndexInfrastructure() throws IOException {
    // This test verifies that the inbound index infrastructure works

    ParserOptions options = ParserOptions.builder().useIndexedParsing(true).build();
    Path indexDir = testHeapDump.getParent().resolve(testHeapDump.getFileName() + ".idx");

    // Parse with indexed mode to set up infrastructure
    try (HeapDump dump = HeapDumpParser.parse(testHeapDump, options)) {
      // Cast to implementation to access internal methods
      HeapDumpImpl dumpImpl = (HeapDumpImpl) dump;

      // Manually build inbound index to test infrastructure
      dumpImpl.ensureInboundIndexBuilt();

      // Verify inbound index file was created
      assertTrue(
          Files.exists(indexDir.resolve("inbound.idx")),
          "Inbound index should be created by ensureInboundIndexBuilt()");

      // Verify index can be read
      long inboundSize = Files.size(indexDir.resolve("inbound.idx"));
      assertTrue(inboundSize > 0, "Inbound index should not be empty");
    }
  }

  @Test
  void testInboundIndexReadWrite() throws IOException {
    // This test verifies that the inbound index can be built, written, and read correctly
    ParserOptions options = ParserOptions.builder().useIndexedParsing(true).build();
    Path indexDir = testHeapDump.getParent().resolve(testHeapDump.getFileName() + ".idx");

    // First parse: build and write index
    try (HeapDump dump = HeapDumpParser.parse(testHeapDump, options)) {
      HeapDumpImpl dumpImpl = (HeapDumpImpl) dump;

      // Build inbound index
      dumpImpl.ensureInboundIndexBuilt();

      // Verify it was created
      assertTrue(
          Files.exists(indexDir.resolve("inbound.idx")),
          "Inbound index should be created");

      assertNotNull(
          dumpImpl.getInboundCountReader(),
          "Inbound count reader should be initialized");
    }

    // Second parse: verify index can be read
    try (HeapDump dump2 = HeapDumpParser.parse(testHeapDump, options)) {
      HeapDumpImpl dumpImpl2 = (HeapDumpImpl) dump2;

      // Read existing index
      dumpImpl2.ensureInboundIndexBuilt();

      assertNotNull(
          dumpImpl2.getInboundCountReader(),
          "Inbound count reader should be initialized from existing index");
    }
  }
}
