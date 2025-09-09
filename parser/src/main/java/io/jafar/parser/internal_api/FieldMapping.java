package io.jafar.parser.internal_api;

final class FieldMapping {
  private final String method;
  private final boolean raw;

  FieldMapping(String method, boolean raw) {
    this.method = method;
    this.raw = raw;
  }

  String method() {
    return method;
  }

  boolean raw() {
    return raw;
  }
}
