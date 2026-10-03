package io.jafar.mcp.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class JsonRpcTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void aRequestHasAMethodAndAnId() {
    JsonRpc.Frame f = JsonRpc.parse("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/list\"}");

    assertNotNull(f);
    assertEquals("tools/list", f.method());
    assertEquals("7", f.idJson());
    assertTrue(f.isRequest());
    assertFalse(f.isResponse());
  }

  @Test
  void aNotificationHasAMethodAndNoId() {
    JsonRpc.Frame f =
        JsonRpc.parse("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");

    assertNotNull(f);
    assertNull(f.idJson());
    assertFalse(f.isRequest());
    assertFalse(f.isResponse());
  }

  @Test
  void aResponseHasAnIdAndNoMethod() {
    JsonRpc.Frame f =
        JsonRpc.parse("{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"x\":[1,2,{\"id\":99}]}}");

    assertNotNull(f);
    assertTrue(f.isResponse());
    assertEquals("3", f.idJson(), "an id nested inside the result must not be mistaken for ours");
  }

  @Test
  void idAndMethodAreFoundWhateverTheFieldOrderAndNesting() {
    JsonRpc.Frame f =
        JsonRpc.parse("{\"params\":{\"id\":1,\"method\":\"inner\"},\"method\":\"outer\",\"id\":5}");

    assertNotNull(f);
    assertEquals("outer", f.method());
    assertEquals("5", f.idJson());
  }

  @Test
  void stringIdsAreCanonicalisedSoEscapedAndPlainSpellingsAgree() {
    JsonRpc.Frame escaped = JsonRpc.parse("{\"id\":\"caf\\u00e9\",\"result\":{}}");
    JsonRpc.Frame plain = JsonRpc.parse("{\"id\":\"café\",\"result\":{}}");

    assertNotNull(escaped);
    assertNotNull(plain);
    assertEquals(plain.idJson(), escaped.idJson());
    assertEquals("\"café\"", plain.idJson());
  }

  @Test
  void notJsonOrNotAnObjectYieldsNull() {
    assertNull(JsonRpc.parse("this is not json"));
    assertNull(JsonRpc.parse("[1,2,3]"));
    assertNull(JsonRpc.parse(""));
  }

  @Test
  void compactCollapsesAMultiLineFrameIntoOneLine() {
    String compact = JsonRpc.compact("{\n  \"id\": 1,\n  \"result\": {\"a\": \"b\"}\n}");

    assertFalse(compact.contains("\n"));
    JsonNode parsed = MAPPER.readTree(compact);
    assertEquals(1, parsed.get("id").asInt());
    assertEquals("b", parsed.get("result").get("a").asString());
  }

  @Test
  void errorResponsesEchoTheIdAndEscapeTheMessage() {
    JsonNode numeric = MAPPER.readTree(JsonRpc.error("42", -32603, "boom"));
    assertEquals("2.0", numeric.get("jsonrpc").asString());
    assertEquals(42, numeric.get("id").asInt());
    assertEquals(-32603, numeric.get("error").get("code").asInt());
    assertEquals("boom", numeric.get("error").get("message").asString());

    JsonNode quoted = MAPPER.readTree(JsonRpc.error("\"abc\"", -32603, "it said \"no\"\nand left"));
    assertEquals("abc", quoted.get("id").asString());
    assertEquals("it said \"no\"\nand left", quoted.get("error").get("message").asString());
    assertFalse(JsonRpc.error("1", -32603, "a\nb").contains("\n"), "must stay on one line");
  }
}
