package io.jafar.mcp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import tools.jackson.databind.JsonNode;

/**
 * Base class for MCP transport integration tests that use {@link McpTransportHarness}.
 *
 * <p>Owns the harness lifecycle ({@code @BeforeEach} / {@code @AfterEach}) so subclasses do not
 * duplicate it. Subclasses that need additional setup should use a differently-named
 * {@code @BeforeEach} method; JUnit 5 runs the superclass method first.
 */
abstract class McpTransportTestBase extends BaseJfrTest {

  protected McpTransportHarness harness;

  @BeforeEach
  void setUpHarness() throws Exception {
    harness = new McpTransportHarness();
  }

  @AfterEach
  void tearDownHarness() throws Exception {
    harness.close();
  }

  /** Asserts that a tool response is a successful, non-error result. */
  protected static void assertSuccess(JsonNode resp, int id) {
    McpTransportHarness.assertSuccess(resp, id);
  }
}
