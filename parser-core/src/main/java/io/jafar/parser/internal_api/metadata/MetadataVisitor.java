package io.jafar.parser.internal_api.metadata;

/**
 * Visitor interface for traversing JFR metadata elements.
 *
 * <p>This interface provides a visitor pattern implementation for processing different types of
 * metadata elements during JFR parsing. All methods have default implementations that do nothing,
 * allowing implementors to override only the methods they need.
 */
public interface MetadataVisitor {
  /**
   * Called when visiting a metadata root element.
   *
   * @param root the metadata root element
   */
  default void visitRoot(MetadataRoot root) {}
  ;

  /**
   * Called when finishing the visit of a metadata root element.
   *
   * @param root the metadata root element
   */
  default void visitEnd(MetadataRoot root) {}
  ;

  /**
   * Called when visiting a metadata element.
   *
   * @param metadata the metadata element
   */
  default void visitMetadata(MetadataElement metadata) {}
  ;

  /**
   * Called when finishing the visit of a metadata element.
   *
   * @param metadata the metadata element
   */
  default void visitEnd(MetadataElement metadata) {}
  ;

  /**
   * Called when visiting a metadata class.
   *
   * @param clz the metadata class
   */
  default void visitClass(MetadataClass clz) {}
  ;

  /**
   * Called when finishing the visit of a metadata class.
   *
   * @param clz the metadata class
   */
  default void visitEnd(MetadataClass clz) {}
  ;

  /**
   * Called when visiting a metadata setting.
   *
   * @param setting the metadata setting
   */
  default void visitSetting(MetadataSetting setting) {}
  ;

  /**
   * Called when finishing the visit of a metadata setting.
   *
   * @param setting the metadata setting
   */
  default void visitEnd(MetadataSetting setting) {}
  ;

  /**
   * Called when visiting a metadata annotation.
   *
   * @param annotation the metadata annotation
   */
  default void visitAnnotation(MetadataAnnotation annotation) {}
  ;

  /**
   * Called when finishing the visit of a metadata annotation.
   *
   * @param annotation the metadata annotation
   */
  default void visitEnd(MetadataAnnotation annotation) {}
  ;

  /**
   * Called when visiting a metadata field.
   *
   * @param field the metadata field
   */
  default void visitField(MetadataField field) {}

  /**
   * Called when finishing the visit of a metadata field.
   *
   * @param field the metadata field
   */
  default void visitEnd(MetadataField field) {}

  /**
   * Called when visiting a metadata region.
   *
   * @param region the metadata region
   */
  default void visitRegion(MetadataRegion region) {}

  /**
   * Called when finishing the visit of a metadata region.
   *
   * @param region the metadata region
   */
  default void visitEnd(MetadataRegion region) {}
}
