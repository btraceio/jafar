package io.jafar.parser.internal_api;

import io.jafar.parser.api.JafarSerializationException;
import io.jafar.parser.internal_api.metadata.MetadataClass;

/**
 * {@link DeserializerFactory} implementation that delegates to {@link CodeGenerator} for ASM-based
 * bytecode generation.
 */
public final class CodeGeneratorDeserializerFactory implements DeserializerFactory {
  @Override
  public Deserializer<?> create(MetadataClass clazz) {
    if (clazz.isPrimitive()) {
      return Deserializer.forPrimitive(clazz.getName());
    }
    try {
      return CodeGenerator.generateDeserializer(clazz);
    } catch (JafarSerializationException e) {
      throw new RuntimeException("Failed to generate deserializer for " + clazz.getName(), e);
    }
  }
}
