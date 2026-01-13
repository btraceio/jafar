package io.jafar.mcp.tools;

import io.jafar.mcp.model.QueryResult;
import io.jafar.mcp.service.JfrSessionService;
import io.jafar.shell.core.SessionManager.SessionRef;
import io.jafar.shell.jfrpath.JfrPath;
import io.jafar.shell.jfrpath.JfrPathEvaluator;
import io.jafar.shell.jfrpath.JfrPathParser;
import io.jafar.shell.llm.ContextBuilder;
import io.jafar.shell.llm.ConversationHistory;
import io.jafar.shell.llm.LLMConfig;
import io.jafar.shell.llm.LLMProvider;
import io.jafar.shell.llm.QueryTranslator;
import io.jafar.shell.llm.QueryTranslator.TranslationResult;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * MCP tools for executing JfrPath queries.
 *
 * <p>Provides both direct query execution and natural language translation.
 */
@Component
public class QueryTools {

  private final JfrSessionService sessionService;
  private final JfrPathEvaluator evaluator;
  private final LLMProvider llmProvider;
  private final ConversationHistory conversationHistory;
  private final LLMConfig llmConfig;

  public QueryTools(
      JfrSessionService sessionService,
      JfrPathEvaluator evaluator,
      LLMProvider llmProvider,
      ConversationHistory conversationHistory) {
    this.sessionService = sessionService;
    this.evaluator = evaluator;
    this.llmProvider = llmProvider;
    this.conversationHistory = conversationHistory;
    try {
      this.llmConfig = LLMConfig.load();
    } catch (Exception e) {
      throw new RuntimeException("Failed to load LLM configuration", e);
    }
  }

  /**
   * Executes a JfrPath query against a session.
   *
   * @param sessionIdOrAlias session ID or alias (required)
   * @param query JfrPath query string (required)
   * @return query result
   */
  @McpTool(
      description =
          "Executes a JfrPath query against a JFR recording session. Use this to run custom"
              + " queries using JfrPath syntax.")
  public QueryResult jfr_query(
      @McpToolParam(description = "Session ID or alias to query", required = true)
          String sessionIdOrAlias,
      @McpToolParam(description = "JfrPath query string to execute", required = true)
          String query) {
    try {
      SessionRef ref = sessionService.getSession(sessionIdOrAlias);
      JfrPath.Query parsedQuery = JfrPathParser.parse(query);
      List<Map<String, Object>> results = evaluator.evaluate(ref.session, parsedQuery);
      return QueryResult.success(query, results);
    } catch (Exception e) {
      return QueryResult.failure(query, e.getMessage());
    }
  }

  /**
   * Translates a natural language question into JfrPath and executes it.
   *
   * @param sessionIdOrAlias session ID or alias (required)
   * @param question natural language question (required)
   * @return query result with explanation
   */
  @McpTool(
      description =
          "Translates a natural language question about JFR data into a JfrPath query and"
              + " executes it. Use this when you want to ask questions in plain English about the"
              + " recording.")
  public QueryResult jfr_ask(
      @McpToolParam(description = "Session ID or alias to query", required = true)
          String sessionIdOrAlias,
      @McpToolParam(
              description =
                  "Natural language question about the JFR data (e.g., 'which threads"
                      + " allocated the most memory?')",
              required = true)
          String question) {
    try {
      SessionRef ref = sessionService.getSession(sessionIdOrAlias);

      // Build context and translate question
      ContextBuilder contextBuilder = new ContextBuilder(ref, llmConfig);
      QueryTranslator translator =
          new QueryTranslator(llmProvider, contextBuilder, conversationHistory);
      TranslationResult translation = translator.translate(question);

      // Handle conversational responses (non-JFR questions)
      if (translation.isConversational()) {
        Map<String, Object> responseMap = new HashMap<>();
        responseMap.put("response", translation.conversationalResponse());
        return new QueryResult(
            null, List.of(responseMap), 1, true, null, "Conversational response");
      }

      // Execute translated query
      JfrPath.Query query = JfrPathParser.parse(translation.jfrPathQuery());
      List<Map<String, Object>> results = evaluator.evaluate(ref.session, query);

      return QueryResult.success(translation.jfrPathQuery(), results, translation.explanation());

    } catch (Exception e) {
      return QueryResult.failure(null, "Failed to process question: " + e.getMessage());
    }
  }
}
