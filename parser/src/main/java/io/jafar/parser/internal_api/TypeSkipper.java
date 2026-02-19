package io.jafar.parser.internal_api;

import io.jafar.parser.ParsingUtils;
import io.jafar.parser.internal_api.collections.IntGrowableArray;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.internal_api.metadata.MetadataField;
import java.io.IOException;
import java.util.List;

/**
 * Utility class for efficiently skipping over JFR data without deserializing it.
 *
 * <p>This class generates instruction sequences for skipping over different types of JFR data
 * structures, allowing efficient navigation through recordings without the overhead of full
 * deserialization.
 */
public final class TypeSkipper {
  /**
   * Skips over a single field's data in the stream.
   *
   * @param field the metadata field to skip
   * @param stream the recording stream to skip over
   * @throws IOException if an I/O error occurs during skipping
   */
  public static void skip(MetadataField field, RecordingStream stream) throws IOException {
    MetadataClass fieldType = field.getType();
    if (fieldType != null) {
      int dimension = field.getDimension();
      if (dimension > 0) {
        int count = (int) stream.readVarint();
        for (int i = 0; i < count; i++) {
          fieldType.skip(stream); // Use cached skipper from MetadataClass
        }
      } else if (field.hasConstantPool()) {
        stream.readVarint(); // skip CP reference
      } else {
        fieldType.skip(stream); // Use cached skipper from MetadataClass
      }
    }
  }

  /**
   * Creates a TypeSkipper for the specified metadata class.
   *
   * @param clz the metadata class to create a skipper for
   * @return a new TypeSkipper instance
   */
  public static TypeSkipper createSkipper(MetadataClass clz) {
    IntGrowableArray instructions = new IntGrowableArray(20);

    // Special case: java.lang.String has 0 fields but needs to skip string data
    // When used in constant pools, the string data is stored directly
    if ("java.lang.String".equals(clz.getName())) {
      instructions.add(Instructions.STRING);
      return new TypeSkipper(instructions.toIntArray());
    }

    // Special case: simple types in constant pools store unwrapped field values
    // The CP entries contain the raw unwrapped type data, not the simple type structure
    if (clz.isSimpleType() && clz.getFields().size() == 1) {
      MetadataField singleField = clz.getFields().get(0);
      MetadataClass fieldType = singleField.getType();

      // Recursively unwrap nested simple types to get the actual field type
      while (fieldType.isSimpleType() && fieldType.getFields().size() == 1) {
        fieldType = fieldType.getFields().get(0).getType();
      }

      // Add skip instruction for the unwrapped type (without CP flag)
      // because in CP context, the data is already unwrapped
      String typeName = fieldType.getName();
      switch (typeName) {
        case "byte":
        case "boolean":
          instructions.add(Instructions.BYTE);
          break;
        case "char":
        case "short":
        case "int":
        case "long":
          instructions.add(Instructions.VARINT);
          break;
        case "float":
          instructions.add(Instructions.FLOAT);
          break;
        case "double":
          instructions.add(Instructions.DOUBLE);
          break;
        case "java.lang.String":
          instructions.add(Instructions.STRING);
          break;
        default:
          // Complex unwrapped type - should not happen for simple types
          for (MetadataField fld : fieldType.getFields()) {
            fillSkipper(fld, instructions);
          }
          break;
      }
      return new TypeSkipper(instructions.toIntArray());
    }

    for (MetadataField fld : clz.getFields()) {
      fillSkipper(fld, instructions);
    }
    return new TypeSkipper(instructions.toIntArray());
  }

