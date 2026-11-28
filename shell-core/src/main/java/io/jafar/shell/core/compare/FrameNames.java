package io.jafar.shell.core.compare;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The frame key a comparison matches frames by, for every format: {@code <class>.<method>} for a
 * managed frame and the symbol for a native one, without the parts that change from one JVM run or
 * one build to the next, and with a Java class name in its dotted form.
 *
 * <p>One place, because both sides of a comparison must agree: a JFR recording and its pprof
 * conversion name the same frame the same way only if both readers build the key here.
 */
public final class FrameNames {

  private FrameNames() {}

  /**
   * A hidden class's address, as in {@code LambdaForm$MH/0x00007f2e141a1000}: assigned per JVM run.
   * A recording may also store it after a dot, as in {@code Handler$$Lambda.0x00007fd6a823c220}
   * (the checked-in {@code tck-test.jfr} does); in that form it must be a whole segment, followed
   * by the method's dot or the end.
   */
  private static final Pattern HIDDEN_CLASS_ADDRESS =
      Pattern.compile("/0x[0-9a-fA-F]+|\\.0x[0-9a-fA-F]+(?=\\.|$)");

  /** A lambda class's sequence number, as in {@code Foo$$Lambda$14}: assigned per JVM run. */
  private static final Pattern LAMBDA_INDEX = Pattern.compile("\\$\\$Lambda\\$\\d+");

  /**
   * javac's number in a synthetic accessor ({@code access$300}, for code compiled for Java 8,
   * before nestmates) or a lambda body ({@code lambda$main$0}): assigned per compilation, in order
   * of appearance, so it changes whenever the class does.
   */
  private static final Pattern JAVAC_INDEX =
      Pattern.compile("(?<=\\.)(access|lambda\\$[\\w$]*?)\\$\\d+$");

  /** A line number jfr2pprof appends with {@code includeLineNumbers}: {@code :42}. */
  private static final Pattern LINE_SUFFIX = Pattern.compile(":\\d+$");

  /**
   * The frame-type mark async-profiler's agent appends in its collapsed and OTLP output: {@code
   * _[j]} JIT-compiled, {@code _[i]} inlined, {@code _[k]} kernel, {@code _[0]} interpreted, {@code
   * _[1]} C1; on an allocation profile's leaf, {@code _[i]} or {@code _[k]} for inside or outside a
   * TLAB ({@code byte[]_[i]}). Its JFR and its {@code jfrconv} output carry none.
   */
  private static final Pattern FRAME_TYPE_SUFFIX = Pattern.compile("_\\[[jik012]\\]$");

  /**
   * A whole name that is a Java class in internal (slashed) form plus a method: {@code
   * java/lang/String.indexOf}. A native path has no method part and does not match; a Go function
   * may ({@code encoding/json.Marshal}), which is why {@link #symbolKey} only applies it to a
   * profile that looks JVM-originated.
   */
  private static final Pattern JAVA_INTERNAL_NAME =
      Pattern.compile("[\\w$]+(/[\\w$]+)+\\.[\\w$<>]+");

  /**
   * {@code frame} without the parts the JVM assigns per run, a hidden class's {@code /0x...} (or
   * {@code .0x...}) address and a lambda class's {@code $$Lambda$<n>} number, and without the
   * numbers javac assigns per compilation, in {@code access$<n>} and {@code lambda$<method>$<n>}.
   * Two recordings come from two JVMs, often running two builds, so with those parts the same frame
   * has a different name on each side and reads as gone from one and new in the other. Frames that
   * differ only in those parts become one: across two runs they cannot be told apart.
   */
  public static String stableName(String frame) {
    // Fast path for the common frame. A hidden class need not have a '$' in its name (Jafar's
    // generated deserializers are io.jafar...UntypedDeserializer_<type>_<n>/0x...), so the '/0x'
    // check is needed too.
    if (frame.indexOf('$') < 0 && !frame.contains("/0x") && !frame.contains(".0x")) {
      return frame;
    }
    String name = HIDDEN_CLASS_ADDRESS.matcher(frame).replaceAll("");
    name = LAMBDA_INDEX.matcher(name).replaceAll(Matcher.quoteReplacement("$$Lambda"));
    return JAVAC_INDEX.matcher(name).replaceAll("$1\\$");
  }

