package io.jafar.mcp.query;

import io.jafar.shell.jfrpath.JfrPath;
import io.jafar.shell.jfrpath.JfrPathParser;

/**
 * Default implementation of QueryParser using JfrPathParser.
 *
 * <p>This adapter wraps the real JfrPathParser for production use.
 */
public final class DefaultQueryParser implements QueryParser {

  @Override
  public JfrPath.Query parse(String queryString) throws Exception {
    return JfrPathParser.parse(queryString);
  }
}
