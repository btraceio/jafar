package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.ConstantPools;
import io.jafar.parser.api.HandlerFactory;
import io.jafar.parser.api.JfrField;
import io.jafar.parser.api.JfrType;
import io.jafar.parser.api.MetadataLookup;
import io.jafar.parser.api.TypedJafarParser;
import io.jafar.parser.internal_api.RecordingStream;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openjdk.jmc.flightrecorder.writer.api.Recording;
import org.openjdk.jmc.flightrecorder.writer.api.Recordings;
import org.openjdk.jmc.flightrecorder.writer.api.Type;
import org.openjdk.jmc.flightrecorder.writer.api.Types;

/**
 * Two interfaces annotated with the same {@link JfrType} in one parser.
 *
 * <p>The parser keeps one interface per JFR type. A build-time factory claimed its type for its own
 * interface as soon as it was registered (every factory {@code ServiceLoader} finds is registered
 * when a parser is created), and {@code handle} kept whichever interface was there first. So {@code
 * handle(B.class, h)} for a type a factory of interface {@code A} had claimed, or that an earlier
 * {@code handle(A.class, ...)} had claimed, returned normally and {@code h} was never called:
 * events are dispatched to the handlers of the claiming interface only. Now a factory decodes its
 * type only for the interface the handlers use, and a second interface for a type is rejected by
 * {@code handle}. {@link DiscoveredHandlerFactoryTest} covers the factory found by {@code
 * ServiceLoader}.
 */
public class HandlerInterfaceConflictTest {
  static final String SAMPLE = "test.Sample";
  static final String OTHER = "test.Other";
  static final String LABEL = "test.SampleLabel";

  /** Events of each of {@value #SAMPLE} and {@value #OTHER}; {@code seq} runs 0 to N - 1. */
  static final int EVENTS = 40;

  /**
   * What {@link SampleAFactory} returns for {@code seq()}, never a {@code seq} in the recording.
   */
  static final int FROM_FACTORY = -1;

  @JfrType(SAMPLE)
  public interface SampleA {
    int seq();
  }

  @JfrType(SAMPLE)
  public interface SampleB {
    int seq();
  }

  @JfrType(LABEL)
  public interface LabelA {
    String name();
  }

  @JfrType(LABEL)
  public interface LabelB {
    String name();
  }

  @JfrType(SAMPLE)
  public interface SampleWithLabelA {
    int seq();

    LabelA label();
  }

  @JfrType(OTHER)
  public interface OtherWithLabelA {
    int seq();

    LabelA label();
  }

  @JfrType(OTHER)
  public interface OtherWithLabelB {
    int seq();

    LabelB label();
  }

  /**
   * Stands in for a factory generated for {@link SampleA}: it decodes nothing, and every event it
   * returns has {@code seq() == FROM_FACTORY}, so its events cannot pass for decoded ones.
   */
  public static final class SampleAFactory implements HandlerFactory<SampleA> {
    private static final SampleA FIXED = new FixedSampleA();

    @Override
    public void bind(MetadataLookup metadata) {}

    @Override
    public SampleA get(RecordingStream stream, MetadataClass metadata, ConstantPools pools) {
      return FIXED;
    }

    @Override
    public SampleA create(RecordingStream stream, MetadataClass metadata, ConstantPools pools) {
      return FIXED;
    }

    @Override
    public String getJfrTypeName() {
      return SAMPLE;
    }

    @Override
    public Class<SampleA> getInterfaceClass() {
      return SampleA.class;
    }
  }

  /** {@value #SAMPLE} reaching {@value #LABEL} through two interfaces. */
  @JfrType(SAMPLE)
  public interface SampleWithTwoLabelViews {
    LabelA label();

    @JfrField("label")
    LabelB sameLabel();
  }

  /** A label view whose accessor a generated handler supports and the runtime path rejects. */
  @JfrType(LABEL)
  public interface BoxedLabel {
    Integer id();
  }

