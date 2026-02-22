package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.jafar.parser.api.JafarConfigurationException;
import io.jafar.parser.impl.ValidationUtils;
import org.junit.jupiter.api.Test;

public class ValidationUtilsTest {

  @Test
  void validateJfrTypeHandler_acceptsPrimitivesAndString() {
    assertDoesNotThrow(() -> ValidationUtils.validateJfrTypeHandler(int.class));
    assertDoesNotThrow(() -> ValidationUtils.validateJfrTypeHandler(String.class));
  }

  @Test
  void validateJfrTypeHandler_rejectsNonInterface() {
    assertThrows(
        JafarConfigurationException.class,
        () -> ValidationUtils.validateJfrTypeHandler(Object.class));
  }

  @Test
  void validateJfrTypeHandler_requiresAnnotation() {
    interface NoAnno {}
    assertThrows(
        JafarConfigurationException.class,
        () -> ValidationUtils.validateJfrTypeHandler(NoAnno.class));
  }
}
