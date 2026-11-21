package io.jafar.shell.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** {@code open}'s argument: a path with spaces, quoted or not, then {@code --alias NAME} (#233). */
class OpenArgumentsTest {

  @Test
  void aPlainPath() {
    OpenArguments args = OpenArguments.parse(" /tmp/rec.jfr ");
    assertEquals("/tmp/rec.jfr", args.path());
    assertNull(args.alias());
  }

  @Test
  void anUnquotedPathKeepsItsSpaces() {
    assertEquals("/data/my dir/rec 1.jfr", OpenArguments.parse("/data/my dir/rec 1.jfr").path());
  }

  @Test
  void anAliasEndsAnUnquotedPath() {
    OpenArguments args = OpenArguments.parse("/data/my dir/rec.jfr --alias base");
    assertEquals("/data/my dir/rec.jfr", args.path());
    assertEquals("base", args.alias());
  }

  @Test
  void quotesAreRemoved() {
    assertEquals("/data/my dir/rec.jfr", OpenArguments.parse("\"/data/my dir/rec.jfr\"").path());
    assertEquals("/data/my dir/rec.jfr", OpenArguments.parse("'/data/my dir/rec.jfr'").path());
    OpenArguments args = OpenArguments.parse("\"/data/my dir/rec.jfr\" --alias base");
    assertEquals("/data/my dir/rec.jfr", args.path());
    assertEquals("base", args.alias());
  }

  @Test
  void aQuotedPathMayContainTheAliasOption() {
    OpenArguments args = OpenArguments.parse("\"/data/x --alias y/rec.jfr\"");
    assertEquals("/data/x --alias y/rec.jfr", args.path());
    assertNull(args.alias());
  }

  @Test
  void aWordThatOnlyStartsWithTheOptionIsPartOfThePath() {
    assertEquals("/data/a --aliased.jfr", OpenArguments.parse("/data/a --aliased.jfr").path());
  }

  @Test
  void malformedArgumentsAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> OpenArguments.parse(""));
    assertThrows(IllegalArgumentException.class, () -> OpenArguments.parse("\"/data/unclosed"));
    assertThrows(IllegalArgumentException.class, () -> OpenArguments.parse("/a.jfr --alias"));
    assertThrows(IllegalArgumentException.class, () -> OpenArguments.parse("/a.jfr --alias x y"));
    assertThrows(IllegalArgumentException.class, () -> OpenArguments.parse("\"/a.jfr\" extra"));
  }

  @Test
  void quoteIsReadBackAsTheSamePath() {
    for (String path :
        new String[] {
          "/tmp/rec.jfr",
          "/tmp/my dir/rec.jfr",
          "/tmp/a --alias b.jfr",
          "/tmp/say \"hi\" now.jfr",
          "\"leading-quote.jfr",
          "C:\\Users\\me\\My Recordings\\app.jfr"
        }) {
      assertEquals(path, OpenArguments.parse(OpenArguments.quote(path)).path(), path);
    }
    assertEquals("/tmp/rec.jfr", OpenArguments.quote("/tmp/rec.jfr"), "unchanged when plain");
  }
}
