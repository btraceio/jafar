package io.jafar.mcp.model;

/**
 * Information about an active JFR session.
 *
 * @param sessionId numeric session identifier
 * @param alias optional human-readable alias
 * @param recordingPath absolute path to JFR file
 * @param eventTypeCount number of event types in recording
 * @param totalEvents total event count (approximate)
 * @param active whether session is currently active
 */
public record SessionInfo(
    int sessionId,
    String alias,
    String recordingPath,
    int eventTypeCount,
    long totalEvents,
    boolean active) {}
