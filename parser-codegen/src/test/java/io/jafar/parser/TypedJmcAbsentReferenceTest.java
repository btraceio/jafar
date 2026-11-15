package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.jafar.parser.api.JfrField;
import io.jafar.parser.api.JfrType;
import io.jafar.parser.api.TypedJafarParser;
import io.jafar.parser.types.JFRThread;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openjdk.jmc.flightrecorder.writer.api.Recording;
import org.openjdk.jmc.flightrecorder.writer.api.Recordings;
import org.openjdk.jmc.flightrecorder.writer.api.Type;
import org.openjdk.jmc.flightrecorder.writer.api.Types;

/**
 * The JMC writer writes constant pool index {@code Long.MIN_VALUE} for an absent value, which is
 * also {@code LongLongHashMap}'s reserved key. The untyped side is covered by {@code
 * UnindexedPoolReferenceTest} in parser-core; this is the typed accessor: an event without a
 * thread, in a chunk that has a {@code java.lang.Thread} pool, reads its thread as {@code null}.
 */
public class TypedJmcAbsentReferenceTest {
  private static final String TYPE = "test.Mixed";

  @JfrType(TYPE)
  public interface Mixed {
    @JfrField("seq")
    int seq();

    @JfrField("eventThread")
    JFRThread eventThread();
  }

  @TempDir Path dir;

  @Test
  void absentReferenceIntoAnExistingPoolReadsAsNull() throws Exception {
    Path jfr = dir.resolve("mixed.jfr");
    try (Recording r = Recordings.newRecording(jfr)) {
      Types types = r.getTypes();
      Type thread = types.getType(Types.JDK.THREAD);
      Type event = r.registerEventType(TYPE, t -> t.addField("seq", Types.Builtin.INT));
      for (int i = 0; i < 2; i++) {
        int n = i;
        r.writeEvent(
            event.asValue(
                v -> {
                  v.putField("stackTrace", types.getType(Types.JDK.STACK_TRACE).nullValue());
                  v.putField(
                      "eventThread",
                      n == 0
                          ? thread.asValue(t -> t.putField("javaName", "main"))
                          : thread.nullValue());
                  v.putField("startTime", System.nanoTime());
                  v.putField("seq", n);
                }));
      }
    }
    List<String> seen = Collections.synchronizedList(new ArrayList<>());
    try (TypedJafarParser p = TypedJafarParser.open(jfr.toString())) {
      p.handle(
          Mixed.class,
          (e, ctl) -> {
            JFRThread t = e.eventThread();
            seen.add(e.seq() + " " + (t == null ? null : t.javaName()));
          });
      p.run();
    }
    assertEquals(List.of("0 main", "1 null"), seen);
  }
}
