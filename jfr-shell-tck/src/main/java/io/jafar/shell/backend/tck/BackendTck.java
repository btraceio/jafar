package io.jafar.shell.backend.tck;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.shell.backend.BackendCapability;
import io.jafar.shell.backend.BackendContext;
import io.jafar.shell.backend.ChunkSource;
import io.jafar.shell.backend.ConstantPoolSource;
import io.jafar.shell.backend.EventSource;
import io.jafar.shell.backend.JfrBackend;
import io.jafar.shell.backend.MetadataSource;
import io.jafar.shell.backend.UnsupportedCapabilityException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Technology Compatibility Kit for JFR Shell backend implementations.
 *
 * <p>Plugin authors should extend this class and implement {@link #createBackend()} to test their
 * backend implementation against the standard test suite.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * class MyBackendTckTest extends BackendTck {
 *   @Override
 *   protected JfrBackend createBackend() {
 *     return new MyBackend();
 *   }
 * }
 * }</pre>
 *
 * <p>The TCK validates:
 *
 * <ul>
 *   <li>Backend identity (id, name, priority)
 *   <li>Capability declarations match actual behavior
 *   <li>Event source functionality (if supported)
 *   <li>Metadata source functionality (if supported)
 *   <li>Chunk source functionality (if supported)
 *   <li>Constant pool source functionality (if supported)
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class BackendTck {

  private JfrBackend backend;
  private BackendContext context;

  /** Create the backend instance to test. Called once before all tests. */
  protected abstract JfrBackend createBackend();

  /**
   * Provide path to a test JFR file. Override to use a custom test file.
   *
   * @return path to JFR test file
   */
  protected Path getTestRecording() {
    // Default: use the test-ap.jfr from parser module (relative to project root)
    return Paths.get(System.getProperty("user.dir"))
        .resolve("../parser/src/test/resources/test-ap.jfr")
        .normalize();
  }

  @BeforeAll
  void initBackend() {
    backend = createBackend();
    assertNotNull(backend, "createBackend() must not return null");
    context = backend.createContext();
    assertNotNull(context, "createContext() must not return null");
  }

  @AfterAll
  void cleanupBackend() {
    if (context != null) {
      context.close();
    }
  }

  // ==================== Identity Tests ====================

  @Test
  void backendHasNonNullId() {
    assertNotNull(backend.getId(), "Backend ID must not be null");
    assertFalse(backend.getId().isBlank(), "Backend ID must not be blank");
  }

  @Test
  void backendHasNonNullName() {
    assertNotNull(backend.getName(), "Backend name must not be null");
    assertFalse(backend.getName().isBlank(), "Backend name must not be blank");
  }

  @Test
  void backendIdIsLowercaseAlphanumeric() {
    String id = backend.getId();
    assertTrue(
        id.matches("[a-z][a-z0-9-]*"),
        "Backend ID should be lowercase alphanumeric with hyphens: " + id);
  }

  @Test
  void backendPriorityIsReasonable() {
    int priority = backend.getPriority();
    assertTrue(priority >= 0 && priority <= 1000, "Priority should be 0-1000, got: " + priority);
  }

  @Test
  void backendHasNonNullVersion() {
    assertNotNull(backend.getVersion(), "Backend version must not be null");
  }

  @Test
  void backendHasCapabilities() {
    Set<BackendCapability> caps = backend.getCapabilities();
    assertNotNull(caps, "Backend capabilities must not be null");
  }

  // ==================== Capability Consistency Tests ====================

  @Test
  void eventStreamingCapabilityMatchesSource() {
    boolean declared = backend.supports(BackendCapability.EVENT_STREAMING);
    // If declared, should be able to create source
    if (declared) {
      EventSource source = backend.createEventSource(context);
      assertNotNull(source, "createEventSource() returned null despite declaring capability");
    }
  }

  @Test
  void metadataCapabilityMatchesSource() {
    boolean declared = backend.supports(BackendCapability.METADATA_CLASSES);
    try {
      MetadataSource source = backend.createMetadataSource();
      assertTrue(
          declared,
          "Backend creates MetadataSource but doesn't declare METADATA_CLASSES capability");
      assertNotNull(source, "createMetadataSource() returned null despite declaring capability");
    } catch (UnsupportedCapabilityException e) {
      assertFalse(
          declared, "Backend declares METADATA_CLASSES but throws UnsupportedCapabilityException");
    }
  }

  @Test
  void chunkCapabilityMatchesSource() {
    boolean declared = backend.supports(BackendCapability.CHUNK_INFO);
    try {
      ChunkSource source = backend.createChunkSource();
      assertTrue(declared, "Backend creates ChunkSource but doesn't declare CHUNK_INFO capability");
      assertNotNull(source, "createChunkSource() returned null despite declaring capability");
    } catch (UnsupportedCapabilityException e) {
      assertFalse(
          declared, "Backend declares CHUNK_INFO but throws UnsupportedCapabilityException");
    }
  }

  @Test
  void constantPoolCapabilityMatchesSource() {
    boolean declared = backend.supports(BackendCapability.CONSTANT_POOLS);
    try {
      ConstantPoolSource source = backend.createConstantPoolSource();
      assertTrue(
          declared,
          "Backend creates ConstantPoolSource but doesn't declare CONSTANT_POOLS capability");
      assertNotNull(
          source, "createConstantPoolSource() returned null despite declaring capability");
    } catch (UnsupportedCapabilityException e) {
      assertFalse(
          declared, "Backend declares CONSTANT_POOLS but throws UnsupportedCapabilityException");
    }
  }

  // ==================== Context Tests ====================

  @Test
  void contextUptimeIncreases() throws InterruptedException {
    long t1 = context.uptime();
    Thread.sleep(10);
    long t2 = context.uptime();
    assertTrue(t2 > t1, "Context uptime should increase over time");
  }

  // ==================== Event Source Tests ====================

  @Test
  void eventSourceStreamsEvents() throws Exception {
    if (!backend.supports(BackendCapability.EVENT_STREAMING)) {
      return; // Skip if not supported
    }

    EventSource source = backend.createEventSource(context);
    Path recording = getTestRecording();

    AtomicInteger eventCount = new AtomicInteger(0);
    Set<String> eventTypes = new HashSet<>();

    source.streamEvents(
        recording,
        event -> {
          eventCount.incrementAndGet();
          assertNotNull(event, "Event should not be null");
          assertNotNull(event.typeName(), "Event typeName should not be null");
          eventTypes.add(event.typeName());
        });

    assertTrue(eventCount.get() > 0, "Should stream at least one event from test recording");
    assertFalse(eventTypes.isEmpty(), "Should find at least one event type");
  }

  @Test
  void eventSourceEventHasValues() throws Exception {
    if (!backend.supports(BackendCapability.EVENT_STREAMING)) {
      return;
    }

    EventSource source = backend.createEventSource(context);
    Path recording = getTestRecording();

    AtomicInteger eventsWithValues = new AtomicInteger(0);

    source.streamEvents(
        recording,
        event -> {
          if (event.value() != null && !event.value().isEmpty()) {
            eventsWithValues.incrementAndGet();
          }
        });

    assertTrue(eventsWithValues.get() > 0, "At least some events should have field values");
  }

  // ==================== Metadata Source Tests ====================

  @Test
  void metadataSourceLoadsAllClasses() throws Exception {
    if (!backend.supports(BackendCapability.METADATA_CLASSES)) {
      return;
    }

    MetadataSource source = backend.createMetadataSource();
    Path recording = getTestRecording();

    List<Map<String, Object>> classes = source.loadAllClasses(recording);

    assertNotNull(classes, "Metadata classes list should not be null");
    assertFalse(classes.isEmpty(), "Should find metadata classes in test recording");

    // Each class should have basic fields
    for (Map<String, Object> clazz : classes) {
      assertTrue(clazz.containsKey("name"), "Metadata class should have 'name' field");
    }
  }

  @Test
  void metadataSourceLoadsClass() throws Exception {
    if (!backend.supports(BackendCapability.METADATA_CLASSES)) {
      return;
    }

    MetadataSource source = backend.createMetadataSource();
    Path recording = getTestRecording();

    // First get all classes to find a valid type name
    List<Map<String, Object>> classes = source.loadAllClasses(recording);
    if (classes.isEmpty()) {
      return; // No metadata in recording
    }

    String typeName = (String) classes.get(0).get("name");
    Map<String, Object> details = source.loadClass(recording, typeName);

    assertNotNull(details, "Should be able to load class details for: " + typeName);
    assertTrue(details.containsKey("name"), "Class details should have 'name' field");
    assertEquals(typeName, details.get("name"), "Loaded class should match requested type");
  }

  @Test
  void metadataSourceReturnsNullForUnknownClass() throws Exception {
    if (!backend.supports(BackendCapability.METADATA_CLASSES)) {
      return;
    }

    MetadataSource source = backend.createMetadataSource();
    Path recording = getTestRecording();

    Map<String, Object> details = source.loadClass(recording, "com.nonexistent.FakeEventType");

    assertNull(details, "Should return null for non-existent class");
  }

  // ==================== Chunk Source Tests ====================

  @Test
  void chunkSourceLoadsAllChunks() throws Exception {
    if (!backend.supports(BackendCapability.CHUNK_INFO)) {
      return;
    }

    ChunkSource source = backend.createChunkSource();
    Path recording = getTestRecording();

    List<Map<String, Object>> chunks = source.loadAllChunks(recording);

    assertNotNull(chunks, "Chunks list should not be null");
    assertFalse(chunks.isEmpty(), "Should find at least one chunk in test recording");

    // Each chunk should have standard fields
    for (Map<String, Object> chunk : chunks) {
      assertTrue(chunk.containsKey("size"), "Chunk should have 'size' field");
      assertTrue(chunk.containsKey("startNanos"), "Chunk should have 'startNanos' field");
    }
  }

  @Test
  void chunkSourceLoadsSingleChunk() throws Exception {
    if (!backend.supports(BackendCapability.CHUNK_INFO)) {
      return;
    }

    ChunkSource source = backend.createChunkSource();
    Path recording = getTestRecording();

    Map<String, Object> chunk = source.loadChunk(recording, 0);

    assertNotNull(chunk, "First chunk should exist in test recording");
    assertTrue(chunk.containsKey("size"), "Chunk should have 'size' field");
  }

  @Test
  void chunkSourceReturnsNullForInvalidIndex() throws Exception {
    if (!backend.supports(BackendCapability.CHUNK_INFO)) {
      return;
    }

    ChunkSource source = backend.createChunkSource();
    Path recording = getTestRecording();

    Map<String, Object> chunk = source.loadChunk(recording, 999999);

    assertNull(chunk, "Should return null for non-existent chunk index");
  }

  @Test
  void chunkSourceProvidesSummary() throws Exception {
    if (!backend.supports(BackendCapability.CHUNK_INFO)) {
      return;
    }

    ChunkSource source = backend.createChunkSource();
    Path recording = getTestRecording();

    Map<String, Object> summary = source.getChunkSummary(recording);

    assertNotNull(summary, "Chunk summary should not be null");
    assertTrue(summary.containsKey("totalChunks"), "Summary should have 'totalChunks' field");
    assertTrue(summary.containsKey("totalSize"), "Summary should have 'totalSize' field");
  }

  // ==================== Constant Pool Source Tests ====================

  @Test
  void constantPoolSourceListsTypes() throws Exception {
    if (!backend.supports(BackendCapability.CONSTANT_POOLS)) {
      return;
    }

    ConstantPoolSource source = backend.createConstantPoolSource();
    Path recording = getTestRecording();

    Set<String> types = source.getAvailableTypes(recording);

    assertNotNull(types, "Constant pool types should not be null");
    // Most recordings have at least some constant pools
  }

  @Test
  void constantPoolSourceLoadsSummary() throws Exception {
    if (!backend.supports(BackendCapability.CONSTANT_POOLS)) {
      return;
    }

    ConstantPoolSource source = backend.createConstantPoolSource();
    Path recording = getTestRecording();

    List<Map<String, Object>> summary = source.loadSummary(recording);

    assertNotNull(summary, "Constant pool summary should not be null");
  }

  @Test
  void constantPoolSourceLoadsEntries() throws Exception {
    if (!backend.supports(BackendCapability.CONSTANT_POOLS)) {
      return;
    }

    ConstantPoolSource source = backend.createConstantPoolSource();
    Path recording = getTestRecording();

    Set<String> types = source.getAvailableTypes(recording);
    if (types.isEmpty()) {
      return; // No constant pools in this recording
    }

    String firstType = types.iterator().next();
    List<Map<String, Object>> entries = source.loadEntries(recording, firstType);

    assertNotNull(entries, "Constant pool entries should not be null");
    // May be empty for some types
  }
}
