package dev.osc.automation.dsl;

/**
 * Sealed hierarchy of AST nodes for the formula expression DSL.
 */
public sealed interface FormulaNode {

    record Identifier(String name) implements FormulaNode {}

    record Literal(Object value) implements FormulaNode {}

    record Add(FormulaNode left, FormulaNode right) implements FormulaNode {}

    record Subtract(FormulaNode left, FormulaNode right) implements FormulaNode {}

    record Multiply(FormulaNode left, FormulaNode right) implements FormulaNode {}

    record Divide(FormulaNode left, FormulaNode right) implements FormulaNode {}

    record Comparison(FormulaNode left, ComparisonOp op, FormulaNode right) implements FormulaNode {}

    record And(FormulaNode left, FormulaNode right) implements FormulaNode {}

    record Or(FormulaNode left, FormulaNode right) implements FormulaNode {}

    record Not(FormulaNode operand) implements FormulaNode {}
}
