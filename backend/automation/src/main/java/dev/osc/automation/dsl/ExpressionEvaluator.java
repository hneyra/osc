package dev.osc.automation.dsl;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Evaluates an ExpressionNode AST against a record's field values.
 * All evaluation is pure — no side effects, no external calls.
 */
@Component
public class ExpressionEvaluator {

    public boolean evaluate(ExpressionNode node, Map<String, Object> record) {
        return switch (node) {
            case ExpressionNode.Comparison c -> evaluateComparison(c, record);
            case ExpressionNode.And a       -> evaluate(a.left(), record) && evaluate(a.right(), record);
            case ExpressionNode.Or o        -> evaluate(o.left(), record) || evaluate(o.right(), record);
            case ExpressionNode.Not n       -> !evaluate(n.operand(), record);
        };
    }

    @SuppressWarnings("unchecked")
    private boolean evaluateComparison(ExpressionNode.Comparison c, Map<String, Object> record) {
        Object fieldValue = record.get(c.fieldName());
        if (fieldValue == null) return false;

        Object literal = c.literal();

        // Numeric comparison — coerce both to Double
        if (isNumeric(fieldValue) && isNumeric(literal)) {
            double fv = toDouble(fieldValue);
            double lv = toDouble(literal);
            return switch (c.op()) {
                case EQ  -> fv == lv;
                case NEQ -> fv != lv;
                case GT  -> fv > lv;
                case GTE -> fv >= lv;
                case LT  -> fv < lv;
                case LTE -> fv <= lv;
            };
        }

        // Boolean comparison
        if (fieldValue instanceof Boolean fb && literal instanceof Boolean lb) {
            return switch (c.op()) {
                case EQ  -> fb.equals(lb);
                case NEQ -> !fb.equals(lb);
                default  -> false;
            };
        }

        // String comparison (EQ / NEQ only)
        String fs = fieldValue.toString();
        String ls = literal != null ? literal.toString() : null;
        return switch (c.op()) {
            case EQ  -> fs.equals(ls);
            case NEQ -> !fs.equals(ls);
            default  -> false;
        };
    }

    private boolean isNumeric(Object v) {
        return v instanceof Number;
    }

    private double toDouble(Object v) {
        return ((Number) v).doubleValue();
    }
}
