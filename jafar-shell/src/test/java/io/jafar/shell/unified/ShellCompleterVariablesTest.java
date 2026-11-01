package io.jafar.shell.unified;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.shell.core.Session;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.core.VariableStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jline.reader.Candidate;
import org.jline.reader.ParsedLine;
import org.jline.reader.impl.DefaultParser;
import org.junit.jupiter.api.Test;

/** Completion for the shell-level variable and scripting commands. */
class ShellCompleterVariablesTest {
  private final VariableStore global = new VariableStore();
  private final SessionManager<Session> sessions =
      new SessionManager<>(
          (p, c) -> {
            throw new UnsupportedOperationException();
          },
          null);
  private final ShellCompleter completer = new ShellCompleter(sessions, Map.of(), global);

  private static ParsedLine parse(String line) {
    return new DefaultParser().parse(line, line.length());
  }

  private List<String> complete(String line) {
    List<Candidate> candidates = new ArrayList<>();
    completer.complete(null, parse(line), candidates);
    return candidates.stream().map(Candidate::value).sorted().collect(Collectors.toList());
  }

  @Test
  void offersTheScriptingCommands() {
    List<String> all = complete("");
    for (String cmd :
        List.of("set", "vars", "unset", "echo", "invalidate", "if", "elif", "else", "endif")) {
      assertTrue(all.contains(cmd), cmd + " missing from " + all);
    }
    assertEquals(List.of("script"), complete("sc"));
    assertEquals(List.of("sessions", "set"), complete("se"));
  }

  @Test
  void completesArgumentsOfTheVariableCommands() {
    global.set("threshold", new VariableStore.ScalarValue(1L));
    global.set(
        "rows",
        new VariableStore.LazyValue() {
          @Override
          public Object get() {
            return List.of();
          }

          @Override
          public String describe() {
            return "lazy";
          }

          @Override
          public void invalidate() {}

          @Override
          public boolean isCached() {
            return false;
          }

          @Override
          public String getQueryString() {
            return "q";
          }
        });

    assertTrue(complete("set ").contains("--global"));
    assertTrue(complete("set ").contains("llm.max-rows"));
    assertEquals(List.of("threshold"), complete("set th"));
    assertEquals(List.of("threshold"), complete("set --global th"));
    assertEquals(List.of("--all", "--global", "--info", "--session"), complete("vars -"));
    assertEquals(List.of("rows", "threshold"), complete("vars --info "));
    assertEquals(List.of("rows"), complete("invalidate "), "only lazy variables");
    assertTrue(complete("unset ").containsAll(List.of("--global", "rows", "threshold")));
    assertTrue(complete("if ").containsAll(List.of("exists(", "empty(", "threshold")));
  }

  @Test
  void ownsShellLevelArgumentsButHandsTheQueryBack() {
    assertTrue(completer.ownsArguments(parse("set th")));
    assertFalse(completer.ownsArguments(parse("set x = objects")), "the query is the module's");
    assertTrue(completer.ownsArguments(parse("vars --info x")));
    assertTrue(completer.ownsArguments(parse("script ")));
    assertFalse(completer.ownsArguments(parse("show objects")));
    assertFalse(completer.ownsArguments(parse("se")), "first word: merged, not owned");

    // A module completer that knows none of them gets the shell's commands merged in, once
    List<Candidate> fromModule =
        new ArrayList<>(List.of(new Candidate("set"), new Candidate("show")));
    completer.addShellCommands(parse("s"), fromModule);
    List<String> values = fromModule.stream().map(Candidate::value).toList();
    assertEquals(1, values.stream().filter("set"::equals).count(), values.toString());
    assertTrue(values.containsAll(List.of("script", "sessions", "show")), values.toString());
  }
}
