package dev.osc.query;

import java.util.List;

/**
 * Root AST node for a parsed SELECT query.
 * fields is empty when selectAll = true.
 * whereClause, orderByField, limit, offset are null when not specified.
 */
public record SelectQuery(
        List<FieldRef> fields,
        boolean selectAll,
        String objectName,
        QueryNode whereClause,
        FieldRef orderByField,
        OrderDirection orderDir,
        Integer limit,
        Integer offset
) implements QueryNode {

    public SelectQuery {
        if (objectName == null || objectName.isBlank())
            throw new IllegalArgumentException("objectName must not be blank");
        if (!selectAll && (fields == null || fields.isEmpty()))
            throw new IllegalArgumentException("fields must not be empty unless selectAll=true");
        if (orderDir == null) orderDir = OrderDirection.ASC;
    }
}
