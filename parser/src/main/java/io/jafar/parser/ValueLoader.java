package io.jafar.parser;

import io.jafar.parser.internal_api.RecordingStream;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import java.io.IOException;

/**
 * Utility class for skipping over JFR values during parsing.
 *
 * <p>This class provides methods to efficiently skip over JFR data values without deserializing
 * them, which is useful for selective parsing or when only certain event types are of interest.
 */
public final class ValueLoader {
  /**
   * Public constructor for ValueLoader.
   *
   * <p>This class provides utility methods for skipping over JFR values during parsing.
   */
  public ValueLoader() {}

  /**
   * Skips over a value in the recording stream without deserializing it.
   *
   * <p>This method efficiently skips over JFR data values based on their type descriptor. For
   * arrays, it reads the length and skips each element. For constant pool values, it skips the
   * constant pool references. For regular values, it delegates to the type descriptor's skip
   * method.
   *
   * @param stream the recording stream to skip over
   * @param typeDescriptor the metadata class describing the value type
   * @param isArray whether the value is an array
   * @param hasConstantPool whether the value uses constant pool references
   * @throws IOException if an I/O error occurs during skipping
   */
  public static void skip(
      RecordingStream stream,
      MetadataClass typeDescriptor,
      boolean isArray,
      boolean hasConstantPool)
      throws IOException {
    int len = isArray ? (int) stream.readVarint() : 1;
    if (hasConstantPool) {
      for (int i = 0; i < len; i++) {
        stream.readVarint();
      }
    } else {
      for (int i = 0; i < len; i++) {
        typeDescriptor.skip(stream);
      }
    }
  }
}
