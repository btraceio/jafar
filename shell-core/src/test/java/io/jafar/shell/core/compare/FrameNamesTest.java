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
        FrameNames.symbolKey("com/example/Handler$$Lambda.0x00007fd6a823c220.test", true));
    // Not a whole hex segment: left alone.
    assertEquals("m.Foo.0xyz", FrameNames.stableName("m.Foo.0xyz"));
    assertEquals("m.Foo$.0x12ab_c", FrameNames.stableName("m.Foo$.0x12ab_c"));
  }

  @Test
  void aPprofNameIsDottedOnlyWhenItIsAJavaInternalName() {
    assertEquals(
        "java.lang.String.indexOf", FrameNames.symbolKey("java/lang/String.indexOf", true));
    assertEquals("java.lang.String.<init>", FrameNames.symbolKey("java/lang/String.<init>", true));
    assertEquals(
        "java.lang.invoke.LambdaForm$MH.invoke",
        FrameNames.symbolKey("java/lang/invoke/LambdaForm$MH/0x00007f2e141a1000.invoke", true));
    // Go functions and native paths contain '/' legitimately.
    assertEquals("github.com/x/y.Func", FrameNames.symbolKey("github.com/x/y.Func", true));
    assertEquals("/usr/lib/libc.so.6", FrameNames.symbolKey("/usr/lib/libc.so.6", true));
    assertEquals(
        "runtime/internal/x.(*T).m", FrameNames.symbolKey("runtime/internal/x.(*T).m", true));
    assertEquals("CodeCache::contains", FrameNames.symbolKey("CodeCache::contains", true));
    assertEquals(
        "java.lang.String.indexOf", FrameNames.symbolKey("java.lang.String.indexOf", true));
    assertNull(FrameNames.symbolKey("", true));
  }

  @Test
  void aProfileThatIsNotFromAJvmKeepsItsSlashedNames() {
    // A Go stdlib function has the shape of a Java internal name; only the profile can tell.
    assertEquals("encoding/json.Marshal", FrameNames.symbolKey("encoding/json.Marshal", false));
    assertEquals("encoding.json.Marshal", FrameNames.symbolKey("encoding/json.Marshal", true));
    assertEquals("net/http.(*conn).serve", FrameNames.symbolKey("net/http.(*conn).serve", false));
  }

  @Test
  void aLineNumberSuffixIsNotPartOfTheKey() {
    // jfr2pprof with includeLineNumbers: true writes Class.method:<line>.
    assertEquals(
        "java.lang.String.indexOf", FrameNames.symbolKey("java/lang/String.indexOf:42", true));
    assertEquals("m.Foo.lambda$main$", FrameNames.symbolKey("m.Foo.lambda$main$0:7", true));
    assertEquals("sendto", FrameNames.symbolKey("sendto:0", false));
    assertNull(FrameNames.symbolKey(":12", true));
  }

  @Test
  void jvmProfilesAreRecognisedByTheirSymbolsAndSourceFiles() {
    assertEquals(true, FrameNames.isJvmSymbol("java/lang/String.<init>"));
    assertEquals(true, FrameNames.isJvmSymbol("m.Foo.<clinit>"));
    assertEquals(true, FrameNames.isJvmSymbol("m.Main$$Lambda.run"));
    assertEquals(false, FrameNames.isJvmSymbol("encoding/json.Marshal"));
    assertEquals(true, FrameNames.isJvmSourceFile("Main.java"));
    assertEquals(true, FrameNames.isJvmSourceFile("Main.kt"));
    assertEquals(true, FrameNames.isJvmSourceFile("Main.scala"));
    assertEquals(false, FrameNames.isJvmSourceFile("encode.go"));
  }

  @Test
  void heapClassNamesLoseTheirPerRunParts() {
    // JDK 21 and 25 heap dumps, as read: a '+' before the hidden class's address
    assertEquals(
        "java.util.stream.Collectors$$Lambda",
        FrameNames.stableClassName("java.util.stream.Collectors$$Lambda+0x0000000019009048"));
    assertEquals(
        "java.lang.invoke.LambdaForm$MH",
        FrameNames.stableClassName("java.lang.invoke.LambdaForm$MH+0x00007f1ea8000a08"));
    // JDK 8: a sequence number
    assertEquals("Dump$$Lambda", FrameNames.stableClassName("Dump$$Lambda$1"));
    // The JVM's '/' form, dotted by the reader: number and address
    assertEquals(
        "m.Foo$$Lambda", FrameNames.stableClassName("m.Foo$$Lambda$14.0x0000000800c03000"));
    assertEquals("m.Foo$$Lambda", FrameNames.stableClassName("m.Foo$$Lambda/0x0000000800c03000"));
    // JDK 17 to 20: a number and an address
    assertEquals(
        "m.Foo$$Lambda", FrameNames.stableClassName("m.Foo$$Lambda$14+0x0000000800c03000"));
    // A hidden class need not have a '$' in its name
    assertEquals("io.jafar.Deser_1", FrameNames.stableClassName("io.jafar.Deser_1+0x00007f00"));
    // An array of a hidden class keeps its brackets
    assertEquals("m.Foo$$Lambda[][]", FrameNames.stableClassName("m.Foo$$Lambda+0x1a2b[][]"));
  }

  @Test
  void ordinaryHeapClassNamesAreUnchanged() {
    assertEquals("m.Outer$1", FrameNames.stableClassName("m.Outer$1"));
    assertEquals("m.Outer$Inner", FrameNames.stableClassName("m.Outer$Inner"));
    assertEquals("m.Hex0x10", FrameNames.stableClassName("m.Hex0x10"));
    assertEquals("m.A$0x10Holder", FrameNames.stableClassName("m.A$0x10Holder"));
    assertEquals("int[]", FrameNames.stableClassName("int[]"));
    // An address must end the name (or come before []): this one does not
    assertEquals("m.Foo$$Lambda+0x1aZ", FrameNames.stableClassName("m.Foo$$Lambda+0x1aZ"));
    assertNull(FrameNames.stableClassName(null));
  }
}
