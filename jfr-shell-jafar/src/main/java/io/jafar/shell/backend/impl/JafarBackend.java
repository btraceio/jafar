package io.jafar.shell.backend.impl;

import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.shell.backend.BackendCapability;
import io.jafar.shell.backend.BackendContext;
import io.jafar.shell.backend.ChunkSource;
import io.jafar.shell.backend.ConstantPoolSource;
import io.jafar.shell.backend.EventSource;
import io.jafar.shell.backend.JfrBackend;
import io.jafar.shell.backend.MetadataSource;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Jafar-based JFR backend implementation. Full-featured reference implementation with all
 * capabilities.
 */
public final class JafarBackend implements JfrBackend {

  private static final Set<BackendCapability> CAPABILITIES =
      EnumSet.of(
          BackendCapability.EVENT_STREAMING,
          BackendCapability.METADATA_CLASSES,
          BackendCapability.CHUNK_INFO,
          BackendCapability.CONSTANT_POOLS,
          BackendCapability.STREAMING_PARSE,
          BackendCapability.TYPED_HANDLERS,
          BackendCapability.UNTYPED_HANDLERS,
          BackendCapability.CONTEXT_REUSE);

  @Override
  public String getId() {
    return "jafar";
  }

  @Override
  public String getName() {
    return "Jafar Parser";
  }

  @Override
  public String getVersion() {
    // Could read from manifest, but keeping it simple
    return getClass().getPackage().getImplementationVersion() != null
        ? getClass().getPackage().getImplementationVersion()
        : "dev";
  }

  @Override
  public int getPriority() {
    return 100; // Highest priority - default backend
  }

  @Override
  public Set<BackendCapability> getCapabilities() {
    return CAPABILITIES;
  }

  @Override
  public BackendContext createContext() {
    return new JafarBackendContext(ParsingContext.create());
  }

  @Override
  public EventSource createEventSource(BackendContext context) {
    return new JafarEventSource();
  }

  @Override
  public MetadataSource createMetadataSource() {
    return new JafarSources.JafarMetadataSource();
  }

  @Override
  public ChunkSource createChunkSource() {
    return new JafarSources.JafarChunkSource();
  }

  @Override
  public ConstantPoolSource createConstantPoolSource() {
    return new JafarSources.JafarConstantPoolSource();
  }

  // --- Inner Classes ---

  /** Jafar backend context wrapping ParsingContext. */
  private static final class JafarBackendContext implements BackendContext {
    private final ParsingContext parsingContext;

    JafarBackendContext(ParsingContext ctx) {
      this.parsingContext = ctx;
    }

    public ParsingContext getParsingContext() {
      return parsingContext;
    }

    @Override
    public long uptime() {
      return parsingContext.uptime();
    }

    @Override
    public void close() {
      // ParsingContext doesn't require closing
    }
  }

  /** Jafar event source using UntypedJafarParser. */
  private static final class JafarEventSource implements EventSource {
    @Override
    public void streamEvents(Path recording, Consumer<Event> consumer) throws Exception {
      try (UntypedJafarParser p = ParsingContext.create().newUntypedParser(recording)) {
        p.handle((type, value, ctl) -> consumer.accept(new Event(type.getName(), value)));
        p.run();
      }
    }
  }
}
