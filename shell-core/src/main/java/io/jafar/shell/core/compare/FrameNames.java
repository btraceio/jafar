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

  /**
   * A whole name that is a Java class in internal (slashed) form plus a method: {@code
   * java/lang/String.indexOf}. A Go function ({@code github.com/x/y.Func}) has a dot before its
   * first slash and a native path has no method part, so neither matches.
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
   * The key of a frame from a format that stores one name per frame (pprof): {@link #stableName},
   * then {@code /} become {@code .} only when the whole name is a Java class in internal form plus
   * a method. Go functions and native paths contain {@code /} legitimately and are left as they
   * are.
   *
   * @return {@code null} for an empty name
   */
  public static String symbolKey(String name) {
    if (name == null || name.isEmpty()) {
      return null;
    }
    String stable = stableName(name);
    return stable.indexOf('/') >= 0 && JAVA_INTERNAL_NAME.matcher(stable).matches()
        ? stable.replace('/', '.')
        : stable;
  }
}
