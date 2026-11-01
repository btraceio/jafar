package io.jafar.shell.unified;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.shell.core.VariableStore;
import java.util.List;
import org.junit.jupiter.api.Test;

class ShellVariablesTest {
  private final VariableStore store = new VariableStore();
  private final ShellVariables vars = new ShellVariables(store);

  @Test
  void settingKeepsDottedNameAndTextValue() throws Exception {
    vars.set("llm.backend = ollama");
    vars.set("LLM.max-rows = 20");

    assertEquals("ollama", store.get("llm.backend").get());
    // Text, not 20L or 20.0: the config reader parses it as an int.
    assertEquals("20", store.get("llm.max-rows").get());
  }

  @Test
  void unknownSettingListsTheRealOnes() {
    var e = assertThrows(IllegalArgumentException.class, () -> vars.set("llm.backed = x"));
    assertTrue(e.getMessage().contains("llm.backend"), e.getMessage());
  }

  @Test
  void rejectsBadNamesAndMissingEquals() {
    assertThrows(IllegalArgumentException.class, () -> vars.set("a.b = 1"));
    assertThrows(IllegalArgumentException.class, () -> vars.set("novalue"));
    assertThrows(IllegalArgumentException.class, () -> vars.set("x = bareword"));
  }

  @Test
  void plainVariablesAreTypedAndSubstituted() throws Exception {
    vars.set("n = 42");
    vars.set("s = \"hi\"");
    vars.set("copy = n");

    assertEquals(42L, store.get("n").get());
    assertEquals(42L, store.get("copy").get());
    assertEquals(List.of("hi 42"), vars.echo("${s} ${n}"));
    vars.set("llm.model = m-${n}");
    assertEquals("m-42", store.get("llm.model").get());
  }

  @Test
  void unsetAndVars() {
    vars.set("a = 1");
    assertTrue(vars.vars().get(1).contains("a = 1"));
    vars.unset("a");
    assertEquals(List.of("No variables defined"), vars.vars());
    assertThrows(IllegalArgumentException.class, () -> vars.unset("a"));
  }
}
