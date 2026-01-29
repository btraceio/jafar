package io.jafar.shell.backend.tck;

import io.jafar.shell.backend.JfrBackend;
import java.nio.file.Path;

/**
 * TCK test suite that runs against the backend loaded by TckRunner.
 *
 * <p>This class extends BackendTck and provides the backend and test recording from TckRunner's
 * static state.
 */
public final class TckTestSuite extends BackendTck {

  @Override
  protected JfrBackend createBackend() {
    JfrBackend backend = TckRunner.getLoadedBackend();
    if (backend == null) {
      throw new IllegalStateException("No backend loaded. TckTestSuite must be run via TckRunner.");
    }
    return backend;
  }

  @Override
  protected Path getTestRecording() {
    Path path = TckRunner.getTestRecordingPath();
    if (path == null) {
      throw new IllegalStateException(
          "No test recording configured. TckTestSuite must be run via TckRunner.");
    }
    return path;
  }
}
