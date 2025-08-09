package io.jafar.parser.internal_api;

import io.jafar.parser.ParsingUtils;
import io.jafar.parser.api.JafarSerializationException;
import io.jafar.parser.internal_api.metadata.MetadataClass;

import java.lang.invoke.MethodHandle;
import java.util.Map;

public abstract class Deserializer<T> {
    private static final Deserializer<String> UTF8_STRING = new Deserializer<>() {
        @Override
        public void skip(RecordingStream stream) throws Exception {
            ParsingUtils.skipUTF8(stream);
        }

        @Override
        public String deserialize(RecordingStream stream) throws Exception {
            return ParsingUtils.readUTF8(stream);
        }
    };
    private static final Deserializer<?> VARINT = new Deserializer<>() {
        @Override
        public void skip(RecordingStream stream) throws Exception {
            stream.readVarint();
        }

        @Override
        public Object deserialize(RecordingStream stream) throws Exception {
            throw new UnsupportedOperationException();
        }
    };
    private static final Deserializer<?> FLOAT = new Deserializer<>() {
        @Override
        public void skip(RecordingStream stream) throws Exception {
            stream.readFloat();
        }

        @Override
        public Object deserialize(RecordingStream stream) throws Exception {
            throw new UnsupportedOperationException();
        }
    };
    private static final Deserializer<?> DOUBLE = new Deserializer<>() {
        @Override
        public void skip(RecordingStream stream) throws Exception {
            stream.readDouble();
        }

        @Override
        public Object deserialize(RecordingStream stream) throws Exception {
            throw new UnsupportedOperationException();
        }
    };
    private static final Deserializer<?> BYTE = new Deserializer<>() {
        @Override
        public void skip(RecordingStream stream) throws Exception {
            stream.read();
        }

        @Override
        public Object deserialize(RecordingStream stream) throws Exception {
            throw new UnsupportedOperationException();
        }
    };
    private static final Map<String, Deserializer<?>> DESERIALIZERS = Map.of(
            "java.lang.String", UTF8_STRING,
            "short", VARINT,
            "char", VARINT,
            "int", VARINT,
            "long", VARINT,
            "double", DOUBLE,
            "float", FLOAT,
            "byte", BYTE,
            "boolean", BYTE
    );

    public static final class Generated<T> extends Deserializer<T> {
        private final MethodHandle skipHandler;
        private final MethodHandle deserializeHandler;
        private final TypeSkipper typeSkipper;

        public Generated(MethodHandle deserializeHandler, MethodHandle skipHandler, TypeSkipper skipper) {
            this.deserializeHandler = deserializeHandler;
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
                if (deserializeHandler == null) {
                    // no deserialize method, skip
                    skip(stream);
                    // no value to return
                    return null;
                }
                return (T)deserializeHandler.invoke(stream);
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        }
    }

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

    public abstract void skip(RecordingStream stream) throws Exception;
    public abstract T deserialize(RecordingStream stream) throws Exception;
}
