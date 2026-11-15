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
    readValue(stream, type, 0);
  }

  /**
   * Upper bound on the nesting of inline values. Types nest by recursion here, and what they nest
   * is decided by the chunk's metadata: a type with an inline field of its own type recurses
   * without consuming a byte, and an array of it recurses once per byte. The Go parser uses the
   * same bound ({@code maxValueDepth}).
   */
  static final int MAX_VALUE_DEPTH = 64;

  private void readValue(RecordingStream stream, MetadataClass type, int depth) throws IOException {
    if (type == null) {
      // An event or a field whose type id the chunk's metadata does not declare.
      throw new IOException("Cannot decode a value of a type not declared in the chunk metadata");
    }
    if (depth > MAX_VALUE_DEPTH) {
      throw new IOException(
          "Type " + type.getName() + ": value nesting exceeds " + MAX_VALUE_DEPTH + " levels");
    }
    if (type.isPrimitive()) {
      readSingleValue(stream, null, type, "");
    }
    for (MetadataField fld : type.getFields()) {
      MetadataClass fldType = fld.getType();
      if (fldType == null) {
        // Checked once per field, before any of the shapes below hands the type to the processor:
        // inline, array and constant pool reference all need it.
        throw new IOException(
            type.getName()
                + "."
                + fld.getName()
                + ": the field's type is not declared in the chunk metadata");
      }
      if (fld.getDimension() == 1) {
        int len = ParsingUtils.arrayLength(stream, type, fld);
        processor.onArrayStart(type, fld.getName(), fldType, len);
        for (int i = 0; i < len; i++) {
          readSingleFieldValue(stream, type, fld, fldType, depth);
        }
        // Not in a finally, like onComplexValueEnd: after a failed element the processor's state
        // is unfinished (an element value may still be open), and completing the array on top of
        // it would misreport or mask the decoding exception. Callers that continue after a failure
        // reset the processor instead.
        processor.onArrayEnd(type, fld.getName(), fldType);
      } else {
        readSingleFieldValue(stream, type, fld, fldType, depth);
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
  private void readSingleFieldValue(
      RecordingStream stream,
      MetadataClass type,
      MetadataField fld,
      MetadataClass fldType,
      int depth)
      throws IOException {
    if (fld.hasConstantPool()) {
      long idx = stream.readVarint();
      processor.onConstantPoolIndex(type, fld.getName(), fldType, idx);
    } else {
      if (fldType.isPrimitive()) {
        readSingleValue(stream, type, fldType, fld.getName());
      } else {
        processor.onComplexValueStart(type, fld.getName(), fldType);
        readValue(stream, fldType, depth + 1);
        processor.onComplexValueEnd(type, fld.getName(), fldType);
      }
    }
  }

  /**
   * Reads a single primitive value from the recording stream.
   *
   * @param stream the recording stream to read from
   * @param owner the metadata class that owns the field (may be null for top-level primitives)
   * @param type the primitive metadata class type of the field value
   * @param fldName the field name
   * @throws IOException if an I/O error occurs during reading
   */
  public void readSingleValue(
      RecordingStream stream, MetadataClass owner, MetadataClass type, String fldName)
      throws IOException {
    switch (type.getName()) {
      case "short":
        processor.onShortValue(owner, fldName, (short) stream.readVarint());
        break;
      case "char":
        processor.onCharValue(owner, fldName, (char) stream.readVarint());
        break;
      case "int":
        processor.onIntValue(owner, fldName, (int) stream.readVarint());
        break;
      case "long":
        processor.onLongValue(owner, fldName, stream.readVarint());
        break;
      case "byte":
        processor.onByteValue(owner, fldName, stream.read());
        break;
      case "boolean":
        processor.onBooleanValue(owner, fldName, stream.read() != 0);
        break;
      case "double":
        processor.onDoubleValue(owner, fldName, stream.readDouble());
        break;
      case "float":
        processor.onFloatValue(owner, fldName, stream.readFloat());
        break;
      case "java.lang.String":
        processor.onStringValue(owner, fldName, stream.readUTF8());
        break;
      default:
        // Reached only when the metadata marks as primitive a type that is none of the above.
        throw new IOException("Unknown primitive type: " + type);
    }
  }
}
