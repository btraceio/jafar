package io.jafar.shell.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collection;
import org.junit.jupiter.api.Test;

class BackendRegistryTest {

  @Test
  void rediscoverFindsBackendsFromClasspath() {
    BackendRegistry registry = BackendRegistry.getInstance();

    // Test classpath includes backends via testRuntimeOnly
    Collection<JfrBackend> before = registry.listAll();
    assertFalse(before.isEmpty(), "Test classpath should have backends");

    // Rediscover should clear and re-find the same backends
    registry.rediscover();

    Collection<JfrBackend> after = registry.listAll();
    assertFalse(after.isEmpty(), "Backends should be re-discovered after rediscover()");
    assertEquals(before.size(), after.size(), "Same number of backends should be found");
  }

  @Test
  void rediscoverResetsCurrentBackendSelection() {
    BackendRegistry registry = BackendRegistry.getInstance();

    // Force backend selection
    JfrBackend first = registry.getCurrent();
    assertNotNull(first);

    // Rediscover clears and re-discovers; current should be re-selected
    registry.rediscover();

    JfrBackend second = registry.getCurrent();
    assertNotNull(second);
    assertEquals(first.getId(), second.getId(), "Same backend should be re-selected by priority");
  }

  @Test
  void listAllReturnsUnmodifiableCollection() {
    BackendRegistry registry = BackendRegistry.getInstance();
    Collection<JfrBackend> backends = registry.listAll();

    assertThrows(
        UnsupportedOperationException.class,
        () -> backends.clear(),
        "listAll() should return unmodifiable collection");
  }
}
