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
    return buildVersion(JafarBackend.class);
  }

  /**
   * The {@code Implementation-Version} in the manifest of the jar {@code type} was loaded from, or
   * {@code dev} (a classes directory, or a jar without the attribute). Read from that jar itself,
   * not from {@link Class#getPackage()}: the jdk backend has classes in this package too, and the
   * shell loads every plugin jar through one class loader, where the package's version is whichever
   * jar defined it first. A released jafar backend next to a built jdk jar reported the jdk jar's
   * version and hid the stale-backend warning (#267). The jdk backend has the same method: a plugin
   * cannot rely on a shell-core helper an older shell lacks.
   */
  static String buildVersion(Class<?> type) {
    try {
      java.security.CodeSource source = type.getProtectionDomain().getCodeSource();
      if (source == null || source.getLocation() == null) {
        return "dev";
      }
      Path location = Path.of(source.getLocation().toURI());
      if (!java.nio.file.Files.isRegularFile(location)) {
        return "dev"; // a classes directory
      }
      try (java.util.jar.JarFile jar = new java.util.jar.JarFile(location.toFile())) {
        java.util.jar.Manifest manifest = jar.getManifest();
        String version =
            manifest == null
                ? null
                : manifest.getMainAttributes().getValue("Implementation-Version");
        return version == null || version.isBlank() ? "dev" : version;
      }
    } catch (java.io.IOException | java.net.URISyntaxException | RuntimeException e) {
      return "dev"; // no manifest to read: treated as a build of this tree, as before
    }
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
    ParsingContext parsingContext =
        (context instanceof JafarBackendContext jbc)
            ? jbc.getParsingContext()
            : ParsingContext.create();
    return new JafarEventSource(parsingContext);
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
    private final ParsingContext parsingContext;

    JafarEventSource(ParsingContext parsingContext) {
      this.parsingContext = parsingContext;
    }

    @Override
    public void streamEvents(Path recording, Consumer<Event> consumer) throws Exception {
      try (UntypedJafarParser p = parsingContext.newUntypedParser(recording)) {
        p.handle((type, value, ctl) -> consumer.accept(new Event(type.getName(), value)));
        p.run();
      }
    }
  }
}
