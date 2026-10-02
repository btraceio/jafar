package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Contracts the guidance silently relies on: descriptions an agent routes by, and error messages an
 * agent recovers by.
 *
 * <p>An agent picking a tool sees only its name and description. When {@code hdump_open} does not
 * say it takes a {@code .hprof} — or when {@code jfr_open}'s description could plausibly cover a
 * heap dump — routing becomes guesswork, and a wrong guess costs a failed call plus a recovery
 * round-trip. The routing assertions keep the four families disambiguated by extension.
 *
 * <p>Recovery is guided by error text. The no-session errors are the load-bearing case: a client
 * that calls an analysis tool before opening a file is the most common first-session mistake, and
 * the only thing that turns it into a successful second call is an error that names the tool to
 * call ("Use jfr_open first"). That contract is pinned here so a refactor of the session registries
 * cannot quietly replace it with a bare "no session".
 */
class ToolDescriptionQualityTest {

  private static final Map<String, List<String>> FAMILY_EXTENSIONS =
      Map.of(
          "jfr", List.of(".jfr"),
          "hdump", List.of(".hprof"),
          "pprof", List.of(".pprof", ".pb.gz"),
          "otlp", List.of(".otlp"));

  /** Tools whose input-schema parameter surface is pinned, with the expected property names. */
  private static final Map<String, Set<String>> EXPECTED_PROPERTIES =
      Map.of(
          "jfr_open", Set.of("path", "alias"),
          "hdump_open", Set.of("path", "alias"),
          "pprof_open", Set.of("path", "alias"),
          "otlp_open", Set.of("path", "alias"),
          "jfr_query", Set.of("query", "sessionId", "limit"),
          "hdump_query", Set.of("query", "sessionId", "limit"),
          "pprof_query", Set.of("query", "sessionId", "limit"),
          "otlp_query", Set.of("query", "sessionId", "limit"));

  @Test
  void everyToolHasASubstantialDescription() {
    GuidanceSurfaces surfaces = new GuidanceSurfaces();
    List<String> problems = new java.util.ArrayList<>();
    for (var tool : surfaces.tools()) {
      String name = tool.tool().name();
      String description = tool.tool().description();
      if (description == null || description.isBlank()) {
        problems.add(name + ": blank description");
        continue;
      }
      // Too short gives an agent nothing to route by; the ceiling only catches accidental
      // wall-of-text regressions, since long descriptions burn client context for every call.
      if (description.length() < 40) {
        problems.add(
            name + ": description is only " + description.length() + " chars: " + description);
      }
      if (description.length() > 4000) {
        problems.add(name + ": description is " + description.length() + " chars — too long");
      }
    }
    assertTrue(
        problems.isEmpty(), () -> "tool description problems:%n" + String.join("%n", problems));
  }

  @Test
  void openToolsNameTheirFileExtensionsAndNoOthers() {
    GuidanceSurfaces surfaces = new GuidanceSurfaces();
    List<String> problems = new java.util.ArrayList<>();
    for (var tool : surfaces.tools()) {
      String name = tool.tool().name();
      String description = tool.tool().description();
      if (description == null) {
        description = "";
      }
      String ownFamily = familyOf(name);
      // Only the *_open tools route by extension; asserting on all descriptions would forbid a
      // cross-family tool from legitimately mentioning another format (e.g. correlation).
      if (!name.endsWith("_open")) {
        continue;
      }
      for (String extension : FAMILY_EXTENSIONS.get(ownFamily)) {
        if (!description.contains(extension)) {
          problems.add(name + " does not mention its extension " + extension);
        }
      }
      for (Map.Entry<String, List<String>> rival : FAMILY_EXTENSIONS.entrySet()) {
        if (rival.getKey().equals(ownFamily)) {
          continue;
        }
        for (String extension : rival.getValue()) {
          if (description.contains(extension)) {
            problems.add(
                name
                    + " mentions "
                    + extension
                    + " ("
                    + rival.getKey()
                    + " family) — routing must stay unambiguous");
          }
        }
      }
    }
    assertTrue(problems.isEmpty(), () -> "routing problems:%n" + String.join("%n", problems));
  }

  @Test
  void coreToolParameterSurfacesMatchTheirSchemas() {
    GuidanceSurfaces surfaces = new GuidanceSurfaces();
    List<String> problems = new java.util.ArrayList<>();
    for (var tool : surfaces.tools()) {
      String name = tool.tool().name();
      Set<String> expected = EXPECTED_PROPERTIES.get(name);
      if (expected == null) {
        continue;
      }
      Set<String> actual = schemaProperties(tool);
      if (!expected.equals(actual)) {
        problems.add(name + ": schema properties " + actual + " != expected " + expected);
      }
    }
    assertTrue(
        problems.isEmpty(),
        () ->
            "tool parameter surface drifted from the pinned contract (update the pin deliberately"
                + " when adding a parameter, and remember the perf-box skills name parameters"
                + " too):%n"
                + String.join("%n", problems));
  }

  @Test
  void noSessionErrorsNameTheToolToCallInstead() {
    GuidanceSurfaces surfaces = new GuidanceSurfaces();
    List<String> problems = new java.util.ArrayList<>();
    for (String tool : List.of("jfr_query", "hdump_query", "pprof_query", "otlp_query")) {
      McpSchema.CallToolResult result = call(surfaces, tool, Map.of("query", "events | count()"));
      String text = errorText(result);
      String remedy = familyOf(tool) + "_open";
      if (!Boolean.TRUE.equals(result.isError()) || text == null) {
        problems.add(tool + " with no session open must return an error");
      } else if (!text.contains(remedy)) {
        problems.add(
            tool + " no-session error must name " + remedy + " (the call that fixes it): " + text);
      }
    }
    assertTrue(
        problems.isEmpty(), () -> "recovery-guidance problems:%n" + String.join("%n", problems));
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Helpers
  // ─────────────────────────────────────────────────────────────────────────────

  private static String familyOf(String toolName) {
    return toolName.substring(0, toolName.indexOf('_'));
  }

  private static McpSchema.CallToolResult call(
      GuidanceSurfaces surfaces, String tool, Map<String, Object> args) {
    for (var spec : surfaces.tools()) {
      if (spec.tool().name().equals(tool)) {
        Map<String, Object> safeArgs = new LinkedHashMap<>(args);
        return spec.callHandler().apply(null, new McpSchema.CallToolRequest(tool, safeArgs));
      }
    }
    throw new IllegalArgumentException("no such tool: " + tool);
  }

  private static String errorText(McpSchema.CallToolResult result) {
    var content = result.content();
    if (content == null || content.isEmpty()) {
      return null;
    }
    return content.get(0) instanceof McpSchema.TextContent text ? text.text() : null;
  }

  private static Set<String> schemaProperties(
      io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification tool) {
    Map<String, Object> properties;
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> parsed =
          (Map<String, Object>)
              new tools.jackson.databind.ObjectMapper()
                  .readValue(
                      io.modelcontextprotocol.json.McpJsonDefaults.getMapper()
                          .writeValueAsString(tool.tool().inputSchema()),
                      Map.class);
      properties = parsed;
    } catch (Exception e) {
      throw new IllegalStateException("cannot read schema of " + tool.tool().name(), e);
    }
    Set<String> names = new HashSet<>();
    Object props = properties.get("properties");
    if (props instanceof Map<?, ?> map) {
      map.keySet().forEach(k -> names.add(String.valueOf(k)));
    }
    return names;
  }
}
