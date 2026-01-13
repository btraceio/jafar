package io.jafar.mcp.config;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.jfrpath.JfrPathEvaluator;
import io.jafar.shell.llm.ConversationHistory;
import io.jafar.shell.llm.LLMConfig;
import io.jafar.shell.llm.LLMProvider;
import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration class for JFR Shell MCP server beans.
 *
 * <p>Provides reusable infrastructure components from jfr-shell module.
 */
@Configuration
public class JfrShellConfig {

  /**
   * Creates a parsing context for JFR file parsing.
   *
   * @return parsing context instance
   */
  @Bean
  public ParsingContext parsingContext() {
    return ParsingContext.create();
  }

  /**
   * Creates a session manager for managing JFR recording sessions.
   *
   * @param context parsing context
   * @return session manager instance
   */
  @Bean
  public SessionManager sessionManager(ParsingContext context) {
    return new SessionManager(context, this::createSession);
  }

  /**
   * Session factory method for SessionManager.
   *
   * @param path JFR file path
   * @param context parsing context
   * @return JFR session
   */
  private JFRSession createSession(Path path, ParsingContext context) throws Exception {
    return new JFRSession(path, context);
  }

  /**
   * Creates JfrPath evaluator for executing queries.
   *
   * @return evaluator instance
   */
  @Bean
  public JfrPathEvaluator jfrPathEvaluator() {
    return new JfrPathEvaluator();
  }

  /**
   * Creates LLM provider for natural language query translation.
   *
   * @return LLM provider instance
   */
  @Bean
  public LLMProvider llmProvider() {
    try {
      return LLMProvider.create(LLMConfig.load());
    } catch (Exception e) {
      throw new RuntimeException("Failed to create LLM provider", e);
    }
  }

  /**
   * Creates conversation history for tracking LLM interactions.
   *
   * @return conversation history instance
   */
  @Bean
  public ConversationHistory conversationHistory() {
    return new ConversationHistory();
  }
}
