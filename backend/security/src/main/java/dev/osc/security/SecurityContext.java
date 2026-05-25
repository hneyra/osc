package dev.osc.security;

/**
 * Reactor Context key for user identity propagation.
 * Never use ThreadLocal — it breaks under WebFlux's non-blocking scheduler.
 */
public final class SecurityContext {

    public static final Class<UserContext> USER_CONTEXT_KEY = UserContext.class;

    private SecurityContext() {}
}
