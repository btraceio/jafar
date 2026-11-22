package io.jafar.processor;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The field orders (layouts) recordings store JFR types in, for the straight-line reads the
 * generated handlers get: a handler is generated without the recording's metadata, so it reads the
 * fields in a loop that dispatches on each field's op; for a layout known here it also gets a read
 * with no dispatch, chosen once per metadata class when the recording's layout matches.
 *
 * <p>Two sources, read once per compilation:
 *
 * <ul>
 *   <li>recordings named by the {@code jafar.layouts} processor option (paths separated by {@code
 *       ,} or the platform's path separator): any producer, async-profiler included;
 *   <li>the JDK running the compiler, for {@code jdk.*} types, unless {@code jafar.layouts.jdk} is
 *       {@code false}: a recording started and stopped at once, dumped to a temporary file, carries
 *       the metadata of every event type that JDK writes, in the order it writes their fields.
 * </ul>
 *
 * Both are read with {@code jdk.jfr.consumer.RecordingFile}, which gives each type's fields in
 * recording order. Failing to read a source only loses its fast paths: the loop reads any layout.
 */
final class Layouts {
  static final String OPTION_RECORDINGS = "jafar.layouts";
  static final String OPTION_JDK = "jafar.layouts.jdk";

  /** JFR type name to its distinct layouts (field names in recording order), in discovery order. */
  private final Map<String, Set<List<String>>> byType = new LinkedHashMap<>();

  private final List<String> notes = new ArrayList<>();

  /** Reads the sources the options name, keeping the layouts of {@code types} only. */
  static Layouts load(Map<String, String> options, Set<String> types) {
    Layouts layouts = new Layouts();
    String recordings = options.get(OPTION_RECORDINGS);
    if (recordings != null) {
      for (String path : recordings.split("[," + File.pathSeparator + "]")) {
        if (!path.trim().isEmpty()) {
          layouts.read(Paths.get(path.trim()), types, path.trim());
        }
      }
    }
    boolean anyJdkType = types.stream().anyMatch(t -> t.startsWith("jdk."));
    if (anyJdkType && !"false".equalsIgnoreCase(options.get(OPTION_JDK))) {
      layouts.readCompilingJdk(types);
    }
    return layouts;
  }

  List<List<String>> of(String type) {
    Set<List<String>> set = byType.get(type);
    return set == null ? new ArrayList<>() : new ArrayList<>(set);
  }

  /** What was read or could not be, for the processor's notes. */
  List<String> notes() {
    return notes;
  }

  private void readCompilingJdk(Set<String> types) {
    Path dump = null;
    try {
      dump = Files.createTempFile("jafar-layouts", ".jfr");
      try (jdk.jfr.Recording r = new jdk.jfr.Recording()) {
        r.start();
        r.stop();
        r.dump(dump);
      }
      read(dump, types, "the compiling JDK (" + System.getProperty("java.version") + ")");
    } catch (Throwable t) {
      // no jdk.jfr module, JFR disabled, no temporary directory: no JDK fast paths
      notes.add("no layouts from the compiling JDK: " + t);
    } finally {
      if (dump != null) {
        try {
          Files.deleteIfExists(dump);
        } catch (IOException ignored) {
          // a temporary file left behind
        }
      }
    }
  }

  /** Records {@code fields}' order as a layout of {@code type} if wanted; 1 if it is new. */
  private int add(String type, List<jdk.jfr.ValueDescriptor> fields, Set<String> types) {
    if (!types.contains(type) || fields.isEmpty()) {
      return 0;
    }
    List<String> layout = new ArrayList<>();
    for (jdk.jfr.ValueDescriptor field : fields) {
      layout.add(field.getName());
    }
    return byType.computeIfAbsent(type, k -> new LinkedHashSet<>()).add(layout) ? 1 : 0;
  }

  private void walk(
      jdk.jfr.ValueDescriptor field, Set<String> types, Set<String> visited, int[] found) {
    List<jdk.jfr.ValueDescriptor> nested = field.getFields();
    if (nested.isEmpty() || !visited.add(field.getTypeName())) {
      return;
    }
    found[0] += add(field.getTypeName(), nested, types);
    for (jdk.jfr.ValueDescriptor f : nested) {
      walk(f, types, visited, found);
    }
  }

  private void read(Path recording, Set<String> types, String source) {
    try (jdk.jfr.consumer.RecordingFile file = new jdk.jfr.consumer.RecordingFile(recording)) {
      int[] found = {0};
      Set<String> visited = new java.util.HashSet<>();
      for (jdk.jfr.EventType type : file.readEventTypes()) {
        found[0] += add(type.getName(), type.getFields(), types);
        // the types its fields reach (jdk.types.StackTrace, java.lang.Thread, ...): their
        // fields come with the field's descriptor
        for (jdk.jfr.ValueDescriptor field : type.getFields()) {
          walk(field, types, visited, found);
        }
      }
      notes.add(found[0] + " layout(s) from " + source);
    } catch (Throwable t) {
      notes.add("no layouts from " + source + ": " + t);
    }
  }
}
