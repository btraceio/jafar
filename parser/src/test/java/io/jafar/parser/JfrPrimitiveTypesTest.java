package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.JfrField;
import io.jafar.parser.api.JfrType;
import io.jafar.parser.api.TypedJafarParser;
import io.jafar.parser.api.UntypedJafarParser;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for parsing all primitive field types in JFR events. */
public class JfrPrimitiveTypesTest {

  @TempDir Path tempDir;

  // Typed interfaces for various primitive events

  @JfrType("test.ByteEvent")
  public interface ByteEvent {
    @JfrField("value")
    byte value();
  }

  @JfrType("test.ShortEvent")
  public interface ShortEvent {
    @JfrField("value")
    short value();
  }

  @JfrType("test.IntEvent")
  public interface IntEvent {
    @JfrField("value")
    int value();
  }

  @JfrType("test.LongEvent")
  public interface LongEvent {
    @JfrField("value")
    long value();
  }

  @JfrType("test.FloatEvent")
  public interface FloatEvent {
    @JfrField("value")
    float value();
  }

  @JfrType("test.DoubleEvent")
  public interface DoubleEvent {
    @JfrField("value")
    double value();
  }

  @JfrType("test.BooleanEvent")
  public interface BooleanEvent {
    @JfrField("value")
    boolean value();
  }

  @JfrType("test.CharEvent")
  public interface CharEvent {
    @JfrField("value")
    char value();
  }

  @JfrType("test.StringEvent")
  public interface StringEvent {
    @JfrField("value")
    String value();
  }

  // Note: stringVal excluded due to JMC Writer string encoding incompatibility
  @JfrType("test.AllPrimitivesEvent")
  public interface AllPrimitivesEvent {
    @JfrField("byteVal")
    byte byteVal();

    @JfrField("shortVal")
    short shortVal();

    @JfrField("intVal")
    int intVal();

    @JfrField("longVal")
    long longVal();

    @JfrField("floatVal")
    float floatVal();

    @JfrField("doubleVal")
    double doubleVal();

    @JfrField("booleanVal")
    boolean booleanVal();

    @JfrField("charVal")
    char charVal();
  }

  // BYTE TESTS

  @Test
  void parsesByteField_typed() throws Exception {
    Path jfrFile = tempDir.resolve("byte.jfr");

    JfrTestHelper.create(jfrFile)
        .eventType("test.ByteEvent")
        .byteField("value")
        .event((byte) 42)
        .event(Byte.MIN_VALUE)
        .event(Byte.MAX_VALUE)
        .event((byte) 0)
        .event((byte) -1)
        .build();

    AtomicInteger index = new AtomicInteger(0);
    byte[] expected = {42, Byte.MIN_VALUE, Byte.MAX_VALUE, 0, -1};

    try (TypedJafarParser parser = TypedJafarParser.open(jfrFile.toString())) {
      parser.handle(
          ByteEvent.class,
          (event, ctl) -> {
            int i = index.getAndIncrement();
            assertEquals(expected[i], event.value(), "Byte value at index " + i);
          });
      parser.run();
    }

    assertEquals(5, index.get());
  }

  @Test
  void parsesByteField_untyped() throws Exception {
    Path jfrFile = tempDir.resolve("byte-untyped.jfr");

    JfrTestHelper.create(jfrFile)
        .eventType("test.ByteEvent")
        .byteField("value")
        .event((byte) 42)
        .build();

    AtomicReference<Object> valueRef = new AtomicReference<>();

    try (UntypedJafarParser parser = UntypedJafarParser.open(jfrFile.toString())) {
      parser.handle(
          (type, event, ctl) -> {
            if (type.getName().equals("test.ByteEvent")) {
              valueRef.set(event.get("value"));
            }
          });
      parser.run();
    }

    assertEquals((byte) 42, ((Number) valueRef.get()).byteValue());
  }

  // SHORT TESTS

  @Test
  void parsesShortField_typed() throws Exception {
    Path jfrFile = tempDir.resolve("short.jfr");

    JfrTestHelper.create(jfrFile)
        .eventType("test.ShortEvent")
        .shortField("value")
        .event((short) 1000)
        .event(Short.MIN_VALUE)
        .event(Short.MAX_VALUE)
        .build();

    AtomicInteger index = new AtomicInteger(0);
    short[] expected = {1000, Short.MIN_VALUE, Short.MAX_VALUE};

    try (TypedJafarParser parser = TypedJafarParser.open(jfrFile.toString())) {
      parser.handle(
          ShortEvent.class,
          (event, ctl) -> {
            int i = index.getAndIncrement();
            assertEquals(expected[i], event.value(), "Short value at index " + i);
          });
      parser.run();
    }

    assertEquals(3, index.get());
  }

