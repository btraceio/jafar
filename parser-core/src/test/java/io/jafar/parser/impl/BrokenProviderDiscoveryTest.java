package io.jafar.parser.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.ConstantPools;
import io.jafar.parser.api.HandlerFactory;
import io.jafar.parser.api.MetadataLookup;
import io.jafar.parser.internal_api.DeserializerFactory;
import io.jafar.parser.internal_api.RecordingStream;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A provider listed in {@code META-INF/services} can be unusable (class missing, static initializer
 * throws, provider answers an interface method with an exception). Discovery must skip it, keep the
 * usable providers, and never let the failure escape as an Error — the typed parser must not become
 * unusable because one jar on the classpath is broken.
 */
class BrokenProviderDiscoveryTest {

  @TempDir Path tempDir;

  // ── HandlerFactory (bytecode-generated deserializers) ─────────────────────

  /** A healthy provider: metadata answers are cheap and stable. */
  public static final class GoodFactory implements HandlerFactory<Object> {
    @Override
    public void bind(MetadataLookup metadata) {}

    @Override
    public Object get(RecordingStream stream, MetadataClass metadata, ConstantPools constantPools) {
      return null;
    }

    @Override
    public String getJfrTypeName() {
      return "jdk.GoodType";
    }

    @Override
    public Class<Object> getInterfaceClass() {
      return Object.class;
    }
  }

  /** Answers getJfrTypeName() by throwing — a broken-but-present provider. */
  public static final class ThrowingTypeNameFactory implements HandlerFactory<Object> {
    @Override
    public void bind(MetadataLookup metadata) {}

    @Override
    public Object get(RecordingStream stream, MetadataClass metadata, ConstantPools constantPools) {
      return null;
    }

    @Override
    public String getJfrTypeName() {
      throw new IllegalStateException("provider is half-loaded");
    }

    @Override
    public Class<Object> getInterfaceClass() {
      return Object.class;
    }
  }

  /** A healthy runtime DeserializerFactory. */
  public static final class GoodDeserializerFactory implements DeserializerFactory {
    @Override
    public io.jafar.parser.internal_api.Deserializer<?> create(MetadataClass clazz) {
      return null;
    }
  }

  @Test
  void unloadableFactoryIsSkippedAndGoodOnesSurvive() throws Exception {
    URLClassLoader loader =
        loaderWithServices(
            "io.jafar.parser.api.HandlerFactory",
            "com.example.does.not.Exist",
            GoodFactory.class.getName());

    List<String> skipped = new ArrayList<>();
    List<HandlerFactory> found =
        assertDoesNotThrow(() -> TypedJafarParserImpl.discoverFactories(loader, skipped));

    assertEquals(1, found.size());
    assertEquals(GoodFactory.class.getName(), found.get(0).getClass().getName());
    assertEquals(1, skipped.size());
  }

  @Test
  void factoryThrowingFromVettingMethodsIsSkipped() throws Exception {
    URLClassLoader loader =
        loaderWithServices(
            "io.jafar.parser.api.HandlerFactory",
            ThrowingTypeNameFactory.class.getName(),
            GoodFactory.class.getName());

    List<String> skipped = new ArrayList<>();
    List<HandlerFactory> found =
        assertDoesNotThrow(() -> TypedJafarParserImpl.discoverFactories(loader, skipped));

    assertEquals(1, found.size(), "the healthy provider must still be registered");
    assertEquals(1, skipped.size());
  }

  @Test
  void handlerFactoryDiscoveryDoesNotThrowWhenNothingIsUsable() throws Exception {
    URLClassLoader loader =
        loaderWithServices("io.jafar.parser.api.HandlerFactory", "com.example.does.not.Exist");

    List<String> skipped = new ArrayList<>();
    List<HandlerFactory> found =
        assertDoesNotThrow(() -> TypedJafarParserImpl.discoverFactories(loader, skipped));

    assertTrue(found.isEmpty());
    assertEquals(1, skipped.size());
  }

  @Test
  void unloadableDeserializerFactoryFallsBackToNull() throws Exception {
    URLClassLoader loader =
        loaderWithServices(
            "io.jafar.parser.internal_api.DeserializerFactory", "com.example.does.not.Exist");

    DeserializerFactory factory =
        assertDoesNotThrow(() -> ParsingContextImpl.discoverDeserializerFactory(loader));
    assertNull(factory);
  }

  @Test
  void goodDeserializerFactoryIsFoundDespiteABrokenSibling() throws Exception {
    URLClassLoader loader =
        loaderWithServices(
            "io.jafar.parser.internal_api.DeserializerFactory",
            GoodDeserializerFactory.class.getName());

    DeserializerFactory factory =
        assertDoesNotThrow(() -> ParsingContextImpl.discoverDeserializerFactory(loader));
    assertTrue(factory instanceof GoodDeserializerFactory);
  }

  private URLClassLoader loaderWithServices(String spi, String... providers) throws IOException {
    Path jar = tempDir.resolve(spi.substring(spi.lastIndexOf('.') + 1) + ".jar");
    try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
      out.putNextEntry(new JarEntry("META-INF/services/" + spi));
      out.write((String.join("\n", providers) + "\n").getBytes(StandardCharsets.UTF_8));
      out.closeEntry();
    }
    return new URLClassLoader(new URL[] {jar.toUri().toURL()}, getClass().getClassLoader());
  }
}
