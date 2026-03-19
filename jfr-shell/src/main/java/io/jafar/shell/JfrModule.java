package io.jafar.shell;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.cli.CommandDispatcher;
import io.jafar.shell.cli.ShellCompleter;
import io.jafar.shell.core.QueryEvaluator;
import io.jafar.shell.core.Session;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.core.ShellModule;
import io.jafar.shell.core.TuiAdapter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;
import org.jline.reader.Completer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shell module for JFR analysis. Provides JFR file support for the Jafar shell.
 *
 * <p>Discovered via ServiceLoader.
 */
public final class JfrModule implements ShellModule {

  private static final Logger LOG = LoggerFactory.getLogger(JfrModule.class);

  // JFR files start with "FLR\0" magic bytes
  private static final byte[] JFR_MAGIC = {0x46, 0x4C, 0x52, 0x00};

  @Override
  public String getId() {
    return "jfr";
  }

  @Override
  public String getDisplayName() {
    return "JFR Analyzer";
  }

  @Override
  public Set<String> getSupportedExtensions() {
    return Set.of("jfr");
  }

  @Override
  public boolean canHandle(Path path) {
    if (!Files.isRegularFile(path)) {
      return false;
    }
    // Check magic bytes for robustness
    try (FileChannel fc = FileChannel.open(path, StandardOpenOption.READ)) {
      if (fc.size() < JFR_MAGIC.length) {
        return false;
      }
      ByteBuffer buf = ByteBuffer.allocate(JFR_MAGIC.length);
      fc.read(buf);
      buf.flip();
      for (int i = 0; i < JFR_MAGIC.length; i++) {
        if (buf.get(i) != JFR_MAGIC[i]) {
          return false;
        }
      }
      return true;
    } catch (IOException e) {
      LOG.debug("Failed to check magic bytes for {}: {}", path, e.getMessage());
      // Fall back to extension check
      return ShellModule.super.canHandle(path);
    }
  }

  @Override
  public Session createSession(Path path, Object context) throws Exception {
    return new JFRSession(path, ParsingContext.create());
  }

  @Override
  public QueryEvaluator getQueryEvaluator() {
    return new JfrQueryEvaluator();
  }

  @SuppressWarnings("unchecked")
  @Override
  public Completer getCompleter(SessionManager<?> sessions, Object context) {
    CommandDispatcher dispatcher = context instanceof CommandDispatcher cd ? cd : null;
    return new ShellCompleter((SessionManager<JFRSession>) sessions, dispatcher);
  }

  @Override
  public List<String> getExamples() {
    return List.of(
        "show events/jdk.ExecutionSample | top(10)",
        "show events/jdk.GCPhasePause | stats(duration)",
        "show events/jdk.ObjectAllocationSample | groupBy(objectClass/name)",
        "show metadata",
        "show constants/jdk.types.Symbol | head(20)");
  }

  @Override
  public TuiAdapter createTuiAdapter(SessionManager<?> sessions, Object context) {
    return new JfrTuiAdapter(sessions, context);
  }

  @Override
  public int getPriority() {
    return 0;
  }

  @Override
  public void initialize() {
    LOG.debug("JFR module initialized");
  }

  @Override
  public void shutdown() {
    LOG.debug("JFR module shutdown");
  }
}
