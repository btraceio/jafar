package io.jafar.shell.core.analysis;

import io.jafar.shell.JFRSession;
import java.nio.file.Path;

/**
 * The recording an analysis runs against.
 *
 * <p>Exists so the analyses do not depend on how their caller tracks sessions. They were written
 * against {@code jfr-mcp}'s {@code SessionRegistry.SessionInfo}, which is why they were reachable
 * only from the MCP server; the shell has its own session manager and the same recording
 * underneath. This carries the three things the analyses actually use.
 *
 * @param sessionId the caller's number for this session, echoed in results as-is — an int because
 *     that is what both session managers use and what the MCP output has always carried
 * @param recordingPath the file on disk
 * @param session the open session to query
 */
public record AnalysisTarget(int sessionId, Path recordingPath, JFRSession session) {

  public static AnalysisTarget of(int sessionId, JFRSession session) {
    return new AnalysisTarget(sessionId, session.getRecordingPath(), session);
  }
}
