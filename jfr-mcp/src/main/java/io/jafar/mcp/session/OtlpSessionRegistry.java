package io.jafar.mcp.session;

import io.jafar.otlp.shell.OtlpSession;
import io.jafar.shell.core.sampling.SamplingSessionRegistry;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Manages OTLP profiling sessions for MCP tools.
 *
 * <p>Thread-safe registry for opening, tracking, and closing otlp sessions. Sessions are identified
 * by unique integer IDs and optional aliases.
 */
public final class OtlpSessionRegistry extends SamplingSessionRegistry<OtlpSession> {

  @Override
  protected OtlpSession openSession(Path path) throws IOException {
    return OtlpSession.open(path);
  }

  @Override
  protected String formatName() {
    return "otlp";
  }
}
