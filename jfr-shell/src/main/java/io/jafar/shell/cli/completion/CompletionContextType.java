package io.jafar.shell.cli.completion;

/**
 * Identifies the type of completion context based on cursor position and line content. Each type
 * corresponds to a distinct completion behavior.
 */
public enum CompletionContextType {
  /** First word completion - show, metadata, open, help, etc. */
  COMMAND,

  /** After -- for command options like --limit, --format */
  COMMAND_OPTION,

  /** Value after specific options like --format json */
  OPTION_VALUE,

  /** After 'show ' - suggests events/, metadata/, cp/, chunks */
  ROOT,

  /** After 'events/' - suggests event type names from metadata */
  EVENT_TYPE,

  /** After 'chunks/' - suggests chunk IDs */
  CHUNK_ID,

  /** After 'metadata/Type/' - suggests fields, settings, annotations */
  METADATA_SUBPROP,

  /** After 'events/Type/' - suggests field names, nested paths */
  FIELD_PATH,

  /** Field name in filter predicate [...] - after [ or && */
  FILTER_FIELD,

  /** Operator in filter predicate - ==, !=, <, >, contains, etc. */
  FILTER_OPERATOR,

  /** Value in filter predicate - after operator */
  FILTER_VALUE,

  /** Logical operator in filter - && or || */
  FILTER_LOGICAL,

  /** After | - suggests count(), sum(, groupBy(, etc. */
  PIPELINE_OPERATOR,

  /** Inside function call - sum(, groupBy(, select(, top( */
  FUNCTION_PARAM,

  /** Named parameter - key=, fields=, etc. */
  NAMED_PARAM,

  /** Fallback when context cannot be determined */
  UNKNOWN
}
