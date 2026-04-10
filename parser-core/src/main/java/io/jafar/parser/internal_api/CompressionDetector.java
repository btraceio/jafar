package io.jafar.parser.internal_api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Detects well-known compression formats by inspecting the first bytes of a file.
 *
 * <p>Used to produce actionable error messages when a compressed file is passed to the JFR parser
 * instead of a raw JFR recording.
 */
final class CompressionDetector {

  private CompressionDetector() {}

  /** Known compression formats that may wrap a JFR file. */
  enum Format {
    LZ4("LZ4", "decompress with: lz4 -d <file>"),
    GZIP("gzip", "decompress with: gzip -d <file>  or  zcat <file> > out.jfr"),
    ZSTD("zstd", "decompress with: zstd -d <file>"),
    NONE(null, null);

    final String label;
    final String hint;

    Format(String label, String hint) {
      this.label = label;
      this.hint = hint;
    }
  }

  /**
   * Reads the first four bytes of {@code path} and returns the detected compression format, or
   * {@link Format#NONE} if none is recognised.
   *
   * @param path the file to inspect
   * @return detected format
   * @throws IOException if the file cannot be read
   */
  static Format detect(Path path) throws IOException {
    byte[] magic = new byte[4];
    int read = 0;
    try (InputStream in = Files.newInputStream(path)) {
      while (read < magic.length) {
        int n = in.read(magic, read, magic.length - read);
        if (n < 0) break;
        read += n;
      }
    }
    if (read < 2) return Format.NONE;

    // LZ4 frame magic: 0x184D2204 stored little-endian → bytes: 04 22 4D 18
    if (read >= 4
        && (magic[0] & 0xFF) == 0x04
        && (magic[1] & 0xFF) == 0x22
        && (magic[2] & 0xFF) == 0x4D
        && (magic[3] & 0xFF) == 0x18) {
      return Format.LZ4;
    }

    // gzip: 1F 8B
    if ((magic[0] & 0xFF) == 0x1F && (magic[1] & 0xFF) == 0x8B) {
      return Format.GZIP;
    }

    // zstd frame magic: 0xFD2FB528 stored little-endian → bytes: 28 B5 2F FD
    if (read >= 4
        && (magic[0] & 0xFF) == 0x28
        && (magic[1] & 0xFF) == 0xB5
        && (magic[2] & 0xFF) == 0x2F
        && (magic[3] & 0xFF) == 0xFD) {
      return Format.ZSTD;
    }

    return Format.NONE;
  }
}
