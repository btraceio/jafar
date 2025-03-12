package io.jafar.parser;

import io.jafar.parser.api.HandlerRegistration;
import io.jafar.parser.api.JafarParser;
import io.jafar.parser.api.JfrIgnore;
import io.jafar.parser.api.JfrType;
import io.jafar.parser.api.JFRHandler;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.internal_api.ParsingContextImpl;
import io.jafar.parser.internal_api.CheckpointEvent;
import io.jafar.parser.internal_api.ChunkHeader;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.RecordingParserContext;
import io.jafar.parser.internal_api.RecordingStream;
import io.jafar.parser.internal_api.StreamingChunkParser;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.internal_api.metadata.MetadataEvent;
import io.jafar.utils.CustomByteBuffer;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class JafarParserImpl implements JafarParser {
//    private record Handlers(MethodHandle ctr, MethodHandle skip) {}

    private final class HandlerRegistrationImpl<T> implements HandlerRegistration<T> {
        private final WeakReference<Class<?>> clzRef;
        private final WeakReference<JafarParser> cookieRef;
        HandlerRegistrationImpl(Class<?> clz, JafarParser cookie) {
            this.clzRef = new WeakReference<>(clz);
            this.cookieRef = new WeakReference<>(cookie);
        }

        @Override
        public void destroy(JafarParser cookie) {
            if (cookie != null && cookie.equals(cookieRef.get())) {
                Class<?> clz = clzRef.get();
                if (clz != null) {
                    handlerMap.remove(clz);

                    handlerMap.keySet().forEach(JafarParserImpl.this::addDeserializer);
                }
            }
        }
    }
    private final StreamingChunkParser parser;
    private final Path recording;

    private final Map<Class<?>, List<JFRHandler.Impl<?>>> handlerMap = new HashMap<>();
    private final Int2ObjectMap<Long2ObjectMap<Class<?>>> chunkTypeClassMap = new Int2ObjectOpenHashMap<>();

    private final Map<String, Class<?>> globalHandlerMap = new HashMap<>();

    private boolean closed = false;

    public JafarParserImpl(Path recording, ParsingContextImpl parsingContext) {
        this.parser = new StreamingChunkParser(parsingContext.newRecordingParserContext());
        this.recording = recording;
    }

    @Override
    public <T> HandlerRegistration<T> handle(Class<T> clz, JFRHandler<T> handler) {
        addDeserializer(clz);
        handlerMap.computeIfAbsent(clz, k -> new ArrayList<>()).add(new JFRHandler.Impl<>(clz, handler));

        return new HandlerRegistrationImpl<>(clz, this);
    }

    private void addDeserializer(Class<?> clz) {
        if (clz.isArray()) {
            clz = clz.getComponentType();
        }
        boolean isPrimitive = clz.isPrimitive() || clz.isAssignableFrom(String.class);

        if (!isPrimitive && !clz.isInterface()) {
            throw new RuntimeException("JFR type handler must be an interface: " + clz.getName());
        }
        String typeName = clz.getName();
        if (!isPrimitive) {
            JfrType typeAnnotation = clz.getAnnotation(JfrType.class);
            if (typeAnnotation == null) {
                throw new RuntimeException("JFR type annotation missing on class: " + clz.getName());
            }
            typeName = typeAnnotation.value();
        }

        if (globalHandlerMap.containsKey(typeName)) {
            return;
        }
        globalHandlerMap.put(typeName, clz);
        if (!isPrimitive) {
            Class<?> superClass = clz.getSuperclass();
            if (superClass != null && superClass.isInterface()) {
                addDeserializer(superClass);
            }
            for (Method m : clz.getMethods()) {
                if (m.getAnnotation(JfrIgnore.class) == null) {
                    addDeserializer(m.getReturnType());
                }
            }
        }
    }

    @Override
    public void run() throws IOException {
        if (closed) {
            throw new IOException("Parser is closed");
        }
        // parse JFR and run handlers
        parser.parse(recording, new ChunkParserListener() {
            @Override
            public void onRecordingStart(RecordingParserContext context) {
                if (!globalHandlerMap.isEmpty()) {
                    context.setTypeFilter(t -> t!= null && globalHandlerMap.containsKey(t.getName()));
                }
            }

            @Override
            public boolean onChunkStart(int chunkIndex, ChunkHeader header, RecordingParserContext context) {
                if (!globalHandlerMap.isEmpty()) {
                    synchronized (this) {
                        context.setClassTypeMap(chunkTypeClassMap.computeIfAbsent(chunkIndex, k -> new Long2ObjectOpenHashMap<>()));
                        context.addTargetTypeMap(globalHandlerMap);
                    }
                    return true;
                }
                return false;
            }

            @Override
            public boolean onChunkEnd(int chunkIndex, boolean skipped) {
                return true;
            }

            @Override
            public boolean onMetadata(MetadataEvent metadata) {
                Long2ObjectMap<Class<?>> typeClassMap = metadata.getContext().getClassTypeMap();

                RecordingParserContext context = metadata.getContext();
                // typeClassMap must be fully initialized before trying to resolve/generate the handlers
                for (MetadataClass clz : metadata.getClasses()) {
                    Class<?> targetClass = context.getClassTargetType(clz.getName());
                    if (targetClass != null) {
                        typeClassMap.putIfAbsent(clz.getId(), targetClass);
                    }
                }

                return true;
            }

            @Override
            public boolean onCheckpoint(CheckpointEvent checkpoint) {
                return ChunkParserListener.super.onCheckpoint(checkpoint);
            }

            @Override
            public boolean onEvent(long typeId, RecordingStream stream, long payloadSize) {
                Long2ObjectMap<Class<?>> typeClassMap = stream.getContext().getClassTypeMap();
                Class<?> typeClz = typeClassMap.get(typeId);
                if (typeClz != null) {
                    if (handlerMap.containsKey(typeClz)) {
                        MetadataClass clz = stream.getContext().getMetadataLookup().getClass(typeId);
                        Object deserialized = clz.read(stream);
                        for (JFRHandler.Impl<?> handler : handlerMap.get(typeClz)) {
                            handler.handle(deserialized, null);
                        }
                    }
                }
                return true;
            };
        });
    }

    @Override
    public void close() throws Exception {
        if (!closed) {
            closed = true;

            parser.close();
            chunkTypeClassMap.clear();
            handlerMap.clear();
            globalHandlerMap.clear();
        }
    }

    private static CustomByteBuffer openJfrStream(Path jfrFile) {
        try {
            return CustomByteBuffer.map(jfrFile, Integer.MAX_VALUE);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
