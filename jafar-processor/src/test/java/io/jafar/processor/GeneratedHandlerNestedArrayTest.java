package io.jafar.processor;

import static io.jafar.processor.ProcessorTestSupport.call;
import static io.jafar.processor.ProcessorTestSupport.compileAndLoad;
import static io.jafar.processor.ProcessorTestSupport.parse;
import static io.jafar.processor.ProcessorTestSupport.source;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.jafar.parser.impl.NestedArrayFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A generated handler skips an undeclared field with {@code TypeSkipper.skip(MetadataField, ...)}.
 * For an array of an inline type that has an array field, the skipper did not interpret the nested
 * array, so the declared fields after it were misread - on the event and inside the pooled entry.
 */
class GeneratedHandlerNestedArrayTest {
  @TempDir Path dir;

  @Test
  void undeclaredNestedArraysAreSkipped() throws Exception {
    Path jfr = dir.resolve("nested.jfr");
    Files.write(jfr, NestedArrayFixture.bytes());
    ClassLoader loader =
        compileAndLoad(
            source(
                "PHolder",
                "@JfrType(\""
                    + NestedArrayFixture.HOLDER
                    + "\")\npublic interface PHolder {\n"
                    + "  int tail();\n}\n"),
            source(
                "PNested",
                "@JfrType(\""
                    + NestedArrayFixture.EVENT
                    + "\")\npublic interface PNested {\n"
                    + "  PHolder holder();\n  int last();\n}\n"));
    List<String> seen = Collections.synchronizedList(new ArrayList<>());
    parse(
        jfr,
        loader,
        loader.loadClass("test.PNested"),
        e -> seen.add(call(e, "last") + " " + call(call(e, "holder"), "tail")),
        "PNested",
        "PHolder");
    assertEquals(List.of("1000 101", "1001 102", "1002 101"), seen);
  }
}
