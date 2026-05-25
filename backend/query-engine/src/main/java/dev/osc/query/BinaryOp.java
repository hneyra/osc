package dev.osc.query;

/** AND / OR connective between two WHERE clauses. */
public record BinaryOp(QueryNode left, OpType op, QueryNode right) implements QueryNode {

    public enum OpType { AND, OR }

    public BinaryOp {
        if (left == null) throw new IllegalArgumentException("left must not be null");
        if (op == null)   throw new IllegalArgumentException("op must not be null");
        if (right == null) throw new IllegalArgumentException("right must not be null");
    }
}
