package dev.osc.automation.engine;

import java.util.UUID;

public record ScriptDefinition(
    UUID id,
    UUID tenantId,
    UUID objectId,
    String objectApiName,
    String kind,
    String triggerEvent,
    String invocableName,
    String scheduleCron,
    String source,
    boolean isActive,
    int timeoutSeconds
) {}
