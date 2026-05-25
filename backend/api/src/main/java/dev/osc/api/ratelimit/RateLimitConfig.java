package dev.osc.api.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for per-tenant rate limiting.
 */
@Component
@ConfigurationProperties("osc.rate-limit")
public class RateLimitConfig {

    private int requestsPerMinute = 1000;

    public int getRequestsPerMinute() {
        return requestsPerMinute;
    }

    public void setRequestsPerMinute(int requestsPerMinute) {
        this.requestsPerMinute = requestsPerMinute;
    }
}
