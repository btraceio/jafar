package io.jafar.shell.core.compare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** The frame key both readers build: decision D2 of the cross-format design. */
class FrameNamesTest {

  @Test
  void aJfrClassNameIsDottedAfterItsStableName() {
    assertEquals("java.lang.String.indexOf", FrameNames.jfrKey("java/lang/String", "indexOf"));
    // stableName needs the slashed form to find the address: the order matters.
    assertEquals(
        "java.lang.invoke.LambdaForm$MH.invoke",
        FrameNames.jfrKey("java/lang/invoke/LambdaForm$MH/0x00007f2e141a1000", "invoke"));
    assertEquals("m.x.Outer.access$", FrameNames.jfrKey("m/x/Outer", "access$400"));
    // Already dotted (another backend, or a scripted test): unchanged.
    assertEquals("m.Foo.bar", FrameNames.jfrKey("m.Foo", "bar"));
  }

  @Test
  void aJfrFrameWithoutAClassKeepsItsSymbol() {
    assertEquals("sendto", FrameNames.jfrKey("", "sendto"));
    assertEquals("CodeCache::contains", FrameNames.jfrKey("", "CodeCache::contains"));
    // Only a class name is dotted: a symbol that is a path stays a path.
    assertEquals("/usr/lib/libc.so.6", FrameNames.jfrKey("", "/usr/lib/libc.so.6"));
    assertNull(FrameNames.jfrKey("", ""));
  }

  @Test
  void aHiddenClassAddressAfterADotIsDroppedToo() {
    // tck-test.jfr stores hidden lambda classes this way.
    assertEquals(
        "com.example.Handler$$Lambda.test",
        FrameNames.jfrKey("com/example/Handler$$Lambda.0x00007fd6a823c220", "test"));
    assertEquals(
        "com.example.Handler$$Lambda.test",
        FrameNames.symbolKey("com/example/Handler$$Lambda.0x00007fd6a823c220.test"));
    // Not a whole hex segment: left alone.
    assertEquals("m.Foo.0xyz", FrameNames.stableName("m.Foo.0xyz"));
    assertEquals("m.Foo$.0x12ab_c", FrameNames.stableName("m.Foo$.0x12ab_c"));
  }

  @Test
  void aPprofNameIsDottedOnlyWhenItIsAJavaInternalName() {
    assertEquals("java.lang.String.indexOf", FrameNames.symbolKey("java/lang/String.indexOf"));
    assertEquals("java.lang.String.<init>", FrameNames.symbolKey("java/lang/String.<init>"));
    assertEquals(
        "java.lang.invoke.LambdaForm$MH.invoke",
        FrameNames.symbolKey("java/lang/invoke/LambdaForm$MH/0x00007f2e141a1000.invoke"));
    // Go functions and native paths contain '/' legitimately.
    assertEquals("github.com/x/y.Func", FrameNames.symbolKey("github.com/x/y.Func"));
    assertEquals("/usr/lib/libc.so.6", FrameNames.symbolKey("/usr/lib/libc.so.6"));
    assertEquals("runtime/internal/x.(*T).m", FrameNames.symbolKey("runtime/internal/x.(*T).m"));
    assertEquals("CodeCache::contains", FrameNames.symbolKey("CodeCache::contains"));
    assertEquals("java.lang.String.indexOf", FrameNames.symbolKey("java.lang.String.indexOf"));
    assertNull(FrameNames.symbolKey(""));
  }
}
