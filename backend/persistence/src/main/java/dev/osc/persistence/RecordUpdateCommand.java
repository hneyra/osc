package dev.osc.persistence;

import java.util.Map;
import java.util.UUID;

/**
 * Command to update an existing record's mutable fields.
 * Partial updates are supported: null values are omitted from the update.
 */
public record RecordUpdateCommand(
        UUID id,
        String name,
        UUID ownerId,
        Map<String, Object> dataPatch
) {
    public RecordUpdateCommand {
        if (id == null) throw new IllegalArgumentException("id must not be null");
    }
}