  @JfrType(SAMPLE)
  public interface SampleWithBoxedLabel {
    int seq();

    BoxedLabel label();
  }

  /** A stand-in factory for {@code iface} that decodes nothing and returns {@code value}. */
  static final class FixedFactory<T> implements HandlerFactory<T> {
    private final Class<T> iface;
    private final String type;
    private final T value;

    FixedFactory(Class<T> iface, String type, T value) {
      this.iface = iface;
      this.type = type;
      this.value = value;
    }

    @Override
    public void bind(MetadataLookup metadata) {}

    @Override
    public T get(RecordingStream stream, MetadataClass metadata, ConstantPools pools) {
      return value;
    }

    @Override
    public T create(RecordingStream stream, MetadataClass metadata, ConstantPools pools) {
      return value;
    }

    @Override
    public String getJfrTypeName() {
      return type;
    }

    @Override
    public Class<T> getInterfaceClass() {
      return iface;
    }
  }

  /** What {@link SampleAFactory} returns. */
  public static final class FixedSampleA implements SampleA {
    @Override
    public int seq() {
      return FROM_FACTORY;
    }
  }

  @TempDir Path dir;

  /** The {@code seq} values every event of one type carries, in order. */
  static List<Integer> allSeqs() {
    return IntStream.range(0, EVENTS).boxed().collect(Collectors.toList());
  }

  /**
   * {@value #EVENTS} {@value #SAMPLE} and {@value #EVENTS} {@value #OTHER} events, each with a
   * {@code seq} and a pooled {@value #LABEL} named {@code "label-<seq>"}.
   */
  static Path writeRecording(Path dir) throws Exception {
    Path jfr = dir.resolve("samples.jfr");
    try (Recording r = Recordings.newRecording(jfr)) {
      Types types = r.getTypes();
      // Two fields: a type with one field can be written as a simple type, which the parser
      // unwraps to that field's value.
      Type label =
          types.getOrAdd(
              LABEL,
              t -> t.addField("name", Types.Builtin.STRING).addField("id", Types.Builtin.INT));
      Type sample =
          r.registerEventType(
              SAMPLE, t -> t.addField("seq", Types.Builtin.INT).addField("label", label));
      Type other =
          r.registerEventType(
              OTHER, t -> t.addField("seq", Types.Builtin.INT).addField("label", label));
      for (int i = 0; i < EVENTS; i++) {
        for (Type event : new Type[] {sample, other}) {
          int seq = i;
          r.writeEvent(
              event.asValue(
                  v -> {
                    v.putField("stackTrace", types.getType(Types.JDK.STACK_TRACE).nullValue());
                    v.putField("eventThread", types.getType(Types.JDK.THREAD).nullValue());
                    v.putField("startTime", System.nanoTime());
                    v.putField("seq", seq);
                    v.putField(
                        "label",
                        label.asValue(l -> l.putField("name", "label-" + seq).putField("id", seq)));
                  }));
        }
      }
    }
    return jfr;
  }

  private static List<Integer> sorted(List<Integer> values) {
    List<Integer> copy = new ArrayList<>(values);
    Collections.sort(copy);
    return copy;
  }

  @Test
  void aFactoryForAnotherInterfaceOfTheTypeDoesNotSilenceTheHandler() throws Exception {
    Path jfr = writeRecording(dir);
    List<Integer> seqs = Collections.synchronizedList(new ArrayList<>());
    try (TypedJafarParser p = TypedJafarParser.open(jfr)) {
      p.registerFactory(new SampleAFactory());
      p.handle(SampleB.class, (e, ctl) -> seqs.add(e.seq()));
      p.run();
    }
    // Was: no event at all. The factory had claimed test.Sample for SampleA, the SampleB handler
    // was registered under SampleB, and events of test.Sample were dispatched to SampleA's.
    assertEquals(allSeqs(), sorted(seqs), "seq of every test.Sample event the SampleB handler got");
  }

