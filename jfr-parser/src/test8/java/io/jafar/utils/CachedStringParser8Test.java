package io.jafar.utils;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Ensures the Java 8 implementation of CachedStringParser uses cached values when parsing identical
 * data and produces new instances when content differs.
 */
public class CachedStringParser8Test {

  @Test
  void byteArrayParserCachesIdenticalContent() {
    CachedStringParser.ByteArrayParser p = CachedStringParser.byteParser();
    byte[] hello = "hello".getBytes(StandardCharsets.UTF_8);
    String s1 = p.parse(hello, hello.length, StandardCharsets.UTF_8);
    // reusing the same backing array
    String s2 = p.parse(hello, hello.length, StandardCharsets.UTF_8);
    assertSame(s1, s2, "Should reuse same String instance for identical content");

    // using a different array with same content should also be cached after copy
    byte[] hello2 = "hello".getBytes(StandardCharsets.UTF_8);
    String s3 = p.parse(hello2, hello2.length, StandardCharsets.UTF_8);
    assertSame(s2, s3, "Should reuse same String instance for equal content and length");

    // different content must yield a different instance
    byte[] hellO = "hellO".getBytes(StandardCharsets.UTF_8);
    String s4 = p.parse(hellO, hellO.length, StandardCharsets.UTF_8);
    assertNotSame(s3, s4);
    assertEquals("hellO", s4);
  }

  @Test
  void charArrayParserCachesIdenticalContent() {
    CachedStringParser.CharArrayParser p = CachedStringParser.charParser();
    char[] hello = "hello".toCharArray();
    String s1 = p.parse(hello, hello.length);
    String s2 = p.parse(hello, hello.length);
    assertSame(s1, s2, "Should reuse same String instance for identical content");

    char[] hello2 = "hello".toCharArray();
    String s3 = p.parse(hello2, hello2.length);
    assertSame(s2, s3, "Should reuse same String instance for equal content and length");

    char[] diff = "hellO".toCharArray();
    String s4 = p.parse(diff, diff.length);
    assertNotSame(s3, s4);
    assertEquals("hellO", s4);
  }
}
