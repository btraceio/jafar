package io.jafar.shell.providers;

import io.jafar.shell.backend.BackendCapability;
import io.jafar.shell.backend.BackendRegistry;
import io.jafar.shell.backend.ChunkSource;
import io.jafar.shell.backend.JfrBackend;
import io.jafar.shell.backend.UnsupportedCapabilityException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Provider for chunk metadata extraction and browsing.
 *
 * <p>Delegates to the current backend's {@link ChunkSource} implementation.
 */
public final class ChunkProvider {
  private ChunkProvider() {}

  private static ChunkSource getSource() throws UnsupportedCapabilityException {
    JfrBackend backend = BackendRegistry.getInstance().getCurrent();
    return backend.createChunkSource();
  }

  /**
   * Load all chunks as row maps. Returns: index, offset, size, startNanos, duration, startTicks,
   * frequency, compressed
   *
   * @param recording the JFR recording file path
   * @return list of chunk metadata rows
   * @throws Exception if parsing fails
   */
  public static List<Map<String, Object>> loadAllChunks(Path recording) throws Exception {
    return getSource().loadAllChunks(recording);
  }

  /**
   * Load a specific chunk by index.
   *
   * @param recording the JFR recording file path
   * @param chunkIndex the chunk index to load
   * @return chunk metadata row, or null if not found
   * @throws Exception if parsing fails
   */
  public static Map<String, Object> loadChunk(Path recording, int chunkIndex) throws Exception {
    return getSource().loadChunk(recording, chunkIndex);
  }

  /**
   * Load chunks matching a predicate.
   *
   * @param recording the JFR recording file path
   * @param filter predicate to filter chunks, or null for all chunks
   * @return list of matching chunk metadata rows
   * @throws Exception if parsing fails
   */
  public static List<Map<String, Object>> loadChunks(
      Path recording, Predicate<Map<String, Object>> filter) throws Exception {
    return getSource().loadChunks(recording, filter);
  }

  /**
   * Get summary statistics about chunks. Returns: totalChunks, totalSize, avgSize, minSize,
   * maxSize, compressedCount
   *
   * @param recording the JFR recording file path
   * @return summary statistics as a single row map
   * @throws Exception if parsing fails
   */
  public static Map<String, Object> getChunkSummary(Path recording) throws Exception {
    return getSource().getChunkSummary(recording);
  }

  /**
   * Check if chunk queries are supported by the current backend.
   *
   * @return true if supported
   */
  public static boolean isSupported() {
    return BackendRegistry.getInstance().getCurrent().supports(BackendCapability.CHUNK_INFO);
  }
}
