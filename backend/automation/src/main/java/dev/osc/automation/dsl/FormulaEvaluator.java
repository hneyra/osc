package dev.osc.automation.dsl;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Evaluates a FormulaNode AST against a record's field values.
 * Pure evaluation with safe fallback handling for nulls and divisions by zero.
 */
@Component
public class FormulaEvaluator {

    public Object evaluate(FormulaNode node, Map<String, Object> record) {
        return switch (node) {
            case FormulaNode.Identifier id -> record.get(id.name());
            case FormulaNode.Literal lit   -> lit.value();
            case FormulaNode.Add add       -> {
                double left = toDouble(evaluate(add.left(), record));
                double right = toDouble(evaluate(add.right(), record));
                yield left + right;
            }
            case FormulaNode.Subtract sub  -> {
                double left = toDouble(evaluate(sub.left(), record));
                double right = toDouble(evaluate(sub.right(), record));
                yield left - right;
            }
            case FormulaNode.Multiply mul  -> {
                double left = toDouble(evaluate(mul.left(), record));
                double right = toDouble(evaluate(mul.right(), record));
                yield left * right;
            }
            case FormulaNode.Divide div    -> {
                double left = toDouble(evaluate(div.left(), record));
                double right = toDouble(evaluate(div.right(), record));
                if (right == 0.0) {
                    yield 0.0; // Safe division by zero fallback
                }
                yield left / right;
            }
            case FormulaNode.Comparison c  -> evaluateComparison(c, record);
            case FormulaNode.And a         -> toBoolean(evaluate(a.left(), record)) && toBoolean(evaluate(a.right(), record));
            case FormulaNode.Or o          -> toBoolean(evaluate(o.left(), record)) || toBoolean(evaluate(o.right(), record));
            case FormulaNode.Not n         -> !toBoolean(evaluate(n.operand(), record));
        };
    }

    private boolean evaluateComparison(FormulaNode.Comparison c, Map<String, Object> record) {
        Object left = evaluate(c.left(), record);
        Object right = evaluate(c.right(), record);

        if (left == null && right == null) {
            return switch (c.op()) {
                case EQ -> true;
                case NEQ -> false;
                default -> false;
            };
        }
        if (left == null || right == null) {
            return switch (c.op()) {
                case EQ -> false;
                case NEQ -> true;
                default -> false;
            };
        }

        if (isNumeric(left) && isNumeric(right)) {
            double lv = toDouble(left);
            double rv = toDouble(right);
            return switch (c.op()) {
                case EQ  -> lv == rv;
                case NEQ -> lv != rv;
                case GT  -> lv > rv;
                case GTE -> lv >= rv;
                case LT  -> lv < rv;
                case LTE -> lv <= rv;
            };
        }

        if (left instanceof Boolean lb && right instanceof Boolean rb) {
            return switch (c.op()) {
                case EQ  -> lb.equals(rb);
                case NEQ -> !lb.equals(rb);
                default  -> false;
            };
        }

        String ls = left.toString();
        String rs = right.toString();
        return switch (c.op()) {
            case EQ  -> ls.equals(rs);
            case NEQ -> !ls.equals(rs);
            default  -> false;
        };
    }

    private boolean isNumeric(Object v) {
        return v instanceof Number;
    }

    private double toDouble(Object v) {
        if (v == null) return 0.0;
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private boolean toBoolean(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v.toString());
    }
}
