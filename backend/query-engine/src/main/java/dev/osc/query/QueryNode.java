package dev.osc.query;

/**
 * Sealed AST node hierarchy for the SOQL-like query DSL.
 * All nodes are immutable records; pattern matching is exhaustive.
 */
public sealed interface QueryNode
        permits SelectQuery, FieldRef, Condition, BinaryOp, Literal {}
