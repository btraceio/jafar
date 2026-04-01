package io.jafar.shell.core.completion;

/** Types of completion context for shell tab completion. */
public enum CompletionContextType {
  /** Completing a top-level command. */
  COMMAND,
  /** Completing a command option (e.g. --format). */
  COMMAND_OPTION,
  /** Completing a query root type (e.g. events, objects). */
  ROOT,
  /** Completing a type/class pattern. */
  TYPE_PATTERN,
  /** Completing a field name inside a filter predicate. */
  FILTER_FIELD,
  /** Completing a comparison operator inside a filter. */
  FILTER_OPERATOR,
  /** Completing a value inside a filter. */
  FILTER_VALUE,
  /** Completing a logical operator (and/or) inside a filter. */
  FILTER_LOGICAL,
  /** Completing a pipeline operator after |. */
  PIPELINE_OPERATOR,
  /** Completing a function parameter. */
  FUNCTION_PARAM,
  /** Completing a variable reference ($var or ${var}). */
  VARIABLE_REFERENCE,
  /** Unknown context. */
  UNKNOWN
}