  @Test
  void aFactoryForTheHandledInterfaceDecodesTheType() throws Exception {
    Path jfr = writeRecording(dir);
    List<Integer> seqs = Collections.synchronizedList(new ArrayList<>());
    try (TypedJafarParser p = TypedJafarParser.open(jfr)) {
      p.registerFactory(new SampleAFactory());
      p.handle(SampleA.class, (e, ctl) -> seqs.add(e.seq()));
      p.run();
    }
    assertEquals(Collections.nCopies(EVENTS, FROM_FACTORY), seqs, "events from SampleAFactory");
  }

  @Test
  void aSecondInterfaceForTheSameTypeIsRejected() throws Exception {
    Path jfr = writeRecording(dir);
    List<Integer> seqs = Collections.synchronizedList(new ArrayList<>());
    try (TypedJafarParser p = TypedJafarParser.open(jfr)) {
      p.handle(SampleA.class, (e, ctl) -> seqs.add(e.seq()));
      IllegalArgumentException thrown =
          assertThrows(
              IllegalArgumentException.class,
              () -> p.handle(SampleB.class, (e, ctl) -> seqs.add(1000 + e.seq())));
      String message = thrown.getMessage();
      assertTrue(message.contains("'" + SAMPLE + "'"), message);
      assertTrue(message.contains(SampleA.class.getName()), message);
      assertTrue(message.contains(SampleB.class.getName()), message);
      p.run();
    }
    // The first registration is untouched by the rejected one.
    assertEquals(allSeqs(), sorted(seqs), "seq of every event the SampleA handler got");
  }

  @Test
  void aSecondInterfaceForAReferencedTypeIsRejectedAndNothingOfItIsRegistered() throws Exception {
    Path jfr = writeRecording(dir);
    List<String> got = Collections.synchronizedList(new ArrayList<>());
    try (TypedJafarParser p = TypedJafarParser.open(jfr)) {
      p.handle(SampleWithLabelA.class, (e, ctl) -> got.add("sample " + e.label().name()));
      IllegalArgumentException thrown =
          assertThrows(
              IllegalArgumentException.class,
              () -> p.handle(OtherWithLabelB.class, (e, ctl) -> got.add("B " + e.label().name())));
      String message = thrown.getMessage();
      assertTrue(message.contains("'" + LABEL + "'"), message);
      assertTrue(message.contains("OtherWithLabelB.label()"), message);
      assertTrue(message.contains(LabelA.class.getName()), message);
      assertTrue(message.contains(LabelB.class.getName()), message);
      // Nothing of the rejected interface stayed registered: test.Other can still go to another
      // interface, here one that agrees on test.SampleLabel.
      p.handle(OtherWithLabelA.class, (e, ctl) -> got.add("other " + e.label().name()));
      p.run();
    }
    List<String> expected = new ArrayList<>();
    for (int i = 0; i < EVENTS; i++) {
      expected.add("other label-" + i);
      expected.add("sample label-" + i);
    }
    Collections.sort(expected);
    List<String> actual = new ArrayList<>(got);
    Collections.sort(actual);
    assertEquals(expected, actual);
  }

  @Test
  void oneInterfaceUsedByTwoHandlersAndTwoInterfacesIsAccepted() throws Exception {
    Path jfr = writeRecording(dir);
    List<String> got = Collections.synchronizedList(new ArrayList<>());
    try (TypedJafarParser p = TypedJafarParser.open(jfr)) {
      p.handle(SampleWithLabelA.class, (e, ctl) -> got.add("1 " + e.label().name()));
      p.handle(SampleWithLabelA.class, (e, ctl) -> got.add("2 " + e.label().name()));
      p.handle(OtherWithLabelA.class, (e, ctl) -> got.add("3 " + e.label().name()));
      p.run();
    }
    assertEquals(3 * EVENTS, got.size(), "events delivered");
    for (int prefix = 1; prefix <= 3; prefix++) {
      for (int i = 0; i < EVENTS; i++) {
        String expected = prefix + " label-" + i;
        assertTrue(got.contains(expected), expected + " missing");
      }
    }
  }

