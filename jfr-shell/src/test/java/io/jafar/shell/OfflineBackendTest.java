package io.jafar.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.jafar.shell.backend.BackendRegistry;
import io.jafar.shell.plugin.NetworkPolicy;
import io.jafar.shell.plugin.PluginInstallException;
import io.jafar.shell.plugin.PluginManager;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Offline mode (#235) at the command line: with no backend installed, or with {@code --backend}
 * naming one that is not, nothing is installed and the command fails saying why, though the plugin
 * system reports the backend as installable.
 */
class OfflineBackendTest {

  private PrintStream originalErr;
  private final ByteArrayOutputStream err = new ByteArrayOutputStream();
  private String savedOffline;
  private String savedBackend;

  @BeforeEach
  void setUp() {
    // Started before PluginManager is mocked, so the registry has the test classpath's backends
    BackendRegistry.getInstance();
    savedOffline = System.getProperty(NetworkPolicy.PROPERTY);
    savedBackend = System.getProperty("jfr.shell.backend");
    System.setProperty(NetworkPolicy.PROPERTY, "true");
    originalErr = System.err;
    System.setErr(new PrintStream(err, true));
  }

  @AfterEach
  void tearDown() {
    System.setErr(originalErr);
    restore(NetworkPolicy.PROPERTY, savedOffline);
    restore("jfr.shell.backend", savedBackend);
    BackendRegistry.getInstance().rediscover();
  }

  private static void restore(String key, String value) {
    if (value == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, value);
    }
  }

  @Test
  void noDefaultBackendInstallOffline() throws PluginInstallException {
    try (MockedStatic<PluginManager> pmStatic = mockStatic(PluginManager.class)) {
      PluginManager pm = mock(PluginManager.class);
      pmStatic.when(PluginManager::getInstance).thenReturn(pm);
      when(pm.canInstall("jafar")).thenReturn(true);

      assertFalse(Main.autoInstallDefaultBackend());

      verify(pm, never()).canInstall(anyString());
      verify(pm, never()).installPlugin(anyString());
      String stderr = err.toString();
      assertFalse(stderr.contains("Installing default backend from Maven"), stderr);
      assertTrue(stderr.contains("No backends installed; offline mode"), stderr);
      assertTrue(stderr.contains("--install-plugin <jar>"), stderr);
    }
  }

  @Test
  void namedBackendIsNotDownloadedOffline() throws PluginInstallException {
    try (MockedStatic<PluginManager> pmStatic = mockStatic(PluginManager.class)) {
      PluginManager pm = mock(PluginManager.class);
      pmStatic.when(PluginManager::getInstance).thenReturn(pm);
      when(pm.canInstall(anyString())).thenReturn(true);

      int exit = Main.validateBackend("nosuch", null);

      assertEquals(1, exit);
      verify(pm, never()).installPlugin(anyString());
      String stderr = err.toString();
      assertFalse(stderr.contains("Downloading from Maven repositories"), stderr);
      assertTrue(stderr.contains("Error: Backend not found: nosuch"), stderr);
      assertTrue(stderr.contains("forbids downloading backend 'nosuch'"), stderr);
    }
  }
}
