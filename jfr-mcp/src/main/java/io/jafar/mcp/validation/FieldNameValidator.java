package io.jafar.mcp.validation;

import java.util.regex.Pattern;

/** Validates query field names before they are interpolated into generated query strings. */
public final class FieldNameValidator {

  private static final Pattern SAFE_FIELD_NAME = Pattern.compile("^[a-zA-Z0-9_.:-]+$");

  private FieldNameValidator() {}

  public static String requireSafeFieldName(String name, String paramName) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException(paramName + " must not be blank");
    }
    if (!SAFE_FIELD_NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("Invalid " + paramName + ": '" + name + "'");
    }
    return name;
  }
}
