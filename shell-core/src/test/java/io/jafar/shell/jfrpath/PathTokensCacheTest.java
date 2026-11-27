package io.jafar.shell.jfrpath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Paths are parsed once and shared; the shared result is the parse, and nobody can change it. */
class PathTokensCacheTest {

  @Test
  void aCachedPathIsItsParse() {
    for (List<String> path :
        List.of(
            List.of("stackTrace", "frames[0]", "method", "name"),
            List.of("a", "3", "b"),
            List.of("frames[1:4]"),
            List.of("sampledThread", "javaName"))) {
      assertEquals(PathValues.buildPathTokens(path), PathValues.tokensOf(path), path.toString());
    }
  }

  @Test
  void theSamePathIsParsedOnce() {
    List<String> path = List.of("cacheTest", "x", "frames[2]");
    assertSame(PathValues.tokensOf(path), PathValues.tokensOf(new ArrayList<>(path)));
  }

  @Test
  void changingTheCallersListLaterDoesNotChangeTheEntry() {
    List<String> path = new ArrayList<>(List.of("cacheTest2", "a"));
    List<Object> first = PathValues.tokensOf(path);
    path.set(1, "b");
    assertEquals(List.of("cacheTest2", "b"), PathValues.tokensOf(path));
    assertEquals(List.of("cacheTest2", "a"), PathValues.tokensOf(List.of("cacheTest2", "a")));
    assertEquals(List.of("cacheTest2", "a"), first);
  }

  @Test
  void theSharedTokensCannotBeChanged() {
    List<Object> tokens = PathValues.tokensOf(List.of("cacheTest3", "a"));
    assertThrows(UnsupportedOperationException.class, () -> tokens.add("x"));
  }

  @Test
  void aBadPathStillFailsEveryTime() {
    List<String> bad = List.of("frames[0:2]", "name");
    for (int i = 0; i < 2; i++) {
      assertThrows(IllegalArgumentException.class, () -> PathValues.tokensOf(bad));
    }
  }
}
