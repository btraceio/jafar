package io.jafar.parser.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.impl.NodePoolFixture.Shape;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The top-level parse error must say why the parse failed.
 *
 * <p>Chunks are parsed on worker threads, so a failure reaches {@code run()} wrapped several times
 * (chunk task, future, chunk). Both shells print only {@link Throwable#getMessage()}, which used to
 * be the bare "Error occurred while parsing JFR recording": a corrupted constant pool was reported
 * at the prompt without its cause.
 */
public class ParseErrorMessageTest {
  @TempDir Path dir;

  private Path write(Shape shape) throws IOException {
    Path out = dir.resolve(shape.fileName());
    Files.write(out, NodePoolFixture.bytes(shape));
    return out;
  }

  /** Guard: the intact fixture parses. Passes with or without the change. */
  @Test
  void intactFixtureParses() throws Exception {
    AtomicInteger events = new AtomicInteger();
    try (UntypedJafarParser p = UntypedJafarParser.open(write(Shape.INTACT))) {
      p.handle((type, value, ctl) -> events.incrementAndGet());
      p.run();
    }
    assertEquals(NodePoolFixture.NODE_REFS.length, events.get());
  }

  @ParameterizedTest
  @EnumSource(
      value = Shape.class,
      names = {"UNREADABLE_ENTRY", "UNDECLARED_POOL"})
  void topLevelMessageCarriesTheCause(Shape shape) throws Exception {
    IOException thrown;
    try (UntypedJafarParser p = UntypedJafarParser.open(write(shape))) {
      p.handle((type, value, ctl) -> {});
      thrown = assertThrows(IOException.class, p::run);
    }
    assertEquals(
        "Error occurred while parsing JFR recording: " + expectedCause(shape), thrown.getMessage());
  }

  /** The messages {@code CheckpointEvent} raises; {@code UnindexedPoolReferenceTest} pins them. */
  private static String expectedCause(Shape shape) {
    switch (shape) {
      case UNREADABLE_ENTRY:
        return "Constant pool test.Node, index 2: Unexpected string constant id: 127";
      case UNDECLARED_POOL:
        return "Constant pool of type "
            + NodePoolFixture.T_UNDECLARED
            + " (1 entries): the type is not declared in the chunk metadata";
      default:
        throw new AssertionError(shape);
    }
  }
}
