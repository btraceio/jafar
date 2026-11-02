package io.jafar.parser.impl;

import static io.jafar.parser.impl.NodePoolFixture.EVENT;
import static io.jafar.parser.impl.NodePoolFixture.NODE_REFS;
import static io.jafar.parser.impl.NodePoolFixture.T_UNDECLARED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.ComplexType;
import io.jafar.parser.api.Control;
import io.jafar.parser.api.EventDecodingException;
import io.jafar.parser.api.EventIterator;
import io.jafar.parser.api.JafarRecordedEvent;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.impl.NodePoolFixture.Shape;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.StreamingChunkParser;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Constant pool references from pooled fields other than strings, when the pool is damaged or the
 * reference does not resolve. The recordings are {@link NodePoolFixture}'s, which the Go parser's
 * tests read too (go-parser/jfr/testdata).
 *
 * <p>Java fails the parse for any constant pool it cannot index completely - an entry that cannot
 * be skipped, or a pool whose type the chunk does not declare - so no event ever sees a partially
 * indexed pool. Go instead keeps what was indexed and fails each event that refers to the rest; see
 * go-parser/README.md.
 */
class UnindexedPoolReferenceTest {
  @TempDir Path dir;

  /** The three generic value paths; only {@link #DEFAULT} is reachable from the public API. */
  enum Stream {
    DEFAULT,
    LAZY,
    BASELINE;

    ChunkParserListener listener(List<String> seen, List<EventDecodingException> skipped) {
      switch (this) {
        case DEFAULT:
          return new EventStream(null) {
            @Override
            protected void onEventValue(MetadataClass t, Map<String, Object> v, Control c) {
              collect(t, v, seen);
            }

            @Override
            protected boolean onDecodeError(EventDecodingException failure) {
              return skipped != null && skipped.add(failure);
            }
          };
        case LAZY:
          return new EventStreamLazy(null) {
            @Override
            protected void onEventValue(MetadataClass t, Map<String, Object> v, Control c) {
              collect(t, v, seen);
            }

            @Override
            protected boolean onDecodeError(EventDecodingException failure) {
              return skipped != null && skipped.add(failure);
            }
          };
        case BASELINE:
          return new EventStreamBaseline(null) {
            @Override
            protected void onEventValue(MetadataClass t, Map<String, Object> v, Control c) {
              collect(t, v, seen);
            }

            @Override
            protected boolean onDecodeError(EventDecodingException failure) {
              return skipped != null && skipped.add(failure);
            }
          };
        default:
          throw new AssertionError(this);
      }
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = Shape.class,
      names = {"UNREADABLE_ENTRY", "UNDECLARED_POOL"})
  void aPoolThatCannotBeIndexedFailsTheParseEvenWithADecodeErrorHandler(Shape shape)
      throws Exception {
    Path jfr = write(shape);
    for (boolean skipEvents : new boolean[] {false, true}) {
      List<String> seen = Collections.synchronizedList(new ArrayList<>());
      List<EventDecodingException> skipped = Collections.synchronizedList(new ArrayList<>());
      IOException thrown;
      try (UntypedJafarParser p = UntypedJafarParser.open(jfr)) {
        p.handle((type, value, ctl) -> collect(type, value, seen));
        if (skipEvents) {
          p.onDecodeError(skipped::add);
        }
        thrown = assertThrows(IOException.class, p::run, () -> shape + ": " + seen);
      }
      String ctx = shape + ", skipEvents=" + skipEvents;
      // Pools are read before any event of the chunk.
      assertEquals(Collections.emptyList(), seen, ctx);
      assertEquals(Collections.emptyList(), skipped, ctx);
      assertTrue(chain(thrown).contains(expectedFailure(shape)), ctx + chain(thrown));
    }
  }

  @ParameterizedTest
  @EnumSource(Stream.class)
  void everyGenericValuePathFailsOnAnUndeclaredPool(Stream stream) throws Exception {
    Path jfr = write(Shape.UNDECLARED_POOL);
    List<String> seen = Collections.synchronizedList(new ArrayList<>());
    IOException thrown =
        assertThrows(
            IOException.class,
            () -> {
              try (StreamingChunkParser p =
                  new StreamingChunkParser(
                      ((ParsingContextImpl) ParsingContext.create()).untypedContextFactory())) {
                p.parse(jfr, stream.listener(seen, new ArrayList<>()));
              }
            });
    assertEquals(Collections.emptyList(), seen, stream.toString());
    assertTrue(
        chain(thrown).contains(expectedFailure(Shape.UNDECLARED_POOL)), stream + chain(thrown));
  }

  /**
   * Without damage, a dangling non-zero reference resolves to {@code null}, as the JDK's {@code
   * ConstantMap} does, and so do the absent-value indices 0 and {@code Long.MIN_VALUE}. {@code
   * Long.MIN_VALUE} is also {@code LongLongHashMap}'s reserved key: before {@code
   * MutableConstantPool} answered it itself, resolving it threw {@code AssertionError} under {@code
   * -ea}.
   */
  @ParameterizedTest
  @EnumSource(Stream.class)
  void absentAndDanglingReferencesInAnIntactChunkResolveToNull(Stream stream) throws Exception {
    Path jfr = write(Shape.INTACT);
    List<String> seen = Collections.synchronizedList(new ArrayList<>());
    try (StreamingChunkParser p =
        new StreamingChunkParser(
            ((ParsingContextImpl) ParsingContext.create()).untypedContextFactory())) {
      p.parse(jfr, stream.listener(seen, null));
    }
    assertEquals(
        Arrays.asList(
            "0=node{name=e, next=null}",
            "1=null",
            "2=null",
            "3=node{name=a, next=node{name=c, next=null}}",
            "4=node{name=b, next=null}",
            "5=node{name=c, next=null}",
            "6=null"),
        seen,
        stream.toString());
  }

