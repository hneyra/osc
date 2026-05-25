package dev.osc.api.record;

import java.util.List;
import java.util.Map;

/**
 * Standard response envelope for list/query endpoints.
 * Uses Java records for immutability; Jackson serializes automatically.
 */
public record RecordResponse(
        List<Map<String, Object>> data,
        long totalCount,
        int limit,
        int offset,
        String objectApiName
) {}
