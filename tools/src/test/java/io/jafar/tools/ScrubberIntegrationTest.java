package io.jafar.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.jafar.parser.JfrTestHelper;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.UntypedJafarParser;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScrubberIntegrationTest {

  @Test
  void scrubFileProducesValidJfr(@TempDir Path tempDir) throws Exception {
    Path input = testResource("test-ap.jfr");
    Path output = tempDir.resolve("scrubbed.jfr");

    Scrubber.scrubFile(
        input,
        output,
        typeName -> {
          if ("jdk.InitialSystemProperty".equals(typeName)) {
            return new Scrubber.ScrubField("key", "value", (key, value) -> "java.home".equals(key));
          }
          return null;
        });

    ParsingContext ctx = ParsingContext.create();
    try (UntypedJafarParser parser = ctx.newUntypedParser(output)) {
      AtomicInteger count = new AtomicInteger();
      parser.handle((type, event, ctl) -> count.incrementAndGet());
      parser.run();
      assertThat(count.get()).isGreaterThan(0);
    }
  }

  @Test
  void scrubbedFieldContainsXBytes(@TempDir Path tempDir) throws Exception {
    Path input = testResource("test-ap.jfr");
    Path output = tempDir.resolve("scrubbed.jfr");

    Scrubber.scrubFile(
        input,
        output,
        typeName -> {
          if ("jdk.InitialSystemProperty".equals(typeName)) {
            return new Scrubber.ScrubField("key", "value", (key, value) -> "java.home".equals(key));
          }
          return null;
        });

    ParsingContext ctx = ParsingContext.create();
    try (UntypedJafarParser parser = ctx.newUntypedParser(output)) {
      List<String> scrubbedValues = new ArrayList<>();
      parser.handle(
          (type, event, ctl) -> {
            if ("jdk.InitialSystemProperty".equals(type.getName())) {
              String key = (String) event.get("key");
              if ("java.home".equals(key)) {
                scrubbedValues.add((String) event.get("value"));
              }
            }
          });
      parser.run();

      assertThat(scrubbedValues).isNotEmpty();
      assertThat(scrubbedValues).allMatch(v -> v.matches("x+"));
    }
  }

  @Test
  void guardPreventsScrubbing(@TempDir Path tempDir) throws Exception {
    Path input = testResource("test-ap.jfr");
    Path output = tempDir.resolve("scrubbed.jfr");

    Scrubber.scrubFile(
        input,
        output,
        typeName -> {
          if ("jdk.InitialSystemProperty".equals(typeName)) {
            return new Scrubber.ScrubField("key", "value", (key, value) -> false);
          }
          return null;
        });

    assertThat(Files.readAllBytes(output)).isEqualTo(Files.readAllBytes(input));
  }

  @Test
  void noMatchingEventProducesIdenticalOutput(@TempDir Path tempDir) throws Exception {
    Path input = testResource("test-ap.jfr");
    Path output = tempDir.resolve("scrubbed.jfr");

    Scrubber.scrubFile(input, output, typeName -> null);

    assertThat(Files.readAllBytes(output)).isEqualTo(Files.readAllBytes(input));
  }

  @Test
  void scrubWithoutGuard(@TempDir Path tempDir) throws Exception {
    Path input = testResource("test-ap.jfr");
    Path output = tempDir.resolve("scrubbed.jfr");

    Scrubber.scrubFile(
        input,
        output,
        typeName -> {
          if ("jdk.InitialSystemProperty".equals(typeName)) {
            return new Scrubber.ScrubField(null, "value", null);
          }
          return null;
        });

    ParsingContext ctx = ParsingContext.create();
    try (UntypedJafarParser parser = ctx.newUntypedParser(output)) {
      List<String> values = new ArrayList<>();
      parser.handle(
          (type, event, ctl) -> {
            if ("jdk.InitialSystemProperty".equals(type.getName())) {
              String value = (String) event.get("value");
              if (value != null) {
                values.add(value);
              }
            }
          });
      parser.run();

      assertThat(values).isNotEmpty();
      assertThat(values).allMatch(v -> v.matches("x+"));
    }
  }

  @Test
  void scrubSyntheticJfr(@TempDir Path tempDir) throws Exception {
    Path input = tempDir.resolve("synthetic.jfr");
    Path output = tempDir.resolve("scrubbed.jfr");

    JfrTestHelper.create(input)
        .eventType("test.ScrubEvent")
        .stringField("name")
        .stringField("secret")
        .stringField("description")
        .event("item1", "sensitive-data", "desc1")
        .event("item2", "more-secret", "desc2")
        .build();

    Scrubber.scrubFile(
        input,
        output,
        typeName -> {
          if ("test.ScrubEvent".equals(typeName)) {
            return new Scrubber.ScrubField(null, "secret", null);
          }
          return null;
        });

    // Scrubbed file must differ from input (the "secret" field bytes were replaced)
    assertThat(Files.readAllBytes(output)).isNotEqualTo(Files.readAllBytes(input));

    // Scrubbed file must still be valid JFR
    ParsingContext ctx = ParsingContext.create();
    try (UntypedJafarParser parser = ctx.newUntypedParser(output)) {
      AtomicInteger eventCount = new AtomicInteger();
      parser.handle(
          (type, event, ctl) -> {
            if ("test.ScrubEvent".equals(type.getName())) {
              eventCount.incrementAndGet();
              // The scrubbed "secret" field should be a valid string (not null)
              Object secret = event.get("secret");
              assertThat(secret).isInstanceOf(String.class);
            }
          });
      parser.run();
      assertThat(eventCount.get()).isEqualTo(2);
    }
  }

  private static Path testResource(String name) throws Exception {
    URI uri = ScrubberIntegrationTest.class.getClassLoader().getResource(name).toURI();
    return Paths.get(new File(uri).getAbsolutePath());
  }
}
