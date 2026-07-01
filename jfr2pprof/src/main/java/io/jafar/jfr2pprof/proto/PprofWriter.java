package io.jafar.jfr2pprof.proto;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

class PprofWriter {
  // Profile field numbers
  static final int PROFILE_SAMPLE_TYPE = 1;
  static final int PROFILE_SAMPLE = 2;
  static final int PROFILE_MAPPING = 3;
  static final int PROFILE_LOCATION = 4;
  static final int PROFILE_FUNCTION = 5;
  static final int PROFILE_STRING_TABLE = 6;
  static final int PROFILE_TIME_NANOS = 9;
  static final int PROFILE_DURATION_NANOS = 10;
  static final int PROFILE_PERIOD_TYPE = 11;
  static final int PROFILE_PERIOD = 12;

  // ValueType field numbers
  static final int VALUE_TYPE_TYPE = 1;
  static final int VALUE_TYPE_UNIT = 2;

  // Sample field numbers
  static final int SAMPLE_LOCATION_ID = 1;
  static final int SAMPLE_VALUE = 2;
  static final int SAMPLE_LABEL = 3;

  // Label field numbers
  static final int LABEL_KEY = 1;
  static final int LABEL_STR = 2;
  static final int LABEL_NUM = 3;
  static final int LABEL_NUM_UNIT = 4;

  // Location field numbers
  static final int LOCATION_ID = 1;
  static final int LOCATION_MAPPING_ID = 2;
  static final int LOCATION_ADDRESS = 3;
  static final int LOCATION_LINE = 4;
  static final int LOCATION_IS_FOLDED = 5;

  // Line field numbers
  static final int LINE_FUNCTION_ID = 1;
  static final int LINE_LINE = 2;

  // Function field numbers
  static final int FUNCTION_ID = 1;
  static final int FUNCTION_NAME = 2;
  static final int FUNCTION_SYSTEM_NAME = 3;
  static final int FUNCTION_FILENAME = 4;
  static final int FUNCTION_START_LINE = 5;

  private static final int WIRE_TYPE_VARINT = 0;
  private static final int WIRE_TYPE_LEN = 2;

  private final Deque<ByteArrayOutputStream> stack = new ArrayDeque<>();

  PprofWriter() {
    stack.push(new ByteArrayOutputStream());
  }

  private ByteArrayOutputStream current() {
    return stack.peek();
  }

  private void writeRawByte(int b) {
    current().write(b);
  }

  void writeVarint(long value) {
    while ((value & ~0x7FL) != 0) {
      writeRawByte((byte) ((value & 0x7F) | 0x80));
      value >>>= 7;
    }
    writeRawByte((byte) (value & 0x7F));
  }

  void writeTag(int fieldNumber, int wireType) {
    writeVarint((long) fieldNumber << 3 | wireType);
  }

  void writeInt64(int fieldNumber, long value) {
    writeTag(fieldNumber, WIRE_TYPE_VARINT);
    writeVarint(value);
  }

  void writeString(int fieldNumber, String s) {
    byte[] bs = s.getBytes(StandardCharsets.UTF_8);
    writeTag(fieldNumber, WIRE_TYPE_LEN);
    writeVarint(bs.length);
    current().write(bs, 0, bs.length);
  }

  void writeBytes(int fieldNumber, byte[] data) {
    writeTag(fieldNumber, WIRE_TYPE_LEN);
    writeVarint(data.length);
    current().write(data, 0, data.length);
  }

  void beginMessage() {
    stack.push(new ByteArrayOutputStream());
  }

  void endMessage(int fieldNumber) {
    byte[] data = stack.pop().toByteArray();
    writeBytes(fieldNumber, data);
  }

  byte[] toByteArray() {
    assert stack.size() == 1;
    return stack.peek().toByteArray();
  }
}
