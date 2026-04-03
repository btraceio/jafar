package io.jafar.otlp.shell;

import io.jafar.otlp.shell.cli.OtlpShellCompleter;
import io.jafar.shell.core.QueryEvaluator;
import io.jafar.shell.core.Session;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.core.ShellModule;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jline.reader.Completer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shell module for OpenTelemetry profiling signal analysis. Provides OTLP profiles file support for
 * the Jafar shell.
 *
 * <p>Supports binary-encoded OTLP profiles ({@code .otlp}) as produced by OpenTelemetry-compatible
 * profilers such as the Datadog profiling agent.
 *
 * <p>Discovered via ServiceLoader.
 */
public final class OtlpModule implements ShellModule {

  private static final Logger LOG = LoggerFactory.getLogger(OtlpModule.class);

  /**
   * Expected first bytes of a binary protobuf {@code ProfilesData} message. Field 1
   * (resource_profiles) encoded as LEN (wire type 2) yields tag byte {@code 0x0A}. Field 2
   * (dictionary) encoded as LEN yields tag byte {@code 0x12}.
   */
  private static final byte PROTO_FIELD1_LEN = 0x0A; // (1 << 3) | 2

  private static final byte PROTO_FIELD2_LEN = 0x12; // (2 << 3) | 2

  @Override
  public String getId() {
    return "otlp";
  }

  @Override
  public String getDisplayName() {
    return "OpenTelemetry Profiling Analyzer";
  }

  @Override
  public Set<String> getSupportedExtensions() {
    return Set.of("otlp");
  }

  @Override
  public boolean canHandle(Path path) {
    if (!Files.isRegularFile(path)) {
      return false;
    }

    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
    boolean extMatch = name.endsWith(".otlp");

    try (FileChannel fc = FileChannel.open(path, StandardOpenOption.READ)) {
      if (fc.size() < 2) {
        return false;
      }
      ByteBuffer buf = ByteBuffer.allocate(1);
      fc.read(buf);
      buf.flip();
      byte firstByte = buf.get(0);
      boolean validProto = firstByte == PROTO_FIELD1_LEN || firstByte == PROTO_FIELD2_LEN;
      return extMatch && validProto;
    } catch (IOException e) {
      LOG.debug("Failed to check content for {}: {}", path, e.getMessage());
      return extMatch;
    }
  }

  @Override
  public Session createSession(Path path, Object context) throws IOException {
    return OtlpSession.open(path);
  }

  @Override
  public QueryEvaluator getQueryEvaluator() {
    return new OtlpQueryEvaluator();
  }

  @Override
  public Completer getCompleter(SessionManager<?> sessions, Object context) {
    return new OtlpShellCompleter(sessions);
  }

  @Override
  public List<String> getExamples() {
    return List.of(
        "show samples | count()",
        "show samples | top(10, cpu)",
        "show samples | groupBy(thread, sum(cpu))",
        "show samples | stats(cpu)",
        "show samples | stackprofile()",
        "show samples[thread='main'] | top(10, cpu)");
  }

  @Override
  public int getPriority() {
    return 0;
  }

  @Override
  public void initialize() {
    LOG.debug("otlp module initialized");
  }

  @Override
  public void shutdown() {
    LOG.debug("otlp module shutdown");
  }
}
