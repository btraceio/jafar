package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

import io.jafar.parser.api.JafarConfigurationException;
import io.jafar.parser.impl.ValidationUtils;

public class ValidationUtilsTest {

    // Static nested interface for Java 8 compatibility
    interface NoAnno {}

    @Test
    void validateJfrTypeHandler_acceptsPrimitivesAndString() {
        assertDoesNotThrow(() -> ValidationUtils.validateJfrTypeHandler(int.class));
        assertDoesNotThrow(() -> ValidationUtils.validateJfrTypeHandler(String.class));
    }

    @Test
    void validateJfrTypeHandler_rejectsNonInterface() {
        assertThrows(JafarConfigurationException.class, () -> ValidationUtils.validateJfrTypeHandler(Object.class));
    }

    @Test
    void validateJfrTypeHandler_requiresAnnotation() {
        assertThrows(JafarConfigurationException.class, () -> ValidationUtils.validateJfrTypeHandler(NoAnno.class));
    }
}


