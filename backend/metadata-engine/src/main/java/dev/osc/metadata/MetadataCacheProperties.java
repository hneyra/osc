package dev.osc.metadata;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration for the metadata object cache used by {@link CaffeineMetadataEngine}.
 *
 * Bound from {@code application.yml} under the {@code osc.metadata.cache} prefix, e.g.:
 * <pre>
 * osc:
 *   metadata:
 *     cache:
 *       expire-after-write: 10m
 *       maximum-size: 10000
 * </pre>
 *
 * Defaults match the Phase 0 contract (10 minute TTL, 10 000 entries).
 */
@ConfigurationProperties("osc.metadata.cache")
public class MetadataCacheProperties {

    /** Time after which a cached object definition is evicted and reloaded. */
    private Duration expireAfterWrite = Duration.ofMinutes(10);

    /** Maximum number of object definitions held in the cache. */
    private long maximumSize = 10_000;

    public Duration getExpireAfterWrite() {
        return expireAfterWrite;
    }

    public void setExpireAfterWrite(Duration expireAfterWrite) {
        this.expireAfterWrite = expireAfterWrite;
    }

    public long getMaximumSize() {
        return maximumSize;
    }

    public void setMaximumSize(long maximumSize) {
        this.maximumSize = maximumSize;
    }
}
