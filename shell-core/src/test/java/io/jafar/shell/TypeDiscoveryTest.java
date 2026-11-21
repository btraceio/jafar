package io.jafar.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class TypeDiscoveryTest {

  private static final Path RECORDING =
      Path.of("..", "jfr-shell-tck", "src", "main", "resources", "tck-test.jfr");

  /**
   * Every type that has events in the recording is discovered, under its own name. The oracle is a
   * plain parse that collects the type of every event delivered.
   */
  @Test
  void discoversEveryTypeThatHasEvents() throws Exception {
    Set<String> delivered = ConcurrentHashMap.newKeySet();
    try (UntypedJafarParser parser = ParsingContext.create().newUntypedParser(RECORDING)) {
      parser.handle((type, value, ctl) -> delivered.add(type.getName()));
      parser.run();
    }
    assertFalse(delivered.isEmpty(), "tck-test.jfr delivered no event");

    Map<String, MetadataClass> discovered =
        new TypeDiscovery(ParsingContext.create()).discoverTypes(RECORDING);

    for (String name : delivered) {
      assertTrue(discovered.containsKey(name), name + " has events but was not discovered");
    }
    discovered.forEach((name, clazz) -> assertEquals(name, clazz.getName()));
  }
}
