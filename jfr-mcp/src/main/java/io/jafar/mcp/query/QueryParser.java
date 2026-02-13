package io.jafar.mcp.query;

import io.jafar.shell.jfrpath.JfrPath;

/**
 * Interface for parsing JfrPath query strings.
 *
 * <p>This abstraction allows testing of MCP handlers without requiring real query parsing.
 */
public interface QueryParser {

  /**
   * Parses a JfrPath query string into a structured query object.
   *
   * @param queryString the query string to parse
   * @return the parsed query
   * @throws Exception if parsing fails
   */
  JfrPath.Query parse(String queryString) throws Exception;
}
