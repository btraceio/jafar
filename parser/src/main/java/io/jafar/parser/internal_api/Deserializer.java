package io.jafar.parser.internal_api;

import io.jafar.parser.ParsingUtils;
import io.jafar.parser.api.JafarSerializationException;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import java.lang.invoke.MethodHandle;
import java.util.Map;

/**
 * Abstract base class for deserializing JFR data from recording streams.
 *
 * <p>This class provides the framework for converting raw byte data from JFR recordings into typed
 * Java objects. It includes built-in deserializers for common primitive types and supports custom
 * deserialization through generated implementations.
 *
 * @param <T> the type of object this deserializer produces
 */
public abstract class Deserializer<T> {
  /**
   * Protected constructor for Deserializer.
   *
   * <p>This abstract class provides the base for all deserializer implementations.
   */
  protected Deserializer() {}

  /** UTF-8 string deserializer implementation. */
  private static final Deserializer<String> UTF8_STRING =
      new Deserializer<>() {
        @Override
        public void skip(RecordingStream stream) throws Exception {
          ParsingUtils.skipUTF8(stream);
        }

        @Override
        public String deserialize(RecordingStream stream) throws Exception {
          return ParsingUtils.readUTF8(stream);
        }
      };

  private static final Deserializer<?> VARINT =
      new Deserializer<>() {
        @Override
        public void skip(RecordingStream stream) throws Exception {
          stream.readVarint();
        }

        @Override
        public Object deserialize(RecordingStream stream) throws Exception {
          throw new UnsupportedOperationException();
        }
      };
  private static final Deserializer<?> FLOAT =
      new Deserializer<>() {
        @Override
        public void skip(RecordingStream stream) throws Exception {
          stream.readFloat();
        }

        @Override
        public Object deserialize(RecordingStream stream) throws Exception {
          throw new UnsupportedOperationException();
        }
      };
  private static final Deserializer<?> DOUBLE =
      new Deserializer<>() {
        @Override
        public void skip(RecordingStream stream) throws Exception {
          stream.readDouble();
        }

        @Override
        public Object deserialize(RecordingStream stream) throws Exception {
          throw new UnsupportedOperationException();
        }
      };
  private static final Deserializer<?> BYTE =
      new Deserializer<>() {
        @Override
        public void skip(RecordingStream stream) throws Exception {
          stream.read();
        }

        @Override
        public Object deserialize(RecordingStream stream) throws Exception {
          throw new UnsupportedOperationException();
        }
      };
  private static final Map<String, Deserializer<?>> DESERIALIZERS =
      Map.of(
          "java.lang.String", UTF8_STRING,
          "short", VARINT,
          "char", VARINT,
          "int", VARINT,
          "long", VARINT,
          "double", DOUBLE,
          "float", FLOAT,
          "byte", BYTE,
          "boolean", BYTE);

  /**
   * Generated implementation of a deserializer.
   *
   * <p>This class provides a concrete implementation of Deserializer that uses method handles for
   * efficient deserialization and skipping operations.
   *
   * @param <T> the type of object this deserializer produces
   */
  public static final class Generated<T> extends Deserializer<T> {
    private final MethodHandle factoryHandle;
    private final MethodHandle skipHandler;
    private final TypeSkipper typeSkipper;

    /**
     * Constructs a new Generated deserializer.
     *
     * @param factoryHandle the method handle for creating new instances
     * @param skipHandler the method handle for skipping data
     * @param skipper the type skipper for complex types
     */
    public Generated(MethodHandle factoryHandle, MethodHandle skipHandler, TypeSkipper skipper) {
      this.factoryHandle = factoryHandle;
      this.skipHandler = skipHandler;
      this.typeSkipper = skipper;
    }

    @Override
    public void skip(RecordingStream stream) throws Exception {
      if (typeSkipper != null) {
        typeSkipper.skip(stream);
      } else if (skipHandler != null) {
        try {
          skipHandler.invokeExact(stream);
        } catch (Throwable t) {
          throw new RuntimeException(t);
        }
      } else {
        throw new RuntimeException("Unsupported skip handler type");
      }
    }

    @SuppressWarnings("unchecked")
    @Override
    public T deserialize(RecordingStream stream) throws Exception {
      try {
        if (factoryHandle == null) {
          // no deserialize method, skip
          skip(stream);
          // no value to return
          return null;
        }
        return (T) factoryHandle.invoke(stream);
      } catch (Throwable t) {
        throw new RuntimeException(t);
      }
    }
  }

  /**
   * Gets a deserializer for the specified metadata class.
   *
   * @param clazz the metadata class to get a deserializer for
   * @return a deserializer instance for the given class
   */
  public static Deserializer<?> forType(MetadataClass clazz) {
    if (clazz.isPrimitive()) {
      return DESERIALIZERS.get(clazz.getName());
    }
    try {
      return CodeGenerator.generateDeserializer(clazz);
    } catch (JafarSerializationException e) {
      throw new RuntimeException("Failed to generate deserializer for " + clazz.getName(), e);
    }
  }

  /**
   * Skips over the data for an object of this type without deserializing it.
   *
   * @param stream the recording stream to read from
   * @throws Exception if an error occurs during skipping
   */
  public abstract void skip(RecordingStream stream) throws Exception;

  /**
   * Deserializes an object of this type from the recording stream.
   *
   * @param stream the recording stream to read from
   * @return the deserialized object
   * @throws Exception if an error occurs during deserialization
   */
  public abstract T deserialize(RecordingStream stream) throws Exception;
}
