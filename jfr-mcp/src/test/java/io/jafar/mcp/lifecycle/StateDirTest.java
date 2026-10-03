package io.jafar.mcp.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StateDirTest {

  private String savedStateDir;
  private String savedHome;

  @BeforeEach
  void save() {
    savedStateDir = System.getProperty(StateDir.PROPERTY);
    savedHome = System.getProperty("user.home");
    System.clearProperty(StateDir.PROPERTY);
  }

  @AfterEach
  void restore() {
    restoreProperty(StateDir.PROPERTY, savedStateDir);
    restoreProperty("user.home", savedHome);
  }

  private static void restoreProperty(String key, String value) {
    if (value == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, value);
    }
  }

  @Test
  void defaultsToDotJafarUnderUserHome(@TempDir Path home) {
    System.setProperty("user.home", home.toString());

    assertEquals(home.resolve(".jafar"), StateDir.resolve());
  }

  @Test
  void propertyOverridesDefault(@TempDir Path home, @TempDir Path state) {
    System.setProperty("user.home", home.toString());
    System.setProperty(StateDir.PROPERTY, state.toString());

    assertEquals(state, StateDir.resolve());
  }

  @Test
  void blankPropertyFallsBackToDefault(@TempDir Path home) {
    System.setProperty("user.home", home.toString());
    System.setProperty(StateDir.PROPERTY, "  ");

    assertEquals(home.resolve(".jafar"), StateDir.resolve());
  }

  @Test
  void portRegistryAndTokenFollowTheStateDir(@TempDir Path state) {
    System.setProperty(StateDir.PROPERTY, state.toString());

    assertEquals(
        state.resolve("mcp-sse.token"),
        SseAuthToken.defaultToken(org.slf4j.LoggerFactory.getLogger("test")).path());
    SsePortRegistry registry =
        SsePortRegistry.defaultRegistry(org.slf4j.LoggerFactory.getLogger("test"));
    registry.write(4321);
    assertEquals(true, java.nio.file.Files.exists(state.resolve("mcp-sse.port")));
    registry.delete();
  }
}
