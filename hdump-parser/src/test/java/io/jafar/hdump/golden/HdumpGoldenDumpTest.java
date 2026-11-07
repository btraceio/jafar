package io.jafar.hdump.golden;

import io.jafar.hdump.api.GcRoot;
import io.jafar.hdump.api.HeapClass;
import io.jafar.hdump.api.HeapDump;
import io.jafar.hdump.api.HeapDumpParser;
import io.jafar.hdump.api.HeapField;
import io.jafar.hdump.api.HeapObject;
import io.jafar.hdump.test.SyntheticHeapDumpGenerator;
import io.jafar.testsupport.DumpDigest;
import io.jafar.testsupport.GoldenFile;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The hdump golden-value-dump gate (AC-4.1): the A5 instance-fields synthetic fixture (T14's
 * allocation fixture) is rendered through the public {@code io.jafar.hdump.api} model — classes and
 * their fields, instances and their resolved field values, GC roots, and String objects — into
 * sorted {@code <kind> k=v…} lines, and its value identity compared against the committed golden in
 * {@code hdump-parser/src/test/golden}.
 *
 * <p>The fixture's bytes are deliberately NOT pinned: {@code SyntheticHeapDumpGenerator} writes
 * {@code System.currentTimeMillis()} into the HPROF header, so the file — and its sha256 — differ
 * on every run. The digest is therefore built with a {@code null} fixture sha (the golden records
 * an empty {@code fixture-sha256} line and {@link GoldenFile} skips the fixture-bytes check), and
 * the header timestamp is likewise kept out of the dump lines, so a golden regenerated on any other
 * day still matches. Both omissions are the price of the generator's wall-clock header; everything
 * else the parser decodes is in the dump.
 *
 * <p>A change to any decoded value — a swapped reference resolution in the instance-field decode
 * path, a misread GC root type — fails here naming the fixture and the regeneration command
 * (AC-4.2, the DataShapes "shapes that lie" class: none of the older tests asserted on the field
 * payload of a parsed object graph).
 */
class HdumpGoldenDumpTest {

  /** The documented regeneration command, printed in every failure and stored in every golden. */
  private static final String REGEN_COMMAND =
      "./gradlew :hdump-parser:test --tests '*HdumpGoldenDumpTest' -Pjafar.golden.update --rerun";

  private static final String FIXTURE = "a5-instance-fields.hprof";

  @TempDir Path tempDir;

  @Test
  void goldenDumpMatches() throws IOException {
    Path file = tempDir.resolve(FIXTURE);
    SyntheticHeapDumpGenerator.generateA5InstanceFields(file);
    List<String> dump;
    try (HeapDump heap = HeapDumpParser.parse(file)) {
      dump = render(heap);
    }
    // Null fixture sha: the generator's HPROF header carries System.currentTimeMillis(), so the
    // fixture bytes change every run — pinning them would fail every test run (see class Javadoc).
    GoldenFile.verify(FIXTURE, DumpDigest.of(null, dump, counts(dump)), () -> dump, REGEN_COMMAND);
  }

  /**
   * The full value dump, sorted: one line per heap identity, first token the kind. Counts are
   * derived from the rendered lines themselves, so the golden's {@code count.<kind>} lines can
   * never drift from what was actually dumped (R7).
   */
  private static List<String> render(HeapDump heap) {
    List<String> lines = new ArrayList<>();
    // The header line pins format version and identifier size — everything the header carries
    // except the wall-clock timestamp, which the generator rewrites on every run and which is
    // therefore kept out of the dump (see class Javadoc).
    lines.add("heap format=" + heap.getFormatVersion() + " idSize=" + heap.getIdSize());
    for (HeapClass cls : heap.getClasses()) {
      lines.add(classLine(cls));
      for (HeapField field : cls.getInstanceFields()) {
        lines.add(fieldLine(cls, field));
      }
      for (HeapField field : cls.getStaticFields()) {
        lines.add(fieldLine(cls, field));
      }
    }
    for (HeapObject obj : heap.getObjects().toList()) {
      lines.add(instanceLine(obj));
      String string = obj.getStringValue();
      if (string != null) {
        lines.add("string id=" + obj.getId() + " value=" + escape(string));
      }
    }
    for (GcRoot root : heap.getGcRoots()) {
      lines.add(
          "gc-root type="
              + root.getType()
              + " object="
              + root.getObjectId()
              + " threadSerial="
              + root.getThreadSerial()
              + " frameNumber="
              + root.getFrameNumber()
              + " threadObjSerial="
              + root.getThreadObjSerial());
    }
    Collections.sort(lines);
    return lines;
  }

