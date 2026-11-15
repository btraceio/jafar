package io.jafar.parser.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.api.Values;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Constant pools are indexed by skipping each entry with its type's {@code TypeSkipper}. For a type
 * with an array of an inline type that itself has an array field, the skipper's array loop ran the
 * nested array instruction as a no-op and its element count as an instruction, so every entry after
 * the first was indexed at the wrong offset.
 */
class NestedArraySkipTest {
  @TempDir Path dir;

  @Test
  void poolEntriesAfterOneWithNestedArraysAreIndexedCorrectly() throws Exception {
    Path jfr = dir.resolve("nested.jfr");
    Files.write(jfr, NestedArrayFixture.bytes());
    List<String> seen = Collections.synchronizedList(new ArrayList<>());
    try (UntypedJafarParser p = UntypedJafarParser.open(jfr)) {
      p.handle(
          (type, value, ctl) -> {
            if (NestedArrayFixture.EVENT.equals(type.getName())) {
              Map<String, Object> v = Values.resolvedDeep(value);
              Map<?, ?> holder = (Map<?, ?>) v.get("holder");
              seen.add(v.get("last") + " " + (holder == null ? null : holder.get("tail")));
            }
          });
      p.run();
    }
    assertEquals(List.of("1000 101", "1001 102", "1002 101"), seen);
  }
}
