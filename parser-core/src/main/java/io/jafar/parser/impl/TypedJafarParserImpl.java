package io.jafar.parser.impl;

import io.jafar.parser.api.Control;
import io.jafar.parser.api.HandlerFactory;
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
import io.jafar.parser.internal_api.collections.IntObjectArrayMap;
import io.jafar.parser.internal_api.collections.LongObjectHashMap;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.internal_api.metadata.MetadataEvent;
import io.jafar.parser.internal_api.metadata.MetadataField;
import io.jafar.utils.CustomByteBuffer;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
    private final HandlerRegistry<JFRHandler.Impl<?>> registry;
    private final JFRHandler.Impl<?> handler;
    private final long registeredIn;
    private final AtomicBoolean closed = new AtomicBoolean();

    HandlerRegistrationImpl(
        HandlerRegistry<JFRHandler.Impl<?>> registry, JFRHandler.Impl<?> handler) {
      this.registry = registry;
      this.handler = handler;
      this.registeredIn = registry.add(handler);
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        registry.remove(handler, registeredIn);
      }
    }

    /** {@inheritDoc} */
    @Override
    @Deprecated
    public void destroy(JafarParser cookie) {
      if (cookie instanceof TypedJafarParserImpl
          && ((TypedJafarParserImpl) cookie).handlerMap == handlerMap) {
        close();
      }
    }
  }

  /** Listener for chunk parsing events. */
  private final ChunkParserListener parserListener;

  /** Streaming chunk parser for processing JFR data. */
  private final StreamingChunkParser parser;

  /** Path to the JFR recording file. */
  private final Path recording;

  /**
   * Event classes to their registered handlers. An entry is created on first registration and never
   * removed; a class whose handlers have all been deregistered keeps an empty registry.
   *
   * <p>This map, {@link #globalHandlerMap} and {@link #factoryMap} are shared with the copies
   * {@link #withParserListener} makes and are concurrent maps: they are written by {@link #handle}
   * and {@link #registerFactory} on the caller's thread while the chunk threads of a running parse
   * read them.
   */
  private final ConcurrentHashMap<Class<?>, HandlerRegistry<JFRHandler.Impl<?>>> handlerMap;

  /**
   * How many parsers share the three maps and have not been closed; the maps are cleared when the
   * last one closes.
   */
  private final AtomicInteger owners;

  /** Map of chunk indices to type ID to class mappings. */
  private final IntObjectArrayMap<LongObjectHashMap<Class<?>>> chunkTypeClassMap;

  /** Global map of event type names to handler classes. */
  private final ConcurrentHashMap<String, Class<?>> globalHandlerMap;

  /** Map of interface classes to their build-time generated factories. */
  private final ConcurrentHashMap<Class<?>, HandlerFactory<?>> factoryMap;

  /** Whether build-time factories have been bound to the current recording. */
  private volatile boolean factoriesBound = false;

  private static final JFRHandler.Impl<?>[] NO_HANDLERS = new JFRHandler.Impl<?>[0];

  /** Whether this parser has been closed. */
  private boolean closed = false;

  /**
   * Constructs a new TypedJafarParserImpl for the specified recording.
   *
   * @param recording the path to the JFR recording file
   * @param parsingContext the parsing context to use
   */
  public TypedJafarParserImpl(Path recording, ParsingContextImpl parsingContext) {
    this.parser =
        new StreamingChunkParser(
            parsingContext.typedContextFactory(), parsingContext.parallelism());
    this.recording = recording;
    this.chunkTypeClassMap = new IntObjectArrayMap<>();
    this.handlerMap = new ConcurrentHashMap<>();
    this.globalHandlerMap = new ConcurrentHashMap<>();
    this.factoryMap = new ConcurrentHashMap<>();
    this.owners = new AtomicInteger(1);
    this.parserListener = null;

    // Auto-discover and register build-time generated factories via ServiceLoader
    discoverFactories();
  }

  private TypedJafarParserImpl(TypedJafarParserImpl other, ChunkParserListener listener) {
    this.parser = other.parser;
    this.recording = other.recording;
    this.handlerMap = other.handlerMap;
    this.owners = other.owners;
    this.owners.incrementAndGet();
    this.chunkTypeClassMap = new IntObjectArrayMap<>(other.chunkTypeClassMap);
    this.globalHandlerMap = other.globalHandlerMap;
    this.factoryMap = other.factoryMap;
    this.factoriesBound = false;
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
      HandlerRegistry<JFRHandler.Impl<?>> registry =
          handlerMap.computeIfAbsent(clz, k -> new HandlerRegistry<>(NO_HANDLERS));
      return new HandlerRegistrationImpl<>(registry, handlerImpl);
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

    if (globalHandlerMap.putIfAbsent(typeName, clz) != null) {
      return;
    }
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
          // One abort state for the whole parse, shared by every chunk thread; see
          // Control#abort(). ControlImpl itself stays per thread for the stream position.
          private final AtomicBoolean aborted = new AtomicBoolean();
          private final ThreadLocal<Control> control =
              ThreadLocal.withInitial(() -> new ControlImpl(aborted));
          private Set<String> referencedTypes = null;

          @Override
          public void onRecordingStart(ParserContext context) {
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
                    chunkTypeClassMap.computeIfAbsent(chunkIndex, k -> new LongObjectHashMap<>()));
                lCtx.addTargetTypeMap(globalHandlerMap);
              }
              context.put(Control.ChunkInfo.class, new ChunkInfoImpl(header));
              ((ControlImpl) control.get()).setStream(context.get(RecordingStream.class));
              boolean proceed =
                  parserListener == null
                      || parserListener.onChunkStart(context, chunkIndex, header);
              // A chunk that starts after an abort is skipped; onChunkEnd still runs.
              return proceed && !aborted.get();
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
            if (aborted.get()) {
              // Aborted after this chunk started: skip binding factories and deserializers for the
              // chunk's metadata, its constant pools and its events. The parser then calls
              // onChunkEnd with skipped=true.
              return false;
            }
            if (!(context instanceof TypedParserContext)) {
              throw new RuntimeException("Invalid context");
            }
            TypedParserContext lContext = (TypedParserContext) context;
            LongObjectHashMap<Class<?>> typeClassMap = lContext.getClassTypeMap();

            // Bind build-time factories once per recording
            if (!factoryMap.isEmpty() && !factoriesBound) {
              synchronized (TypedJafarParserImpl.this) {
                if (!factoriesBound) {
                  // First, bind factories for type ID resolution
                  for (HandlerFactory<?> factory : factoryMap.values()) {
                    factory.bind(context.getMetadataLookup());
                  }

                  // Then inject factories into corresponding MetadataClass instances
                  for (Map.Entry<Class<?>, HandlerFactory<?>> entry : factoryMap.entrySet()) {
                    String jfrTypeName = entry.getValue().getJfrTypeName();
                    MetadataClass metadataClass = context.getMetadataLookup().getClass(jfrTypeName);
                    if (metadataClass != null) {
                      metadataClass.bindFactory(entry.getValue());
                    }
                  }

                  factoriesBound = true;
                }
              }
            }

            // Build transitive closure of all types referenced by registered handlers (once per
            // session)
            if (!globalHandlerMap.isEmpty() && referencedTypes == null) {
              referencedTypes = computeReferencedTypes(context, globalHandlerMap.keySet());
              lContext.setTypeFilter(t -> t != null && referencedTypes.contains(t.getName()));
            }

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
            if (aborted.get()) {
              // Aborted while this chunk's constant pools are being read: skip the rest of the
              // chain.
              return false;
            }
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
            if (aborted.get()) {
              // aborted by a handler in this or another chunk: skip the rest of this chunk
              return false;
            }
            TypedParserContext typedContext = (TypedParserContext) context;
            LongObjectHashMap<Class<?>> typeClassMap = typedContext.getClassTypeMap();
            Class<?> typeClz = typeClassMap.get(typeId);
            if (typeClz != null) {
              // One read per event: a handler deregistered while this event is being dispatched
              // (from a handler or from another thread) still receives it, and no later one.
              HandlerRegistry<JFRHandler.Impl<?>> registry = handlerMap.get(typeClz);
              JFRHandler.Impl<?>[] handlers = registry == null ? NO_HANDLERS : registry.snapshot();
              if (handlers.length > 0) {
                RecordingStream stream = context.get(RecordingStream.class);
                MetadataClass clz = context.getMetadataLookup().getClass(typeId);

                // MetadataClass.read() handles both factory and deserializer paths
                Object deserialized = clz.read(stream);

                ControlImpl ctrl = (ControlImpl) control.get();
                for (int i = 0; i < handlers.length; i++) {
                  handlers[i].handle(deserialized, ctrl);
                  if (ctrl.isAborted()) {
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
      if (owners.decrementAndGet() == 0) {
        for (HandlerRegistry<JFRHandler.Impl<?>> registry : handlerMap.values()) {
          registry.clear();
        }
        globalHandlerMap.clear();
        factoryMap.clear();
      }
    }
  }

  // Factories are classpath-static: discovered once and reused across all parser instances.
  @SuppressWarnings("rawtypes")
  private static final List<HandlerFactory> DISCOVERED_FACTORIES = discoverFactoriesOnce();

  @SuppressWarnings("rawtypes")
  private static List<HandlerFactory> discoverFactoriesOnce() {
    List<HandlerFactory> result = new ArrayList<>();
    try {
      for (HandlerFactory<?> factory : ServiceLoader.load(HandlerFactory.class)) {
        result.add(factory);
      }
    } catch (Exception e) {
      // No factories available; fall back to runtime bytecode generation
    }
    return result;
  }

  private void discoverFactories() {
    for (HandlerFactory<?> factory : DISCOVERED_FACTORIES) {
      registerFactory(factory);
    }
  }

  @Override
  public <T> void registerFactory(HandlerFactory<T> factory) {
    if (factory == null) {
      throw new IllegalArgumentException("factory cannot be null");
    }
    Class<T> interfaceClass = factory.getInterfaceClass();
    factoryMap.put(interfaceClass, factory);

    // Also add to globalHandlerMap for type filtering
    String jfrTypeName = factory.getJfrTypeName();
    globalHandlerMap.put(jfrTypeName, interfaceClass);
  }

  @SuppressWarnings("unchecked")
  @Override
  public TypedJafarParserImpl withParserListener(ChunkParserListener listener) {
    return new TypedJafarParserImpl(this, listener);
  }

  /**
   * Computes the transitive closure of all types referenced by the given root types.
   *
   * <p>This method recursively traverses all fields of the root types to find all types that are
   * directly or indirectly referenced. This ensures that all necessary types (including simple
   * types used as field values) are included in the type filter and loaded into constant pools.
   *
   * <p><b>Why this is needed:</b> Simple types like jdk.types.Symbol are not directly registered as
   * event handlers, but are referenced by fields (e.g., jdk.types.Method.name). Without transitive
   * closure, the type filter would exclude Symbol, its constant pool wouldn't be loaded, and
   * Method.name would return null.
   *
   * <p><b>Nested Simple Types:</b> This handles arbitrarily nested simple types. For example:
   *
   * <pre>
   * jdk.ExecutionSample (registered handler)
   *   → stackTrace: jdk.types.StackTrace
   *     → frames[]: jdk.types.StackFrame
   *       → method: jdk.types.Method
   *         → name: jdk.types.Symbol (simple type)
   *           → string: String
   * </pre>
   *
   * The worklist algorithm processes each type and adds all its field types to the queue, ensuring
   * Symbol and any nested simple types are included in the closure.
   *
   * <p><b>Algorithm:</b> Uses a worklist-based breadth-first traversal:
   *
   * <ol>
   *   <li>Start with registered event handler types
   *   <li>For each type, examine all its fields
   *   <li>Add each field's type to the worklist (if not already processed)
   *   <li>Repeat until no new types are discovered
   * </ol>
   *
   * @param context the parser context providing access to metadata
   * @param rootTypes the set of root type names (typically registered event handlers)
   * @return the complete set of type names transitively referenced by the root types
   */
  private static Set<String> computeReferencedTypes(ParserContext context, Set<String> rootTypes) {
    Set<String> referencedTypes = new HashSet<>();
    Set<String> toProcess = new HashSet<>(rootTypes);

    while (!toProcess.isEmpty()) {
      String typeName = toProcess.iterator().next();
      toProcess.remove(typeName);

      if (referencedTypes.contains(typeName)) {
        continue;
      }

      referencedTypes.add(typeName);

      // Get metadata for this type
      MetadataClass metadataClass = context.getMetadataLookup().getClass(typeName);
      if (metadataClass != null) {
        // Add all field types to processing queue
        // This recursively includes simple types: if Method has field "name" of type Symbol,
        // Symbol gets added to toProcess, and then Symbol's field "string" gets processed
        for (MetadataField field : metadataClass.getFields()) {
          MetadataClass fieldType = field.getType();
          if (fieldType != null) {
            String fieldTypeName = fieldType.getName();
            if (fieldTypeName != null && !referencedTypes.contains(fieldTypeName)) {
              toProcess.add(fieldTypeName);
            }
          }
        }
      }
    }

    return referencedTypes;
  }

  private static CustomByteBuffer openJfrStream(Path jfrFile) {
    try {
      return CustomByteBuffer.map(jfrFile, Integer.MAX_VALUE);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
