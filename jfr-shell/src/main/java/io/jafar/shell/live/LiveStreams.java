package io.jafar.shell.live;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * The injectable seam between the {@code connect}/{@code tail} commands and live discovery —
 * dispatcher tests inject fakes here instead of spawning real target JVMs (Verification.md R2:
 * enumerate the paths; a seam is a path too). The production implementation delegates to {@link
 * JvmProcesses} and {@link LiveJfrSession}.
 */
public interface LiveStreams {

  /** Local JVM listing for {@code connect} with no argument. */
  List<JvmProcess> listLocalJvm();

  /**
   * Resolves the target's JFR repository directory (discovery can fail — {@link IOException} names
   * the searched base and suggests {@code --repository}).
   */
  Path findRepository(int pid) throws IOException;

  /** Creates and starts a live session attached to {@code repository}. */
  LiveJfrSession attach(int pid, Path repository) throws IOException;
}
