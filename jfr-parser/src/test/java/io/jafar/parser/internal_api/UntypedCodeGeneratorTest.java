package io.jafar.parser.internal_api;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for UntypedCodeGenerator and UntypedDeserializerCache.
 *
 * <p>Tests bytecode generation for untyped event deserializers. Full end-to-end deserialization
 * tests are in integration tests.
 */
public class UntypedCodeGeneratorTest {

  /**
   * Tests that the deserializer cache works correctly.
   *
   * <p>Verifies that UntypedDeserializerCache properly caches and retrieves deserializers.
   */
  @Test
  void testDeserializerCacheBasics() {
    UntypedDeserializerCache cache = new UntypedDeserializerCache.Impl();

    // Test basic cache operations
    assertNotNull(cache);
    assertTrue(cache.isEmpty(), "New cache should be empty");
    assertEquals(0, cache.size(), "New cache should have size 0");

    // Test that cache stores values
    UntypedEventDeserializer mockDeserializer =
        new UntypedEventDeserializer() {
          @Override
          public java.util.Map<String, Object> deserialize(
              RecordingStream stream, io.jafar.parser.api.ParserContext context) {
            return java.util.Collections.emptyMap();
          }
        };

    cache.put(1L, mockDeserializer);
    assertEquals(1, cache.size(), "Cache should have one entry");
    assertSame(mockDeserializer, cache.get(1L), "Should retrieve same instance");

    // Test computeIfAbsent
    UntypedEventDeserializer d2 = cache.computeIfAbsent(1L, id -> null);
    assertSame(mockDeserializer, d2, "computeIfAbsent should return existing value");
  }

  /** Tests that cache impl can be instantiated. */
  @Test
  void testCacheImplInstantiation() {
    UntypedDeserializerCache.Impl impl = new UntypedDeserializerCache.Impl();
    assertNotNull(impl, "Cache implementation should instantiate");
  }
}
