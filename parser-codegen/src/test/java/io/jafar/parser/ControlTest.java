package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.jafar.parser.api.Control;
import io.jafar.parser.api.HandlerRegistration;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.TypedJafarParser;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.impl.EventStreamGenerated;
import io.jafar.parser.impl.ParsingContextImpl;
import io.jafar.parser.internal_api.StreamingChunkParser;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.types.JFRExecutionSample;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link Control#abort()} stops the whole parse, not only the chunk whose handler called it.
 *
 * <p>Every test runs against {@code test-ap.jfr} and against the same recording concatenated into
 * three chunks (see {@link AbortProbe#fixtures}), and asserts through {@link AbortProbe}, whose
 * rules hold for any chunk count and any thread-pool size.
 */
public class ControlTest {
  private static final String SINGLE = "test-ap.jfr";
  private static final String MULTI_CHUNK = "test-ap.jfr x3";

  @TempDir Path tempDir;

  private Path fixture(String which) {
    List<Path> fixtures = AbortProbe.fixtures(tempDir);
    return fixtures.get(SINGLE.equals(which) ? 0 : 1);
  }

  // ---- typed --------------------------------------------------------------------------------

  private static int countTyped(Path recording) throws Exception {
    AtomicInteger n = new AtomicInteger();
    try (TypedJafarParser p = TypedJafarParser.open(recording.toString())) {
      p.handle(JFRExecutionSample.class, (e, ctl) -> n.incrementAndGet());
      p.run();
    }
    return n.get();
  }

  @ParameterizedTest(name = "{displayName}[{0}]")
  @ValueSource(strings = {SINGLE, MULTI_CHUNK})
  void abortStopsFurtherEventDeliveryTyped(String which) throws Exception {
    Path recording = fixture(which);
    AbortProbe probe = new AbortProbe(5);
    try (TypedJafarParser p = TypedJafarParser.open(recording.toString())) {
      HandlerRegistration<JFRExecutionSample> reg =
          p.handle(JFRExecutionSample.class, (e, ctl) -> probe.onEvent(ctl));
      p.run();
      reg.destroy(p);
    }
    probe.assertAborted(recording.getFileName().toString(), countTyped(recording));
  }

  @ParameterizedTest(name = "{displayName}[{0}]")
  @ValueSource(strings = {SINGLE, MULTI_CHUNK})
  void abortAllowsSubsequentRunsAfterReconfiguringHandlersTyped(String which) throws Exception {
    Path recording = fixture(which);
    int total = countTyped(recording);
    try (TypedJafarParser p = TypedJafarParser.open(recording.toString())) {
      // First run: abort early
      AbortProbe probe = new AbortProbe(3);
      HandlerRegistration<JFRExecutionSample> r1 =
          p.handle(JFRExecutionSample.class, (e, ctl) -> probe.onEvent(ctl));
      p.run();
      r1.destroy(p);
      probe.assertAborted(recording.getFileName().toString(), total);

      // Second run on the same parser: the abort belonged to the first run only
      AtomicInteger second = new AtomicInteger();
      HandlerRegistration<JFRExecutionSample> r2 =
          p.handle(JFRExecutionSample.class, (e, ctl) -> second.incrementAndGet());
      p.run();
      r2.destroy(p);
      assertEquals(total, second.get(), "a run after an aborted one must see every event");
    }
  }

  // ---- untyped (EventStream) ----------------------------------------------------------------

  private static int countUntyped(Path recording) throws Exception {
    AtomicInteger n = new AtomicInteger();
    try (UntypedJafarParser p = UntypedJafarParser.open(recording.toString())) {
      p.handle((type, value, ctl) -> n.incrementAndGet());
      p.run();
    }
    return n.get();
  }

  @ParameterizedTest(name = "{displayName}[{0}]")
  @ValueSource(strings = {SINGLE, MULTI_CHUNK})
  void abortStopsFurtherEventDeliveryUntyped(String which) throws Exception {
    Path recording = fixture(which);
    AbortProbe probe = new AbortProbe(5);
    try (UntypedJafarParser p = UntypedJafarParser.open(recording.toString())) {
      HandlerRegistration<?> reg = p.handle((type, value, ctl) -> probe.onEvent(ctl));
      p.run();
      reg.destroy(p);
    }
    probe.assertAborted(recording.getFileName().toString(), countUntyped(recording));
  }

  @ParameterizedTest(name = "{displayName}[{0}]")
  @ValueSource(strings = {SINGLE, MULTI_CHUNK})
  void abortAllowsSubsequentRunsAfterReconfiguringHandlersUntyped(String which) throws Exception {
    Path recording = fixture(which);
    int total = countUntyped(recording);
    try (UntypedJafarParser p = UntypedJafarParser.open(recording.toString())) {
      AbortProbe probe = new AbortProbe(3);
      HandlerRegistration<?> r1 = p.handle((type, value, ctl) -> probe.onEvent(ctl));
      p.run();
      r1.destroy(p);
      probe.assertAborted(recording.getFileName().toString(), total);

      AtomicInteger second = new AtomicInteger();
      HandlerRegistration<?> r2 = p.handle((type, value, ctl) -> second.incrementAndGet());
      p.run();
      r2.destroy(p);
      assertEquals(total, second.get(), "a run after an aborted one must see every event");
    }
  }

  @ParameterizedTest(name = "{displayName}[{0}]")
  @ValueSource(strings = {SINGLE, MULTI_CHUNK})
  void abortStopsRemainingHandlersForTheSameEventUntyped(String which) throws Exception {
    // The typed parser has always stopped calling further handlers of an event once one aborts;
    // the untyped parser must do the same.
    Path recording = fixture(which);
    AtomicInteger first = new AtomicInteger();
    AtomicInteger second = new AtomicInteger();
    try (UntypedJafarParser p = UntypedJafarParser.open(recording.toString())) {
      // two handlers that both abort on their first event: whichever runs first must be the only
      // one ever called
      p.handle(
          (type, value, ctl) -> {
            first.incrementAndGet();
            ctl.abort();
          });
      p.handle(
          (type, value, ctl) -> {
            second.incrementAndGet();
            ctl.abort();
          });
      p.run();
    }
    assertEquals(
        1,
        first.get() + second.get(),
        "first=" + first.get() + " second=" + second.get() + " (one event, one handler)");
  }

  // ---- untyped (EventStreamGenerated) -------------------------------------------------------

  private static void runGenerated(Path recording, Consumer<Control> onEvent) throws Exception {
    ParsingContext ctx = ParsingContext.create();
    try (StreamingChunkParser parser =
        new StreamingChunkParser(((ParsingContextImpl) ctx).untypedContextFactory())) {
      parser.parse(
          recording,
          new EventStreamGenerated(null) {
            @Override
            protected void onEventValue(
                MetadataClass type, Map<String, Object> value, Control ctl) {
              onEvent.accept(ctl);
            }
          });
    }
  }

  @ParameterizedTest(name = "{displayName}[{0}]")
  @ValueSource(strings = {SINGLE, MULTI_CHUNK})
  void abortStopsFurtherEventDeliveryGeneratedUntyped(String which) throws Exception {
    Path recording = fixture(which);
    AtomicInteger total = new AtomicInteger();
    runGenerated(recording, ctl -> total.incrementAndGet());

    AbortProbe probe = new AbortProbe(5);
    runGenerated(recording, probe::onEvent);
    probe.assertAborted(recording.getFileName().toString(), total.get());
  }
}
