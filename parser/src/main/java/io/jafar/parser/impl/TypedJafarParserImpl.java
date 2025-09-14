package io.jafar.parser.impl;

import io.jafar.parser.api.Control;
import io.jafar.parser.api.HandlerRegistration;
import io.jafar.parser.api.JFRHandler;
import io.jafar.parser.api.JafarConfigurationException;
import io.jafar.parser.api.JafarIOException;
import io.jafar.parser.api.JafarParser;
import io.jafar.parser.api.JfrIgnore;
import io.jafar.parser.api.JfrType;
import io.jafar.parser.api.ParserContext;
import io.jafar.parser.api.TypedJafarParser;
import io.jafar.parser.internal_api.CheckpointEvent;
import io.jafar.parser.internal_api.ChunkHeader;
import io.jafar.parser.internal_api.ChunkParserListener;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Implementation of TypedJafarParser that provides type-safe JFR event parsing.
 *
 * <p>This class implements the typed JFR parser interface, allowing users to register handlers for
 * specific JFR event types. It automatically generates deserializers for registered event types and
 * manages the parsing lifecycle.
 */
public final class TypedJafarParserImpl implements TypedJafarParser {
  /**
   * Implementation of HandlerRegistration that manages handler lifecycle.
   *
   * @param <T> the type of JFR events this handler processes
   */
  private final class HandlerRegistrationImpl<T> implements HandlerRegistration<T> {
    private final WeakReference<Class<?>> clzRef;
    private final WeakReference<TypedJafarParser> cookieRef;
    private final JFRHandler.Impl<?> handler;

    /**
     * Constructs a new HandlerRegistrationImpl.
     *
     * @param clz the class type for the handler
     * @param handler the JFR handler implementation
     * @param cookie the parser instance that owns this registration
     */
    HandlerRegistrationImpl(Class<?> clz, JFRHandler.Impl<?> handler, TypedJafarParser cookie) {
      this.clzRef = new WeakReference<>(clz);
      this.handler = handler;
      this.cookieRef = new WeakReference<>(cookie);
    }

    /** {@inheritDoc} */
    @Override
    public void destroy(JafarParser cookie) {
      if (cookie != null && cookie.equals(cookieRef.get())) {
        Class<?> clz = clzRef.get();
        if (clz != null) {
          Set<JFRHandler.Impl<?>> handlers = handlerMap.get(clz);
          handlers.remove(handler);
          if (handlers.isEmpty()) {
            handlerMap.remove(clz);
          }
        }
      }
    }
  }

  /** Listener for chunk parsing events. */
  private final ChunkParserListener parserListener;

  /** Streaming chunk parser for processing JFR data. */
  private final StreamingChunkParser parser;

  /** Path to the JFR recording file. */
  private final Path recording;

  /** Map of event classes to their registered handlers. */
  private final Map<Class<?>, Set<JFRHandler.Impl<?>>> handlerMap;

  /** Map of chunk indices to type ID to class mappings. */
  private final Int2ObjectMap<Long2ObjectMap<Class<?>>> chunkTypeClassMap;

  /** Global map of event type names to handler classes. */
  private final Map<String, Class<?>> globalHandlerMap;

  /** Whether this parser has been closed. */
  private boolean closed = false;

  /**
   * Constructs a new TypedJafarParserImpl for the specified recording.
   *
   * @param recording the path to the JFR recording file
   * @param parsingContext the parsing context to use
   */
  public TypedJafarParserImpl(Path recording, ParsingContextImpl parsingContext) {
    this.parser = new StreamingChunkParser(parsingContext.typedContextFactory());
    this.recording = recording;
    this.handlerMap = new HashMap<>();
    this.chunkTypeClassMap = new Int2ObjectOpenHashMap<>();
    this.globalHandlerMap = new HashMap<>();
    this.parserListener = null;
  }

  private TypedJafarParserImpl(TypedJafarParserImpl other, ChunkParserListener listener) {
    this.parser = other.parser;
    this.recording = other.recording;
    this.handlerMap = new HashMap<>(other.handlerMap);
    this.chunkTypeClassMap = new Int2ObjectOpenHashMap<>(other.chunkTypeClassMap);
    this.globalHandlerMap = new HashMap<>(other.globalHandlerMap);
    this.closed = other.closed;
    this.parserListener = listener;
  }

  @Override
  public <T> HandlerRegistration<T> handle(Class<T> clz, JFRHandler<T> handler) {
    try {
      ValidationUtils.requireNonNull(clz, "clz");
      ValidationUtils.requireNonNull(handler, "handler");
      addDeserializer(clz);
      JFRHandler.Impl<T> handlerImpl = new JFRHandler.Impl<>(clz, handler);
      handlerMap.computeIfAbsent(clz, k -> new HashSet<>()).add(handlerImpl);

      return new HandlerRegistrationImpl<>(clz, handlerImpl, this);
    } catch (JafarConfigurationException e) {
      throw new RuntimeException(e);
    }
  }

