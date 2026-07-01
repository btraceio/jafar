package io.jafar.jfr2pprof.config;

public final class FrameFormat {
  private final String format;
  private final boolean includeLineNumbers;

  public FrameFormat(String format, boolean includeLineNumbers) {
    this.format = format;
    this.includeLineNumbers = includeLineNumbers;
  }

  public static FrameFormat defaultFormat() {
    return new FrameFormat("{class}.{method}", false);
  }

  public String render(String className, String methodName, int lineNumber) {
    String rendered = format.replace("{class}", className).replace("{method}", methodName);
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
