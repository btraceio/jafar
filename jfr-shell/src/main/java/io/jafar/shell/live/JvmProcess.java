package io.jafar.shell.live;

import java.nio.file.Path;

/**
 * A JVM process discovered on the local machine by scanning the HotSpot perf-data (hsperfdata)
 * directory tree — no attach API involved. {@code command} is the target's {@code java.command}
 * perf counter, or {@code null} when the perf-data file was unreadable or did not parse (permission
 * denial, foreign JDK version, torn read); the PID always comes from the hsperfdata filename, which
 * is readable even when the file content is not.
 */
public record JvmProcess(int pid, String command, Path repository) {
  /** True when a JFR repository directory was found for this process. */
  public boolean hasRepository() {
    return repository != null;
  }
}
