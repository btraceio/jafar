package io.jafar.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.JfrType;
import io.jafar.parser.api.ParsingContext;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JFRSessionLazyScanTest {

  private static final Path TCK_RECORDING =
      Path.of("..", "jfr-shell-tck", "src", "main", "resources", "tck-test.jfr")
          .toAbsolutePath()
          .normalize();

  @TempDir Path tempDir;

  /**
   * Completion asks for chunk ids and constant-pool types on every TAB. A failed scan is
   * remembered, so a broken recording is not re-read up to its failure point on each keypress,
   * until {@code refreshTypes()} asks for a fresh look.
   */
  @Test
  void aFailedLazyScanIsNotRepeatedUntilRefresh() throws Exception {
    Path recording = tempDir.resolve("rec.jfr");
    Files.copy(TCK_RECORDING, recording);
    try (JFRSession session = new JFRSession(recording, ParsingContext.create())) {
      Files.delete(recording);
      Files.writeString(recording, "<!DOCTYPE html>not a recording", StandardCharsets.US_ASCII);

      UncheckedIOException chunks =
          assertThrows(UncheckedIOException.class, session::getAvailableChunkIds);
      UncheckedIOException pools =
          assertThrows(UncheckedIOException.class, session::getAvailableConstantPoolTypes);

      // Put the good recording back. A getter that re-read the file would now succeed.
      Files.copy(TCK_RECORDING, recording, StandardCopyOption.REPLACE_EXISTING);
      assertSame(chunks, assertThrows(UncheckedIOException.class, session::getAvailableChunkIds));
      assertSame(
          pools, assertThrows(UncheckedIOException.class, session::getAvailableConstantPoolTypes));

      session.refreshTypes();
      assertEquals(List.of(1, 2), session.getAvailableChunkIds());
      assertTrue(session.getAvailableConstantPoolTypes().contains("jdk.types.Symbol"));
    }
  }

  /** Named nothing like its type, so only the annotation can say what it is. */
  @JfrType("jdk.TenuringDistribution")
  public interface JFRAgeHistogram {
    int age();
  }

  /**
   * The counting wrapper used to match the class's simple name against type names only, so a typed
   * class whose name does not resemble its type was never counted. Its {@code @JfrType} now wins.
   */
  @Test
  void eventsAreCountedUnderTheDeclaredJfrType() throws Exception {
    try (JFRSession session = new JFRSession(TCK_RECORDING, ParsingContext.create())) {
      session.handle(JFRAgeHistogram.class, (event, ctl) -> {});
      session.run();
      assertEquals(13_800L, session.getEventTypeCounts().get("jdk.TenuringDistribution"));
    }
  }
}