  private static String classLine(HeapClass cls) {
    StringBuilder line = new StringBuilder("class");
    line.append(" id=").append(cls.getId());
    line.append(" name=").append(escape(cls.getName()));
    line.append(" super=")
        .append(cls.getSuperClass() == null ? "none" : cls.getSuperClass().getId());
    line.append(" loader=").append(cls.getClassLoaderId());
    line.append(" instanceSize=").append(cls.getInstanceSize());
    line.append(" instanceCount=").append(cls.getInstanceCount());
    line.append(" array=").append(cls.isArray());
    line.append(" primArray=").append(cls.isPrimitiveArray());
    line.append(" objArray=").append(cls.isObjectArray());
    if (cls.isArray()) {
      line.append(" component=")
          .append(cls.getComponentType() == null ? "none" : cls.getComponentType().getId());
    }
    return line.toString();
  }

  private static String fieldLine(HeapClass cls, HeapField field) {
    StringBuilder line = new StringBuilder("field");
    line.append(" class=").append(cls.getId());
    line.append(" name=").append(escape(field.getName()));
    line.append(" type=").append(field.getType());
    line.append(" typeName=").append(field.getTypeName());
    line.append(" static=").append(field.isStatic());
    if (field.isStatic()) {
      // Instance-field values live on the instance lines; a static field's value belongs here.
      line.append(" value=").append(value(field.getStaticValue()));
    }
    return line.toString();
  }

  private static String instanceLine(HeapObject obj) {
    StringBuilder line = new StringBuilder("instance");
    line.append(" id=").append(obj.getId());
    line.append(" class=").append(obj.getHeapClass() == null ? "none" : obj.getHeapClass().getId());
    line.append(" shallowSize=").append(obj.getShallowSize());
    line.append(" array=").append(obj.isArray());
    if (obj.isArray()) {
      line.append(" length=").append(obj.getArrayLength());
      Object[] elements = obj.getArrayElements();
      if (elements != null) {
        for (int i = 0; i < elements.length; i++) {
          line.append(" elem.").append(i).append('=').append(value(elements[i]));
        }
      }
    } else {
      // Sorted field order: getFieldValues() follows the class's field declaration order, which a
      // refactor may legitimately change; the dump must not depend on it.
      Map<String, Object> fields = new TreeMap<>(obj.getFieldValues());
      for (Map.Entry<String, Object> entry : fields.entrySet()) {
        line.append(" field.")
            .append(escape(entry.getKey()))
            .append('=')
            .append(value(entry.getValue()));
      }
    }
    return line.toString();
  }

  /**
   * One decoded value's rendering: {@code null}, an object reference as {@code @id}, floats by
   * their canonicalized IEEE-754 bit pattern (a NaN payload must not leak a JVM-specific bit
   * sequence into the digest), everything else as its text form.
   */
  private static String value(Object v) {
    if (v == null) {
      return "null";
    }
    if (v instanceof HeapObject obj) {
      return "@" + obj.getId();
    }
    if (v instanceof Float || v instanceof Double) {
      return String.format(
          Locale.ROOT, "f:%016x", Double.doubleToLongBits(((Number) v).doubleValue()));
    }
    return escape(String.valueOf(v));
  }

  /**
   * Escapes the two characters that would split a dump line: a raw newline in a value would turn
   * one record into several lines and silently change the line count (the embedded-newline
   * pitfall).
   */
  private static String escape(String text) {
    return text.replace("\n", "\\n").replace("\r", "\\r");
  }

  /** kind → count, derived from the sorted dump lines (the kind is the first token). */
  private static Map<String, Long> counts(List<String> sortedLines) {
    Map<String, Long> counts = new TreeMap<>();
    for (String line : sortedLines) {
      String kind = line.split(" ", 2)[0];
      counts.merge(kind, 1L, Long::sum);
    }
    return counts;
  }
}
