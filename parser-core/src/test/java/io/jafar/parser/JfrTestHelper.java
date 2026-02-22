package io.jafar.parser;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.openjdk.jmc.flightrecorder.writer.api.Recording;
import org.openjdk.jmc.flightrecorder.writer.api.Recordings;
import org.openjdk.jmc.flightrecorder.writer.api.Type;
import org.openjdk.jmc.flightrecorder.writer.api.TypedValueBuilder;
import org.openjdk.jmc.flightrecorder.writer.api.Types;

/**
 * Helper class for creating JFR test files using the JMC Writer API.
 *
 * <p>This class works around a bug in the JMC Writer API where implicit event fields (stackTrace,
 * eventThread, startTime) are added to metadata but not automatically written to event data. The
 * helper ensures these fields are explicitly written with appropriate default values.
 */
public final class JfrTestHelper {

  private JfrTestHelper() {}

  /**
   * Creates a new JFR file builder.
   *
   * @param jfrFile the path to the JFR file to create
   * @return a new builder instance
   */
  public static JfrFileBuilder create(Path jfrFile) {
    return new JfrFileBuilder(jfrFile);
  }

  /** Builder for creating JFR files with events. */
  public static class JfrFileBuilder {
    private final Path jfrFile;
    private final List<EventTypeBuilder> eventTypes = new ArrayList<>();

    private JfrFileBuilder(Path jfrFile) {
      this.jfrFile = jfrFile;
    }

    /**
     * Defines an event type with the given name.
     *
     * @param eventTypeName the fully qualified event type name
     * @return a builder for configuring the event type
     */
    public EventTypeBuilder eventType(String eventTypeName) {
      EventTypeBuilder builder = new EventTypeBuilder(this, eventTypeName);
      eventTypes.add(builder);
      return builder;
    }

    /**
     * Writes the JFR file with all configured events.
     *
     * @throws Exception if writing fails
     */
    public void build() throws Exception {
      try (Recording recording = Recordings.newRecording(jfrFile)) {
        Types types = recording.getTypes();
        Type stackTraceType = types.getType(Types.JDK.STACK_TRACE);
        Type threadType = types.getType(Types.JDK.THREAD);

        for (EventTypeBuilder etb : eventTypes) {
          Type eventType =
              recording.registerEventType(
                  etb.name,
                  type -> {
                    for (FieldDef field : etb.fields) {
                      type.addField(field.name, field.builtinType);
                    }
                  });

          for (Map<String, Object> eventData : etb.events) {
            recording.writeEvent(
                eventType.asValue(
                    v -> {
                      // Write implicit event fields first (JMC Writer bug workaround)
                      v.putField("stackTrace", stackTraceType.nullValue());
                      v.putField("eventThread", threadType.nullValue());
                      v.putField("startTime", System.nanoTime());

                      // Write custom fields
                      for (Map.Entry<String, Object> entry : eventData.entrySet()) {
                        putFieldValue(v, entry.getKey(), entry.getValue());
                      }
                    }));
          }
        }
      }
    }

    private void putFieldValue(TypedValueBuilder v, String name, Object value) {
      if (value == null) {
        v.putField(name, (String) null);
      } else if (value instanceof Byte) {
        v.putField(name, (Byte) value);
      } else if (value instanceof Short) {
        v.putField(name, (Short) value);
      } else if (value instanceof Integer) {
        v.putField(name, (Integer) value);
      } else if (value instanceof Long) {
        v.putField(name, (Long) value);
      } else if (value instanceof Float) {
        v.putField(name, (Float) value);
      } else if (value instanceof Double) {
        v.putField(name, (Double) value);
      } else if (value instanceof Boolean) {
        v.putField(name, (Boolean) value);
      } else if (value instanceof Character) {
        v.putField(name, (Character) value);
      } else if (value instanceof String) {
        v.putField(name, (String) value);
      } else {
        throw new IllegalArgumentException("Unsupported field type: " + value.getClass());
      }
    }
  }

  /** Builder for configuring an event type. */
  public static class EventTypeBuilder {
    private final JfrFileBuilder parent;
    private final String name;
    private final List<FieldDef> fields = new ArrayList<>();
    private final List<Map<String, Object>> events = new ArrayList<>();

