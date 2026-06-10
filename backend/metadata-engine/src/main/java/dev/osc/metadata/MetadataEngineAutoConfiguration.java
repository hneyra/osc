package dev.osc.metadata;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Makes {@link MetadataCacheProperties} available to any Spring Boot application that
 * includes the metadata-engine module, so the cache can be tuned from {@code application.yml}
 * without component-scanning the {@code dev.osc.metadata} package.
 */
@AutoConfiguration
@EnableConfigurationProperties(MetadataCacheProperties.class)
public class MetadataEngineAutoConfiguration {
}
