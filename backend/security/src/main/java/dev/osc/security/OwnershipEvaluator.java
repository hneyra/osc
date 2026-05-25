package dev.osc.security;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/** Evaluates whether a user owns a given record based on the ownerId field. */
@Component
public class OwnershipEvaluator {

    public boolean isOwner(UUID userId, Map<String, Object> record) {
        Object ownerId = record.get("ownerId");
        return ownerId != null && userId.toString().equals(ownerId.toString());
    }
}