  // INT TESTS

  @Test
  void parsesIntField_typed() throws Exception {
    Path jfrFile = tempDir.resolve("int.jfr");

    JfrTestHelper.create(jfrFile)
        .eventType("test.IntEvent")
        .intField("value")
        .event(123456)
        .event(Integer.MIN_VALUE)
        .event(Integer.MAX_VALUE)
        .event(0)
        .event(-1)
        .build();

    AtomicInteger index = new AtomicInteger(0);
    int[] expected = {123456, Integer.MIN_VALUE, Integer.MAX_VALUE, 0, -1};

    try (TypedJafarParser parser = TypedJafarParser.open(jfrFile.toString())) {
      parser.handle(
          IntEvent.class,
          (event, ctl) -> {
            int i = index.getAndIncrement();
            assertEquals(expected[i], event.value(), "Int value at index " + i);
          });
      parser.run();
    }

    assertEquals(5, index.get());
  }

  @Test
  void parsesIntField_untyped() throws Exception {
    Path jfrFile = tempDir.resolve("int-untyped.jfr");

    JfrTestHelper.create(jfrFile)
        .eventType("test.IntEvent")
        .intField("value")
        .event(999999)
        .build();

    AtomicReference<Object> valueRef = new AtomicReference<>();

    try (UntypedJafarParser parser = UntypedJafarParser.open(jfrFile.toString())) {
      parser.handle(
          (type, event, ctl) -> {
            if (type.getName().equals("test.IntEvent")) {
              valueRef.set(event.get("value"));
            }
          });
      parser.run();
    }

    assertEquals(999999, ((Number) valueRef.get()).intValue());
  }

  // LONG TESTS

  @Test
  void parsesLongField_typed() throws Exception {
    Path jfrFile = tempDir.resolve("long.jfr");

    JfrTestHelper.create(jfrFile)
        .eventType("test.LongEvent")
        .longField("value")
        .event(9876543210L)
        .event(Long.MIN_VALUE)
        .event(Long.MAX_VALUE)
        .build();

    AtomicInteger index = new AtomicInteger(0);
    long[] expected = {9876543210L, Long.MIN_VALUE, Long.MAX_VALUE};

    try (TypedJafarParser parser = TypedJafarParser.open(jfrFile.toString())) {
      parser.handle(
          LongEvent.class,
          (event, ctl) -> {
            int i = index.getAndIncrement();
            assertEquals(expected[i], event.value(), "Long value at index " + i);
          });
      parser.run();
    }

    assertEquals(3, index.get());
  }

  // FLOAT TESTS

  @Test
  void parsesFloatField_typed() throws Exception {
    Path jfrFile = tempDir.resolve("float.jfr");

    JfrTestHelper.create(jfrFile)
        .eventType("test.FloatEvent")
        .floatField("value")
        .event(3.14f)
        .event(Float.MIN_VALUE)
        .event(Float.MAX_VALUE)
        .event(0.0f)
        .event(-1.5f)
        .build();

    AtomicInteger index = new AtomicInteger(0);
    float[] expected = {3.14f, Float.MIN_VALUE, Float.MAX_VALUE, 0.0f, -1.5f};

    try (TypedJafarParser parser = TypedJafarParser.open(jfrFile.toString())) {
      parser.handle(
          FloatEvent.class,
          (event, ctl) -> {
            int i = index.getAndIncrement();
            assertEquals(expected[i], event.value(), 0.0001f, "Float value at index " + i);
          });
      parser.run();
    }

    assertEquals(5, index.get());
  }

  // DOUBLE TESTS

  @Test
  void parsesDoubleField_typed() throws Exception {
    Path jfrFile = tempDir.resolve("double.jfr");

    JfrTestHelper.create(jfrFile)
        .eventType("test.DoubleEvent")
        .doubleField("value")
        .event(3.141592653589793)
        .event(Double.MIN_VALUE)
        .event(Double.MAX_VALUE)
        .build();

    AtomicInteger index = new AtomicInteger(0);
    double[] expected = {3.141592653589793, Double.MIN_VALUE, Double.MAX_VALUE};

    try (TypedJafarParser parser = TypedJafarParser.open(jfrFile.toString())) {
      parser.handle(
          DoubleEvent.class,
          (event, ctl) -> {
            int i = index.getAndIncrement();
            assertEquals(expected[i], event.value(), 0.0000001, "Double value at index " + i);
          });
      parser.run();
    }

    assertEquals(3, index.get());
  }