  private void addDeserializer(Class<?> clz) throws JafarConfigurationException {
    if (clz.isArray()) {
      clz = clz.getComponentType();
    }

    ValidationUtils.validateJfrTypeHandler(clz);

    boolean isPrimitive = clz.isPrimitive() || String.class.equals(clz);
    String typeName = clz.getName();
    if (!isPrimitive) {
      JfrType typeAnnotation = clz.getAnnotation(JfrType.class);
      typeName = typeAnnotation.value();
    }

    if (globalHandlerMap.containsKey(typeName)) {
      return;
    }
    globalHandlerMap.put(typeName, clz);
    if (!isPrimitive) {
      for (Method m : clz.getMethods()) {
        if (m.getAnnotation(JfrIgnore.class) == null) {
          addDeserializer(m.getReturnType());
        }
      }
    }
  }

  @Override
  public void run() throws IOException {
    try {
      ValidationUtils.validateParserNotClosed(closed);
    } catch (JafarIOException e) {
      throw new IOException(e.getMessage(), e);
    }
    // parse JFR and run handlers
    parser.parse(
        recording,
        new ChunkParserListener() {
          private final ThreadLocal<Control> control = ThreadLocal.withInitial(ControlImpl::new);

          @Override
          public void onRecordingStart(ParserContext context) {
            if (!globalHandlerMap.isEmpty()) {
              ((TypedParserContext) context)
                  .setTypeFilter(t -> t != null && globalHandlerMap.containsKey(t.getName()));
            }
            if (parserListener != null) {
              parserListener.onRecordingStart(context);
            }
          }

          @Override
          public boolean onChunkStart(ParserContext context, int chunkIndex, ChunkHeader header) {
            if (!globalHandlerMap.isEmpty()) {
              TypedParserContext lCtx = (TypedParserContext) context;
              synchronized (this) {
                lCtx.setClassTypeMap(
                    chunkTypeClassMap.computeIfAbsent(
                        chunkIndex, k -> new Long2ObjectOpenHashMap<>()));
                lCtx.addTargetTypeMap(globalHandlerMap);
              }
              context.put(Control.ChunkInfo.class, new ChunkInfoImpl(header));
              ((ControlImpl) control.get()).setStream(context.get(RecordingStream.class));
              return parserListener == null
                  || parserListener.onChunkStart(context, chunkIndex, header);
            }
            return parserListener != null
                && parserListener.onChunkStart(context, chunkIndex, header);
          }

          @Override
          public boolean onChunkEnd(ParserContext context, int chunkIndex, boolean skipped) {
            ((ControlImpl) control.get()).setStream(null);
            return parserListener == null
                || parserListener.onChunkEnd(context, chunkIndex, skipped);
          }

          @Override
          public boolean onMetadata(ParserContext context, MetadataEvent metadata) {
            if (!(context instanceof TypedParserContext)) {
              throw new RuntimeException("Invalid context");
            }
            TypedParserContext lContext = (TypedParserContext) context;
            Long2ObjectMap<Class<?>> typeClassMap = lContext.getClassTypeMap();

            // typeClassMap must be fully initialized before trying to resolve/generate the handlers
            for (MetadataClass clz : metadata.getClasses()) {
              Class<?> targetClass = lContext.getClassTargetType(clz.getName());
              if (targetClass != null) {
                typeClassMap.putIfAbsent(clz.getId(), targetClass);
              }
            }

            return parserListener == null || parserListener.onMetadata(context, metadata);
          }

          @Override
          public boolean onCheckpoint(ParserContext context, CheckpointEvent checkpoint) {
            if (parserListener != null) {
              return parserListener.onCheckpoint(context, checkpoint);
            } else {
              return ChunkParserListener.super.onCheckpoint(context, checkpoint);
            }
          }

          @Override
          public boolean onEvent(
              ParserContext context,
              long typeId,
              long eventStartPos,
              long rawSize,
              long payloadSize) {
            if (!(context instanceof TypedParserContext)) {
              throw new RuntimeException("Invalid context");
            }
            Long2ObjectMap<Class<?>> typeClassMap =
                ((TypedParserContext) context).getClassTypeMap();
            Class<?> typeClz = typeClassMap.get(typeId);
            if (typeClz != null) {
              if (handlerMap.containsKey(typeClz)) {
                RecordingStream stream = context.get(RecordingStream.class);
                MetadataClass clz = context.getMetadataLookup().getClass(typeId);
                Object deserialized = clz.read(stream);
                ControlImpl ctrl = (ControlImpl) control.get();
                for (JFRHandler.Impl<?> handler : handlerMap.get(typeClz)) {
                  handler.handle(deserialized, ctrl);
                  if (ctrl.abortFlag) {
                    return false;
                  }
                }
              }
            }
            return parserListener == null
                || parserListener.onEvent(context, typeId, eventStartPos, rawSize, payloadSize);
          }
          ;
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

  @SuppressWarnings("unchecked")
  @Override
  public TypedJafarParserImpl withParserListener(ChunkParserListener listener) {
    return new TypedJafarParserImpl(this, listener);
  }

  private static CustomByteBuffer openJfrStream(Path jfrFile) {
    try {
      return CustomByteBuffer.map(jfrFile, Integer.MAX_VALUE);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
