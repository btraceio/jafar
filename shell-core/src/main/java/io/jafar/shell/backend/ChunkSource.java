package io.jafar.shell.backend;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Abstraction for chunk information queries against a JFR recording. */
public interface ChunkSource {

  /**
   * Load all chunks as metadata rows.
   *
   * @param recording path to the JFR recording file
   * @return list of chunk metadata rows with index, offset, size, startNanos, duration, etc.
   * @throws Exception if parsing fails
   */
  List<Map<String, Object>> loadAllChunks(Path recording) throws Exception;

  /**
   * Load a specific chunk by index.
   *
   * @param recording path to the JFR recording file
   * @param chunkIndex the chunk index to load
   * @return chunk metadata row, or null if not found
   * @throws Exception if parsing fails
   */
  Map<String, Object> loadChunk(Path recording, int chunkIndex) throws Exception;

  /**
   * Load chunks matching a predicate.
   *
   * @param recording path to the JFR recording file
   * @param filter predicate to filter chunks, or null for all chunks
   * @return list of matching chunk metadata rows
   * @throws Exception if parsing fails
   */
  List<Map<String, Object>> loadChunks(Path recording, Predicate<Map<String, Object>> filter)
      throws Exception;

  /**
   * Get summary statistics about chunks.
   *
   * @param recording path to the JFR recording file
   * @return summary map with totalChunks, totalSize, avgSize, minSize, maxSize, compressedCount
   * @throws Exception if parsing fails
   */
  Map<String, Object> getChunkSummary(Path recording) throws Exception;
}