  /**
   * A hidden class's address in a class name read from a heap dump. HotSpot writes it after a
   * {@code +} ({@code java.util.stream.Collectors$$Lambda+0x0000000019009048}, seen in JDK 21 and
   * 25 dumps). {@code Class.getName()} writes a {@code /}, which reads as {@code .0x...} once a
   * name is dotted; both are accepted in case a dump was written from that form. Either way it ends
   * the name or comes before an array's {@code []}.
   */
  private static final Pattern HEAP_HIDDEN_CLASS_ADDRESS =
      Pattern.compile("[+./]0x[0-9a-fA-F]+(?=\\[|$)");

  /**
   * {@code className} without the parts the JVM assigns per run: a hidden class's address ({@code
   * Foo$$Lambda+0x...}, {@code LambdaForm$MH+0x...}) and a lambda class's {@code $$Lambda$<n>}
   * number (before JDK 21). The address differs from run to run; the number follows the order in
   * which a run created its lambdas, so it differs whenever that order does (another build, another
   * code path). With those parts the same class can have a different name in each of two dumps.
   * Several classes can share the result ({@code Collectors$$Lambda} for every lambda in {@code
   * Collectors}): across two runs they cannot be told apart by name.
   */
  public static String stableClassName(String className) {
    if (className == null || (className.indexOf('$') < 0 && !className.contains("0x"))) {
      return className;
    }
    String name = HEAP_HIDDEN_CLASS_ADDRESS.matcher(className).replaceAll("");
    return LAMBDA_INDEX.matcher(name).replaceAll(Matcher.quoteReplacement("$$Lambda"));
  }

  /**
   * The key of a JFR frame: {@code <class>.<method>}, or the method alone when the frame has no
   * class (a native frame), after {@link #stableName}; then the class name's {@code /} become
   * {@code .}. The slashes go after {@code stableName}, whose {@code /0x...} pattern needs them. A
   * JVM method name cannot contain {@code /}, so only the class part changes.
   *
   * @return {@code null} when the frame has neither a class nor a method, as JFR's frame reader
   *     drops such a frame
   */
  public static String jfrKey(String className, String methodName) {
    String cls = className == null ? "" : className;
    String method = methodName == null ? "" : methodName;
    if (cls.isEmpty() && method.isEmpty()) {
      return null;
    }
    if (cls.isEmpty()) {
      return stableName(method);
    }
    return stableName(cls + "." + method).replace('/', '.');
  }

  /**
   * The key of a frame from a format that stores one name per frame (pprof, OTLP). A trailing
   * {@code :<line>} goes first (jfr2pprof's {@code includeLineNumbers} writes {@code
   * Class.method:42}, and the key never includes the line), and a trailing async-profiler
   * frame-type mark ({@code _[k]}, {@code _[i]}), then {@link #stableName}. Then, only in a profile
   * that {@code jvmProfile} says came from a JVM, a name that is wholly a Java class in internal
   * form plus a method ({@code java/lang/String.indexOf}) has its {@code /} turned into {@code .}.
   * Whether to dot is decided per profile, not per name: {@code encoding/json.Marshal} is a Go
   * function with the same shape as a Java internal name, so a Go profile keeps its names as
   * stored. Native paths never match, having no method part.
   *
   * @param jvmProfile whether the profile looks JVM-originated: {@link #isJvmSymbol} or {@link
   *     #isJvmSourceFile} holds for any of its functions
   * @return {@code null} for an empty name
   */
  public static String symbolKey(String name, boolean jvmProfile) {
    if (name == null) {
      return null;
    }
    String bare =
        FRAME_TYPE_SUFFIX.matcher(LINE_SUFFIX.matcher(name).replaceFirst("")).replaceFirst("");
    if (bare.isEmpty()) {
      return null;
    }
    String stable = stableName(bare);
    return jvmProfile && stable.indexOf('/') >= 0 && JAVA_INTERNAL_NAME.matcher(stable).matches()
        ? stable.replace('/', '.')
        : stable;
  }

  /**
   * Whether a function name only a JVM produces appears: a constructor or class initialiser ({@code
   * <init>}, {@code <clinit>}) or a lambda class ({@code $$Lambda}).
   */
  public static boolean isJvmSymbol(String name) {
    return name != null
        && (name.contains("<init>") || name.contains("<clinit>") || name.contains("$$Lambda"));
  }

  /** Whether a source file name is a JVM language's: {@code .java}, {@code .kt}, {@code .scala}. */
  public static boolean isJvmSourceFile(String filename) {
    return filename != null
        && (filename.endsWith(".java") || filename.endsWith(".kt") || filename.endsWith(".scala"));
  }
}
