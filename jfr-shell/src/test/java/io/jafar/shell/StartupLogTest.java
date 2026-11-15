package io.jafar.shell;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.shell.core.ShellModule;
import io.jafar.shell.core.ShellModuleLoader;
import io.jafar.shell.plugin.PluginManager;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * jfr-shell logs through slf4j-simple, which prints INFO to the terminal (stderr). Two routine
 * status lines were logged at INFO: "Loaded N plugin(s)" on every start, and "Loaded module: JFR
 * Analyzer (jfr)" on every 'jfr-shell --tui' start, written before the TUI took the screen and
 * still there after exit. They are DEBUG now; INFO itself still prints, so lines that say where a
 * backend came from, and plugin update notices, still reach the user.
 */
class StartupLogTest {

  @Test
  void routineStartupStatusIsNotPrintedButInfoStillIs(@TempDir Path dir) throws Exception {
    Path backendJar = Files.createFile(dir.resolve("local-backend.jar"));
    List<ShellModule> modules;
    PrintStream originalErr = System.err;
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    try {
      System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
      System.setProperty("jfr.shell.backend.jars", backendJar.toString());
      PluginManager.reinitialize(); // what Main does at start, with one local override jar
      modules = ShellModuleLoader.loadAll(); // what TuiShell does at start
    } finally {
      System.clearProperty("jfr.shell.backend.jars");
      System.setErr(originalErr);
      PluginManager.reinitialize(); // drop the override for later tests
    }
    String text = err.toString(StandardCharsets.UTF_8);

    assertFalse(modules.isEmpty(), "no module was loaded, so nothing was tested");
    // INFO still reaches the terminal: the capture works, and this line stays
    assertTrue(text.contains("Local backend override: " + backendJar), text);
    assertFalse(text.matches("(?s).*Loaded \\d+ plugin.*"), text);
    assertFalse(text.contains("Loaded module:"), text);
  }
}
