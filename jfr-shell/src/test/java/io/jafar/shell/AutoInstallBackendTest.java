package io.jafar.shell;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.jafar.shell.backend.BackendRegistry;
import io.jafar.shell.plugin.PluginInstallException;
import io.jafar.shell.plugin.PluginManager;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class AutoInstallBackendTest {

  private PrintStream originalErr;
  private ByteArrayOutputStream errContent;

  @BeforeEach
  void setUp() {
    originalErr = System.err;
    errContent = new ByteArrayOutputStream();
    System.setErr(new PrintStream(errContent));
  }

  @AfterEach
  void tearDown() {
    System.setErr(originalErr);
    // Restore BackendRegistry to a known-good state since test classpath has backends
    BackendRegistry.getInstance().rediscover();
  }

  @Test
  void returnsFalseWhenPluginNotInRegistry() throws PluginInstallException {
    try (MockedStatic<PluginManager> pmStatic = mockStatic(PluginManager.class)) {
      PluginManager mockPm = mock(PluginManager.class);
      pmStatic.when(PluginManager::getInstance).thenReturn(mockPm);
      when(mockPm.canInstall("jafar")).thenReturn(false);

      boolean result = Main.autoInstallDefaultBackend();

      assertFalse(result);
      verify(mockPm).canInstall("jafar");
      verify(mockPm, never()).installPlugin(anyString());
    }
  }

  @Test
  void returnsFalseWhenInstallThrows() throws PluginInstallException {
    try (MockedStatic<PluginManager> pmStatic = mockStatic(PluginManager.class)) {
      PluginManager mockPm = mock(PluginManager.class);
      pmStatic.when(PluginManager::getInstance).thenReturn(mockPm);
      when(mockPm.canInstall("jafar")).thenReturn(true);
      doThrow(new PluginInstallException("network error")).when(mockPm).installPlugin("jafar");

      boolean result = Main.autoInstallDefaultBackend();

      assertFalse(result);
      String stderrOutput = errContent.toString();
      assertTrue(stderrOutput.contains("failed: network error"));
    }
  }

  @Test
  void returnsTrueWhenInstallSucceeds() throws PluginInstallException {
    try (MockedStatic<PluginManager> pmStatic = mockStatic(PluginManager.class)) {
      PluginManager mockPm = mock(PluginManager.class);
      pmStatic.when(PluginManager::getInstance).thenReturn(mockPm);
      // Allow reinitialize() to be called (no-op since we mocked the static)
      pmStatic.when(PluginManager::reinitialize).then(invocation -> null);

      when(mockPm.canInstall("jafar")).thenReturn(true);
      // installPlugin succeeds (no exception)

      boolean result = Main.autoInstallDefaultBackend();

      // Test classpath has backends, so after rediscover() listAll() will be non-empty
      assertTrue(result);
      verify(mockPm).installPlugin("jafar");
      String stderrOutput = errContent.toString();
      assertTrue(stderrOutput.contains("No backends found. Installing default backend from Maven"));
      assertTrue(stderrOutput.contains("done."));
    }
  }

  @Test
  void installCallsReinitializeAndRediscover() throws PluginInstallException {
    try (MockedStatic<PluginManager> pmStatic = mockStatic(PluginManager.class)) {
      PluginManager mockPm = mock(PluginManager.class);
      pmStatic.when(PluginManager::getInstance).thenReturn(mockPm);
      pmStatic.when(PluginManager::reinitialize).then(invocation -> null);
      when(mockPm.canInstall("jafar")).thenReturn(true);

      Main.autoInstallDefaultBackend();

      // Verify the full reload sequence: install -> reinitialize -> rediscover
      var inOrder = inOrder(mockPm);
      inOrder.verify(mockPm).canInstall("jafar");
      inOrder.verify(mockPm).installPlugin("jafar");
      pmStatic.verify(PluginManager::reinitialize);
    }
  }
}
