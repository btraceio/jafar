package io.jafar.shell.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.shell.core.llm.PromptBuilder.TypeEntry;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The type inventory the model is given.
 *
 * <p>It used to be a bare list of names in the user message, which left the model choosing an event
 * type by whether its name happened to contain a word from the question. A recording already
 * documents its own types — JFR puts {@code @Label("CPU Load")} and a {@code @Description} on the
 * event class — so that text is now sent, and sent in the cached prefix where a fixed-per-recording
 * block belongs.
 */
class TypeInventoryTest {

  private static final TypeEntry CPU_LOAD =
      TypeEntry.documented(
          "jdk.CPULoad", "CPU Load", "Information about the recent CPU usage of the JVM process");
  private static final TypeEntry PLAIN = TypeEntry.of("com.example.Custom");

  @Test
  void theLabelAndDescriptionAreBothRendered() {
    String text = PromptBuilder.renderInventory(List.of(CPU_LOAD));

    assertTrue(text.contains("jdk.CPULoad — CPU Load"), text);
    assertTrue(text.contains("Information about the recent CPU usage"), text);
  }

  @Test
  void anUndocumentedTypeIsStillListed() {
    // A custom event with no annotations is exactly the one the model cannot guess at.
    String text = PromptBuilder.renderInventory(List.of(PLAIN));

    assertTrue(text.contains("com.example.Custom"), text);
    assertFalse(text.contains("—"), "nothing to render after the name: " + text);
  }

  @Test
  void theRenderingIsStableRegardlessOfInputOrder() {
    // The inventory lives in the cached prefix. If it reorders between calls the cache misses
    // every time and the feature quietly costs full price, with nothing visibly broken.
    String one = PromptBuilder.renderInventory(List.of(CPU_LOAD, PLAIN));
    String other = PromptBuilder.renderInventory(List.of(PLAIN, CPU_LOAD));

    assertEquals(one, other);
  }

  @Test
  void theInventoryIsFencedAsRecordingData() {
    // Type names and descriptions come out of the artifact under analysis, and a custom type can be
    // labelled by whoever produced the recording. Moving it into the system prompt does not make it
    // trusted.
    String text = PromptBuilder.renderInventory(List.of(CPU_LOAD));

    assertTrue(text.contains(PromptBuilder.DATA_OPEN), text);
    assertTrue(text.contains(PromptBuilder.DATA_CLOSE), text);
  }

  @Test
  void anEmptyInventoryRendersNothingAtAll() {
    assertEquals("", PromptBuilder.renderInventory(List.of()));
    assertEquals("", PromptBuilder.renderInventory(null));
  }

  @Test
  void theSystemPromptCarriesTheInventoryAndTheUserMessageDoesNot() {
    String system =
        PromptBuilder.translationSystemPrompt("JfrPath", "REFERENCE", List.of(CPU_LOAD));
    String user = PromptBuilder.translationUserMessage("which threads used the most CPU?");

    assertTrue(system.contains("jdk.CPULoad"), system);
    assertFalse(user.contains("jdk.CPULoad"), user);
    assertTrue(user.contains("which threads used the most CPU?"), user);
  }

  @Test
  void countsAreOmittedWhenUnknown() {
    // They always are: counting means scanning the recording, which ask must not do.
    String text = PromptBuilder.renderInventory(List.of(CPU_LOAD));

    assertFalse(text.contains("events)"), "an unknown count must not be rendered: " + text);
  }
}
