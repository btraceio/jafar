package io.jafar.pprof.shell;

import io.jafar.pprof.shell.cli.PprofShellCompleter;
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
 * Shell module for pprof profile analysis. Provides pprof file support for the Jafar shell.
 *
 * <p>Supports gzip-compressed protobuf profiles ({@code .pb.gz}, {@code .pprof}) as produced by Go,
 * Java (async-profiler), Rust, and other pprof-compatible profilers.
 *
 * <p>Discovered via ServiceLoader.
 */
public final class PprofModule implements ShellModule {

  private static final Logger LOG = LoggerFactory.getLogger(PprofModule.class);

  // gzip magic bytes
  private static final byte[] GZIP_MAGIC = {(byte) 0x1F, (byte) 0x8B};

  @Override
  public String getId() {
    return "pprof";
  }

  @Override
  public String getDisplayName() {
    return "pprof Analyzer";
  }

  @Override
  public Set<String> getSupportedExtensions() {
    return Set.of("pprof", "pb.gz");
  }

  @Override
  public boolean canHandle(Path path) {
    if (!Files.isRegularFile(path)) {
      return false;
    }

    // Check by filename: .pb.gz is a two-part extension — handle it explicitly
    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
    boolean extMatch = name.endsWith(".pprof") || name.endsWith(".pb.gz");

    // Verify gzip magic bytes regardless of extension
    try (FileChannel fc = FileChannel.open(path, StandardOpenOption.READ)) {
      if (fc.size() < GZIP_MAGIC.length) {
        return false;
      }
      ByteBuffer buf = ByteBuffer.allocate(GZIP_MAGIC.length);
      fc.read(buf);
      buf.flip();
      for (int i = 0; i < GZIP_MAGIC.length; i++) {
        if (buf.get(i) != GZIP_MAGIC[i]) {
          return false;
        }
      }
      // Magic bytes match: accept if extension also matched, otherwise fall through
      return extMatch || isValidPprofContent(path);
    } catch (IOException e) {
      LOG.debug("Failed to check magic bytes for {}: {}", path, e.getMessage());
      return extMatch;
    }
  }

  /**
   * Reads a few bytes after gzip decompression to confirm the content starts with a valid pprof
   * protobuf field tag (field numbers 1–14, wire types 0 or 2).
   */
  private static boolean isValidPprofContent(Path path) {
    try (java.io.InputStream is = Files.newInputStream(path);
        java.util.zip.GZIPInputStream gzis = new java.util.zip.GZIPInputStream(is)) {
      byte[] header = gzis.readNBytes(2);
      if (header.length < 1) return false;
      // First byte is a protobuf tag: (fieldNumber << 3) | wireType
      int tag = header[0] & 0xFF;
      int fieldNumber = tag >>> 3;
      int wireType = tag & 7;
      // Valid Profile field numbers: 1–14; valid wire types for pprof: 0 (varint) or 2 (len)
      return fieldNumber >= 1 && fieldNumber <= 14 && (wireType == 0 || wireType == 2);
    } catch (IOException e) {
      return false;
    }
  }

  @Override
  public Session createSession(Path path, Object context) throws IOException {
    return PprofSession.open(path);
  }

  @Override
  public QueryEvaluator getQueryEvaluator() {
    return new PprofQueryEvaluator();
  }

  @Override
  public Completer getCompleter(SessionManager<?> sessions, Object context) {
    return new PprofShellCompleter(sessions);
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
    LOG.debug("pprof module initialized");
  }

  @Override
  public void shutdown() {
    LOG.debug("pprof module shutdown");
  }
}
