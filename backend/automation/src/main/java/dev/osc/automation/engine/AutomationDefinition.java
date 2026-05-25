package dev.osc.automation.engine;

import java.util.List;
import java.util.UUID;

public record AutomationDefinition(
        UUID id,
        UUID tenantId,
        UUID objectId,
        String apiName,
        TriggerType triggerType,
        List<AutomationAction> actions,
        boolean isActive
) {}
