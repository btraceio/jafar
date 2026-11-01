package io.jafar.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.jafar.parser.api.ParsingContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The metadata scan runs each chunk's onMetadata on the parser's thread pool, so a multi-chunk
 * recording fills the session's type sets from several threads at once. Unsynchronized, a HashSet
 * loses size updates: it then holds more entries than size() says, and copying it fails with "Index
 * N out of bounds for length N" (seen as an intermittent 'metadata --summary' failure).
 */
class JFRSessionMetadataConcurrencyTest {

  @Test
  void multiChunkMetadataScanLeavesConsistentTypeSets() throws Exception {
    Path recording =
        Path.of("..", "jfr-shell-tck", "src", "main", "resources", "tck-test.jfr")
            .toAbsolutePath()
            .normalize(); // two chunks
    ParsingContext context = ParsingContext.create();
    for (int i = 0; i < 300; i++) {
      try (JFRSession session = new JFRSession(recording, context)) {
        check("non-primitive", session.getNonPrimitiveMetadataTypes(), i);
        check("primitive", session.getPrimitiveMetadataTypes(), i);
        check("all", session.getAllMetadataTypes(), i);
        check("metadata", session.getAvailableMetadataTypes(), i);
        assertEquals(
            session.getAllMetadataTypes().size(), session.getMetadataTypeIds().size(), "ids " + i);
      }
    }
  }

  private static void check(String which, Set<String> set, int iteration) {
    // Iterating counts the entries actually there; size() is the map's counter. They must agree,
    // and copying (sized from size()) must not overrun.
    int iterated = 0;
    for (String ignored : set) {
      iterated++;
    }
    List<String> copy = new ArrayList<>(set);
    assertEquals(iterated, set.size(), which + " set, scan " + iteration);
    assertEquals(iterated, copy.size(), which + " copy, scan " + iteration);
  }
}
