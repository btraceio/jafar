package io.jafar.mcp.session;

import io.jafar.otelp.shell.OtelpSession;
import io.jafar.shell.core.sampling.SamplingSessionRegistry;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Manages OTLP profiling sessions for MCP tools.
 *
 * <p>Thread-safe registry for opening, tracking, and closing otelp sessions. Sessions are
 * identified by unique integer IDs and optional aliases.
 */
public final class OtelpSessionRegistry extends SamplingSessionRegistry<OtelpSession> {

  @Override
  protected OtelpSession openSession(Path path) throws IOException {
    return OtelpSession.open(path);
  }

  @Override
  protected String formatName() {
    return "otelp";
  }
}
