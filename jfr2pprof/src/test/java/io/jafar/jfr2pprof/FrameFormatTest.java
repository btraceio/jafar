package io.jafar.jfr2pprof;

import static org.assertj.core.api.Assertions.assertThat;

import io.jafar.jfr2pprof.config.FrameFormat;
import io.jafar.jfr2pprof.convert.FrameExtractor;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FrameFormatTest {

  // ---- FrameFormat.render() tests ----

  @Test
  void testDefaultFormatNoLineNumbers() {
    FrameFormat fmt = FrameFormat.defaultFormat();
    assertThat(fmt.render("com.Foo", "bar", 10)).isEqualTo("com.Foo.bar");
  }

  @Test
  void testDefaultFormatWithLineNumbers() {
    FrameFormat fmt = new FrameFormat("{class}.{method}", true);
    assertThat(fmt.render("com.Foo", "bar", 42)).isEqualTo("com.Foo.bar:42");
  }

  @Test
  void testDefaultFormatWithLineNumbersAbsent() {
    FrameFormat fmt = new FrameFormat("{class}.{method}", true);
    // lineNumber == -1 means absent; no suffix should be appended
    assertThat(fmt.render("com.Foo", "bar", -1)).isEqualTo("com.Foo.bar");
  }

  @Test
  void testCustomFormatMethodDotClass() {
    FrameFormat fmt = new FrameFormat("{method}.{class}", false);
    assertThat(fmt.render("com.Foo", "bar", 10)).isEqualTo("bar.com.Foo");
  }

  @Test
  void testCustomFormatClassHashMethod() {
    FrameFormat fmt = new FrameFormat("{class}#{method}", false);
    assertThat(fmt.render("com.Foo", "bar", 10)).isEqualTo("com.Foo#bar");
  }

  // ---- FrameExtractor.extract() tests ----

  @Test
  void testExtractBasicFrame() {
    // Build a synthetic frame map: {method: {type: {name: "com.Foo"}, name: "bar"}, lineNumber: -1}
    Map<String, Object> nameMap = new HashMap<>();
    nameMap.put("name", "com.Foo");

    Map<String, Object> typeMap = nameMap; // type map has "name" key

    Map<String, Object> methodMap = new HashMap<>();
    methodMap.put("type", typeMap);
    methodMap.put("name", "bar");

    Map<String, Object> frameMap = new HashMap<>();
    frameMap.put("method", methodMap);
    frameMap.put("lineNumber", -1);

    FrameFormat fmt = FrameFormat.defaultFormat();
    String result = FrameExtractor.extract(frameMap, fmt);
    assertThat(result).isEqualTo("com.Foo.bar");
  }

  @Test
  void testExtractNullFrameReturnsNull() {
    FrameFormat fmt = FrameFormat.defaultFormat();
    assertThat(FrameExtractor.extract(null, fmt)).isNull();
  }

  @Test
  void testExtractFrameWithLineNumbers() {
    Map<String, Object> typeMap = new HashMap<>();
    typeMap.put("name", "Foo");

    Map<String, Object> methodMap = new HashMap<>();
    methodMap.put("type", typeMap);
    methodMap.put("name", "bar");

    Map<String, Object> frameMap = new HashMap<>();
    frameMap.put("method", methodMap);
    frameMap.put("lineNumber", 42);

    FrameFormat fmt = new FrameFormat("{class}.{method}", true);
    String result = FrameExtractor.extract(frameMap, fmt);
    assertThat(result).isEqualTo("Foo.bar:42");
  }

  @Test
  void testExtractFrameWithConstantPoolSymbolName() {
    // Nested {string: "com.Foo"} name shape (constant-pool symbol)
    Map<String, Object> symbolMap = new HashMap<>();
    symbolMap.put("string", "com.Foo");

    Map<String, Object> typeMap = new HashMap<>();
    typeMap.put("name", symbolMap);

    Map<String, Object> methodNameSymbol = new HashMap<>();
    methodNameSymbol.put("string", "myMethod");

    Map<String, Object> methodMap = new HashMap<>();
    methodMap.put("type", typeMap);
    methodMap.put("name", methodNameSymbol);

    Map<String, Object> frameMap = new HashMap<>();
    frameMap.put("method", methodMap);
    frameMap.put("lineNumber", -1);

    FrameFormat fmt = FrameFormat.defaultFormat();
    String result = FrameExtractor.extract(frameMap, fmt);
    assertThat(result).isEqualTo("com.Foo.myMethod");
  }
}