  /**
   * Fills the instruction list with skipping instructions for a field.
   *
   * @param fld the metadata field to generate instructions for
   * @param instructions the instruction list to fill
   */
  private static void fillSkipper(MetadataField fld, IntGrowableArray instructions) {
    int startingSize = instructions.size();
    int arraySizeIdx = -1;
    MetadataClass fldClz = fld.getType();
    if (fld.getDimension() > 0) {
      instructions.add(Instructions.ARRAY);
      arraySizeIdx = instructions.size();
      instructions.add(0); // reserve slot for the array size
    }
    boolean withCp = fld.hasConstantPool();
    while (fldClz.isSimpleType()) {
      List<MetadataField> fl = fldClz.getFields();
      fldClz = fl.get(0).getType();
    }
    String name = fldClz.getName();
    switch (name) {
      case "byte":
      case "boolean":
        instructions.add(Instructions.BYTE);
        break;
      case "char":
      case "short":
      case "int":
      case "long":
        instructions.add(Instructions.VARINT);
        break;
      case "float":
        instructions.add(Instructions.FLOAT);
        break;
      case "double":
        instructions.add(Instructions.DOUBLE);
        break;
      case "java.lang.String":
        if (withCp) {
          instructions.add(Instructions.CP_ENTRY);
        } else {
          instructions.add(Instructions.STRING);
        }
        break;
      default:
        if (withCp) {
          instructions.add(Instructions.CP_ENTRY);
        } else {
          for (MetadataField subField : fldClz.getFields()) {
            fillSkipper(subField, instructions);
          }
        }
        break;
    }
    if (fld.getDimension() > 0) {
      instructions.set(arraySizeIdx, instructions.size() - startingSize - 2);
    }
  }

  /** Constants defining the different types of skipping instructions. */
  private static final class Instructions {
    /** Instruction to handle array data. */
    public static final int ARRAY = 1;

    /** Instruction to skip a single byte. */
    public static final int BYTE = 2;

    /** Instruction to skip a float value. */
    public static final int FLOAT = 3;

    /** Instruction to skip a double value. */
    public static final int DOUBLE = 4;

    /** Instruction to skip a string value. */
    public static final int STRING = 5;

    /** Instruction to skip a variable-length integer. */
    public static final int VARINT = 6;

    /** Instruction to skip a constant pool entry. */
    public static final int CP_ENTRY = 7;
  }

  /**
   * Listener interface for monitoring skip operations.
   *
   * <p>This interface allows monitoring of skip operations with callbacks for each skipped section
   * of data.
   */
  public interface Listener {
    /** A no-operation listener that does nothing. */
    Listener NOOP = (idx, from, to) -> {};

    /**
     * Called when a section of data is skipped.
     *
     * @param idx the instruction index
     * @param from the starting position
     * @param to the ending position
     */
    void onSkip(int idx, long from, long to);
  }

  /** The array of skipping instructions. */
  private final int[] instructions;

  /**
   * Constructs a new TypeSkipper with the specified instructions.
   *
   * @param instructions the array of skipping instructions
   */
  public TypeSkipper(int[] instructions) {
    this.instructions = instructions;
  }

  /**
   * Skips over the data in the stream using the default no-op listener.
   *
   * @param stream the recording stream to skip over
   * @throws IOException if an I/O error occurs during skipping
   */
  public void skip(RecordingStream stream) throws IOException {
    skip(stream, Listener.NOOP);
  }

  /**
   * Skips over the data in the stream using the specified listener.
   *
   * @param stream the recording stream to skip over
   * @param listener the listener to notify of skip operations
   * @throws IOException if an I/O error occurs during skipping
   */
  public void skip(RecordingStream stream, Listener listener) throws IOException {
    for (int i = 0; i < instructions.length; i++) {
      long from = stream.position();
      int instruction = instructions[i];
      if (instruction == Instructions.ARRAY) {
        int endIndex =
            (++i)
                + instructions[
                    i++]; // the next instruction for array is encoding the number of instructions
        // per array item
        int cnt = (int) stream.readVarint();
        if (cnt == 0) {
          i = endIndex;
          continue;
        }
        int savedIndex = i;
        instruction = instructions[i];
        for (int j = 0; j < cnt; ) {
          skip(instruction, stream);
          if (endIndex == i++) {
            i = savedIndex;
            j++;
          }
        }
        listener.onSkip(i, from, stream.position());

        i = endIndex;
        continue;
      }
      skip(instruction, stream);
      listener.onSkip(i, from, stream.position());
    }
  }

  private static void skip(int instruction, RecordingStream stream) throws IOException {
    switch (instruction) {
      case Instructions.VARINT:
      case Instructions.CP_ENTRY:
        stream.readVarint();
        break;
      case Instructions.BYTE:
        stream.skip(1);
        break;
      case Instructions.FLOAT:
        stream.skip(4);
        break;
      case Instructions.DOUBLE:
        stream.skip(8);
        break;
      case Instructions.STRING:
        ParsingUtils.skipUTF8(stream);
        break;
    }
  }
}