  private static final String DANGLING_NAME_FAILURE =
      "String constant 42 referenced, but the chunk has no java.lang.String pool";

  /**
   * An entry that cannot be decoded is decoded once: every later resolution rethrows the cached
   * failure. Before, {@code MutableConstantPool.getAsMap} cached only successes, so each resolution
   * built a new builder, reader and partial map and threw a new exception.
   */
  @Test
  void anUndecodableEntryFailsTheSameWayWithoutBeingDecodedAgain() throws Exception {
    Path jfr = write(Shape.DANGLING_NAME);
    List<RuntimeException> failures = Collections.synchronizedList(new ArrayList<>());
    try (UntypedJafarParser p = UntypedJafarParser.open(jfr)) {
      p.handle(
          (type, value, ctl) -> {
            if (!EVENT.equals(type.getName()) || NODE_REFS[seq(value)] != 1) {
              return;
            }
            ComplexType node = (ComplexType) value.get("node");
            for (int i = 0; i < 2; i++) {
              try {
                node.getValue();
              } catch (RuntimeException e) {
                failures.add(e);
              }
            }
          });
      p.run();
    }
    assertEquals(2, failures.size(), "both resolutions of entry 1 must fail: " + failures);
    assertTrue(chain(failures.get(0)).contains(DANGLING_NAME_FAILURE), chain(failures.get(0)));
    assertSame(failures.get(0), failures.get(1), "the second resolution decoded the entry again");
  }

  /**
   * The pull API decodes on the parser's thread and hands events over through a queue; the consumer
   * resolves references on its own thread, after the hand-off. An entry that cannot be decoded
   * fails there, the same way each time, and the iteration ends without a parsing error: the events
   * themselves decoded fine.
   */
  @Test
  void eventIteratorResolvesOnTheConsumerThread() throws Exception {
    Path jfr = write(Shape.DANGLING_NAME);
    List<String> seen = new ArrayList<>();
    List<RuntimeException> failures = new ArrayList<>();
    try (EventIterator it = EventIterator.open(jfr)) {
      while (it.hasNext()) {
        JafarRecordedEvent e = it.next();
        collect(e.type(), e.value(), seen);
        if (EVENT.equals(e.typeName()) && NODE_REFS[seq(e.value())] == 1) {
          for (int i = 0; i < 2; i++) {
            try {
              ((ComplexType) e.value().get("node")).getValue();
            } catch (RuntimeException ex) {
              failures.add(ex);
            }
          }
        }
      }
      assertEquals(null, it.getParsingError());
    }
    assertEquals(
        Arrays.asList(
            "0=node{name=e, next=null}",
            "1=null",
            "2=null",
            "3=error: " + DANGLING_NAME_FAILURE,
            "4=node{name=b, next=null}",
            "5=node{name=c, next=null}",
            "6=null"),
        seen);
    assertEquals(2, failures.size(), String.valueOf(failures));
    assertTrue(chain(failures.get(0)).contains(DANGLING_NAME_FAILURE), chain(failures.get(0)));
    assertSame(failures.get(0), failures.get(1), "resolved again on the consumer thread");
  }

  private static String expectedFailure(Shape shape) {
    switch (shape) {
      case UNREADABLE_ENTRY:
        return "Constant pool test.Node, index 2: Unexpected string constant id: 127";
      case UNDECLARED_POOL:
        return "Constant pool of type "
            + T_UNDECLARED
            + " (1 entries): the type is not declared in the chunk metadata";
      default:
        throw new AssertionError(shape);
    }
  }

  // --- helpers -------------------------------------------------------------------------------

  /**
   * Records what an event's {@code node} resolves to, as {@code seq=<summary>}. Resolved inside the
   * handler, while the chunk is being parsed.
   */
  private static void collect(MetadataClass type, Map<String, Object> value, List<String> sink) {
    if (!EVENT.equals(type.getName())) {
      return;
    }
    String resolved;
    try {
      resolved = describe(value.get("node"));
    } catch (RuntimeException e) {
      resolved = "error: " + rootMessage(e);
    }
    sink.add(seq(value) + "=" + resolved);
  }

  private static int seq(Map<String, Object> value) {
    return ((Number) value.get("seq")).intValue();
  }

  /** Renders a node reference, resolving it and its {@code next} chain. */
  private static String describe(Object ref) {
    Object v = ref instanceof ComplexType ? ((ComplexType) ref).getValue() : ref;
    if (v == null) {
      return "null";
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> m = (Map<String, Object>) v;
    return "node{name=" + m.get("name") + ", next=" + describe(m.get("next")) + "}";
  }

  private static String rootMessage(Throwable t) {
    Throwable c = t;
    while (c.getCause() != null) {
      c = c.getCause();
    }
    return c.getMessage();
  }

  private static String chain(Throwable t) {
    StringBuilder sb = new StringBuilder();
    for (Throwable c = t; c != null; c = c.getCause()) {
      sb.append("\n  ").append(c);
    }
    return sb.toString();
  }

  // --- the recording -------------------------------------------------------------------------

  private Path write(Shape shape) throws IOException {
    Path out = dir.resolve(shape.fileName());
    Files.write(out, NodePoolFixture.bytes(shape));
    return out;
  }
}
