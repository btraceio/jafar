package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Issue #224: payloads formatted numbers with the JVM default locale, so on a ru-RU host
 * jfr_summary said {@code "pct": "66,6%"} next to the JSON number {@code 6.3}. Runs the tools with
 * the default locale set to ru-RU (decimal comma) and checks that no string carries a comma-decimal
 * number with a unit.
 */
class DefaultLocaleIndependenceTest extends BaseJfrTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** A decimal written with a comma, followed by % or a size unit: "47,8%", "8,9 MB". */
  private static final Pattern COMMA_DECIMAL = Pattern.compile("\\d,\\d+\\s*(%|[KMGT]?B\\b)");

  /** A dot decimal with % : the non-vacuity check that payloads do carry such strings. */
  private static final Pattern DOT_DECIMAL_PERCENT = Pattern.compile("\\d\\.\\d+%");

  private Locale saved;
  private Locale savedFormat;
  private Locale savedDisplay;
  private JafarMcpServer server;

  @BeforeEach
  void setUp() throws Exception {
    saved = Locale.getDefault();
    savedFormat = Locale.getDefault(Locale.Category.FORMAT);
    savedDisplay = Locale.getDefault(Locale.Category.DISPLAY);
    Locale.setDefault(Locale.of("ru", "RU"));
    server = new JafarMcpServer();
    Method open = JafarMcpServer.class.getDeclaredMethod("handleJfrOpen", Map.class);
    open.setAccessible(true);
    CallToolResult opened =
        (CallToolResult) open.invoke(server, Map.of("path", getComprehensiveJfr()));
    assertFalse(opened.isError(), "failed to open the test recording");
  }

  @AfterEach
  void restore() {
    // setDefault(Locale) resets both categories; restore each as it was
    Locale.setDefault(saved);
    Locale.setDefault(Locale.Category.FORMAT, savedFormat);
    Locale.setDefault(Locale.Category.DISPLAY, savedDisplay);
  }

  @Test
  void jfrSummaryUsesDotDecimals() throws Exception {
    Method summary =
        JafarMcpServer.class.getDeclaredMethod(
            "handleJfrSummary", McpSyncServerExchange.class, Map.class, Object.class);
    summary.setAccessible(true);
    CallToolResult result =
        (CallToolResult) summary.invoke(server, null, new HashMap<String, Object>(), null);
    assertLocaleIndependent(result);
  }

  @Test
  void jfrUseUsesDotDecimals() throws Exception {
    Method use =
        JafarMcpServer.class.getDeclaredMethod(
            "handleJfrUse", McpSyncServerExchange.class, Map.class, Object.class);
    use.setAccessible(true);
    Map<String, Object> args = new HashMap<>();
    args.put("resources", List.of("all"));
    args.put("includeInsights", true);
    CallToolResult result = (CallToolResult) use.invoke(server, null, args, null);
    assertLocaleIndependent(result);
  }

  private static void assertLocaleIndependent(CallToolResult result) throws Exception {
    assertFalse(result.isError(), () -> text(result));
    List<String> strings = new ArrayList<>();
    collectStrings(MAPPER.readTree(text(result)), strings);
    List<String> commaDecimals =
        strings.stream().filter(s -> COMMA_DECIMAL.matcher(s).find()).toList();
    assertTrue(commaDecimals.isEmpty(), "comma decimals under ru-RU: " + commaDecimals);
    assertTrue(
        strings.stream().anyMatch(s -> DOT_DECIMAL_PERCENT.matcher(s).find()),
        "expected at least one formatted percentage, so the check is not vacuous: " + strings);
  }

  private static void collectStrings(JsonNode node, List<String> out) {
    if (node.isString()) {
      out.add(node.asString());
    }
    for (JsonNode child : node.values()) {
      collectStrings(child, out);
    }
  }

  private static String text(CallToolResult result) {
    return ((TextContent) result.content().get(0)).text();
  }
}
