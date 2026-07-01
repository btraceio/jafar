package io.jafar.jfr2pprof.convert;

import io.jafar.jfr2pprof.config.FrameFormat;
import io.jafar.parser.api.ComplexType;
import java.util.Map;

/** Extracts a rendered frame string from a single JFR stack frame object. */
public final class FrameExtractor {

  /** Raw frame components extracted from a JFR stack frame. */
  public record FrameData(String className, String methodName, int lineNumber) {}

  private FrameExtractor() {}

  /**
   * Extracts the raw frame components from a JFR frame object.
   *
   * @param frame the raw frame object (may be a {@link ComplexType} or {@code Map<String, Object>})
   * @return a {@link FrameData} with className, methodName, and lineNumber, or {@code null} if the
   *     frame cannot be parsed
   */
  public static FrameData extractData(Object frame) {
    if (frame == null) return null;
    if (frame instanceof ComplexType ct) frame = ct.getValue();
    if (!(frame instanceof Map<?, ?> fm)) return null;
    @SuppressWarnings("unchecked")
    Map<String, Object> frameMap = (Map<String, Object>) fm;

    Object method = frameMap.get("method");
    if (method instanceof ComplexType ct) method = ct.getValue();
    if (!(method instanceof Map<?, ?> mm)) return null;
    @SuppressWarnings("unchecked")
    Map<String, Object> methodMap = (Map<String, Object>) mm;

    String className = resolveSymbol(resolveType(methodMap.get("type")));
    String methodName = resolveSymbol(methodMap.get("name"));
    if (className.isEmpty() && methodName.isEmpty()) return null;

    int lineNumber = -1;
    Object ln = frameMap.get("lineNumber");
    if (ln instanceof Number n) lineNumber = n.intValue();

    return new FrameData(className, methodName, lineNumber);
  }

  /**
   * Extracts and renders a frame string from a JFR frame object.
   *
   * @param frame the raw frame object (may be a {@link ComplexType} or {@code Map<String, Object>})
   * @param fmt the frame format to use for rendering
   * @return the rendered frame string, or {@code null} if the frame cannot be rendered
   */
  public static String extract(Object frame, FrameFormat fmt) {
    FrameData data = extractData(frame);
    if (data == null) return null;
    return fmt.render(data.className(), data.methodName(), data.lineNumber());
  }

  /**
   * Gets the {@code name} field from a type value that may be a {@link ComplexType} wrapping a
   * {@code Map}.
   */
  private static Object resolveType(Object typeVal) {
    if (typeVal instanceof ComplexType ct) typeVal = ct.getValue();
    if (typeVal instanceof Map<?, ?> m) return m.get("name");
    return null;
  }

  /**
   * Resolves a symbol name from a raw value that may be a plain {@link String}, a {@link
   * ComplexType} wrapping a {@code Map} with a {@code "string"} key, or such a {@code Map}
   * directly.
   */
  static String resolveSymbol(Object nameValue) {
    if (nameValue instanceof ComplexType ct) nameValue = ct.getValue();
    if (nameValue instanceof String s) return s;
    if (nameValue instanceof Map<?, ?> m) {
      Object str = m.get("string");
      return str != null ? str.toString() : "";
    }
    return "";
  }
}
