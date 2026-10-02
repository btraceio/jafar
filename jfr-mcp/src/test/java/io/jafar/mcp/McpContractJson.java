package io.jafar.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.Comparator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Canonical JSON rendering of the server's guidance contract: every tool with its description and
 * input schema, every prompt, every resource.
 *
 * <p>One rendering serves three consumers, so they can never disagree (one source of truth): the
 * golden-snapshot test ({@code ToolCatalogSnapshotTest}) compares it against the committed {@code
 * src/test/resources/mcp-contract.json}; a change there must be conscious, because the diff is
 * exactly what every MCP client — and the jafar-perf-box plugin skills, which name tools and
 * parameters explicitly — will experience. The {@code exportMcpContract} task writes the same JSON
 * to the build directory so out-of-repo consumers (perf-box's tool-drift check) can diff their
 * skills against the in-development server instead of waiting for a release.
 *
 * <p>Keys are sorted and tools are ordered by name, so cosmetic reordering in the Java sources does
 * not churn the file — only real contract changes do.
 */
final class McpContractJson {

  private static final ObjectMapper MAPPER =
      JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

  private McpContractJson() {}

  static String render(GuidanceSurfaces surfaces) {
    ObjectNode root = MAPPER.createObjectNode();

    var tools = new ArrayList<>(surfaces.tools());
    tools.sort(Comparator.comparing(t -> t.tool().name()));
    ArrayNode toolsNode = root.putArray("tools");
    for (var tool : tools) {
      ObjectNode toolNode = toolsNode.addObject();
      toolNode.put("name", tool.tool().name());
      toolNode.put("description", tool.tool().description());
      toolNode.set("inputSchema", MAPPER.readTree(schemaJson(tool)));
    }

    var prompts = new ArrayList<>(surfaces.promptSpecs());
    prompts.sort(Comparator.comparing(p -> p.prompt().name()));
    var promptTexts = surfaces.promptTexts();
    ArrayNode promptsNode = root.putArray("prompts");
    for (var prompt : prompts) {
      ObjectNode promptNode = promptsNode.addObject();
      promptNode.put("name", prompt.prompt().name());
      promptNode.put("description", prompt.prompt().description());
      ArrayNode argsNode = promptNode.putArray("arguments");
      for (McpSchema.PromptArgument arg : prompt.prompt().arguments()) {
        ObjectNode argNode = argsNode.addObject();
        argNode.put("name", arg.name());
        argNode.put("description", arg.description());
        argNode.put("required", Boolean.TRUE.equals(arg.required()));
      }
      promptNode.put("renderedText", promptTexts.get(prompt.prompt().name()));
    }

    var resources = new ArrayList<>(surfaces.resourceSpecs());
    resources.sort(Comparator.comparing(r -> r.resource().uri()));
    var resourceTexts = surfaces.resourceTexts();
    ArrayNode resourcesNode = root.putArray("resources");
    for (var resource : resources) {
      ObjectNode resourceNode = resourcesNode.addObject();
      resourceNode.put("uri", resource.resource().uri());
      resourceNode.put("name", resource.resource().name());
      resourceNode.put("description", resource.resource().description());
      resourceNode.put("mimeType", resource.resource().mimeType());
      resourceNode.put("renderedText", resourceTexts.get(resource.resource().uri()));
    }

    return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
  }

  private static String schemaJson(
      io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification tool) {
    try {
      return io.modelcontextprotocol.json.McpJsonDefaults.getMapper()
          .writeValueAsString(tool.tool().inputSchema());
    } catch (java.io.IOException e) {
      throw new IllegalStateException("cannot serialize schema of " + tool.tool().name(), e);
    }
  }

  static JsonNode parse(String json) {
    return MAPPER.readTree(json);
  }
}
