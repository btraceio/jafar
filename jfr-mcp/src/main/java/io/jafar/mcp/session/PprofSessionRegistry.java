package io.jafar.mcp.session;

import io.jafar.pprof.shell.PprofSession;
import io.jafar.shell.core.sampling.SamplingSessionRegistry;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Manages pprof profile sessions for MCP tools.
 *
 * <p>Thread-safe registry for opening, tracking, and closing pprof sessions. Sessions are
 * identified by unique integer IDs and optional aliases.
 */
public final class PprofSessionRegistry extends SamplingSessionRegistry<PprofSession> {

  @Override
  protected PprofSession openSession(Path path) throws IOException {
    return PprofSession.open(path);
  }

  @Override
  protected String formatName() {
    return "pprof";
  }
}
