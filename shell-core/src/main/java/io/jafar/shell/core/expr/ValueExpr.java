package io.jafar.shell.core.expr;

import java.util.Map;

/**
 * Value expression AST for computing numeric values from row data.
 *
 * <p>Supports arithmetic expressions like {@code instanceCount * instanceSize} for use in
 * aggregations and computed fields.
 *
 * <p>Example usage:
 *
 * <pre>
 * // groupBy(name, agg=sum, value=instanceCount * instanceSize)
 * ValueExpr expr = new BinaryExpr(
 *     new FieldRef("instanceCount"),
 *     ArithOp.MUL,
 *     new FieldRef("instanceSize"));
 * double result = expr.evaluate(rowMap);
 * </pre>
 */
public sealed interface ValueExpr permits FieldRef, NumberLiteral, BinaryExpr {

  /**
   * Evaluates this expression against a row of data.
   *
   * @param row the row data as a map
   * @return the computed numeric value, or {@code Double.NaN} if evaluation fails
   */
  double evaluate(Map<String, Object> row);

  /** Arithmetic operators for binary expressions. */
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