  // BOOLEAN TESTS

  @Test
  void parsesBooleanField_typed() throws Exception {
    Path jfrFile = tempDir.resolve("boolean.jfr");

    JfrTestHelper.create(jfrFile)
        .eventType("test.BooleanEvent")
        .booleanField("value")
        .event(true)
        .event(false)
        .build();

    AtomicInteger index = new AtomicInteger(0);
    boolean[] expected = {true, false};

    try (TypedJafarParser parser = TypedJafarParser.open(jfrFile.toString())) {
      parser.handle(
          BooleanEvent.class,
          (event, ctl) -> {
            int i = index.getAndIncrement();
            assertEquals(expected[i], event.value(), "Boolean value at index " + i);
          });
      parser.run();
    }

    assertEquals(2, index.get());
  }

  @Test
  void parsesBooleanField_untyped() throws Exception {
    Path jfrFile = tempDir.resolve("boolean-untyped.jfr");

    JfrTestHelper.create(jfrFile)
        .eventType("test.BooleanEvent")
        .booleanField("value")
        .event(true)
        .build();

    AtomicReference<Object> valueRef = new AtomicReference<>();

    try (UntypedJafarParser parser = UntypedJafarParser.open(jfrFile.toString())) {
      parser.handle(
          (type, event, ctl) -> {
            if (type.getName().equals("test.BooleanEvent")) {
              valueRef.set(event.get("value"));
            }
          });
      parser.run();
    }

    assertTrue((Boolean) valueRef.get());
  }

  // CHAR TESTS

  @Test
  void parsesCharField_typed() throws Exception {
    Path jfrFile = tempDir.resolve("char.jfr");

    JfrTestHelper.create(jfrFile)
        .eventType("test.CharEvent")
        .charField("value")
        .event('A')
        .event(Character.MIN_VALUE)
        .event(Character.MAX_VALUE)
        .event('0')
        .build();

    AtomicInteger index = new AtomicInteger(0);
    char[] expected = {'A', Character.MIN_VALUE, Character.MAX_VALUE, '0'};

    try (TypedJafarParser parser = TypedJafarParser.open(jfrFile.toString())) {
      parser.handle(
          CharEvent.class,
          (event, ctl) -> {
            int i = index.getAndIncrement();
            assertEquals(expected[i], event.value(), "Char value at index " + i);
          });
      parser.run();
    }

    assertEquals(4, index.get());
  }

  // STRING TESTS
  // Note: String tests are disabled due to JMC Writer string encoding incompatibility
  // See ~/Documents/JMC-Writer-API-Issues.md for details

  @Disabled("JMC Writer string encoding incompatible with parser - see JMC-Writer-API-Issues.md")
  @Test
  void parsesStringField_typed() throws Exception {
    Path jfrFile = tempDir.resolve("string.jfr");

    JfrTestHelper.create(jfrFile)
        .eventType("test.StringEvent")
        .stringField("value")
        .event("Hello, World!")
        .event("")
        .event("Special chars: \t\n\r\u0000")
        .event("Unicode: \u00e9\u00e0\u00fc")
        .build();

    AtomicInteger index = new AtomicInteger(0);
    String[] expected = {
      "Hello, World!", "", "Special chars: \t\n\r\u0000", "Unicode: \u00e9\u00e0\u00fc"
    };

    try (TypedJafarParser parser = TypedJafarParser.open(jfrFile.toString())) {
      parser.handle(
          StringEvent.class,
          (event, ctl) -> {
            int i = index.getAndIncrement();
            assertEquals(expected[i], event.value(), "String value at index " + i);
          });
      parser.run();
    }

    assertEquals(4, index.get());
  }

  @Disabled("JMC Writer string encoding incompatible with parser - see JMC-Writer-API-Issues.md")
  @Test
  void parsesStringField_untyped() throws Exception {
    Path jfrFile = tempDir.resolve("string-untyped.jfr");

    JfrTestHelper.create(jfrFile)
        .eventType("test.StringEvent")
        .stringField("value")
        .event("test string")
        .build();

    AtomicReference<Object> valueRef = new AtomicReference<>();

    try (UntypedJafarParser parser = UntypedJafarParser.open(jfrFile.toString())) {
      parser.handle(
          (type, event, ctl) -> {
            if (type.getName().equals("test.StringEvent")) {
              valueRef.set(event.get("value"));
            }
          });
      parser.run();
    }

    assertEquals("test string", valueRef.get());
  }

