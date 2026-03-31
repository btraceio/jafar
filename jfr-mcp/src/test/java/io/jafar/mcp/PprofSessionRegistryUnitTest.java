package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.mcp.session.PprofSessionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for PprofSessionRegistry session management logic. */
@UnitTest
class PprofSessionRegistryUnitTest {

  private PprofSessionRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new PprofSessionRegistry();
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
    assertTrue(ex.getMessage().contains("pprof_open"));
  }

  @Test
  void getOrCurrentThrowsForNonexistentId() {
    assertThrows(IllegalArgumentException.class, () -> registry.getOrCurrent("999"));
  }

  @Test
  void getOrCurrentThrowsForNonexistentAlias() {
    assertThrows(IllegalArgumentException.class, () -> registry.getOrCurrent("nonexistent"));
  }

  @Test
  void getReturnsEmptyForNonexistentId() {
    assertTrue(registry.get("999").isEmpty());
  }

  @Test
  void getReturnsEmptyForNonexistentAlias() {
    assertTrue(registry.get("nonexistent").isEmpty());
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
}
