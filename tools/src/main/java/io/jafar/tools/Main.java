package io.jafar.tools;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Dispatches to the individual command-line tools bundled in jafar-tools.
 *
 * <p>Tools are invoked via reflection because they may be compiled for a newer bytecode target than
 * this module's own Java 8 baseline (e.g. jfr2pprof), so this class must not reference their types
 * directly at compile time.
 */
public final class Main {

  private Main() {}

  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      usage();
      System.exit(1);
    }

    String tool = args[0];
    String[] rest = Arrays.copyOfRange(args, 1, args.length);

    switch (tool) {
      case "jfr2pprof":
        invoke("io.jafar.jfr2pprof.Main", rest);
        break;
      case "scrub":
        ScrubberMain.main(rest);
        break;
      default:
        System.err.println("Unknown tool: " + tool);
        usage();
        System.exit(1);
    }
  }

  private static void invoke(String mainClassName, String[] args) {
    try {
      Method main = Class.forName(mainClassName).getMethod("main", String[].class);
      main.invoke(null, (Object) args);
    } catch (InvocationTargetException e) {
      throw new IllegalStateException(mainClassName + " failed", e.getCause());
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Failed to launch " + mainClassName, e);
    }
  }

  private static void usage() {
    System.err.println("Usage: <tool> [args...]");
    System.err.println("Available tools:");
    System.err.println("  jfr2pprof   Convert a JFR recording to a pprof profile");
    System.err.println("  scrub       Redact sensitive fields from a JFR recording");
  }
}
