package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.HandlerRegistration;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.TypedJafarParser;
import io.jafar.parser.types.JFRExecutionSample;
import java.io.File;
import java.net.URI;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

public class TypedJafarParserStabilityTest {

  @RepeatedTest(3)
  void multipleRunsWithSharedContext() throws Exception {
    URI uri = TypedJafarParserTest.class.getClassLoader().getResource("test-ap.jfr").toURI();
    ParsingContext ctx = ParsingContext.create();
    for (int i = 0; i < 3; i++) {
      try (TypedJafarParser p = ctx.newTypedParser(new File(uri).toPath())) {
        HandlerRegistration<JFRExecutionSample> reg =
            p.handle(JFRExecutionSample.class, (e, c) -> {});
        p.run();
        reg.destroy(p);
      }
    }
    assertTrue(ctx.uptime() > 0);
  }

  @Test
  void runsInParallelThreads() throws Exception {
    URI uri = TypedJafarParserTest.class.getClassLoader().getResource("test-ap.jfr").toURI();
    var exec = Executors.newFixedThreadPool(2);
    Callable<Void> task =
        () -> {
          try (TypedJafarParser p = TypedJafarParser.open(new File(uri).getAbsolutePath())) {
            HandlerRegistration<JFRExecutionSample> reg =
                p.handle(JFRExecutionSample.class, (e, c) -> {});
            p.run();
            reg.destroy(p);
          }
          return null;
        };
    try {
      Future<Void> first = exec.submit(task);
      Future<Void> second = exec.submit(task);
      // The bound only catches a hang: a parse failure surfaces here, and a busy machine is fine.
      first.get(5, TimeUnit.MINUTES);
      second.get(5, TimeUnit.MINUTES);
    } finally {
      exec.shutdownNow();
    }
  }
}
