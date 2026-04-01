package io.jafar.shell.core.expr;

import java.util.Map;

/** Sealed interface for arithmetic value expressions used in query languages. */
public sealed interface ValueExpr permits BinaryExpr, FieldRef, NumberLiteral {

  /**
   * Evaluates this expression against a row of data.
   *
   * @param row the data row as field-name to value map
   * @return the numeric result, or {@link Double#NaN} if the value is not numeric
   */
  double evaluate(Map<String, Object> row);

  /** Arithmetic operators. */
  enum ArithOp {
    ADD("+"),
    SUB("-"),
    MUL("*"),
    DIV("/");

    private final String symbol;

    ArithOp(String symbol) {
      this.symbol = symbol;
    }

    public String symbol() {
      return symbol;
    }

    public static ArithOp fromSymbol(String s) {
      return switch (s) {
        case "+" -> ADD;
        case "-" -> SUB;
        case "*" -> MUL;
        case "/" -> DIV;
        default -> throw new IllegalArgumentException("Unknown arithmetic operator: " + s);
      };
    }
  }
}
