package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.mcp.session.OtlpSessionRegistry;
import io.jafar.otlp.shell.MinimalOtlpBuilder;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for OtlpSessionRegistry session management logic. */
@UnitTest
class OtlpSessionRegistryUnitTest {

  @TempDir Path tempDir;

  private OtlpSessionRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new OtlpSessionRegistry();
  }

  private Path buildProfile() throws Exception {
    MinimalOtlpBuilder b = new MinimalOtlpBuilder();
    b.setSampleType(b.addString("cpu"), b.addString("nanoseconds"));
    int fn = b.addFunction(b.addString("main"), b.addString("Main.java"));
    int loc = b.addLocation(fn, 1);
    int stack = b.addStack(List.of(loc));
    b.addSample(stack, List.of(), List.of(1_000_000L));
    return b.write(tempDir);
  }

  @Test
  void newRegistryHasNoCurrentSession() {
    assertTrue(registry.getCurrent().isEmpty());
  }

  @Test
  void newRegistryHasZeroSize() {
    assertEquals(0, registry.size());
  }

  @Test
  void getOrCurrentThrowsWhenNoCurrentSession() {
    var ex = assertThrows(IllegalArgumentException.class, () -> registry.getOrCurrent(null));
    assertTrue(ex.getMessage().contains("otlp_open"));
  }

  @Test
  void getOrCurrentThrowsForNonexistentId() {
    assertThrows(IllegalArgumentException.class, () -> registry.getOrCurrent("999"));
  }

  @Test
  void getReturnsEmptyForNonexistentId() {
    assertTrue(registry.get("999").isEmpty());
  }

  @Test
  void getReturnsEmptyForNullId() {
    assertTrue(registry.get(null).isEmpty());
  }

  @Test
  void closeReturnsFalseForNonexistentSession() {
    assertFalse(registry.close("nonexistent"));
  }

  @Test
  void closeAllOnEmptyRegistryDoesNotThrow() {
    assertDoesNotThrow(() -> registry.closeAll());
  }

  @Test
  void listReturnsEmptyListWhenEmpty() {
    var sessions = registry.list();
    assertNotNull(sessions);
    assertTrue(sessions.isEmpty());
  }

  @Test
  void shutdownOnEmptyRegistryDoesNotThrow() {
    assertDoesNotThrow(() -> registry.shutdown());
  }

  @Test
  void openCreatesSessionAndSetsCurrent() throws Exception {
    var info = registry.open(buildProfile(), null);
    assertEquals(1, registry.size());
    assertTrue(registry.getCurrent().isPresent());
    assertEquals(info.id(), registry.getCurrent().get().id());
  }

  @Test
  void getByIdReturnsSession() throws Exception {
    var info = registry.open(buildProfile(), null);
    var found = registry.get(String.valueOf(info.id()));
    assertTrue(found.isPresent());
    assertEquals(info.id(), found.get().id());
  }

  @Test
  void getByAliasReturnsSession() throws Exception {
    registry.open(buildProfile(), "myalias");
    var found = registry.get("myalias");
    assertTrue(found.isPresent());
    assertEquals("myalias", found.get().alias());
  }

  @Test
  void duplicateAliasThrows() throws Exception {
    registry.open(buildProfile(), "dup");
    assertThrows(IllegalArgumentException.class, () -> registry.open(buildProfile(), "dup"));
  }

  @Test
  void closeByIdRemovesSession() throws Exception {
    var info = registry.open(buildProfile(), null);
    assertTrue(registry.close(String.valueOf(info.id())));
    assertEquals(0, registry.size());
    assertTrue(registry.getCurrent().isEmpty());
  }

  @Test
  void closeByAliasRemovesSession() throws Exception {
    registry.open(buildProfile(), "toclose");
    assertTrue(registry.close("toclose"));
    assertEquals(0, registry.size());
  }

  @Test
  void currentSessionTransitionsOnClose() throws Exception {
    var first = registry.open(buildProfile(), null);
    var second = registry.open(buildProfile(), null);
    assertEquals(second.id(), registry.getCurrent().get().id());

    registry.close(String.valueOf(second.id()));
    assertTrue(registry.getCurrent().isPresent());
    assertEquals(first.id(), registry.getCurrent().get().id());
  }

  @Test
  void listReturnsAllOpenSessions() throws Exception {
    registry.open(buildProfile(), null);
    registry.open(buildProfile(), null);
    assertEquals(2, registry.list().size());
  }
}
