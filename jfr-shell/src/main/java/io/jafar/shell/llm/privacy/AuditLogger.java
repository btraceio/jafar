package io.jafar.shell.llm.privacy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Logs all LLM interactions to an audit file for transparency and debugging. Records what data was
 * sent, to which provider, and response metadata.
 */
public class AuditLogger {

  private static final Path AUDIT_LOG =
      Path.of(System.getProperty("user.home"), ".jfr-shell", "llm-audit.log");

  private static final DateTimeFormatter TIMESTAMP_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

  /**
   * Logs an LLM interaction.
   *
   * @param entry audit entry
   * @throws IOException if logging fails
   */
  public synchronized void log(AuditEntry entry) throws IOException {
    // Create directory if needed
    Files.createDirectories(AUDIT_LOG.getParent());

    // Append to log file
    String logLine = entry.toLogLine() + "\n";
    Files.writeString(AUDIT_LOG, logLine, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
  }

  /**
   * Reads recent audit log entries.
   *
   * @param maxLines maximum number of lines to read
   * @return log content
   * @throws IOException if reading fails
   */
  public static String readRecentEntries(int maxLines) throws IOException {
    if (!Files.exists(AUDIT_LOG)) {
      return "No audit log found";
    }

    // Read all lines and return last N
    var lines = Files.readAllLines(AUDIT_LOG);
    int start = Math.max(0, lines.size() - maxLines);
    return String.join("\n", lines.subList(start, lines.size()));
  }

  /**
   * Gets the path to the audit log file.
   *
   * @return audit log path
   */
  public static Path getAuditLogPath() {
    return AUDIT_LOG;
  }

  /**
   * An audit entry representing a single LLM interaction.
   *
   * @param timestamp when the interaction occurred
   * @param provider provider type (local, openai, anthropic)
   * @param model model name
   * @param queryType type of query (ask, analyze, etc.)
   * @param contextBytes size of context sent in bytes
   * @param responseBytes size of response in bytes
   * @param dataSent whether any data was sent (vs just metadata)
   * @param duration how long the request took
   */
  public record AuditEntry(
      Instant timestamp,
      String provider,
      String model,
      String queryType,
      int contextBytes,
      int responseBytes,
      boolean dataSent,
      Duration duration) {

    /**
     * Creates an audit entry with current timestamp.
     *
     * @param provider provider type
     * @param model model name
     * @param queryType query type
     * @param contextBytes context size
     * @param responseBytes response size
     * @param dataSent whether data was sent
     * @param duration request duration
     */
    public AuditEntry(
        String provider,
        String model,
        String queryType,
        int contextBytes,
        int responseBytes,
        boolean dataSent,
        Duration duration) {
      this(
          Instant.now(),
          provider,
          model,
          queryType,
          contextBytes,
          responseBytes,
          dataSent,
          duration);
    }

    /**
     * Formats this entry as a log line.
     *
     * @return formatted log line
     */
    public String toLogLine() {
      return String.format(
          "%s | %s | %s | %s | ctx=%d bytes | resp=%d bytes | sent=%b | %dms",
          TIMESTAMP_FORMAT.format(timestamp),
          provider,
          model,
          queryType,
          contextBytes,
          responseBytes,
          dataSent,
          duration.toMillis());
    }
  }
}
