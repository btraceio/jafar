package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.mcp.session.HeapSessionRegistry;
import io.jafar.shell.core.RequestScope;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Per-client ownership in the heap dump registry; the same rules as the other registries (see
 * {@link SessionOwnershipRegistryTest}), on a minimal HPROF: just the header, no records.
 */
class HeapSessionRegistryOwnershipTest {

  @TempDir Path tempDir;

  private HeapSessionRegistry registry;
  private Path dump;

  @BeforeEach
  void setUp() throws Exception {
    registry = new HeapSessionRegistry();
    RequestScope.clear();
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      out.write("JAVA PROFILE 1.0.2\0".getBytes(StandardCharsets.UTF_8));
      out.writeInt(8); // identifier size
      out.writeLong(0L); // timestamp
    }
    dump = tempDir.resolve("minimal.hprof");
    Files.write(dump, bytes.toByteArray());
  }

  @AfterEach
  void tearDown() {
    RequestScope.clear();
    registry.shutdown();
  }

  private void as(String scope) {
    RequestScope.set(scope);
  }

  @Test
  void twoClientsCanOpenDumpsUnderTheSameAlias() throws Exception {
    as("A");
    var a = registry.open(dump, "live");
    as("B");
    var b = registry.open(dump, "live");

    assertEquals(b.id(), registry.getOrCurrent("live").id());
    as("A");
    assertEquals(a.id(), registry.getOrCurrent("live").id());
  }

  @Test
  void aClientCannotReuseItsOwnAliasAndTheFailedOpenLeavesNothingBehind() throws Exception {
    as("A");
    registry.open(dump, "live");

    var e = assertThrows(IllegalArgumentException.class, () -> registry.open(dump, "live"));

    assertTrue(e.getMessage().contains("Alias already in use: live"));
    assertEquals(1, registry.size());
  }

  @Test
  void anotherClientsDumpCannotBeReachedListedOrClosed() throws Exception {
    as("A");
    var a = registry.open(dump, null);

    as("B");
    assertTrue(registry.get(String.valueOf(a.id())).isEmpty());
    assertTrue(registry.list().isEmpty());
    assertFalse(registry.close(String.valueOf(a.id())));

    as("A");
    assertEquals(a.id(), registry.getOrCurrent(null).id());
  }

  @Test
  void closeAllClosesOnlyTheCallersOwnDumpsAndReportsHowMany() throws Exception {
    as("A");
    registry.open(dump, null);
    as("B");
    var b = registry.open(dump, null);

    as("A");
    assertEquals(1, registry.closeAll());

    as("B");
    assertEquals(b.id(), registry.getOrCurrent(null).id());
  }

  @Test
  void dumpsOfClientsThatDisconnectedAreReleased() throws Exception {
    as("A");
    registry.open(dump, null);
    as("B");
    registry.open(dump, "keep");

    registry.retainScopes(Set.of("B"));

    assertEquals(1, registry.size());
    assertEquals("keep", registry.getOrCurrent("keep").alias());
  }
}
