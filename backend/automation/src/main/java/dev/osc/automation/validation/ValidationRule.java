package dev.osc.automation.validation;

import java.util.UUID;

public record ValidationRule(
        UUID id,
        UUID tenantId,
        UUID objectId,
        String apiName,
        String conditionDsl,
        String errorMessage,
        boolean isActive
) {}
