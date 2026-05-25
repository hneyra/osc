package dev.osc.security;

import java.util.UUID;

/** Carries authenticated user identity through the Reactor Context. */
public record UserContext(UUID userId, UUID tenantId) {}
