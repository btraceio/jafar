package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.server.McpServerFeatures;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Guards against malformed tool input-schema JSON. Every schema string embedded in {@link
 * JafarMcpServer} must parse successfully at registration time; otherwise the server fails to start
 * at runtime (regression seen in 0.21.0 where an unescaped quote in {@code pprof_flamegraph}'s
 * filter description broke startup).
 *
 * <p>Exercises {@code createToolSpecifications()} end-to-end so every schema is parsed by the same
 * path used by the live server.
 */
class ToolSchemaRegistrationTest {

  @Test
  void allToolSchemasParseSuccessfully() throws Exception {
    JafarMcpServer server = new JafarMcpServer();
    Method method = JafarMcpServer.class.getDeclaredMethod("createToolSpecifications");
    method.setAccessible(true);

    List<?> tools;
    try {
      @SuppressWarnings("unchecked")
      List<McpServerFeatures.SyncToolSpecification> result =
          (List<McpServerFeatures.SyncToolSpecification>) method.invoke(server);
      tools = result;
    } catch (InvocationTargetException ite) {
      Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
      throw new AssertionError(
          "createToolSpecifications() threw — likely a malformed tool input-schema JSON: "
              + cause.getMessage(),
          cause);
    }

    assertNotNull(tools, "tool specifications list");
    assertFalse(tools.isEmpty(), "expected at least one registered tool");

    Set<String> seenNames = new HashSet<>();
    for (Object spec : tools) {
      McpServerFeatures.SyncToolSpecification s = (McpServerFeatures.SyncToolSpecification) spec;
      String name = s.tool().name();
      assertNotNull(name, "tool has null name");
      assertFalse(name.isBlank(), "tool has blank name");
      assertTrue(seenNames.add(name), "duplicate tool name: " + name);
      assertNotNull(s.tool().inputSchema(), "tool " + name + " has null input schema");
    }
  }
}
