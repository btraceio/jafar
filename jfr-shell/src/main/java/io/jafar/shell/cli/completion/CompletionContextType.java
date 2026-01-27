package io.jafar.shell.cli.completion;

/**
 * Identifies the type of completion context based on cursor position and line content. Each type
 * corresponds to a distinct completion behavior.
 *
 * <p>Context types are organized by query structure:
 *
 * <ul>
 *   <li>Command-level: COMMAND, COMMAND_OPTION, OPTION_VALUE
 *   <li>Path-level: ROOT, EVENT_TYPE, MULTI_EVENT_TYPE, CHUNK_ID, METADATA_SUBPROP, FIELD_PATH
 *   <li>Filter-level: FILTER_FIELD, FILTER_OPERATOR, FILTER_VALUE, FILTER_LOGICAL, FILTER_FUNCTION,
 *       FILTER_FUNCTION_ARG
 *   <li>Pipeline-level: PIPELINE_OPERATOR, FUNCTION_PARAM, NAMED_PARAM
 *   <li>Expression-level: SELECT_EXPRESSION, SELECT_ALIAS, STRING_TEMPLATE
 *   <li>Special: DECORATOR_FIELD, VARIABLE_REFERENCE
 * </ul>
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

  /** After 'show ' - suggests events/, metadata/, cp/, chunks */
  ROOT,

  /** After 'events/' - suggests event type names from metadata */
  EVENT_TYPE,

  /** Inside (Type1|Type2) multi-event syntax - suggests event types with | separator */
  MULTI_EVENT_TYPE,

  /** After 'chunks/' - suggests chunk IDs */
  CHUNK_ID,

  /** After 'metadata/Type/' - suggests fields, settings, annotations */
  METADATA_SUBPROP,

  /** After 'events/Type/' - suggests field names, nested paths */
  FIELD_PATH,

  // === FILTER LEVEL ===

  /** Field name in filter predicate [...] - after [ or && */
  FILTER_FIELD,

  /** Operator in filter predicate - ==, !=, <, >, contains, etc. */
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

  /** Named parameter - key=, fields=, etc. */
  NAMED_PARAM,

  // === EXPRESSION LEVEL (inside select) ===

  /** Inside select() - field names, arithmetic, function calls */
  SELECT_EXPRESSION,

  /** After 'as' keyword in select - alias name (no completion) */
  SELECT_ALIAS,

  /** Inside string template "${...}" - field references */
  STRING_TEMPLATE,

  // === SPECIAL CONTEXTS ===

  /** After $decorator. - field names from decorator event type */
  DECORATOR_FIELD,

  /** After ${ - variable names */
  VARIABLE_REFERENCE,

  /** Fallback when context cannot be determined */
  UNKNOWN
}
