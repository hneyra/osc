package dev.osc.persistence;

import java.util.Map;
import java.util.UUID;

/**
 * Command to insert a new record. tenantId comes from Reactor Context — not from here.
 */
public record RecordInsertCommand(
        UUID objectId,
        String name,
        UUID ownerId,
        Map<String, Object> data
) {
    public RecordInsertCommand {
        if (objectId == null) throw new IllegalArgumentException("objectId must not be null");
        if (data == null)     throw new IllegalArgumentException("data must not be null");
    }
}