  @Disabled("JMC Writer string encoding incompatible with parser - see JMC-Writer-API-Issues.md")
  @Test
  void parsesNullString_typed() throws Exception {
    Path jfrFile = tempDir.resolve("string-null.jfr");

    JfrTestHelper.create(jfrFile)
        .eventType("test.StringEvent")
        .stringField("value")
        .event(e -> e.putNull("value"))
        .build();

    AtomicInteger eventCount = new AtomicInteger(0);

    try (TypedJafarParser parser = TypedJafarParser.open(jfrFile.toString())) {
      parser.handle(
          StringEvent.class,
          (event, ctl) -> {
            eventCount.incrementAndGet();
            assertNull(event.value(), "Null string should be null");
          });
      parser.run();
    }

    assertEquals(1, eventCount.get());
  }

  // ALL PRIMITIVES IN ONE EVENT

  @Test
  void parsesAllPrimitivesInOneEvent_typed() throws Exception {
    Path jfrFile = tempDir.resolve("all-primitives.jfr");

    JfrTestHelper.create(jfrFile)
        .eventType("test.AllPrimitivesEvent")
        .byteField("byteVal")
        .shortField("shortVal")
        .intField("intVal")
        .longField("longVal")
        .floatField("floatVal")
        .doubleField("doubleVal")
        .booleanField("booleanVal")
        .charField("charVal")
        .event(
            e ->
                e.put("byteVal", (byte) 1)
                    .put("shortVal", (short) 2)
                    .put("intVal", 3)
                    .put("longVal", 4L)
                    .put("floatVal", 5.0f)
                    .put("doubleVal", 6.0)
                    .put("booleanVal", true)
                    .put("charVal", 'X'))
        .build();

    AtomicInteger eventCount = new AtomicInteger(0);

    try (TypedJafarParser parser = TypedJafarParser.open(jfrFile.toString())) {
      parser.handle(
          AllPrimitivesEvent.class,
          (event, ctl) -> {
            eventCount.incrementAndGet();
            assertEquals((byte) 1, event.byteVal());
            assertEquals((short) 2, event.shortVal());
            assertEquals(3, event.intVal());
            assertEquals(4L, event.longVal());
            assertEquals(5.0f, event.floatVal(), 0.001f);
            assertEquals(6.0, event.doubleVal(), 0.001);
            assertTrue(event.booleanVal());
            assertEquals('X', event.charVal());
          });
      parser.run();
    }

    assertEquals(1, eventCount.get());
  }

  @Test
  void parsesAllPrimitivesInOneEvent_untyped() throws Exception {
    Path jfrFile = tempDir.resolve("all-primitives-untyped.jfr");

    JfrTestHelper.create(jfrFile)
        .eventType("test.AllPrimitivesEvent")
        .byteField("byteVal")
        .shortField("shortVal")
        .intField("intVal")
        .longField("longVal")
        .floatField("floatVal")
        .doubleField("doubleVal")
        .booleanField("booleanVal")
        .charField("charVal")
        .event(
            e ->
                e.put("byteVal", (byte) 10)
                    .put("shortVal", (short) 20)
                    .put("intVal", 30)
                    .put("longVal", 40L)
                    .put("floatVal", 50.5f)
                    .put("doubleVal", 60.6)
                    .put("booleanVal", false)
                    .put("charVal", 'Y'))
        .build();

    AtomicReference<Map<String, Object>> eventRef = new AtomicReference<>();

    try (UntypedJafarParser parser = UntypedJafarParser.open(jfrFile.toString())) {
      parser.handle(
          (type, event, ctl) -> {
            if (type.getName().equals("test.AllPrimitivesEvent")) {
              eventRef.set(event);
            }
          });
      parser.run();
    }

    Map<String, Object> event = eventRef.get();
    assertEquals((byte) 10, ((Number) event.get("byteVal")).byteValue());
    assertEquals((short) 20, ((Number) event.get("shortVal")).shortValue());
    assertEquals(30, ((Number) event.get("intVal")).intValue());
    assertEquals(40L, ((Number) event.get("longVal")).longValue());
    assertEquals(50.5f, ((Number) event.get("floatVal")).floatValue(), 0.001f);
    assertEquals(60.6, ((Number) event.get("doubleVal")).doubleValue(), 0.001);
    assertFalse((Boolean) event.get("booleanVal"));
    // Char values may come back as Character or Number depending on parser implementation
    Object charVal = event.get("charVal");
    if (charVal instanceof Character) {
      assertEquals('Y', (char) charVal);
    } else {
      assertEquals((int) 'Y', ((Number) charVal).intValue());
    }
  }
}
