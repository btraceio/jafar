package io.jafar.hdump.shell;

import io.jafar.hdump.api.HeapDumpParser.ParserOptions;
import io.jafar.hdump.shell.cli.HdumpShellCompleter;
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
import java.util.Set;
import org.jline.reader.Completer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shell module for heap dump analysis. Provides HPROF file support for the Jafar shell.
 *
 * <p>Discovered via ServiceLoader.
 */
public final class HdumpModule implements ShellModule {

  private static final Logger LOG = LoggerFactory.getLogger(HdumpModule.class);

  // HPROF files start with "JAVA PROFILE "
  private static final byte[] HPROF_MAGIC = "JAVA PROFILE ".getBytes();

  @Override
  public String getId() {
    return "hdump";
  }

  @Override
  public String getDisplayName() {
    return "Heap Dump Analyzer";
  }

  @Override
  public Set<String> getSupportedExtensions() {
    return Set.of("hprof", "hdump");
  }

  @Override
  public boolean canHandle(Path path) {
    // First check extension
    if (!ShellModule.super.canHandle(path)) {
      // Extension doesn't match, but check magic bytes anyway
      // Some heap dumps don't have standard extensions
      if (!Files.isRegularFile(path)) {
        return false;
      }
    }

    // Verify magic bytes for robustness
    try (FileChannel fc = FileChannel.open(path, StandardOpenOption.READ)) {
      if (fc.size() < HPROF_MAGIC.length) {
        return false;
      }
      ByteBuffer buf = ByteBuffer.allocate(HPROF_MAGIC.length);
      fc.read(buf);
      buf.flip();
      for (int i = 0; i < HPROF_MAGIC.length; i++) {
        if (buf.get(i) != HPROF_MAGIC[i]) {
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
    ParserOptions options = ParserOptions.DEFAULT;
    if (context instanceof ParserOptions opts) {
      options = opts;
    }
    return HeapSession.open(path, options);
  }

  @Override
  public QueryEvaluator getQueryEvaluator() {
    return new HdumpQueryEvaluator();
  }

  @Override
  public Completer getCompleter(SessionManager sessions, Object context) {
    return new HdumpShellCompleter(sessions);
  }

  @Override
  public List<String> getExamples() {
    return List.of(
        "show objects/java.lang.String | top(10, retainedSize)",
        "show objects/java.util.HashMap | groupBy(class.name, count)",
        "show objects/java.lang.Thread[name='main']",
        "show classes/java.* | select(name, instanceCount, totalSize)",
        "show gcroots | stats(retainedSize)");
  }

  @Override
  public int getPriority() {
    return 0;
  }

  @Override
  public void initialize() {
    LOG.debug("Heap dump module initialized");
  }

  @Override
  public void shutdown() {
    LOG.debug("Heap dump module shutdown");
  }
}
