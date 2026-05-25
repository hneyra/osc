package dev.osc.metadata.performance;

import java.util.List;

/**
 * Report of the most-accessed fields for a given object within a tenant.
 */
public record HotFieldReport(List<FieldHit> fields) {

    /**
     * A single field access hit entry.
     */
    public record FieldHit(String fieldApiName, long hitCount) {}
}
