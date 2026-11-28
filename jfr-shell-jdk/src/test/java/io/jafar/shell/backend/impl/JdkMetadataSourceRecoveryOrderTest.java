package io.jafar.shell.backend.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.jafar.shell.backend.impl.ChunkLocalRecording.Chunk;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import jdk.jfr.EventType;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which descriptor the chunk-local type recovery gives for a type that two chunks define
 * differently (#198, item 3): the first one in file order, from both {@link
 * JdkJfrBackend.JdkMetadataSource#loadClass} and {@link
 * JdkJfrBackend.JdkMetadataSource#loadAllClasses}.
 *
 * <p>The recording is written here, four chunks. The first two take ids 101 and 102 for {@code
 * test.Other} and {@code test.Other2}, so the merged type view the JDK builds (the first chunk to
 * use an id keeps it) has no {@code test.Local}. Chunk 3 defines {@code test.Local} at id 101 with
 * a field {@code first}, chunk 4 at id 102 with a field {@code second}: two descriptors the
 * recovery scan both meets, under different ids, so neither hides the other before the choice
 * between them is made. The same recording with the two ids the other way round tells file order
 * from id order.
 */
class JdkMetadataSourceRecoveryOrderTest {

  private static final String LOCAL = "test.Local";

  @TempDir Path tempDir;

  /** The recording, with {@code test.Local} at {@code earlierId} in chunk 3 and the other in 4. */
  private Path write(String file, long earlierId) throws Exception {
    long laterId = earlierId == 101 ? 102 : 101;
    Path path = tempDir.resolve(file);
    Files.write(
        path,
        ChunkLocalRecording.write(
            new Chunk(101, "test.Other", List.of("other"), 1),
            new Chunk(102, "test.Other2", List.of("other2"), 1),
            new Chunk(earlierId, LOCAL, List.of("first"), 2),
            new Chunk(laterId, LOCAL, List.of("second"), 2)));
    return path;
  }

  private static JdkJfrBackend.JdkMetadataSource source() {
    return new JdkJfrBackend.JdkMetadataSource(1_000, warning -> {});
  }

  @SuppressWarnings("unchecked")
  private static List<String> fieldNames(Map<String, Object> meta) {
    return new ArrayList<>(((Map<String, Object>) meta.get("fieldsByName")).keySet());
  }

  /** The fixture has the shape the test needs, as the JDK itself reads it. */
  @Test
  void fixtureLosesTheTypeInTheMergedViewAndCarriesBothDescriptors() throws Exception {
    Path recording = write("shape.jfr", 101);
    List<String> merged = new ArrayList<>();
    try (RecordingFile rf = new RecordingFile(recording)) {
      for (EventType type : rf.readEventTypes()) {
        merged.add(type.getId() + ":" + type.getName());
      }
    }
    assertEquals(List.of("101:test.Other", "102:test.Other2"), merged);

    List<String> local = new ArrayList<>();
    try (RecordingFile rf = new RecordingFile(recording)) {
      while (rf.hasMoreEvents()) {
        RecordedEvent event = rf.readEvent();
        if (LOCAL.equals(event.getEventType().getName())) {
          local.add(event.getEventType().getId() + ":" + event.getFields().get(1).getName());
        }
      }
    }
    assertEquals(List.of("101:first", "101:first", "102:second", "102:second"), local);
  }

  @Test
  void loadClassGivesTheFirstDescriptorInFileOrder() throws Exception {
    Map<String, Object> meta = source().loadClass(write("a.jfr", 101), LOCAL);
    assertNotNull(meta);
    assertEquals(101L, meta.get("id"));
    assertEquals(List.of("startTime", "first"), fieldNames(meta));

    // The earlier chunk now has the higher id: still the earlier descriptor, so it is file order
    // that decides, not the id
    Map<String, Object> higherIdFirst = source().loadClass(write("b.jfr", 102), LOCAL);
    assertEquals(102L, higherIdFirst.get("id"));
    assertEquals(List.of("startTime", "first"), fieldNames(higherIdFirst));
  }

  @Test
  void loadAllClassesListsTheTypeOnceWithTheFirstDescriptor() throws Exception {
    for (long earlierId : new long[] {101, 102}) {
      List<Map<String, Object>> all =
          source().loadAllClasses(write("c" + earlierId + ".jfr", earlierId));
      List<Map<String, Object>> local =
          all.stream().filter(m -> LOCAL.equals(m.get("name"))).toList();
      assertEquals(1, local.size(), all.toString());
      assertEquals(earlierId, local.get(0).get("id"));
      assertEquals(List.of("startTime", "first"), fieldNames(local.get(0)));
    }
  }
}
