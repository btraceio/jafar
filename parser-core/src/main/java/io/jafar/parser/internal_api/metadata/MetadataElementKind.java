package io.jafar.parser.internal_api.metadata;

/**
 * Enumeration of metadata element kinds in JFR recordings.
 *
 * <p>This enum defines the different types of metadata elements that can be encountered while
 * parsing JFR recording metadata.
 */
public enum MetadataElementKind {
  /** Root element representing the top-level metadata structure. */
  ROOT,

  /** Class metadata element containing class definitions and information. */
  CLASS,

  /** Annotation metadata element containing annotation definitions. */
  ANNOTATION,

  /** Field metadata element containing field definitions and types. */
  FIELD,

  /** Region metadata element defining recording regions. */
  REGION,

  /** Setting metadata element containing configuration settings. */
  SETTING,

  /** Meta metadata element containing additional metadata information. */
  META
}
