package io.jafar.testsupport.jfr;

import io.jafar.parser.api.ArrayType;
import io.jafar.parser.api.ComplexType;
import io.jafar.parser.api.ConstantPool;
import io.jafar.parser.api.ParserContext;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.api.Values;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.MutableConstantPool;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.internal_api.metadata.MetadataEvent;
import io.jafar.parser.internal_api.metadata.MetadataField;
import io.jafar.testsupport.DumpDigest;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The one canonical JFR value-dump format (R7): the golden tests (T20) and the Java side of the
 * Java/Go differential (T34) both render through this class, and the Go harness ({@code
 * go-parser/internal/differential}) mirrors it line for line. There is no second JFR line format to
 * drift from it.
 *
 * <h2>Event lines</h2>
 *
 * One line per delivered event: {@code <type> k=v k=v …} with
 *
 * <ul>
 *   <li>values taken from {@link Values#resolvedDeep(Map)} — never {@code resolvedDeepCollapsed}: a
 *       reference to a JFR <em>simple type</em> stays a one-entry wrapper map, as the Go side's
 *       {@code ResolveDeepMap} does (the only known such residue, {@code
 *       datadog.Endpoint.endpoint}, is root-caused separately in T35 and must not be normalized
 *       here);
 *   <li>keys are dotted paths into nested maps, sorted lexicographically;
 *   <li>arrays contribute one key per element, indexed {@code .0 .1 …} — including nested arrays
 *       ({@code k.0.1=…}) and primitive arrays other than {@code byte[]};
 *   <li>{@code byte[]} values, and one-entry {@code {"string": byte[]}} wrapper maps (an unresolved
 *       string constant), render as {@code b<len>:<hex>};
 *   <li>{@code \n} and {@code \r} are escaped inside keys and values — real recordings embed raw
 *       newlines (/proc/cpuinfo, os-release), which would otherwise split one event over several
 *       lines and corrupt line-per-event counting;
 *   <li>{@code null} renders as {@code null};
 *   <li>{@code float} and {@code double} render as {@code f:} plus the 16 hex digits of {@code
 *       Double.doubleToLongBits((double) v)} — the IEEE-754 bit pattern, so {@code -0.0}, {@code
 *       0.0} and NaN are distinct and locale-independent;
 *   <li>no chunk identity: two chunks parsed in parallel produce the same sorted dump (AC-4.3),
 *       which is why the collected lines may be sorted across chunks.
 * </ul>
 *
 * <h2>Dump file header (the differential dump, not the golden)</h2>
 *
 * {@code # jafar-dump v1 side=java fixture-sha256=<hex> events=<n>} — the Go side writes the same
 * header with {@code side=go}, so {@code DumpComparator} can validate both dumps before comparing a
 * single line.
 *
 * <h2>Golden-only sections</h2>
 *
 * {@link #digest(Path, boolean) digest(rec, true)} additionally collects the constant pools and the
 * chunk metadata, which the Go differential does not mirror. Their lines are prefixed {@code C }
 * and {@code M } and keyed by the chunk ordinal delivered to the chunk callbacks, so a multi-chunk
 * recording's per-chunk state stays distinguishable without putting chunk identity into event
 * lines:
 *
 * <ul>
 *   <li>{@code C <chunk> <poolType>#<id> k=v …} — one constant-pool entry per line, its map
 *       flattened by the same rules as an event; an entry that cannot be decoded (the pool caches
 *       the failure) renders as {@code C <chunk> <poolType>#<id> error=<throwable class>}, which
 *       the golden pins just as deterministically;
 *   <li>{@code M <chunk> metadata id=<metadataId> classes=<n>}, then per class {@code M <chunk>
 *       class id=<id> name=<name> super=<super> simpleType=<b> primitive=<b> fields=<n>} and per
 *       field {@code M <chunk> class id=<classId> field name=<name> type=<typeName> typeId=<typeId>
 *       constantPool=<b> dimension=<d>}. An absent name/super/type renders as {@code -}, which no
 *       metadata element can be named.
 * </ul>
 *
 * <h2>Threading</h2>
 *
 * Chunks are parsed on the parser's own pool, so the chunk callbacks and the event handler run
 * concurrently. Lines are collected into a synchronized list and counts into a concurrent map
 * (kb/probe-pitfalls 4: a plain {@code ArrayList} loses events there). The constant pools are read
 * in {@code onChunkEnd} — by then the chunk's checkpoint has been processed and the entries are
 * decoded from the same thread that decoded the chunk's events, which is the single-consumer thread
 * {@code MutableConstantPool.getAsMap} is designed for.
 *
 * <p>Java 8 source, like the rest of test-support, so the JDK-8 cells can run it too.
 */
public final class JfrCanonicalDump {

  /** The dump format marker; the header line starts with it. Mirrored by the Go harness. */
  public static final String DUMP_FORMAT = "# jafar-dump v1";

  /** This side's name in the header; the Go harness writes {@code side=go}. */
  public static final String SIDE = "java";

  private static final String NULL = "null";

  private JfrCanonicalDump() {}

  /**
   * The sorted event lines of {@code recording}: one canonical line per delivered event, no header
   * and no golden-only sections. The differential dump is exactly these lines under the header.
   *
   * <p>The full dump is materialized (the ~480 MB tck-test.jfr deep-resolved dump needs a large
   * heap); only {@link DumpDigest} keeps a small fixed-size identity of it.
   */
  public static List<String> events(Path recording) throws IOException {
    return dump(recording, false);
  }

  /**
   * The full sorted dump: the event lines plus — when {@code withConstantsAndMetadata} — the
   * golden-only {@code C } and {@code M } lines. Package-private: the format test asserts on it;
   * production callers take {@link #events(Path)}, {@link #digest(Path, boolean)} or {@link
   * #writeEventDump(Path, Path)}, which never expose the section lines.
   */
  static List<String> dump(Path recording, boolean withConstantsAndMetadata) throws IOException {
    List<String> lines = Collections.synchronizedList(new ArrayList<String>());
    parse(recording, withConstantsAndMetadata, lines, null);
    return sorted(lines);
  }

  /**
   * The value identity of {@code recording} (see {@link DumpDigest}): per-line hashes over the
   * sorted event lines, plus — when {@code withConstantsAndMetadata} — the golden-only {@code C }
   * and {@code M } lines, and the per-event-type counts.
   */
  public static DumpDigest digest(Path recording, boolean withConstantsAndMetadata)
      throws IOException {
    List<String> lines = Collections.synchronizedList(new ArrayList<String>());
    Map<String, Long> counts = new ConcurrentHashMap<String, Long>();
    parse(recording, withConstantsAndMetadata, lines, counts);
    return DumpDigest.of(sha256Of(recording), sorted(lines), counts);
  }

  /**
   * Writes the differential event dump of {@code recording} to {@code out}: the header (with this
   * side, the recording's sha256 and the event count) followed by the sorted event lines. No
   * golden-only sections — the Go harness cannot produce them.
   */
  public static void writeEventDump(Path recording, Path out) throws IOException {
    List<String> lines = events(recording);
    try (OutputStream stream = Files.newOutputStream(out)) {
      stream.write(
          (DUMP_FORMAT
                  + " side="
                  + SIDE
                  + " fixture-sha256="
                  + sha256Of(recording)
                  + " events="
                  + lines.size()
                  + "\n")
              .getBytes(StandardCharsets.UTF_8));
      for (String line : lines) {
        stream.write(line.getBytes(StandardCharsets.UTF_8));
        stream.write('\n');
      }
    }
  }

  /**
   * The canonical rendering of one event map — the single line format every JFR net shares (the
   * golden and differential dumps, the shared-context stress reference, the typed/untyped parity
   * renders). The map is resolved with {@link Values#resolvedDeep} here, so no caller can skip the
   * normalization step and quietly render a different shape.
   *
   * @param typeName the event type's metadata name (the line's first token)
   * @param event the raw event map, as delivered to an untyped handler
   */
  public static String eventLine(String typeName, Map<String, Object> event) {
    StringBuilder line = new StringBuilder(escape(typeName));
    for (Map.Entry<String, String> field : flatten(event).entrySet()) {
      line.append(' ').append(field.getKey()).append('=').append(field.getValue());
    }
    return line.toString();
  }

  /**
   * Flattens a resolved event (or constant-pool entry) map into its sorted dotted-key view. Also
   * unwraps a still-wrapped {@link ComplexType} or {@link ArrayType}, so a caller that did not
   * resolve first still gets the canonical shape rather than a wrapper's {@code toString}.
   */
  static SortedMap<String, String> flatten(Map<String, Object> event) {
    SortedMap<String, String> out = new TreeMap<String, String>();
    flattenInto(stringKeyed(Values.resolvedDeep(event)), "", out);
    return out;
  }

  private static void flattenInto(
      Map<String, Object> map, String prefix, SortedMap<String, String> out) {
    for (Map.Entry<String, Object> entry : map.entrySet()) {
      String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
      flattenValue(key, entry.getValue(), out);
    }
  }

  /**
   * One value at one dotted key. A one-entry {@code {"string": byte[]}} map is an unresolved string
   * constant and renders like any other byte array; every other map recurses, every array (object,
   * nested or primitive except {@code byte[]}) indexes its elements, and everything else is a
   * scalar.
   */
  private static void flattenValue(String key, Object value, SortedMap<String, String> out) {
    if (value instanceof ComplexType) {
      flattenInto(stringKeyed(((ComplexType) value).getValue()), key, out);
    } else if (value instanceof ArrayType) {
      flattenValue(key, ((ArrayType) value).getArray(), out);
    } else if (value instanceof Map) {
      Map<?, ?> map = (Map<?, ?>) value;
      if (map.size() == 1 && map.containsKey("string") && map.get("string") instanceof byte[]) {
        out.put(key, bytes((byte[]) map.get("string")));
      } else {
        flattenInto(stringKeyed(map), key, out);
      }
    } else if (value instanceof byte[]) {
      out.put(key, bytes((byte[]) value));
    } else if (value instanceof List) {
      List<?> list = (List<?>) value;
      for (int i = 0; i < list.size(); i++) {
        flattenValue(key + "." + i, list.get(i), out);
      }
    } else if (value != null && value.getClass().isArray()) {
      // every other array — Object[], nested arrays, primitive arrays — indexes its elements;
      // Array.get boxes the primitives, so each element lands in this method again
      int length = Array.getLength(value);
      for (int i = 0; i < length; i++) {
        flattenValue(key + "." + i, Array.get(value, i), out);
      }
    } else {
      out.put(key, scalar(value));
    }
  }

  /** A scalar's rendering: {@code null}, an IEEE-754 bit pattern for floats, text for the rest. */
  static String scalar(Object value) {
    if (value == null) {
      return NULL;
    }
    if (value instanceof Float || value instanceof Double) {
      return floatBits(((Number) value).doubleValue());
    }
    return escape(String.valueOf(value));
  }

  /**
   * {@code f:} plus the 16 hex digits of {@link Double#doubleToLongBits} of the widened value.
   * {@code doubleToLongBits} (not the raw variant) canonicalizes NaN, so a NaN decoded from a float
   * and one decoded from a double agree, and the rendering never carries a JVM-specific NaN
   * payload.
   */
  static String floatBits(double value) {
    return String.format(Locale.ROOT, "f:%016x", Double.doubleToLongBits(value));
  }

  /** {@code b<len>:<hex>} — the rendering of every byte array and string-constant wrapper. */
  static String bytes(byte[] bytes) {
    StringBuilder out = new StringBuilder(bytes.length * 2 + 6);
    out.append('b').append(bytes.length).append(':');
    for (byte b : bytes) {
      out.append(Character.forDigit((b >> 4) & 0xf, 16));
      out.append(Character.forDigit(b & 0xf, 16));
    }
    return out.toString();
  }

  /**
   * Escapes the two characters that would split a dump line: a raw newline in a value (/proc
   * cpuinfo, os-release, libc strings) turns one event into several lines and silently changes the
   * line count. Keys are escaped too: they come from the recording's metadata, which a corrupted or
   * adversarial recording controls.
   */
  static String escape(String text) {
    return text.replace("\n", "\\n").replace("\r", "\\r");
  }

  /** Copy of {@code map} with {@code String} keys; untyped maps can carry non-string keys. */
  private static Map<String, Object> stringKeyed(Map<?, ?> map) {
    Map<String, Object> out = new TreeMap<String, Object>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      out.put(String.valueOf(entry.getKey()), entry.getValue());
    }
    return out;
  }

  private static List<String> sorted(List<String> lines) {
    List<String> sorted = new ArrayList<String>(lines);
    Collections.sort(sorted);
    return sorted;
  }

  private static void parse(
      Path recording,
      boolean withSections,
      final List<String> lines,
      final Map<String, Long> counts)
      throws IOException {
    ParsingContext context = ParsingContext.create();
    ChunkParserListener sections = withSections ? new Sections(lines) : null;
    // withParserListener(null) is the no-delegate parser, the same one a plain newUntypedParser
    // run uses, so both dump modes go through one code path
    try (UntypedJafarParser parser =
        context.newUntypedParser(recording).<UntypedJafarParser>withParserListener(sections)) {
      parser.handle(
          (type, event, ctl) -> {
            lines.add(eventLine(type.getName(), event));
            if (counts != null) {
              counts.merge(type.getName(), 1L, Long::sum);
            }
          });
      parser.run();
    } catch (IOException e) {
      throw e;
    } catch (Exception e) {
      throw new IOException(e);
    }
  }

  /**
   * Collects the golden-only sections. Every callback runs on the thread parsing its chunk, so the
   * shared {@code lines} list must be synchronized — and it is: every caller passes the
   * synchronized list the parse collects into.
   */
  private static final class Sections implements ChunkParserListener {

    private final List<String> lines;

    Sections(List<String> lines) {
      this.lines = lines;
    }

    @Override
    public boolean onMetadata(ParserContext context, MetadataEvent metadata) {
      int chunk = context.getChunkIndex();
      lines.add(
          "M "
              + chunk
              + " metadata id="
              + metadata.metadataId
              + " classes="
              + metadata.getClasses().size());
      for (MetadataClass clazz : metadata.getClasses()) {
        renderClass(chunk, clazz);
      }
      return true;
    }

    private void renderClass(int chunk, MetadataClass clazz) {
      String name = clazz.getName() == null ? "-" : escape(clazz.getName());
      String superType = clazz.getSuperType() == null ? "-" : escape(clazz.getSuperType());
      lines.add(
          "M "
              + chunk
              + " class id="
              + clazz.getId()
              + " name="
              + name
              + " super="
              + superType
              + " simpleType="
              + clazz.isSimpleType()
              + " primitive="
              + clazz.isPrimitive()
              + " fields="
              + clazz.getFields().size());
      for (MetadataField field : clazz.getFields()) {
        MetadataClass type = field.getType();
        String typeName = type == null || type.getName() == null ? "-" : escape(type.getName());
        lines.add(
            "M "
                + chunk
                + " class id="
                + clazz.getId()
                + " field name="
                + escape(field.getName())
                + " type="
                + typeName
                + " typeId="
                + field.getTypeId()
                + " constantPool="
                + field.hasConstantPool()
                + " dimension="
                + field.getDimension());
      }
    }

    @Override
    public boolean onChunkEnd(ParserContext context, int chunkIndex, boolean skipped) {
      if (skipped) {
        return true; // an empty or aborted chunk contributed no pools worth pinning
      }
      int chunk = context.getChunkIndex();
      // pools() iterates an unsynchronized map; snapshot it once, then render each entry on this
      // chunk's own parsing thread, after its checkpoint — the thread getAsMap is designed for
      for (ConstantPool pool : context.getConstantPools().pools().collect(Collectors.toList())) {
        renderPool(chunk, pool);
      }
      return true;
    }

    private void renderPool(int chunk, ConstantPool pool) {
      if (!(pool instanceof MutableConstantPool)) {
        throw new AssertionError(
            "unexpected constant pool implementation " + pool.getClass().getName());
      }
      MutableConstantPool mutable = (MutableConstantPool) pool;
      if (!mutable.isIndexed()) {
        return; // a pool with no entries has nothing to pin
      }
      String poolName = escape(pool.getType().getName());
      List<Long> ids = new ArrayList<Long>();
      for (java.util.Iterator<Long> it = mutable.ids(); it.hasNext(); ) {
        ids.add(it.next());
      }
      Collections.sort(ids);
      for (Long id : ids) {
        renderEntry(chunk, poolName, mutable, id);
      }
    }

    private void renderEntry(int chunk, String poolName, MutableConstantPool pool, long id) {
      try {
        Map<String, Object> entry = pool.getAsMap(id);
        StringBuilder line =
            new StringBuilder("C ")
                .append(chunk)
                .append(' ')
                .append(poolName)
                .append('#')
                .append(id);
        if (entry == null) {
          line.append(' ').append("value=null");
        } else {
          for (Map.Entry<String, String> field : flatten(entry).entrySet()) {
            line.append(' ').append(field.getKey()).append('=').append(field.getValue());
          }
        }
        lines.add(line.toString());
      } catch (RuntimeException undecodable) {
        // the pool caches the failure per entry and rethrows it forever: pin it, do not fail the
        // dump — a golden over it is exactly as deterministic as one over the value
        lines.add(
            "C "
                + chunk
                + ' '
                + poolName
                + '#'
                + id
                + " error="
                + undecodable.getClass().getName());
      }
    }
  }

  /** sha256 of the recording's bytes — the fixture identity the golden and the header carry. */
  private static String sha256Of(Path recording) throws IOException {
    return DumpDigest.sha256Hex(Files.readAllBytes(recording));
  }
}
