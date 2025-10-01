package io.jafar.parser.internal_api;

import io.jafar.parser.ParsingUtils;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.internal_api.metadata.MetadataField;
import java.io.IOException;

/**
 * Generic value reader for processing JFR data values.
 *
 * <p>This class provides functionality to read and process various types of values from JFR
 * recordings, including primitives, arrays, and complex types.
 */
public final class GenericValueReader {
  /** The value processor to delegate value handling to. */
  private final ValueProcessor processor;

  /**
   * Constructs a new GenericValueReader with the specified processor.
   *
   * @param processor the value processor to use for handling values
   */
  public GenericValueReader(ValueProcessor processor) {
    this.processor = processor;
  }

  @SuppressWarnings("unchecked")
  public <T extends ValueProcessor> T getProcessor() {
    return (T) processor;
  }

  /**
   * Reads a value of the specified type from the recording stream.
   *
   * @param stream the recording stream to read from
   * @param type the metadata class type to read
   * @throws IOException if an I/O error occurs during reading
   */
  public void readValue(RecordingStream stream, MetadataClass type) throws IOException {
    if (type.isPrimitive()) {
      readSingleValue(stream, type, "");
    }
    for (MetadataField fld : type.getFields()) {
      if (fld.getDimension() == 1) {
        int len = (int) stream.readVarint();
        try {
          processor.onArrayStart(type, fld.getName(), fld.getType(), len);
          for (int i = 0; i < len; i++) {
            readSingleFieldValue(stream, type, fld);
          }
        } finally {
          processor.onArrayEnd(type, fld.getName(), fld.getType());
        }
      } else {
        readSingleFieldValue(stream, type, fld);
      }
    }
  }

  /**
   * Reads a single field value from the recording stream.
   *
   * @param stream the recording stream to read from
   * @param type the metadata class type
   * @param fld the metadata field to read
   * @throws IOException if an I/O error occurs during reading
   */
  private void readSingleFieldValue(RecordingStream stream, MetadataClass type, MetadataField fld)
      throws IOException {
    if (fld.hasConstantPool()) {
      long idx = stream.readVarint();
      processor.onConstantPoolIndex(type, fld.getName(), fld.getType(), idx);
    } else {
      if (fld.getType().isPrimitive()) {
        readSingleValue(stream, fld.getType(), fld.getName());
      } else {
        processor.onComplexValueStart(type, fld.getName(), fld.getType());
        readValue(stream, fld.getType());
        processor.onComplexValueEnd(type, fld.getName(), fld.getType());
      }
    }
  }

  /**
   * Reads a single primitive value from the recording stream.
   *
   * @param stream the recording stream to read from
   * @param type the metadata class type
   * @param fldName the field name
   * @throws IOException if an I/O error occurs during reading
   */
  private void readSingleValue(RecordingStream stream, MetadataClass type, String fldName)
      throws IOException {
    switch (type.getName()) {
      case "short":
        processor.onShortValue(type, fldName, (short) stream.readVarint());
        break;
      case "char":
        processor.onCharValue(type, fldName, (char) stream.readVarint());
        break;
      case "int":
        processor.onIntValue(type, fldName, (int) stream.readVarint());
        break;
      case "long":
        processor.onLongValue(type, fldName, stream.readVarint());
        break;
      case "byte":
        processor.onByteValue(type, fldName, stream.read());
        break;
      case "boolean":
        processor.onBooleanValue(type, fldName, stream.read() != 0);
        break;
      case "double":
        processor.onDoubleValue(type, fldName, stream.readDouble());
        break;
      case "float":
        processor.onFloatValue(type, fldName, stream.readFloat());
        break;
      case "java.lang.String":
        processor.onStringValue(type, fldName, ParsingUtils.readUTF8(stream));
        break;
      default:
        throw new IllegalStateException("Unknown primitive type: " + type);
    }
  }
}
