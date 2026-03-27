package io.jafar.parser.internal_api;

final class FieldMapping {
  private final String method;
  private final boolean raw;
  private final Class<?> expectedReturnType;

  FieldMapping(String method, boolean raw) {
    this.method = method;
    this.raw = raw;
    this.expectedReturnType = null;
  }

  FieldMapping(String method, boolean raw, Class<?> expectedReturnType) {
    this.method = method;
    this.raw = raw;
    this.expectedReturnType = expectedReturnType;
  }

  String method() {
    return method;
  }

  boolean raw() {
    return raw;
  }

  Class<?> expectedReturnType() {
    return expectedReturnType;
  }
}
