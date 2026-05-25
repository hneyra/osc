package dev.osc.metadata;

/**
 * Reactor Context key for tenant propagation.
 * Using a typed key avoids String collisions and is the idiomatic reactive approach.
 * Never use ThreadLocal — it breaks under WebFlux's non-blocking scheduler.
 */
public final class TenantContext {

    public static final Class<String> TENANT_ID_KEY = String.class;

    private TenantContext() {}
}
