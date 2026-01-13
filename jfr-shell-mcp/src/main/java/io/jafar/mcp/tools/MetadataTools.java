package io.jafar.mcp.tools;

import io.jafar.mcp.model.QueryResult;
import io.jafar.mcp.service.JfrSessionService;
import io.jafar.shell.core.SessionManager.SessionRef;
import io.jafar.shell.jfrpath.JfrPath;
import io.jafar.shell.jfrpath.JfrPathEvaluator;
import io.jafar.shell.jfrpath.JfrPathParser;
import java.util.List;
import java.util.Map;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * MCP tools for JFR metadata queries.
 *
 * <p>Provides access to event type structure and field information.
 */
@Component
public class MetadataTools {

  private final JfrSessionService sessionService;
  private final JfrPathEvaluator evaluator;

  public MetadataTools(JfrSessionService sessionService, JfrPathEvaluator evaluator) {
    this.sessionService = sessionService;
    this.evaluator = evaluator;
  }

  /**
   * Queries metadata for event types.
   *
   * @param sessionIdOrAlias session ID or alias
   * @param eventType optional specific event type (e.g., "jdk.ExecutionSample"); if null, lists all
   *     types
   * @return metadata query result
   */
  @McpTool(
      description =
          "Queries metadata about JFR event types. Without eventType parameter, lists all available"
              + " event types. With eventType, shows detailed structure including fields,"
              + " descriptions, and types for the specified event.")
  public QueryResult jfr_metadata(
      @McpToolParam(description = "Session ID or alias to query", required = true)
          String sessionIdOrAlias,
      @McpToolParam(
              description =
                  "Specific event type to inspect (e.g., 'jdk.ExecutionSample',"
                      + " 'jdk.GarbageCollection'). If not provided, lists all available event"
                      + " types.",
              required = false)
          String eventType) {
    try {
      SessionRef ref = sessionService.getSession(sessionIdOrAlias);

      // Build metadata query
      String queryString;
      if (eventType == null || eventType.isBlank()) {
        // List all event types
        queryString = "metadata";
      } else {
        // Show structure for specific event type
        queryString = "metadata/" + eventType;
      }

      JfrPath.Query query = JfrPathParser.parse(queryString);
      List<Map<String, Object>> results = evaluator.evaluate(ref.session, query);

      return QueryResult.success(queryString, results);
    } catch (Exception e) {
      return QueryResult.failure("metadata query", e.getMessage());
    }
  }
}
