package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Issue #264: under tr-TR {@code "I".toLowerCase()} is a dotless {@code "ı"}, so {@code
 * jfr_list_types} with {@code filter=initial} matched nothing: {@code jdk.InitialSystemProperty}
 * lowercased to {@code jdk.ınitialsystemproperty}.
 */
class TurkishLocaleCaseMappingTest {

  private static final Path RECORDING =
      Path.of("../demo/src/test/resources/test-dd.jfr").toAbsolutePath().normalize();

  private Locale saved;
  private Locale savedFormat;
  private Locale savedDisplay;

  @BeforeEach
  void save() {
    Assumptions.assumeTrue(Files.isRegularFile(RECORDING), "missing " + RECORDING);
    saved = Locale.getDefault();
    savedFormat = Locale.getDefault(Locale.Category.FORMAT);
    savedDisplay = Locale.getDefault(Locale.Category.DISPLAY);
  }

  @AfterEach
  void restore() {
    if (saved == null) {
      return;
    }
    // setDefault(Locale) resets both categories; restore each as it was
    Locale.setDefault(saved);
    Locale.setDefault(Locale.Category.FORMAT, savedFormat);
    Locale.setDefault(Locale.Category.DISPLAY, savedDisplay);
  }

  @Test
  void listTypesFilterMatchesTheSameTypesUnderTurkish() throws Exception {
    Locale.setDefault(Locale.ROOT);
    int expected = typesMatching("initial");
    assertTrue(expected > 0, "the recording has types whose name contains 'Initial'");

    Locale.setDefault(Locale.of("tr", "TR"));

    assertEquals(expected, typesMatching("initial"), "jfr_list_types filter=initial under tr-TR");
  }

  private static int typesMatching(String filter) throws Exception {
    JafarMcpServer server = new JafarMcpServer();
    Method open = JafarMcpServer.class.getDeclaredMethod("handleJfrOpen", Map.class);
    open.setAccessible(true);
    assertFalse(
        ((CallToolResult) open.invoke(server, Map.of("path", RECORDING.toString()))).isError());
    Method list = JafarMcpServer.class.getDeclaredMethod("handleJfrListTypes", Map.class);
    list.setAccessible(true);
    CallToolResult result = (CallToolResult) list.invoke(server, Map.of("filter", filter));
    assertFalse(result.isError(), result.content().toString());
    return new ObjectMapper()
        .readTree(((TextContent) result.content().get(0)).text())
        .get("eventTypes")
        .size();
  }
}
