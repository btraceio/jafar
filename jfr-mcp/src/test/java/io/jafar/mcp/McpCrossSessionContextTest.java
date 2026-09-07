package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.mcp.session.HeapSessionRegistry;
import io.jafar.mcp.session.McpCrossSessionContext;
import io.jafar.mcp.session.SessionRegistry;
import io.jafar.shell.core.CrossSessionContext;
import io.jafar.shell.core.QueryEvaluator;
import io.jafar.shell.core.Session;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The cross-session context is what makes {@code join(session=...)} across formats reachable over
 * MCP; {@code HdumpPathEvaluator} rejects a plain {@code SessionResolver}.
 */
class McpCrossSessionContextTest {

  @Test
  void isACrossSessionContextNotJustAResolver() {
    McpCrossSessionContext context =
        new McpCrossSessionContext(new HeapSessionRegistry(), new SessionRegistry());

    // HdumpPathEvaluator does exactly this instanceof check before allowing a cross-type join.
    assertTrue(context instanceof CrossSessionContext);
  }

  @Test
  void resolvingAnUnknownReferenceIsEmptyRatherThanAnError() {
    McpCrossSessionContext context =
        new McpCrossSessionContext(new HeapSessionRegistry(), new SessionRegistry());

    assertTrue(context.resolve("no-such-session").isEmpty());
    assertTrue(context.resolve("9999").isEmpty());
  }

  @Test
  void suppliesNoEvaluatorForANonJfrSession() {
    McpCrossSessionContext context =
        new McpCrossSessionContext(new HeapSessionRegistry(), new SessionRegistry());

    Session notJfr =
        new Session() {
          @Override
          public String getType() {
            return "test";
          }

          @Override
          public java.nio.file.Path getFilePath() {
            return java.nio.file.Path.of("/tmp/test");
          }

          @Override
          public boolean isClosed() {
            return false;
          }

          @Override
          public java.util.Set<String> getAvailableTypes() {
            return java.util.Set.of();
          }

          @Override
          public java.util.Map<String, Object> getStatistics() {
            return java.util.Map.of();
          }

          @Override
          public void close() {}
        };

    Optional<QueryEvaluator> evaluator = context.evaluatorFor(notJfr);
    assertFalse(evaluator.isPresent());
  }
}
