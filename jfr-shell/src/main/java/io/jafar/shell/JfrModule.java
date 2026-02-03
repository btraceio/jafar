package io.jafar.shell;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.core.QueryEvaluator;
import io.jafar.shell.core.Session;
import io.jafar.shell.core.ShellModule;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shell module for JFR (Java Flight Recording) analysis. Provides JFR file support for the Jafar
 * shell.
 *
 * <p>Discovered via ServiceLoader.
 */
public final class JfrModule implements ShellModule {

  private static final Logger LOG = LoggerFactory.getLogger(JfrModule.class);

  // JFR files start with "FLR\0" magic bytes
  private static final byte[] JFR_MAGIC = new byte[] {'F', 'L', 'R', 0};

  private ParsingContext parsingContext;

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
    // First check extension
    if (!ShellModule.super.canHandle(path)) {
      // Extension doesn't match, check magic bytes anyway
      if (!Files.isRegularFile(path)) {
        return false;
      }
    }

    // Verify magic bytes for robustness
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
      return false;
    }
  }

  @Override
  public Session createSession(Path path, Object context) throws IOException {
    if (parsingContext == null) {
      throw new IllegalStateException("Module not initialized");
    }
    return new JFRSession(path, parsingContext);
  }

  @Override
  public QueryEvaluator getQueryEvaluator() {
    return new JfrQueryEvaluator();
  }

  @Override
  public int getPriority() {
    return 0;
  }

  @Override
  public void initialize() {
    this.parsingContext = ParsingContext.create();
    LOG.debug("JFR module initialized");
  }

  @Override
  public void shutdown() {
    LOG.debug("JFR module shutdown");
  }
}
