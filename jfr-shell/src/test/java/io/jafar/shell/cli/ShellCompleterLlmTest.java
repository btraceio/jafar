package io.jafar.shell.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.core.SessionManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jline.reader.Candidate;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Tab completion for the {@code ask} / {@code explain} / {@code llm} commands.
 *
 * <p>These commands shipped without completion, which made the settings in particular
 * undiscoverable: nothing in the shell would tell you that {@code llm.max-retries} exists or how it
 * is spelled.
 */
class ShellCompleterLlmTest {

  private static List<String> complete(String line) {
    ParsingContext ctx = ParsingContext.create();
    SessionManager.SessionFactory<JFRSession> factory =
        (path, c) -> {
          JFRSession s = Mockito.mock(JFRSession.class);
          Mockito.when(s.getRecordingPath()).thenReturn(path);
          Mockito.when(s.getAvailableTypes()).thenReturn(Set.of());
          return s;
        };
    SessionManager<JFRSession> sessions = new SessionManager<>(factory, ctx);
    ShellCompleter completer = new ShellCompleter(sessions, null);
    List<Candidate> candidates = new ArrayList<>();
    completer.complete(null, new ShellCompleterTest.SimpleParsedLine(line), candidates);
    return candidates.stream().map(Candidate::value).collect(Collectors.toList());
  }

  @Test
  void theCommandsThemselvesComplete() {
    assertTrue(complete("as").contains("ask"), "ask");
    assertTrue(complete("expl").contains("explain"), "explain");
    assertTrue(complete("ll").contains("llm"), "llm");
  }

  @Test
  void llmOffersItsSubcommands() {
    List<String> subs = complete("llm ");
    assertTrue(subs.contains("status"), subs.toString());
    assertTrue(subs.contains("dry-run"), subs.toString());
    assertTrue(subs.contains("cost"), subs.toString());
  }

  @Test
  void llmSubcommandsArePrefixFiltered() {
    assertEquals(List.of("cost"), complete("llm co"));
  }

  @Test
  void theQuestionAfterDryRunIsNotCompleted() {
    // Free text — offering command names mid-question would be noise.
    assertTrue(complete("llm dry-run which ").isEmpty(), "expected no candidates for free text");
  }

  @Test
  void setOffersTheLlmSettings() {
    List<String> names = complete("set llm.");
    assertTrue(names.contains("llm.backend"), names.toString());
    assertTrue(names.contains("llm.max-retries"), names.toString());
    assertTrue(names.contains("llm.redact-fields"), names.toString());
  }

  @Test
  void helpOffersTheLlmSubjects() {
    List<String> subjects = complete("help ");
    assertTrue(subjects.contains("ask"), subjects.toString());
    assertTrue(subjects.contains("explain"), subjects.toString());
    assertTrue(subjects.contains("llm"), subjects.toString());
  }

  /**
   * The completion list and the settings {@code LlmConfig} actually reads must not drift apart. A
   * setting that completes but is never read is worse than one that does not complete: it looks
   * supported and silently does nothing.
   */
  @Test
  void everySettingLlmConfigReadsIsOffered() {
    Path source =
        Path.of(
                "..",
                "shell-core",
                "src",
                "main",
                "java",
                "io",
                "jafar",
                "shell",
                "core",
                "llm",
                "LlmConfig.java")
            .normalize();
    Assumptions.assumeTrue(Files.isReadable(source), "LlmConfig source not reachable from here");

    String text;
    try {
      text = Files.readString(source);
    } catch (Exception e) {
      throw new AssertionError(e);
    }

    Matcher m = Pattern.compile("\"(llm\\.[a-z-]+)\"").matcher(text);
    List<String> declared = new ArrayList<>();
    while (m.find()) {
      if (!declared.contains(m.group(1))) {
        declared.add(m.group(1));
      }
    }
    assertFalse(declared.isEmpty(), "found no llm.* keys in LlmConfig — regex out of date?");

    List<String> offered = complete("set llm.");
    List<String> missing =
        declared.stream().filter(k -> !offered.contains(k)).collect(Collectors.toList());
    assertTrue(
        missing.isEmpty(),
        "LlmConfig reads these settings but tab completion does not offer them: " + missing);
  }
}
