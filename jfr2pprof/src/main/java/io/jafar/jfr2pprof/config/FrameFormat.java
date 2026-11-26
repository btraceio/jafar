package io.jafar.jfr2pprof.config;

public final class FrameFormat {
  private static final java.util.regex.Pattern CLASS_THEN_METHOD =
      java.util.regex.Pattern.compile("\\{class\\}[^{}]*\\{method\\}");
  private static final java.util.regex.Pattern METHOD_THEN_CLASS =
      java.util.regex.Pattern.compile("\\{method\\}[^{}]*\\{class\\}");

  private final String format;
  private final boolean includeLineNumbers;

  public FrameFormat(String format, boolean includeLineNumbers) {
    this.format = format;
    this.includeLineNumbers = includeLineNumbers;
  }

  public static FrameFormat defaultFormat() {
    return new FrameFormat("{class}.{method}", false);
  }

  /**
   * The frame's name. A frame without a class (a native frame: {@code sendto}, {@code
   * CodeCache::contains}) is its method alone: the text between {@code {class}} and {@code
   * {method}} goes with the missing class, so the default format gives {@code sendto}, not {@code
   * .sendto}.
   */
  public String render(String className, String methodName, int lineNumber) {
    String pattern = format;
    if (className.isEmpty()) {
      pattern =
          CLASS_THEN_METHOD
              .matcher(METHOD_THEN_CLASS.matcher(pattern).replaceAll("{method}"))
              .replaceAll("{method}");
    }
    String rendered = pattern.replace("{class}", className).replace("{method}", methodName);
    if (includeLineNumbers && lineNumber != -1) {
      return rendered + ":" + lineNumber;
    }
    return rendered;
  }

  public String format() {
    return format;
  }

  public boolean includeLineNumbers() {
    return includeLineNumbers;
  }
}
