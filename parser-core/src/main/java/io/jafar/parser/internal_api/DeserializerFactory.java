package io.jafar.parser.internal_api;

import io.jafar.parser.internal_api.metadata.MetadataClass;

/**
 * SPI for creating {@link Deserializer} instances from metadata classes.
 *
 * <p>Implementations are discovered via {@link java.util.ServiceLoader}. The core parser module
 * defines this interface; the typed parser module provides the ASM-based implementation.
 */
@FunctionalInterface
public interface DeserializerFactory {
  Deserializer<?> create(MetadataClass clazz);
}