    private EventTypeBuilder(JfrFileBuilder parent, String name) {
      this.parent = parent;
      this.name = name;
    }

    /** Adds a byte field. */
    public EventTypeBuilder byteField(String name) {
      fields.add(new FieldDef(name, Types.Builtin.BYTE));
      return this;
    }

    /** Adds a short field. */
    public EventTypeBuilder shortField(String name) {
      fields.add(new FieldDef(name, Types.Builtin.SHORT));
      return this;
    }

    /** Adds an int field. */
    public EventTypeBuilder intField(String name) {
      fields.add(new FieldDef(name, Types.Builtin.INT));
      return this;
    }

    /** Adds a long field. */
    public EventTypeBuilder longField(String name) {
      fields.add(new FieldDef(name, Types.Builtin.LONG));
      return this;
    }

    /** Adds a float field. */
    public EventTypeBuilder floatField(String name) {
      fields.add(new FieldDef(name, Types.Builtin.FLOAT));
      return this;
    }

    /** Adds a double field. */
    public EventTypeBuilder doubleField(String name) {
      fields.add(new FieldDef(name, Types.Builtin.DOUBLE));
      return this;
    }

    /** Adds a boolean field. */
    public EventTypeBuilder booleanField(String name) {
      fields.add(new FieldDef(name, Types.Builtin.BOOLEAN));
      return this;
    }

    /** Adds a char field. */
    public EventTypeBuilder charField(String name) {
      fields.add(new FieldDef(name, Types.Builtin.CHAR));
      return this;
    }

    /** Adds a String field. */
    public EventTypeBuilder stringField(String name) {
      fields.add(new FieldDef(name, Types.Builtin.STRING));
      return this;
    }

    /**
     * Adds an event with the given field values. Field values should be provided in the same order
     * as field definitions.
     */
    public EventTypeBuilder event(Object... values) {
      if (values.length != fields.size()) {
        throw new IllegalArgumentException(
            "Expected " + fields.size() + " values, got " + values.length);
      }
      Map<String, Object> eventData = new LinkedHashMap<>();
      for (int i = 0; i < fields.size(); i++) {
        eventData.put(fields.get(i).name, values[i]);
      }
      events.add(eventData);
      return this;
    }

    /**
     * Adds an event using a consumer to set field values.
     *
     * @param eventBuilder consumer that receives an EventBuilder to set values
     */
    public EventTypeBuilder event(Consumer<EventBuilder> eventBuilder) {
      EventBuilder eb = new EventBuilder();
      eventBuilder.accept(eb);
      events.add(eb.values);
      return this;
    }

    /** Finishes this event type and returns to the parent builder. */
    public JfrFileBuilder done() {
      return parent;
    }

    /** Writes the JFR file (convenience method). */
    public void build() throws Exception {
      parent.build();
    }
  }

  /** Builder for setting event field values by name. */
  public static class EventBuilder {
    private final Map<String, Object> values = new LinkedHashMap<>();

    public EventBuilder put(String name, byte value) {
      values.put(name, value);
      return this;
    }

    public EventBuilder put(String name, short value) {
      values.put(name, value);
      return this;
    }

    public EventBuilder put(String name, int value) {
      values.put(name, value);
      return this;
    }

    public EventBuilder put(String name, long value) {
      values.put(name, value);
      return this;
    }

    public EventBuilder put(String name, float value) {
      values.put(name, value);
      return this;
    }

    public EventBuilder put(String name, double value) {
      values.put(name, value);
      return this;
    }

    public EventBuilder put(String name, boolean value) {
      values.put(name, value);
      return this;
    }

    public EventBuilder put(String name, char value) {
      values.put(name, value);
      return this;
    }

    public EventBuilder put(String name, String value) {
      values.put(name, value);
      return this;
    }

    public EventBuilder putNull(String name) {
      values.put(name, null);
      return this;
    }
  }

  private static class FieldDef {
    final String name;
    final Types.Builtin builtinType;

    FieldDef(String name, Types.Builtin builtinType) {
      this.name = name;
      this.builtinType = builtinType;
    }
  }
}