  @Test
  void oneInterfaceReachingATypeThroughTwoInterfacesIsRejectedAndNothingIsRegistered()
      throws Exception {
    Path jfr = writeRecording(dir);
    List<Integer> seqs = Collections.synchronizedList(new ArrayList<>());
    try (TypedJafarParser p = TypedJafarParser.open(jfr)) {
      IllegalArgumentException thrown =
          assertThrows(
              IllegalArgumentException.class,
              () -> p.handle(SampleWithTwoLabelViews.class, (e, ctl) -> {}));
      String message = thrown.getMessage();
      assertTrue(message.contains("'" + LABEL + "'"), message);
      assertTrue(message.contains(LabelA.class.getName()), message);
      assertTrue(message.contains(LabelB.class.getName()), message);
      // Nothing was registered before the clash, so nothing "already" decodes the type.
      assertTrue(
          message.contains("also reaches the type through SampleWithTwoLabelViews."), message);
      assertTrue(!message.contains("already decodes"), message);
      // test.Sample was not claimed by the rejected interface.
      p.handle(SampleA.class, (e, ctl) -> seqs.add(e.seq()));
      p.run();
    }
    assertEquals(allSeqs(), sorted(seqs), "seq of every event the SampleA handler got");
  }

  @Test
  void aFactoryForAReferencedTypeIsUsedOnlyForItsInterface() throws Exception {
    Path jfr = writeRecording(dir);
    LabelA fromFactory = () -> "from-factory";
    List<String> got = Collections.synchronizedList(new ArrayList<>());
    try (TypedJafarParser p = TypedJafarParser.open(jfr)) {
      p.registerFactory(new FixedFactory<>(LabelA.class, LABEL, fromFactory));
      p.handle(SampleWithLabelA.class, (e, ctl) -> got.add(e.label().name()));
      p.run();
    }
    assertEquals(Collections.nCopies(EVENTS, "from-factory"), got, "LabelA values");

    got.clear();
    try (TypedJafarParser p = TypedJafarParser.open(jfr)) {
      p.registerFactory(new FixedFactory<>(LabelA.class, LABEL, fromFactory));
      p.handle(OtherWithLabelB.class, (e, ctl) -> got.add(e.label().name()));
      p.run();
    }
    List<String> expected = new ArrayList<>();
    for (int i = 0; i < EVENTS; i++) {
      expected.add("label-" + i);
    }
    Collections.sort(expected);
    List<String> actual = new ArrayList<>(got);
    Collections.sort(actual);
    assertEquals(expected, actual, "LabelB values, decoded at runtime");
  }

  @Test
  void aFactoryServedInterfaceMayReachAnInterfaceOnlyAGeneratedHandlerCanRead() throws Exception {
    // BoxedLabel.id() returns Integer, which the runtime path rejects; the generated handler of
    // SampleWithBoxedLabel reads an inline label with the generated handler of BoxedLabel, so
    // handle() must not demand a runtime-decodable BoxedLabel when no factory for it is registered.
    Path jfr = writeRecording(dir);
    SampleWithBoxedLabel fixed =
        new SampleWithBoxedLabel() {
          @Override
          public int seq() {
            return FROM_FACTORY;
          }

          @Override
          public BoxedLabel label() {
            return null;
          }
        };
    List<Integer> seqs = Collections.synchronizedList(new ArrayList<>());
    try (TypedJafarParser p = TypedJafarParser.open(jfr)) {
      p.registerFactory(new FixedFactory<>(SampleWithBoxedLabel.class, SAMPLE, fixed));
      p.handle(SampleWithBoxedLabel.class, (e, ctl) -> seqs.add(e.seq()));
      p.run();
    }
    assertEquals(Collections.nCopies(EVENTS, FROM_FACTORY), seqs, "events from the factory");
  }
}
