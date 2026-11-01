package io.jafar.shell.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SecretCommandsTest {

  /** Every form both shells' 'set' accepts for the key. */
  @Test
  void recognisesEveryFormOfSettingTheKey() {
    assertEquals(
        "# set llm.api-key = <redacted: not recorded>",
        SecretCommands.redact("set llm.api-key = sk-1"));
    assertEquals(
        "# set --global llm.api-key = <redacted: not recorded>",
        SecretCommands.redact("set --global llm.api-key = sk-2"));
    assertEquals(
        "# let llm.api-key= <redacted: not recorded>",
        SecretCommands.redact("let llm.api-key=sk-3"));
    assertEquals(
        "# SET   LLM.API-KEY   = <redacted: not recorded>",
        SecretCommands.redact("  SET   LLM.API-KEY   =   \"sk-4\""));
    assertEquals(
        "# set llm.api-key = <redacted: not recorded>",
        SecretCommands.redact("set llm.api-key = sk-5=tail"));
    assertTrue(SecretCommands.setsSecret("set\tllm.api-key\t=\tsk-6"));
  }

  /** Other settings, variables and lines that merely mention the key are left as typed. */
  @Test
  void leavesOtherLinesAlone() {
    for (String line :
        List.of(
            "set llm.backend = ollama",
            "set --global llm.base-url = http://localhost:11434/v1",
            "set key = events/jdk.ThreadStart | count()",
            "set x = llm.api-key",
            "echo llm.api-key = sk-not-a-set",
            "settings llm.api-key = sk",
            "unset llm.api-key",
            "set llm.api-key",
            "set",
            "")) {
      assertFalse(SecretCommands.setsSecret(line), line);
      assertSame(line, SecretCommands.redact(line), line);
    }
    assertFalse(SecretCommands.setsSecret(null));
  }

  /** Each credential is a setting 'set' accepts, so the filter can match what users type (R7). */
  @Test
  void secretsAreSettings() {
    assertTrue(LlmSettings.isSecret("llm.api-key"));
    assertTrue(LlmSettings.isSetting("llm.api-key"));
    assertFalse(LlmSettings.isSecret("llm.backend"));
  }
}
