package dev.osc.api.ratelimit;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-tenant sliding-window rate limiter using a simple 60-second counter.
 * Counters are reset every minute by a scheduled task.
 *
 * The map is keyed by tenant ID string; entries are created lazily on first request.
 */
@Component
public class TenantRateLimiter {

    private final RateLimitConfig config;
    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();

    public TenantRateLimiter(RateLimitConfig config) {
        this.config = config;
    }

    /**
     * Tries to record a request for the given tenant.
     *
     * @return {@code true} if the request is allowed; {@code false} if the limit is exceeded.
     */
    public boolean tryAcquire(String tenantId) {
        AtomicLong counter = counters.computeIfAbsent(tenantId, k -> new AtomicLong(0));
        long current = counter.incrementAndGet();
        return current <= config.getRequestsPerMinute();
    }

    /** Resets all tenant counters at the start of each new minute window. */
    @Scheduled(fixedDelay = 60_000)
    public void resetCounters() {
        counters.clear();
    }
}
