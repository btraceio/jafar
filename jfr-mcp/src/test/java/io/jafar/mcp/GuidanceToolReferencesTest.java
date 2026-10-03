package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Every tool name and resource URI the guidance mentions must actually exist.
 *
 * <p>Help topics, prompts, tool descriptions and resources name tools explicitly ("run
 * jfr_diagnose", "hdump_report focus=leaks") because that is what makes them guidance rather than
 * reference. A rename that leaves one mention behind turns that line into confident instructions
 * for a call that fails — the same failure mode {@code doc/agents/Mcp.md} warns about for the
 * out-of-repo plugin skills, caught here for every surface this repo ships.
 *
 * <p>The scan is over the full rendered text of every surface (see {@link GuidanceSurfaces}), so
 * resource contents and prompt texts are covered too, not only tool descriptions.
 */
class GuidanceToolReferencesTest {

  /**
   * Tool-name-looking tokens: a family prefix followed by at least one letter. Deliberately does
   * not match {@code jfr_*} wildcards or {@code hdump_} alone, so prose saying "the jfr_* tools" is
   * not a reference.
   */
  private static final Pattern TOOL_REFERENCE =
      Pattern.compile("\\b((?:jfr|hdump|pprof|otlp)_[a-z][a-z0-9_]*)");

  /** Resource URIs mentioned anywhere in the guidance. */
  private static final Pattern RESOURCE_REFERENCE = Pattern.compile("\\b(jafar://[A-Za-z0-9/_-]+)");

  @Test
  void everyMentionedToolIsRegistered() {
    GuidanceSurfaces surfaces = new GuidanceSurfaces();
    Set<String> registered = new HashSet<>(surfaces.toolNames());
    List<String> dangling = new ArrayList<>();
    for (GuidanceSurfaces.Surface surface : surfaces.surfaces()) {
      Matcher matcher = TOOL_REFERENCE.matcher(surface.text());
      while (matcher.find()) {
        if (!registered.contains(matcher.group(1))) {
          dangling.add(surface.name() + ": " + matcher.group(1));
        }
      }
    }
    assertTrue(
        dangling.isEmpty(),
        () ->
            "The guidance names tools that are not registered — an agent following it will call a"
                + " tool that does not exist. Either register the tool or fix the text:%n%n"
                + String.join("%n", dangling));
  }

  @Test
  void everyMentionedResourceUriIsRegistered() {
    GuidanceSurfaces surfaces = new GuidanceSurfaces();
    Set<String> registered = new HashSet<>(surfaces.resourceTexts().keySet());
    List<String> dangling = new ArrayList<>();
    for (GuidanceSurfaces.Surface surface : surfaces.surfaces()) {
      Matcher matcher = RESOURCE_REFERENCE.matcher(surface.text());
      while (matcher.find()) {
        if (!registered.contains(matcher.group(1))) {
          dangling.add(surface.name() + ": " + matcher.group(1));
        }
      }
    }
    assertTrue(
        dangling.isEmpty(),
        () ->
            "The guidance mentions resources the server does not serve:%n%n"
                + String.join("%n", dangling));
  }

  @Test
  void helpTextsMentionTheToolsTheyTeach() {
    // A guard against help texts that drift into vague prose: each family's tool-selection
    // guide must still name at least its family's open tool, or it no longer guides anything.
    GuidanceSurfaces surfaces = new GuidanceSurfaces();
    String toolsHelp = surfaces.resourceTexts().get("jafar://help/tools");
    assertTrue(
        toolsHelp != null && toolsHelp.contains("jfr_open"),
        "jafar://help/tools must mention jfr_open — it is the entry point it teaches");
  }
}
