package io.jafar.shell.core.completion;

/**
 * Identifies the type of completion context based on cursor position and line content. Each type
 * corresponds to a distinct completion behavior.
 *
 * <p>Context types are organized by query structure:
 *
 * <ul>
 *   <li>Command-level: COMMAND, COMMAND_OPTION, OPTION_VALUE
 *   <li>Path-level: ROOT, TYPE_PATTERN, FIELD_PATH
 *   <li>Filter-level: FILTER_FIELD, FILTER_OPERATOR, FILTER_VALUE, FILTER_LOGICAL
 *   <li>Pipeline-level: PIPELINE_OPERATOR, FUNCTION_PARAM
 *   <li>Special: VARIABLE_REFERENCE
 * </ul>
 *
 * <p>Module-specific context types (e.g., CHUNK_ID for JFR, GC_ROOT_TYPE for hdump) should be
 * handled by checking the extras map in {@link CompletionContext}.
 */
public enum CompletionContextType {
  // === COMMAND LEVEL ===

  /** First word completion - show, metadata, open, help, etc. */
  COMMAND,

  /** After -- for command options like --limit, --format */
  COMMAND_OPTION,

  /** Value after specific options like --format json */
  OPTION_VALUE,

  // === PATH LEVEL ===

  /** After 'show ' - suggests root types (events/, objects/, etc.) */
  ROOT,

  /** After root type - suggests type/class names from metadata */
  TYPE_PATTERN,

  /** After type name and / - suggests field names, nested paths */
  FIELD_PATH,

  // === FILTER LEVEL ===

  /** Field name in filter predicate [...] - after [ or && */
  FILTER_FIELD,

  /** Operator in filter predicate - ==, !=, <, >, ~, etc. */
  FILTER_OPERATOR,

  /** Value in filter predicate - after operator */
  FILTER_VALUE,

  /** Logical operator in filter - && or || or and/or */
  FILTER_LOGICAL,

  /** Filter function name - contains(, exists(, between(, etc. */
  FILTER_FUNCTION,

  /** Inside filter function arguments - contains(field, |) */
  FILTER_FUNCTION_ARG,

  // === PIPELINE LEVEL ===

  /** After | - suggests count(), sum(, groupBy(, etc. */
  PIPELINE_OPERATOR,

  /** Inside function call - sum(, groupBy(, select(, top( */
  FUNCTION_PARAM,

  // === SPECIAL CONTEXTS ===

  /** After ${ - variable names */
  VARIABLE_REFERENCE,

  /** Fallback when context cannot be determined */
  UNKNOWN
}
