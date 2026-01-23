package io.jafar.shell.providers;

import io.jafar.parser.api.ParserContext;
import io.jafar.parser.impl.UntypedParserContextFactory;
import io.jafar.parser.internal_api.ChunkHeader;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.StreamingChunkParser;
import io.jafar.parser.internal_api.metadata.MetadataEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Provider for chunk metadata extraction and browsing. Follows the pattern established by
 * MetadataProvider.
 */
public final class ChunkProvider {
  private ChunkProvider() {}

  /**
   * Load all chunks as row maps. Returns: index, offset, size, startNanos, duration, startTicks,
   * frequency, compressed
   *
   * @param recording the JFR recording file path
   * @return list of chunk metadata rows
   * @throws Exception if parsing fails
   */
  public static List<Map<String, Object>> loadAllChunks(Path recording) throws Exception {
    return loadChunks(recording, null);
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
    final Map<String, Object>[] result = new Map[1];
    try (StreamingChunkParser parser =
        new StreamingChunkParser(new UntypedParserContextFactory())) {
      parser.parse(
          recording,
          new ChunkParserListener() {
            @Override
            public boolean onChunkStart(ParserContext context, int idx, ChunkHeader header) {
              if (idx == chunkIndex) {
                result[0] = createChunkRow(idx, header);
                return false; // Stop parsing after finding target chunk
              }
              return true;
            }

            @Override
            public boolean onMetadata(ParserContext context, MetadataEvent metadata) {
              return true;
            }
          });
    }
    return result[0];
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
    final List<Map<String, Object>> rows = Collections.synchronizedList(new ArrayList<>());
    try (StreamingChunkParser parser =
        new StreamingChunkParser(new UntypedParserContextFactory())) {
      parser.parse(
          recording,
          new ChunkParserListener() {
            @Override
            public boolean onChunkStart(ParserContext context, int chunkIndex, ChunkHeader header) {
              Map<String, Object> row = createChunkRow(chunkIndex, header);
              if (filter == null || filter.test(row)) {
                rows.add(row);
              }
              return true;
            }

            @Override
            public boolean onMetadata(ParserContext context, MetadataEvent metadata) {
              return true;
            }
          });
    }
    return rows;
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
    final long[] stats = new long[5]; // count, totalSize, minSize, maxSize, compressedCount
    stats[2] = Long.MAX_VALUE; // minSize
    stats[3] = Long.MIN_VALUE; // maxSize

    try (StreamingChunkParser parser =
        new StreamingChunkParser(new UntypedParserContextFactory())) {
      parser.parse(
          recording,
          new ChunkParserListener() {
            @Override
            public boolean onChunkStart(ParserContext context, int chunkIndex, ChunkHeader header) {
              stats[0]++; // count
              stats[1] += header.size; // totalSize
              stats[2] = Math.min(stats[2], header.size); // minSize
              stats[3] = Math.max(stats[3], header.size); // maxSize
              if (header.compressed) {
                stats[4]++; // compressedCount
              }
              return true;
            }

            @Override
            public boolean onMetadata(ParserContext context, MetadataEvent metadata) {
              return true;
            }
          });
    }

    Map<String, Object> summary = new HashMap<>();
    summary.put("totalChunks", stats[0]);
    summary.put("totalSize", stats[1]);
    summary.put("avgSize", stats[0] > 0 ? stats[1] / stats[0] : 0);
    summary.put("minSize", stats[0] > 0 ? stats[2] : 0);
    summary.put("maxSize", stats[0] > 0 ? stats[3] : 0);
    summary.put("compressedCount", stats[4]);
    return summary;
  }

  private static Map<String, Object> createChunkRow(int chunkIndex, ChunkHeader header) {
    Map<String, Object> m = new HashMap<>();
    m.put("index", chunkIndex);
    m.put("offset", header.offset);
    m.put("size", header.size);
    m.put("startNanos", header.startNanos);
    m.put("duration", header.duration);
    m.put("startTicks", header.startTicks);
    m.put("frequency", header.frequency);
    m.put("compressed", header.compressed);
    return m;
  }
}
