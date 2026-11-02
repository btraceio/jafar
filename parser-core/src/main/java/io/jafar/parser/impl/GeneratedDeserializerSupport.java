package io.jafar.parser.impl;

import io.jafar.parser.api.Control;
import io.jafar.parser.api.Internal;
import io.jafar.parser.api.ParserContext;
import io.jafar.parser.internal_api.metadata.MetadataClass;

/**
 * Entry points for the untyped deserializers that {@code parser-codegen} generates at run time.
 *
 * <p>The generated path must produce the same values as the generic one ({@link EventStream} with
 * {@link MapValueBuilder}). Rather than re-implement that value model in bytecode, generated code
 * calls into the classes the generic path uses — {@link ArrayHolder} for arrays and {@link
 * TemporalNormalizer} for tick conversion — through this class, because those are package-private.
 *
 * <p><b>API note:</b> public solely so that ASM-generated classes in another package can call it,
 * like {@link ConstantPoolAccessor}. Not part of the stable API.
 */
@Internal
public final class GeneratedDeserializerSupport {
  private GeneratedDeserializerSupport() {}

  /**
   * Resolves, at generation time, the tick normalization the generic path applies to {@code
   * owner.fld}.
   *
   * @return an opaque kind to pass to {@link #normalize}; {@code 0} means no normalization
   */
  public static int temporalKind(MetadataClass owner, String fld) {
    return TemporalNormalizer.kindOf(owner, fld);
  }

  /**
   * The chunk info {@link MapValueBuilder} normalizes with. Generated code looks it up once per
   * event and passes it to {@link #normalize}.
   */
  public static Control.ChunkInfo chunkInfo(ParserContext context) {
    return context.get(Control.ChunkInfo.class);
  }

  /**
   * Normalizes an {@code int}/{@code long} field value exactly as {@link MapValueBuilder} does;
   * {@code chunkInfo} may be null (no normalization, as in the generic path).
   */
  public static long normalize(Control.ChunkInfo chunkInfo, int kind, long value) {
    return TemporalNormalizer.normalizeKind(kind, value, chunkInfo);
  }

  /**
   * Creates the array value the generic path creates for an array field of the given element type.
   *
   * @return an {@link ArrayHolder}, typed as {@code Object} for the generated call site
   */
  public static Object newArray(String elementTypeName, int length) {
    return new ArrayHolder(elementTypeName, length);
  }

  /**
   * Wraps a primitive array the generated code filled directly — without boxing each element and
   * unboxing it again through {@code Array.set} — in the same {@link ArrayHolder} the generic path
   * produces for that element type.
   *
   * @param elementTypeName the JFR element type; must be one whose {@link ArrayHolder} array type
   *     is {@code filled}'s class
   */
  public static Object wrapArray(String elementTypeName, Object filled) {
    return ArrayHolder.ofFilled(elementTypeName, filled);
  }

  /** Appends the next element to an array created by {@link #newArray}. */
  public static void addToArray(Object array, Object element) {
    ((ArrayHolder) array).add(element);
  }
}
