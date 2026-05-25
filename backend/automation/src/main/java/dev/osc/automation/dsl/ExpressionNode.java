package dev.osc.automation.dsl;

/** Sealed hierarchy of AST nodes for the validation rule DSL. */
public sealed interface ExpressionNode {

    record Comparison(String fieldName, ComparisonOp op, Object literal) implements ExpressionNode {}

    record And(ExpressionNode left, ExpressionNode right) implements ExpressionNode {}

    record Or(ExpressionNode left, ExpressionNode right) implements ExpressionNode {}

    record Not(ExpressionNode operand) implements ExpressionNode {}
}
